(ns adsb.schema)

(def icao-address
  [:re {:error/message "icao must be 6 hex digits, optionally ~-prefixed"}
   #"(?i)^~?[0-9a-f]{6}$"])

(def squawk [:re #"^[0-7]{4}$"])
(def latitude [:and number? [:>= -90] [:<= 90]])
(def longitude [:and number? [:>= -180] [:<= 180]])

(def emitter-category
  ;; Set D (D0-D7) is omitted deliberately: readsb occasionally emits it, but
  ;; the category is mostly reserved and carries nothing we render, so a set-D
  ;; value drops the FIELD (advisory), never the aircraft.
  (into [:enum]
        (for [set  ["A" "B" "C"]
              code (range 8)]
          (str set code))))

(def raw-aircraft
  [:map
   [:hex icao-address]
   [:alt_baro {:optional true} [:maybe [:or number? [:= "ground"]]]]
   [:flight {:optional true} [:maybe [:string {:max 8}]]]
   [:lat {:optional true} [:maybe latitude]]
   [:lon {:optional true} [:maybe longitude]]
   [:squawk {:optional true} [:maybe squawk]]
   [:gs {:optional true} [:maybe number?]]
   [:track {:optional true} [:maybe number?]]
   [:baro_rate {:optional true} [:maybe number?]]
   [:seen {:optional true} [:maybe number?]]
   [:seen_pos {:optional true} [:maybe number?]]])
;; type/category/mlat are deliberately absent above: they are advisory
;; fields, and garbage in them costs the FIELD, never the aircraft — the
;; coercion layer field-validates each. The typed fields above are
;; arithmetic inputs; garbage there rejects the entry at the boundary.
;; rssi is absent because it is never read at all: receiver-relative
;; signal strength locates the antenna, so it dies at ingest (adsb-4qi),
;; alongside r_dst/r_dir.

(def position
  [:map
   [:geo/lat latitude]
   [:geo/lon longitude]])

(def aircraft
  [:map
   [:aircraft/icao icao-address]
   [:aircraft/callsign {:optional true} [:string {:min 1 :max 8}]]
   [:aircraft/position {:optional true} position]
   [:aircraft/altitude-ft {:optional true} number?]
   [:aircraft/on-ground? {:optional true} :boolean]
   [:aircraft/squawk {:optional true} squawk]
   [:aircraft/category {:optional true} emitter-category]
   [:aircraft/ground-speed-kt {:optional true} number?]
   [:aircraft/track-deg {:optional true} number?]
   [:aircraft/baro-rate-fpm {:optional true} number?]
   ;; seen-s / position-seen-s are feeder-relative ages: inputs that
   ;; aircraft/->observation converts to absolute -at-ms and dissocs pre-merge,
   ;; so they are accepted on the way in but never served on the way out.
   [:aircraft/seen-s {:optional true} number?]
   [:aircraft/seen-at-ms {:optional true} number?]
   [:aircraft/position-seen-s {:optional true} number?]
   [:aircraft/position-at-ms {:optional true} number?]
   [:aircraft/position-suspect? {:optional true} :boolean]
   [:aircraft/mlat? {:optional true} :boolean]])

(def ^:const min-plausible-altitude-ft -1500)
(def ^:const max-plausible-altitude-ft 60000)
(def ^:const max-plausible-ground-speed-kt 1000)

(def plausible-altitude-ft
  [:and number?
   [:>= min-plausible-altitude-ft]
   [:<= max-plausible-altitude-ft]])

(def plausible-ground-speed-kt
  [:and number? [:>= 0] [:<= max-plausible-ground-speed-kt]])
