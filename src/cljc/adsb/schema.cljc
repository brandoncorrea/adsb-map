(ns adsb.schema
  "Malli schemas for the ingest trust boundary and the domain aircraft.

  The feeder relays unauthenticated radio as JSON, so `raw-aircraft`
  describes what actually arrives — warts included — and `aircraft`
  describes the shape the rest of the system may trust completely.
  See docs/validation-boundaries.md.")

(def icao-address
  "An ICAO 24-bit address: six hex digits, optionally `~`-prefixed for
  non-ICAO (TIS-B / ADS-R) targets. Stays a string — never keywordized.
  Case-insensitive on the way in (the feeder and URLs may use either
  case); ingest normalizes domain identities to lower-case
  (see adsb.ingest.coerce)."
  [:re {:error/message "icao must be 6 hex digits, optionally ~-prefixed"}
   #"(?i)^~?[0-9a-f]{6}$"])

;; ---------------------------------------------------------------------
;; Raw feeder vocabulary (aircraft.json)

(def squawk
  "Four octal digits, as a string. \"0000\" is meaningful and not nil."
  [:re #"^[0-7]{4}$"])

(def latitude [:and number? [:>= -90] [:<= 90]])

(def longitude [:and number? [:>= -180] [:<= 180]])

(def raw-aircraft
  "One entry of the feeder's `aircraft` array, as it actually arrives.

  Open map: real payloads carry dozens of other keys. Every field except
  `hex` may be absent, and any field may be an explicit null — both mean
  the decoder had nothing to say. Absent is not zero."
  [:map
   [:hex icao-address]
   ;; A number, OR the string "ground", OR absent entirely — ~13% of
   ;; real observations are bare mode_s targets with no altitude.
   [:alt_baro {:optional true} [:maybe [:or number? [:= "ground"]]]]
   ;; The callsign, space-padded to 8 chars ("UPS2717 "). Often absent.
   [:flight {:optional true} [:maybe [:string {:max 8}]]]
   [:lat {:optional true} [:maybe latitude]]
   [:lon {:optional true} [:maybe longitude]]
   [:squawk {:optional true} [:maybe squawk]]
   [:gs {:optional true} [:maybe number?]]
   [:track {:optional true} [:maybe number?]]
   [:baro_rate {:optional true} [:maybe number?]]
   [:seen {:optional true} [:maybe number?]]
   [:rssi {:optional true} [:maybe number?]]])

;; ---------------------------------------------------------------------
;; Domain vocabulary

(def position
  [:map
   [:geo/lat latitude]
   [:geo/lon longitude]])

(def aircraft
  "A domain aircraft. Everything past the ingest boundary trusts this
  shape completely. An absent key means the sky never said — never zero.
  `:aircraft/position` is absent for heard-but-never-positioned targets,
  which are kept (see docs/validation-boundaries.md)."
  [:map
   [:aircraft/icao icao-address]
   [:aircraft/callsign {:optional true} [:string {:min 1 :max 8}]]
   [:aircraft/position {:optional true} position]
   [:aircraft/altitude-ft {:optional true} number?]
   [:aircraft/on-ground? {:optional true} :boolean]
   [:aircraft/squawk {:optional true} squawk]
   [:aircraft/ground-speed-kt {:optional true} number?]
   [:aircraft/track-deg {:optional true} number?]
   [:aircraft/baro-rate-fpm {:optional true} number?]
   [:aircraft/seen-s {:optional true} number?]
   ;; Stamped by adsb.aircraft/merge-batch (capture time minus seen);
   ;; absent on aircraft fresh from ingest.
   [:aircraft/seen-at-ms {:optional true} number?]
   [:aircraft/rssi {:optional true} number?]
   ;; Set at merge time when a new position implies an impossible jump
   ;; from the previous observation — the fingerprint of spoofing.
   ;; Flagged and surfaced, never dropped or clamped; cleared by the
   ;; next consistent observation (adsb.ingest.plausibility).
   [:aircraft/position-suspect? {:optional true} :boolean]])

;; ---------------------------------------------------------------------
;; Plausibility — a second, separate layer from schema validity.
;;
;; A schema-valid value can still be physical nonsense (400,000 ft,
;; 3,000 kt). An implausible value costs the FIELD, never the aircraft,
;; and is never clamped into range. Receiver-range gating and position
;; jump detection need more than one field and live in
;; adsb.ingest.plausibility.

(def ^:const min-plausible-altitude-ft -1500)  ; below-sea-level airports
(def ^:const max-plausible-altitude-ft 60000)  ; above all civil traffic
(def ^:const max-plausible-ground-speed-kt 1000)

(def plausible-altitude-ft
  [:and number?
   [:>= min-plausible-altitude-ft]
   [:<= max-plausible-altitude-ft]])

(def plausible-ground-speed-kt
  ;; Real ground speeds arrive as doubles (450.5), hence number bounds.
  [:and number? [:>= 0] [:<= max-plausible-ground-speed-kt]])
