(ns aircraft-db
  "Build-time transform: turn the tar1090-db aircraft database into the static
  sharded JSON the browser enriches against (adsb.enrich).

  ## What this produces, and why it is not committed

  The source is `aircraft.csv.gz` from tar1090-db, whose data is maintained by
  Mictronics (https://www.mictronics.de/aircraft-database/) — a third-party
  compilation of public aircraft-registration data. It is DATA, not code, and
  large; it carries no explicit license, so it is fetched here at build/dev
  time, written into `resources/public/db/` (gitignored), and never committed.
  Everything downstream degrades to absent when the directory is missing.

  ## The shard format (ours, not tar1090's)

  tar1090's own `db/` uses an adaptive split with a `children` recursion that
  varies between its `db`/`db-2` schemes; rather than reimplement that lazy
  trie walk in the browser, we shard on a FIXED three-hex-character prefix —
  `resources/public/db/<abc>.json`, each a JSON object:

      {\"abc0e4\": {\"t\": \"B744\", \"d\": \"Boeing 747-400\",
                    \"r\": \"N570UP\", \"o\": \"United Parcel Service\"}, ...}

  keyed by the full lowercased ICAO hex, absent fields omitted. Three chars
  keeps each file small even across the dense US (`a…`) allocation.

  ## The CSV

  Semicolon-delimited, no header, backslash-escaped (Python csv QUOTE_NONE).
  Columns, in order:

      0 icao   1 registration   2 typecode   3 flags
      4 description   5 year   6 operator   7 (trailing empty)

  We keep icao, registration (r), typecode (t), description (d), operator (o)."
  (:require
    [babashka.fs :as fs]
    [babashka.process :as p]
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]))

(def ^:const csv-gz-url
  "https://github.com/wiedehopf/tar1090-db/raw/refs/heads/csv/aircraft.csv.gz")

(def ^:const out-dir "resources/public/db")
(def ^:const shard-prefix-length 3)

;; A clean six-hex ICAO address. Non-ICAO (`~`-prefixed) and malformed rows are
;; skipped — they can never be looked up.
(def ^:private icao-re #"[0-9a-f]{6}")

(defn parse-row
  "Split one CSV line on unescaped `;`, honouring `\\` as the escape char."
  [line]
  (loop [chars  (seq line)
         field  (StringBuilder.)
         fields (transient [])
         esc?   false]
    (if-let [c (first chars)]
      (cond
        esc?       (recur (rest chars) (.append field c) fields false)
        (= c \\)   (recur (rest chars) field fields true)
        (= c \;)   (recur (rest chars) (StringBuilder.) (conj! fields (str field)) false)
        :else      (recur (rest chars) (.append field c) fields false))
      (persistent! (conj! fields (str field))))))

(defn- non-empty
  "The trimmed string, or nil when blank — a blank CSV cell is absence."
  [s]
  (when s
    (let [t (str/trim s)]
      (when-not (str/blank? t) t))))

(defn row->entry
  "One CSV row as `[icao record]`, or nil when the row has no usable ICAO.
  `record` carries only the fields the database actually knows (t/d/r/o)."
  [[icao reg typecode _flags desc _year operator & _]]
  (let [icao (some-> icao str/trim str/lower-case)]
    (when (and icao (re-matches icao-re icao))
      (let [record (cond-> {}
                     (non-empty typecode) (assoc "t" (non-empty typecode))
                     (non-empty desc)     (assoc "d" (non-empty desc))
                     (non-empty reg)      (assoc "r" (non-empty reg))
                     (non-empty operator) (assoc "o" (non-empty operator)))]
        ;; A row with an ICAO but no known facts adds nothing to look up.
        (when (seq record)
          [icao record])))))

(defn shard
  "Group `[icao record]` entries into `{prefix {icao record}}`."
  [entries]
  (reduce (fn [acc [icao record]]
            (assoc-in acc [(subs icao 0 shard-prefix-length) icao] record))
          {}
          entries))

(defn- read-entries!
  "Every usable `[icao record]` from the gzipped CSV at `gz-path`."
  [gz-path]
  (with-open [in  (java.util.zip.GZIPInputStream. (io/input-stream (str gz-path)))
              rdr (io/reader in)]
    (into []
          (keep (comp row->entry parse-row))
          (line-seq rdr))))

(defn- write-shards!
  "Write each shard to out-dir/<prefix>.json, replacing any prior contents."
  [shards]
  (fs/create-dirs out-dir)
  ;; Clear stale shards so a shrunk database never leaves orphans behind.
  (doseq [f (fs/glob out-dir "*.json")]
    (fs/delete f))
  (doseq [[prefix records] shards]
    (spit (str (fs/path out-dir (str prefix ".json")))
          (json/generate-string records)))
  (count shards))

(defn- download!
  "Fetch the CSV.gz to `dest` with curl (follows GitHub's redirects)."
  [dest]
  (println "Downloading" csv-gz-url)
  (p/check (p/process ["curl" "-fsSL" csv-gz-url "-o" (str dest)] {:inherit true})))

(defn build!
  "Download (unless :csv-gz points at a local file) and shard the aircraft
  database into resources/public/db/. Idempotent; safe to re-run."
  [{:keys [csv-gz]}]
  (let [tmp-dir (fs/create-temp-dir {:prefix "adsb-db"})
        gz      (or csv-gz (str (fs/path tmp-dir "aircraft.csv.gz")))]
    (try
      (when-not csv-gz (download! gz))
      (println "Sharding" gz "→" out-dir)
      (let [entries (read-entries! gz)
            shards  (shard entries)
            n       (write-shards! shards)]
        (println (format "Wrote %,d aircraft across %,d shards into %s"
                         (count entries) n out-dir)))
      (finally
        (fs/delete-tree tmp-dir)))))
