(ns adsb.aircraft-db
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:const csv-gz-url
  "https://github.com/wiedehopf/tar1090-db/raw/refs/heads/csv/aircraft.csv.gz")

(def ^:const out-dir "resources/public/db")
(def ^:const shard-prefix-length 3)

(def ^:private icao-re #"[0-9a-f]{6}")

(defn parse-row [line]
  (loop [chars  (seq line)
         field  (StringBuilder.)
         fields (transient [])
         esc?   false]
    (if-let [c (first chars)]
      (cond
        esc? (recur (rest chars) (.append field c) fields false)
        (= c \\) (recur (rest chars) field fields true)
        (= c \;) (recur (rest chars) (StringBuilder.) (conj! fields (str field)) false)
        :else (recur (rest chars) (.append field c) fields false))
      (persistent! (conj! fields (str field))))))

(defn- non-empty [s]
  (when s
    (let [t (str/trim s)]
      (when-not (str/blank? t) t))))

(defn row->entry [[icao reg typecode _flags desc _year operator & _]]
  (let [icao (some-> icao str/trim str/lower-case)]
    (when (and icao (re-matches icao-re icao))
      (let [record (cond-> {}
                           (non-empty typecode) (assoc "t" (non-empty typecode))
                           (non-empty desc) (assoc "d" (non-empty desc))
                           (non-empty reg) (assoc "r" (non-empty reg))
                           (non-empty operator) (assoc "o" (non-empty operator)))]
        (when (seq record)
          [icao record])))))

(defn shard [entries]
  (reduce (fn [acc [icao record]]
            (assoc-in acc [(subs icao 0 shard-prefix-length) icao] record))
          {}
          entries))

(defn- read-entries! [gz-path]
  (with-open [in  (java.util.zip.GZIPInputStream. (io/input-stream (str gz-path)))
              rdr (io/reader in)]
    (into []
          (keep (comp row->entry parse-row))
          (line-seq rdr))))

(defn- write-shards! [shards]
  (fs/create-dirs out-dir)
  (doseq [f (fs/glob out-dir "*.json")]
    (fs/delete f))
  (doseq [[prefix records] shards]
    (spit (str (fs/path out-dir (str prefix ".json")))
          (json/generate-string records)))
  (count shards))

(defn- download! [dest]
  (println "Downloading" csv-gz-url)
  (p/check (p/process ["curl" "-fsSL" csv-gz-url "-o" (str dest)] {:inherit true})))

(defn build! [{:keys [csv-gz]}]
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
