(ns adsb.aircraft
  "The aircraft domain model and the current picture of the sky. Pure —
  no I/O, no clock; time is an argument. The aircraft shape itself is
  defined in adsb.schema and produced at the ingest boundary by
  adsb.ingest.coerce.

  The picture is a plain map of icao -> aircraft. Each poll delivers a
  full snapshot of what the feeder currently tracks; merge-batch folds
  it in, and age-out drops aircraft the sky has gone quiet on. An
  aircraft absent from a single poll is retained — it leaves the
  picture by silence, never by absence from one snapshot.")

(def ^:const stale-threshold-ms 60000)

(def ^:const age-out-threshold-ms
  "Silence past this removes an aircraft from the picture entirely —
  the long-silent cast member's fate (docs/testing-standards.md).

  2 minutes (adsb-rg1). Was 5 minutes; that left ghost tracks hanging long
  after the radio went quiet. Stale fade still begins at 60 s, so the last
  minute is a visible fade-out rather than a sudden pop."
  120000)

(defn positioned?
  "True when the aircraft has ever reported a position. Position-less
  is a lawful domain state — heard on the radio, never located — kept
  in the picture and the sidebar, just never on the map."
  [aircraft]
  (contains? aircraft :aircraft/position))

;; ---------------------------------------------------------------------
;; Emergency squawks
;;
;; Three transponder codes are reserved distress signals, and each means
;; something different — a fact worth keeping in the DOMAIN, not buried in
;; a boolean at the map boundary. adsb.geo delegates its `emergency`
;; feature property here; the chrome (ribbon, badges) reads the KIND so it
;; can name the emergency in words. The human-readable words themselves
;; ("hijacking", "radio failure", "general emergency") are presentation and
;; live at the UI edge, not here — the domain knows the kind, not the copy.

(def ^:const emergency-squawks
  "Distress squawk -> the kind of emergency it signals. 7500 is a hijack,
  7600 a radio (comms) failure, 7700 a general emergency."
  {"7500" :hijack
   "7600" :radio-failure
   "7700" :general})

(defn squawk->emergency-kind
  "The kind of emergency a squawk code signals — :hijack, :radio-failure,
  or :general — or nil for an ordinary (or absent) squawk."
  [squawk]
  (get emergency-squawks squawk))

(defn emergency-kind
  "The kind of emergency an aircraft is squawking, or nil when it is not
  squawking a distress code."
  [{:aircraft/keys [squawk]}]
  (squawk->emergency-kind squawk))

(defn emergency?
  "True when the aircraft is squawking one of the three distress codes."
  [aircraft]
  (some? (emergency-kind aircraft)))

(defn stale?
  "True when the aircraft has not been heard from within the stale
  threshold. :aircraft/seen-at-ms is stamped by merge-batch."
  [{:aircraft/keys [seen-at-ms]} now-ms]
  (> (- now-ms seen-at-ms) stale-threshold-ms))

(defn aged-out?
  "True when the aircraft has been silent past the age-out threshold
  and no longer belongs in the picture at all."
  [{:aircraft/keys [seen-at-ms]} now-ms]
  (> (- now-ms seen-at-ms) age-out-threshold-ms))

(defn observed-at-ms
  "The absolute instant an ingested aircraft was heard, given the
  instant its batch was captured.

  An aircraft that ALREADY carries :aircraft/seen-at-ms was stamped
  when it was heard, by the source that assembled it — the streaming
  accumulator stamps every message with its receiving instant
  (adsb.accumulator/merge-delta), and its snapshots carry no seen-s at
  all. That stamp is the observation; re-deriving one from capture time
  would re-hear a 100 s-silent aircraft as if it had just spoken, which
  is both a lie about freshness and, to the jump detector, a 33,000 kt
  teleport (adsb-0g0).

  Otherwise the stamp is derived. The feeder's seen is seconds-since-
  last-message at CAPTURE time (aircraft.json), so the observation
  instant is capture minus seen — treating arrival as observation would
  make a message heard 250 s before capture look fresh. Neither field
  pins the observation to the capture itself: the freshest instant we
  can honestly claim, so an aircraft can only be older than the picture
  says, never fresher."
  [{:aircraft/keys [seen-at-ms seen-s]} captured-at-ms]
  (long (or seen-at-ms
            (- captured-at-ms (* 1000 (or seen-s 0))))))

(defn position-observed-at-ms
  "The absolute instant an aircraft's POSITION was last updated, given
  the instant its batch was captured — observed-at-ms, asked about the
  position rather than about the aircraft.

  The two answers differ, and the difference is the whole point. Most
  messages carry no position at all: a velocity, a callsign, a squawk
  all refresh seen without moving the aircraft an inch. Measuring a
  position-to-position hop against time-since-last-MESSAGE therefore
  divides a real distance by a fictitious near-zero interval, and reads
  an airliner at cruise as a teleport (adsb-zxk).

  An aircraft that already carries :aircraft/position-at-ms was stamped
  when its position arrived (adsb.accumulator/merge-delta) — that stamp
  wins, as it does in observed-at-ms. Otherwise the feeder's seen_pos is
  seconds-since-the-position-moved at capture time, so the instant is
  capture minus that. A positioned aircraft with neither — a feeder that
  omits seen_pos — falls back to when it was last heard: the old,
  optimistic answer, which is the freshest instant we can honestly claim
  and errs toward calling a jump ordinary rather than inventing one."
  [{:aircraft/keys [position-at-ms position-seen-s] :as aircraft}
   captured-at-ms]
  (long (or position-at-ms
            (when position-seen-s
              (- captured-at-ms (* 1000 position-seen-s)))
            (observed-at-ms aircraft captured-at-ms))))

(defn- ->observation
  "Stamp an ingested aircraft with the absolute instant it was heard
  (observed-at-ms), and its position with the instant that MOVED
  (position-observed-at-ms). The capture-relative :aircraft/seen-s and
  :aircraft/position-seen-s are dropped — they rot the moment the poll
  ends; the absolute stamps are their durable form."
  [aircraft captured-at-ms]
  (cond-> (-> aircraft
              (dissoc :aircraft/seen-s :aircraft/position-seen-s)
              (assoc :aircraft/seen-at-ms
                     (observed-at-ms aircraft captured-at-ms)))
          (positioned? aircraft)
          (assoc :aircraft/position-at-ms
                 (position-observed-at-ms aircraft captured-at-ms))))

(defn- merge-observation
  "One aircraft's step of merge-batch: the new observation wins, except
  a position-less observation inherits the last-known position — and the
  stamp that says when that position was last true. The two travel
  together or not at all: an inherited position re-stamped as fresh is
  the very lie that makes ordinary flight look like a teleport."
  [previous observation]
  (if (or (positioned? observation) (not (positioned? previous)))
    observation
    (merge observation
           (select-keys previous [:aircraft/position
                                  :aircraft/position-at-ms]))))

(defn merge-batch
  "Merge one coerced feeder batch — a full snapshot of what the feeder
  currently tracks, captured at captured-at-ms — into the picture.

  Present in both: the new observation replaces the old wholesale,
  except position — the last-known position is retained when the new
  observation has none, because an aircraft that momentarily stops
  sending positions has not teleported to nowhere. Every other absent
  field goes absent: the feeder already carries last-known values for
  a live track, so a field it stopped reporting is a field it no
  longer stands behind, and retaining it here would launder stale data
  as current (docs/validation-boundaries.md — absent is not zero).

  Newly appeared: added. Missing from this poll: retained untouched —
  aircraft leave the picture by age-out, never by absence from one
  snapshot."
  [picture batch captured-at-ms]
  (reduce
    (fn [merged aircraft]
      (update merged (:aircraft/icao aircraft)
              merge-observation (->observation aircraft captured-at-ms)))
    picture
    batch))

(defn age-out
  "The picture without aircraft silent past the age-out threshold."
  [picture now-ms]
  (into {}
        (remove (fn [[_icao aircraft]] (aged-out? aircraft now-ms)))
        picture))
