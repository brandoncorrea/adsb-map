(ns adsb.http.assets
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.security MessageDigest)))

(def ^:const asset-root "/assets")
(def ^:const fingerprinted-assets ["js/main.js" "app.css" "maplibre-gl.css"])
(def ^:const immutable-cache-control "public, max-age=31536000, immutable")
(def ^:const document-cache-control "public, no-cache")
(def ^:const data-cache-control "public, max-age=86400")
(def ^:const long-lived-prefixes ["/db/" "/glyphs/" "/fonts/"])

(defn- long-lived? [uri]
  (some (partial str/starts-with? uri) long-lived-prefixes))

(defn- resource [path]
  (io/resource (str "public/" path)))

(defn- hex [bytes]
  (str/join (map #(format "%02x" %) bytes)))

(defn content-version [entries]
  (let [digest (MessageDigest/getInstance "SHA-256")
        buffer (byte-array 8192)]
    (doseq [[name source] entries]
      (.update digest (.getBytes ^String name "UTF-8"))
      (when source
        (with-open [in (io/input-stream source)]
          (loop []
            (let [n (.read in buffer)]
              (when (pos? n)
                (.update digest buffer 0 n)
                (recur)))))))
    (subs (hex (.digest digest)) 0 12)))

(defn- digest-version []
  (->> fingerprinted-assets
       (map (juxt identity resource))
       (content-version)))

(defn- staleness-key []
  (mapv (fn [path]
          (when-let [res (resource path)]
            (let [conn (.openConnection res)]
              [path (.getLastModified conn) (.getContentLengthLong conn)])))
        fingerprinted-assets))

(defn asset-url [version path]
  (str asset-root "/" version "/" path))

(defn- versioned-path [uri]
  (when-let [[_ tail] (re-matches #"/assets/[^/]+(/.+)" uri)]
    tail))

(defn index-html [version]
  (when-let [res (resource "index.html")]
    (reduce (fn [html path]
              (str/replace html
                           (str "\"/" path "\"")
                           (str "\"" (asset-url version path) "\"")))
            (slurp res)
            fingerprinted-assets)))

(def ^:private cache (atom nil))

(defn- current! []
  (let [probe (staleness-key)]
    (or (when-let [cached @cache]
          (when (= (:probe cached) probe) cached))
        (let [version (digest-version)]
          (reset! cache {:probe   probe
                         :version version
                         :html    (index-html version)})))))

(defn version! [] (:version (current!)))

(def ^:private index-uris #{"/" "/index.html"})

(defn- document-response [html]
  {:status  200
   :headers {"Content-Type"  "text/html; charset=utf-8"
             "Cache-Control" document-cache-control}
   :body    html})

(defn- cache-control-for [uri]
  (if (long-lived? uri)
    data-cache-control
    document-cache-control))

(defn handler [resources]
  (fn [{:keys [uri] :as request}]
    (let [tail (versioned-path uri)]
      (cond
        (contains? index-uris uri)
        (when-let [html (:html (current!))]
          (document-response html))

        tail
        (some-> (resources (assoc request :uri tail))
                (assoc-in [:headers "Cache-Control"] immutable-cache-control))

        :else
        (some-> (resources request)
                (assoc-in [:headers "Cache-Control"] (cache-control-for uri)))))))
