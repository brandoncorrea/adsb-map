(ns adsb.env
  "The environment, as the app sees it: the real process environment, with a
  .env file filling in only what the environment does not already define.

  A JVM app cannot see a .env file on its own — System/getenv reports the
  process environment and nothing else. This namespace is the boundary that
  reconciles the two, so every entry point (the uberjar's -main, `bb dev`, a
  bare `clojure -M:dev`, an IDE, the REPL) boots from the same config.

  Precedence is the whole point: a real exported variable always beats the
  file. Production sets real environment variables and ships no .env, so in
  Docker and on the droplet this is a no-op that reads a file that isn't
  there. It can never shadow deployed config."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:const default-file ".env")

(defn- unquote-value
  "Strip one layer of matched surrounding quotes, if present."
  [v]
  (let [wrapped? (fn [q] (and (>= (count v) 2)
                              (str/starts-with? v q)
                              (str/ends-with? v q)))]
    (if (or (wrapped? "\"") (wrapped? "'"))
      (subs v 1 (dec (count v)))
      v)))

(defn parse
  "Pure: .env contents -> a string->string map. Blank lines and # comments are
  skipped, a leading `export ` is tolerated, values may be quoted. A value may
  itself contain `=`; only the first one separates key from value."
  [contents]
  (into {}
        (keep (fn [line]
                (let [line (str/trim line)]
                  (when-not (or (str/blank? line) (str/starts-with? line "#"))
                    (let [[k v] (str/split line #"=" 2)]
                      (when (and k v)
                        [(str/trim (str/replace k #"^export\s+" ""))
                         (unquote-value (str/trim v))]))))))
        (str/split-lines contents)))

(defn merge-file
  "Pure: `environment` wins over `file-contents`, always. The file only fills
  gaps — that is what keeps a stray .env from shadowing real deployed config."
  [environment file-contents]
  (merge (parse file-contents) environment))

(defn read!
  "The environment map to boot from: the process environment, backfilled from
  .env when that file exists. Missing file => the process environment, plain."
  ([] (read! default-file (System/getenv)))
  ([file environment]
   (let [environment (into {} environment)]
     (if (.exists (io/file file))
       (merge-file environment (slurp file))
       environment))))
