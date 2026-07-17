(ns adsb.env
  (:refer-clojure :exclude [read])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:const default-file ".env")

(defn- unquote-value [v]
  (let [wrapped? (fn [q] (and (>= (count v) 2)
                              (str/starts-with? v q)
                              (str/ends-with? v q)))]
    (if (or (wrapped? "\"") (wrapped? "'"))
      (subs v 1 (dec (count v)))
      v)))

(defn parse [contents]
  (->> (str/split-lines contents)
       (keep (fn [line]
               (let [line (str/trim line)]
                 (when-not (or (str/blank? line) (str/starts-with? line "#"))
                   (let [[k v] (str/split line #"=" 2)]
                     (when (and k v)
                       [(str/trim (str/replace k #"^export\s+" ""))
                        (unquote-value (str/trim v))]))))))
       (into {})))

(defn merge-file [environment file-contents]
  (merge (parse file-contents) environment))

(defn positive-long
  "A strictly-positive long read from an env map entry.
   Returns nil otherwise."
  [env var-name]
  (when-some [value (get env var-name)]
    (when-some [n (parse-long (str/trim value))]
      (when (pos? n) n))))

(defn flag?
  "True only when the env entry is \"true\"."
  [env var-name]
  (= "true" (some-> (get env var-name) str/trim str/lower-case)))

(defn read
  ([] (read default-file (System/getenv)))
  ([file environment]
   (let [environment (into {} environment)]
     (if (.exists (io/file file))
       (merge-file environment (slurp file))
       environment))))
