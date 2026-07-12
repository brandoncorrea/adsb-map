(ns adsb.stream.sse
  "SSE framing per the EventSource spec: `event:`/`id:`/`data:` lines,
  each frame closed by a blank line; a line starting `:` is a comment
  the browser ignores — sent periodically as a heartbeat so buffering
  proxies see traffic on an otherwise quiet stream. Pure string
  building; the channels live in adsb.stream.broadcast."
  (:require
    [clojure.string :as str]))

(def headers
  "Response headers for an SSE stream. no-cache defeats intermediary
  caching, and X-Accel-Buffering tells nginx-style proxies not to
  buffer the stream to death."
  {"Content-Type"      "text/event-stream; charset=utf-8"
   "Cache-Control"     "no-cache"
   "X-Accel-Buffering" "no"})

(defn- data-lines
  "One `data:` line per line of payload, so the framing survives a
  payload that happens to contain newlines (the spec's own answer)."
  [data]
  (->> (str/split-lines data)
       (map #(str "data: " %))
       (str/join "\n")))

(defn event-frame
  "One complete SSE frame: a named event, a monotonically increasing
  id, and the payload. The id lets a reconnecting EventSource report
  where it left off (Last-Event-ID) — and because every frame carries
  the full picture, the server may ignore that and simply send a fresh
  snapshot."
  [event-name id data]
  (str "event: " event-name "\n"
       "id: " id "\n"
       (data-lines data)
       "\n\n"))

(defn comment-frame
  "A comment frame (`: <text>`) — invisible to EventSource, visible to
  proxies. The heartbeat."
  [text]
  (str ": " text "\n\n"))
