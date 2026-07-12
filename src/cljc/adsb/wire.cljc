(ns adsb.wire
  "The SSE wire format: the one place that decides what a domain
  aircraft looks like as JSON and how the browser turns it back. Pure —
  the server (adsb.stream.broadcast) encodes with picture->wire, the
  browser SSE client (adsb-2yu.2) decodes with wire->picture, and
  because both directions live in this shared namespace the contract
  cannot drift.

  ## The format

  Every SSE data payload — `snapshot` and `update` events alike — is
  one envelope carrying the FULL current picture and a small stats map:

      {\"at\": 1720713600000,
       \"stats\": {\"max-range-km\": 312, \"message-rate\": 148},
       \"aircraft\": [<wire aircraft> ...]}

  `at` is the epoch-ms instant the frame was built. An update is not a
  delta, so a client treats every frame as a wholesale replacement and
  can never accumulate drift; a reconnect needs no replay.

  `stats` is the session readout the server computes (adsb.stats) — two
  SCALARS, no position:

      max-range-km   whole-km distance to the furthest aircraft this
                     antenna has heard this session. A NUMBER reveals no
                     position; OMITTED when the receiver position is
                     unavailable (no reference to measure from).
      message-rate   feeder messages per second, OMITTED when unknown.

  Aircraft counts are NOT on the wire: the browser derives total and
  positioned counts from the `aircraft` array itself (adsb.ui.header),
  so duplicating them here would be a second source of truth.

  A wire aircraft uses simple, UNqualified kebab-case keys — the same
  choice adsb.geo makes for GeoJSON feature properties, and for the
  same reason: JSON has no keyword namespaces, so the wire names fields
  plainly and this namespace owns the mapping back to domain keys.
  Absent facts are OMITTED, never defaulted — absent is not zero
  (docs/validation-boundaries.md):

      icao              :aircraft/icao              always present
      callsign          :aircraft/callsign
      lat, lon          :aircraft/position          both or neither
      altitude          :aircraft/altitude-ft       feet
      on-ground         :aircraft/on-ground?        true only, else omitted
      squawk            :aircraft/squawk            four octal digits, string
      ground-speed      :aircraft/ground-speed-kt   knots
      track             :aircraft/track-deg         degrees from true north
      baro-rate         :aircraft/baro-rate-fpm     feet per minute
      seen-at           :aircraft/seen-at-ms        epoch ms last heard
      position-suspect  :aircraft/position-suspect? true only — the
                        spoofing fingerprint (adsb.ingest.plausibility),
                        flagged so the browser can surface it
      mlat              :aircraft/mlat?             true only — the
                        position is multilaterated, not ADS-B
                        (adsb.ingest.coerce); lower confidence, so the
                        browser can render it distinctly

  ## Privacy (the adsb-kbm.2 mandate)

  The receiver's position is private. The wire must NEVER carry it, nor
  any receiver-relative field it could be recovered from — r_dst and
  r_dir are trivially invertible to the antenna location. aircraft->wire
  is therefore an ALLOWLIST projection: only the keys named above can
  ever reach the wire, so a receiver-relative field that someday appears
  on a domain aircraft is excluded here by construction, not by
  enumeration. :aircraft/rssi is excluded for the same reason — signal
  strength is a measurement of the receiver, not of the aircraft.

  The stats map is held to the same discipline: stats->wire is an
  ALLOWLIST projecting only the two scalars above. max-range-km is a
  DISTANCE, not a position — a radius reveals no bearing, so no antenna
  location can be recovered from it. The receiver's coordinates and every
  receiver-relative field are excluded by construction.")

(defn aircraft->wire
  "Project one domain aircraft onto its wire shape (see the ns
  docstring). An allowlist: keys not named here — including any
  receiver-relative field — never reach the wire."
  [{:aircraft/keys [icao callsign position altitude-ft on-ground? squawk
                    ground-speed-kt track-deg baro-rate-fpm seen-at-ms
                    position-suspect? mlat?]}]
  (cond-> {:icao icao}
          callsign (assoc :callsign callsign)
          position (assoc :lat (:geo/lat position)
                          :lon (:geo/lon position))
          altitude-ft (assoc :altitude altitude-ft)
          on-ground? (assoc :on-ground true)
          squawk (assoc :squawk squawk)
          ground-speed-kt (assoc :ground-speed ground-speed-kt)
          track-deg (assoc :track track-deg)
          baro-rate-fpm (assoc :baro-rate baro-rate-fpm)
          seen-at-ms (assoc :seen-at seen-at-ms)
          position-suspect? (assoc :position-suspect true)
          mlat? (assoc :mlat true)))

(defn stats->wire
  "Project the server stats (adsb.stats, :stats/-namespaced) onto the
  envelope's `stats` map. An ALLOWLIST, exactly like aircraft->wire: only
  the two scalars named in the ns docstring may leave, so a
  receiver-relative field that someday joins the stats map is excluded
  here by construction. Absent facts are omitted — no receiver position
  means no max-range-km, an unknown feeder rate means no message-rate.
  nil stats yields an empty map."
  [{:stats/keys [max-range-km message-rate]}]
  (cond-> {}
          max-range-km (assoc :max-range-km max-range-km)
          message-rate (assoc :message-rate message-rate)))

(defn picture->wire
  "The picture (icao -> aircraft) and the session `stats` as one frame
  envelope, built at `at-ms`. Sent as the connect-time snapshot and as
  every update. `stats` is the server stats map (adsb.stats) or nil."
  [picture stats at-ms]
  {:at       at-ms
   :stats    (stats->wire stats)
   :aircraft (mapv aircraft->wire (vals picture))})

(defn wire->aircraft
  "The inverse projection: one decoded wire aircraft (keywordized JSON)
  back into a domain aircraft. For the browser SSE client (adsb-2yu.2)."
  [{:keys [icao callsign lat lon altitude on-ground squawk ground-speed
           track baro-rate seen-at position-suspect mlat]}]
  (cond-> {:aircraft/icao icao}
          callsign (assoc :aircraft/callsign callsign)
          (and lat lon) (assoc :aircraft/position {:geo/lat lat :geo/lon lon})
          altitude (assoc :aircraft/altitude-ft altitude)
          on-ground (assoc :aircraft/on-ground? true)
          squawk (assoc :aircraft/squawk squawk)
          ground-speed (assoc :aircraft/ground-speed-kt ground-speed)
          track (assoc :aircraft/track-deg track)
          baro-rate (assoc :aircraft/baro-rate-fpm baro-rate)
          seen-at (assoc :aircraft/seen-at-ms seen-at)
          position-suspect (assoc :aircraft/position-suspect? true)
          mlat (assoc :aircraft/mlat? true)))

(defn wire->stats
  "The inverse projection: a decoded envelope's `stats` map back into the
  server-stats vocabulary (:stats/-namespaced) the browser subscribes to.
  Absent keys stay absent — the readout dashes them, never zeroes them."
  [{:keys [stats]}]
  (let [{:keys [max-range-km message-rate]} stats]
    (cond-> {}
            max-range-km (assoc :stats/max-range-km max-range-km)
            message-rate (assoc :stats/message-rate message-rate))))

(defn wire->picture
  "A decoded frame envelope back into the domain picture, icao ->
  aircraft — what the browser holds between frames."
  [{:keys [aircraft]}]
  (into {}
        (map (fn [wire-aircraft]
               (let [domain-aircraft (wire->aircraft wire-aircraft)]
                 [(:aircraft/icao domain-aircraft) domain-aircraft])))
        aircraft))
