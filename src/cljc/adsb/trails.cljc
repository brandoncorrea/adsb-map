(ns adsb.trails
  "Pure per-aircraft position history and its GeoJSON trail conversion.
  No MapLibre, no I/O, no clock, no atoms — the imperative aircraft layer
  (adsb.map.aircraft-layer) owns the accumulating `!state`; this namespace
  only says what the next history and the next FeatureCollection ARE, given
  the current ones. Runs identically on the JVM and in the browser, which is
  what lets clojure.test exercise the ring semantics against literals.

  ## The history shape

  History is a plain map, `icao -> ring`, where a ring is a VECTOR of the
  domain's `{:geo/lat _ :geo/lon _}` positions, oldest first, newest last.
  It is a client-session accumulation — no persistence (the Overseer's call).

  ## Two invariants keep memory bounded

  1. Each ring is capped at `max-positions` (oldest dropped on overflow), so
     one aircraft can never grow without limit.
  2. `accumulate` rebuilds history from the aircraft PRESENT in each frame's
     picture — an aircraft that has left the picture is simply not carried
     forward, so its ring is dropped and reclaimed. History can therefore
     never hold more aircraft than the sky currently does.

  Memory bound: `max-positions` (60) positions x the live-aircraft count.
  A position is two doubles; 60 x ~a few hundred aircraft is a few MB, and
  it does not grow with time — only with the size of the sky.

  ## Append-on-change

  A position is appended only when it DIFFERS from the ring's newest point.
  A stationary aircraft (repeating its last position) and a position-less
  update both append nothing — the trail records movement, not the passage
  of time. Time-based disappearance is the LAYER's job (an aged-out aircraft
  drops out of the picture, and `history->trail-feature-collection` renders
  only the icaos the layer says are live).")

(def ^:const max-positions
  "The per-aircraft ring cap. At the feeder's ~1 Hz this is roughly a
  minute of trail — long enough to read the aircraft's recent path, short
  enough that history stays a few MB across a busy sky. DATA: lengthen for
  a longer ribbon, at a linear memory cost."
  60)

(defn append-position
  "Append `position` (a `{:geo/lat _ :geo/lon _}`) to `ring`, returning the
  new ring. A no-op when `position` equals the ring's newest point — the
  trail records movement, not stillness. Caps the result at `max-positions`,
  dropping the oldest. `ring` may be nil (an aircraft's first sighting)."
  [ring position]
  (let [ring (or ring [])]
    (if (= position (peek ring))
      ring
      (let [grown (conj ring position)
            n     (count grown)]
        (if (> n max-positions)
          (subvec grown (- n max-positions))
          grown)))))

(defn accumulate
  "Fold one frame's picture (a seq of domain aircraft) into trail `history`,
  returning the next `icao -> ring`. Only aircraft present in the picture
  survive — a departed aircraft's ring is dropped (memory reclaimed). A
  positioned aircraft appends its position (on change, capped); a
  position-less aircraft keeps whatever ring it already had, appending
  nothing."
  [history picture]
  (reduce
    (fn [acc ac]
      (let [icao (:aircraft/icao ac)
            ring (get history icao)]
        (if-let [position (:aircraft/position ac)]
          (assoc acc icao (append-position ring position))
          (cond-> acc ring (assoc icao ring)))))
    {}
    picture))

(defn- trail-feature
  "One aircraft's ring as a GeoJSON LineString Feature. Coordinates are
  [lon lat] (GeoJSON order), oldest first — so a line-progress gradient runs
  tail (0, the oldest point) to head (1, nearest the aircraft)."
  [icao ring]
  {:type       "Feature"
   :properties {:icao icao}
   :geometry   {:type        "LineString"
                :coordinates (mapv (fn [{:geo/keys [lat lon]}] [lon lat]) ring)}})

(defn history->trail-feature-collection
  "History (`icao -> ring`) as a GeoJSON FeatureCollection of LineString
  trails. A feature is emitted only for an aircraft whose icao is in
  `live-icaos` AND whose ring holds at least two points — a lone point is
  not a line, and an aircraft the layer no longer draws (aged out, departed)
  must not leave a trail behind. `live-icaos` is exactly the set of icaos in
  the aircraft FeatureCollection the layer just built, so trails vanish in
  lockstep with their aircraft. Plain data, ready for clj->js."
  [history live-icaos]
  {:type     "FeatureCollection"
   :features (into []
                   (keep (fn [[icao ring]]
                           (when (and (contains? live-icaos icao)
                                      (>= (count ring) 2))
                             (trail-feature icao ring))))
                   history)})
