(ns adsb.stream.sse
  (:require [clojure.string :as str]))

(def headers
  {"Content-Type"      "text/event-stream; charset=utf-8"
   "Cache-Control"     "no-cache"
   "X-Accel-Buffering" "no"})

(defn- data-lines [data]
  (->> (str/split-lines data)
       (map #(str "data: " %))
       (str/join "\n")))

(defn event-frame [event-name id data]
  (str "event: " event-name "\n"
       "id: " id "\n"
       (data-lines data)
       "\n\n"))

(defn comment-frame [text] (str ": " text "\n\n"))
