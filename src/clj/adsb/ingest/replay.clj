(ns adsb.ingest.replay
  "A Source that replays the recorded aircraft.json fixture forever, so
  the app runs — and ingest/SSE can be exercised — with no feeder
  reachable (docs/CLAUDE.md: never test against a live feeder; bb dev
  must work off the home network).

  It is the ultrafeeder Source's fixture-backed twin: the same
  open!/fetch!/close! seam, the same already-coerced domain aircraft out
  (adsb.ingest.coerce is the trust boundary for the fixture bytes too).
  What differs is that the picture MOVES. A static snapshot replayed
  verbatim would prove nothing about staleness or the map — every fetch
  would be byte-identical — so fetch! projects the fixture through a
  monotonic replay clock:

  - POSITIONS dead-reckon along each aircraft's own track at its own
    ground speed, so the map visibly moves. Aircraft with no ground
    speed or no track stay put; a position-less aircraft never gains
    one.

  - SEEN ages ACCRUE, at a fraction of the replay clock, so aircraft
    drift toward and across the 60 s stale line — staleness is genuinely
    exercised — while the per-lap reset refreshes them well before the
    300 s age-out, so the picture never empties.

  Everything is a pure function of an injected clock (time is an
  argument), so a fake clock replays the exact same sky deterministically
  — which is the whole unit test."
  (:require [adsb.ingest.coerce :as coerce]
            [adsb.ingest.source :as source]
            [cheshire.core :as json]))

(def ^:const default-fixture-path
  "The recorded payload, read by relative path from the project root:
  test/resources is not on the runtime classpath, and replay is a
  dev/test convenience, never a shipped artifact."
  "test/resources/aircraft-sample.json")

(def ^:const default-loop-ms
  "One replay lap. Positions and seen ages advance across a lap, then
  reset — long enough that dead-reckoning stays in the local area and
  no aircraft reaches the 300 s age-out, short enough that the loop is
  visible."
  240000)

(def ^:const default-age-rate
  "Seen-seconds accrued per replay second. Below 1 for two reasons: the
  age must climb slowly enough to linger near the 60 s stale line rather
  than blow straight past it, and observed time must keep advancing — a
  seen that tracked the clock exactly would freeze observed time and make
  every moving aircraft read as an impossible teleport to the jump
  detector (adsb.ingest.plausibility). At 0.5 the implied speed a moving
  aircraft shows is gs/(1-rate) = 2·gs, comfortably under the 1200 kt
  jump ceiling for this sky's ground speeds."
  0.5)

;; ---------------------------------------------------------------------
;; Dead reckoning — the great-circle destination point, in the shared
;; geo units (meters, degrees). Inlined so src/cljc/adsb/geo stays
;; untouched; the math is the standard direct spherical solution.

(def ^:private earth-radius-m 6371000)
(def ^:private meters-per-nm 1852)

(defn- deg->rad [d] (* d (/ Math/PI 180)))
(defn- rad->deg [r] (* r (/ 180 Math/PI)))

(defn- dead-reckon
  "The position reached from `position` after `elapsed-s` seconds along
  `track-deg` (degrees clockwise from true north) at `gs-kt` knots."
  [{:geo/keys [lat lon]} gs-kt track-deg elapsed-s]
  (let [dist-m  (/ (* gs-kt meters-per-nm elapsed-s) 3600.0)
        ang     (/ dist-m earth-radius-m)
        bearing (deg->rad track-deg)
        lat1    (deg->rad lat)
        lon1    (deg->rad lon)
        lat2    (Math/asin (+ (* (Math/sin lat1) (Math/cos ang))
                              (* (Math/cos lat1) (Math/sin ang)
                                 (Math/cos bearing))))
        lon2    (+ lon1 (Math/atan2 (* (Math/sin bearing) (Math/sin ang)
                                       (Math/cos lat1))
                                    (- (Math/cos ang)
                                       (* (Math/sin lat1) (Math/sin lat2)))))]
    {:geo/lat (rad->deg lat2)
     ;; Normalize longitude back into [-180, 180).
     :geo/lon (- (mod (+ (rad->deg lon2) 540) 360) 180)}))

;; ---------------------------------------------------------------------
;; One fixture aircraft, advanced to a replay instant

(defn- advance
  "One fixture aircraft as of `elapsed-s` into the current lap: position
  dead-reckoned along its track (unchanged when it has no ground speed,
  no track, or no position), and seen age accrued so staleness climbs."
  [{:aircraft/keys [position ground-speed-kt track-deg seen-s] :as aircraft}
   elapsed-s age-rate]
  (let [aged (assoc aircraft :aircraft/seen-s
                    (+ (or seen-s 0) (* age-rate elapsed-s)))]
    (if (and position ground-speed-kt track-deg)
      (assoc aged :aircraft/position
             (dead-reckon position ground-speed-kt track-deg elapsed-s))
      aged)))

;; ---------------------------------------------------------------------
;; The Source

(defn- elapsed-s
  "Seconds into the current lap: replay time since open! folded into
  [0, loop-ms), so positions and ages advance across a lap and reset."
  [start-ms now-ms loop-ms]
  (/ (mod (- now-ms start-ms) loop-ms) 1000.0))

(defrecord ReplaySource [base clock loop-ms age-rate start]
  source/Source
  (open! [this]
    (reset! start (clock))
    this)
  (fetch! [_]
    (let [start-ms (or @start (reset! start (clock)))
          seconds  (elapsed-s start-ms (clock) loop-ms)]
      (mapv #(advance % seconds age-rate) base)))
  (close! [this] this))

(defn- load-fixture!
  "The recorded aircraft.json at `path`, parsed and coerced to domain
  aircraft through the same ingest boundary the live feeder uses."
  [path]
  (-> (slurp path)
      (json/parse-string true)
      :aircraft
      coerce/->aircraft-batch))

(defn ->source
  "A Source replaying the recorded fixture on a loop (see the ns
  docstring). All options are optional:

    :batch         pre-coerced aircraft to replay instead of reading the
                   fixture from disk — for tests
    :fixture-path  where to read the fixture (default the recorded
                   payload)
    :clock         0-arg fn returning epoch ms, monotonic — injected so a
                   fake clock replays deterministically (default the wall
                   clock)
    :loop-ms       lap length (default default-loop-ms)
    :age-rate      seen-seconds per replay second (default
                   default-age-rate)"
  ([] (->source {}))
  ([{:keys [batch fixture-path clock loop-ms age-rate]
     :or   {fixture-path default-fixture-path
            clock        #(System/currentTimeMillis)
            loop-ms      default-loop-ms
            age-rate     default-age-rate}}]
   (->ReplaySource (or batch (load-fixture! fixture-path))
                   clock loop-ms age-rate (atom nil))))
