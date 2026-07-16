(ns adsb.env
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

(defn read!
  ([] (read! default-file (System/getenv)))
  ([file environment]
   (let [environment (into {} environment)]
     (if (.exists (io/file file))
       (merge-file environment (slurp file))
       environment))))
