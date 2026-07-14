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

(def emitter-category
  "The ADS-B emitter category the aircraft TRANSMITS about itself: a set
  letter and a code, \"A3\", \"A7\", \"C2\". Three sets exist in the sky —
  A (fixed-wing, by weight class, A7 rotorcraft), B (glider, balloon,
  parachutist, UAV), C (surface vehicles and obstacles) — each with eight
  codes, of which 0 means the aircraft declined to say. Set D is reserved
  by the spec and nothing emits it.

  A CLOSED enum, and closed is the whole point: the feeder is
  unauthenticated radio (docs/validation-boundaries.md), so `category` is
  an attacker-controlled string. Only the 24 values named here may enter
  the domain.

  Deliberately NOT a member of `raw-aircraft` below, though it arrives on
  the same entry. Every field declared there is validate-or-REJECT — a
  malformed squawk costs the whole aircraft — and that is exactly the
  wrong trade for this one: a category we failed to enumerate (a spec
  revision, a feeder's private extension) would drop the aircraft out of
  the picture entirely rather than merely out of the symbology. So
  category is checked as a FIELD, the way an absurd altitude is
  (adsb.ingest.coerce): an unrecognized value costs the FIELD and yields
  ABSENCE, never a passthrough and never the aircraft."
  (into [:enum] (for [set ["A" "B" "C"] code (range 8)] (str set code))))

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
   ;; NOT a true-or-absent marker: an explicit false means the aircraft
   ;; said it is airborne, and absent means it never said either way. The
   ;; streaming path needs that distinction — deltas fold through a plain
   ;; merge, so only a false can clear a landed aircraft's stale true once
   ;; it takes off again (adsb.ingest.sbs, adsb-b0w).
   [:aircraft/on-ground? {:optional true} :boolean]
   [:aircraft/squawk {:optional true} squawk]
   ;; What the airframe says it is (`emitter-category`) — an OBSERVATION
   ;; off the wire, not reference data about the type, so it rides the
   ;; live path like track and squawk. Absent for the aircraft that never
   ;; transmit one (and for every MLAT target), which the map treats as
   ;; the generic plane — absence is first-class here, as everywhere.
   [:aircraft/category {:optional true} emitter-category]
   [:aircraft/ground-speed-kt {:optional true} number?]
   [:aircraft/track-deg {:optional true} number?]
   [:aircraft/baro-rate-fpm {:optional true} number?]
   [:aircraft/seen-s {:optional true} number?]
   ;; The absolute instant the aircraft was heard. Stamped when the
   ;; message arrives on the streaming path (adsb.accumulator), and
   ;; derived at merge from capture time minus seen on the poll path
   ;; (adsb.aircraft/observed-at-ms) — which honours an existing stamp
   ;; rather than overwriting it. Absent on a polled aircraft fresh from
   ;; ingest, which carries only the capture-relative seen-s.
   [:aircraft/seen-at-ms {:optional true} number?]
   ;; seen-s/seen-at-ms answer "when did we last hear ANYTHING from this
   ;; aircraft"; these two answer "when did its POSITION last move". They
   ;; are not the same question, and the jump detector needs this one:
   ;; most messages carry no position (velocity, callsign, squawk), so a
   ;; position-to-position hop divided by time-since-last-MESSAGE reads an
   ;; ordinary airliner as a 27,000 kt teleport (adsb-zxk). The feeder
   ;; publishes seen_pos beside seen for exactly this reason. Capture-
   ;; relative on the way in, absolute once merged — the seen-s/seen-at-ms
   ;; pairing, applied to the position.
   [:aircraft/position-seen-s {:optional true} number?]
   [:aircraft/position-at-ms {:optional true} number?]
   [:aircraft/rssi {:optional true} number?]
   ;; Set at merge time when a new position implies an impossible jump
   ;; from the previous observation — the fingerprint of spoofing.
   ;; Flagged and surfaced, never dropped or clamped; sticks for the
   ;; track's life in the picture and is cleared only by age-out, never
   ;; by anything the aircraft transmits (adsb.ingest.plausibility).
   [:aircraft/position-suspect? {:optional true} :boolean]
   ;; True only when the position derives from multilateration rather
   ;; than the aircraft's own ADS-B — lower-confidence data the UI
   ;; renders distinctly (adsb.ingest.coerce). Like position-suspect?,
   ;; this is a true-or-absent marker: absent means not-MLAT, never an
   ;; explicit false.
   [:aircraft/mlat? {:optional true} :boolean]])

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
