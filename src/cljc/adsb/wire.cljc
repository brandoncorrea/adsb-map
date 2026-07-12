(ns adsb.wire
  "The SSE wire format: the one place that decides what a domain
  aircraft looks like as JSON and how the browser turns it back. Pure —
  the server (adsb.stream.broadcast) encodes with picture->wire, the
  browser SSE client (adsb-2yu.2) decodes with wire->picture, and
  because both directions live in this shared namespace the contract
  cannot drift.

  ## The format

  Every SSE data payload — `snapshot` and `update` events alike — is
  one envelope carrying the FULL current picture:

      {\"at\": 1720713600000, \"aircraft\": [<wire aircraft> ...]}

  `at` is the epoch-ms instant the frame was built. An update is not a
  delta, so a client treats every frame as a wholesale replacement and
  can never accumulate drift; a reconnect needs no replay.

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

  ## Privacy (the adsb-kbm.2 mandate)

  The receiver's position is private. The wire must NEVER carry it, nor
  any receiver-relative field it could be recovered from — r_dst and
  r_dir are trivially invertible to the antenna location. aircraft->wire
  is therefore an ALLOWLIST projection: only the keys named above can
  ever reach the wire, so a receiver-relative field that someday appears
  on a domain aircraft is excluded here by construction, not by
  enumeration. :aircraft/rssi is excluded for the same reason — signal
  strength is a measurement of the receiver, not of the aircraft.")

(defn aircraft->wire
  "Project one domain aircraft onto its wire shape (see the ns
  docstring). An allowlist: keys not named here — including any
  receiver-relative field — never reach the wire."
  [{:aircraft/keys [icao callsign position altitude-ft on-ground? squawk
                    ground-speed-kt track-deg baro-rate-fpm seen-at-ms
                    position-suspect?]}]
  (cond-> {:icao icao}
    callsign          (assoc :callsign callsign)
    position          (assoc :lat (:geo/lat position)
                             :lon (:geo/lon position))
    altitude-ft       (assoc :altitude altitude-ft)
    on-ground?        (assoc :on-ground true)
    squawk            (assoc :squawk squawk)
    ground-speed-kt   (assoc :ground-speed ground-speed-kt)
    track-deg         (assoc :track track-deg)
    baro-rate-fpm     (assoc :baro-rate baro-rate-fpm)
    seen-at-ms        (assoc :seen-at seen-at-ms)
    position-suspect? (assoc :position-suspect true)))

(defn picture->wire
  "The picture (icao -> aircraft) as one frame envelope, built at
  `at-ms`. Sent as the connect-time snapshot and as every update."
  [picture at-ms]
  {:at       at-ms
   :aircraft (mapv aircraft->wire (vals picture))})

(defn wire->aircraft
  "The inverse projection: one decoded wire aircraft (keywordized JSON)
  back into a domain aircraft. For the browser SSE client (adsb-2yu.2)."
  [{:keys [icao callsign lat lon altitude on-ground squawk ground-speed
           track baro-rate seen-at position-suspect]}]
  (cond-> {:aircraft/icao icao}
    callsign         (assoc :aircraft/callsign callsign)
    (and lat lon)    (assoc :aircraft/position {:geo/lat lat :geo/lon lon})
    altitude         (assoc :aircraft/altitude-ft altitude)
    on-ground        (assoc :aircraft/on-ground? true)
    squawk           (assoc :aircraft/squawk squawk)
    ground-speed     (assoc :aircraft/ground-speed-kt ground-speed)
    track            (assoc :aircraft/track-deg track)
    baro-rate        (assoc :aircraft/baro-rate-fpm baro-rate)
    seen-at          (assoc :aircraft/seen-at-ms seen-at)
    position-suspect (assoc :aircraft/position-suspect? true)))

(defn wire->picture
  "A decoded frame envelope back into the domain picture, icao ->
  aircraft — what the browser holds between frames."
  [{:keys [aircraft]}]
  (into {}
        (map (fn [wire-aircraft]
               (let [domain-aircraft (wire->aircraft wire-aircraft)]
                 [(:aircraft/icao domain-aircraft) domain-aircraft])))
        aircraft))
