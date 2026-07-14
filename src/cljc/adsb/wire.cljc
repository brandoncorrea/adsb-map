(ns adsb.wire
  "The SSE wire format: the one place that decides what a domain
  aircraft looks like as JSON and how the browser turns it back. Pure —
  the server (adsb.stream.broadcast) encodes with picture->wire, the
  browser SSE client (adsb-2yu.2) decodes with wire->picture, and
  because both directions live in this shared namespace the contract
  cannot drift.

  ## The format

  Three payload shapes, one per family of SSE event. AIRCRAFT DATA AND
  STATS NEVER SHARE A PAYLOAD (owner decision, adsb-jpf): mixing them in
  one frame couples them — a change to either forces itself on the
  other — while separated, each can evolve without breaking the other.

  A FULL-PICTURE payload — `snapshot` and `update` events alike — is
  one envelope carrying the FULL current picture and nothing else:

      {\"at\": 1720713600000,
       \"aircraft\": [<wire aircraft> ...]}

  `at` is the epoch-ms instant the frame was built. An update is not a
  delta, so a client treats every full-picture frame as a wholesale
  replacement and can never accumulate drift; a reconnect needs no
  replay.

  An UPSERT payload — the `aircraft` event (adsb-jpf) — carries ONE
  aircraft's full merged state, pushed the instant a streaming Source
  applied a message:

      {\"at\": 1720713600123,
       \"aircraft\": <wire aircraft>}

  The aircraft is the SAME wire shape a full-picture frame's array holds
  (aircraft->wire both ways), so a client decodes it with the same codec
  and merges it into its picture by `icao`. It is a full merged state,
  not a field diff — idempotent, so a lost or dropped upsert heals on
  that aircraft's next message; no sequencing or replay protocol exists
  or is needed.

  A STATS payload — the `stats` event — carries the session stats and
  the feeder's health on a low, fixed cadence (~10 s), plus once right
  after the connect-time snapshot so the chrome populates immediately:

      {\"at\": 1720713600000,
       \"stats\": {\"max-range-km\": 312, \"message-rate\": 148},
       \"feeder\": {\"status\": \"ok\", \"last-success\": 1720713599000}}

  `stats` is the session readout the server computes (adsb.stats) — two
  SCALARS, no position:

      max-range-km   whole-km distance to the furthest aircraft this
                     antenna has heard this session. A NUMBER reveals no
                     position; OMITTED when the receiver position is
                     unavailable (no reference to measure from).
      message-rate   feeder messages per second, OMITTED when unknown.

  `feeder` is the ingest side's health (adsb.ingest.poll/status) — the
  one thing the picture alone cannot tell the browser. A live stream over
  a dead feeder looks perfectly healthy while the sky silently ages out,
  so the feeder's reachability rides every stats frame:

      status         \"ok\" | \"down\" | \"starting\" — an ALLOWLIST of the
                     three states (adsb.ingest.poll). A status the wire
                     does not name is OMITTED, so an internal state that
                     someday appears cannot leak by construction.
      last-success   epoch-ms of the last successful poll, OMITTED before
                     the first one (a :starting feeder has none).

  The feeder's :feeder/last-error is DELIBERATELY off the wire. An
  internal exception message could carry a path or a hostname
  (docs/validation-boundaries.md, the feeder is untrusted and so are the
  strings it provokes), and the browser is owed only WHETHER the feeder is
  reachable, not why it isn't — status and timestamp, never prose.

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
  receiver-relative field are excluded by construction.

  feeder->wire is the same shape of allowlist: only a named status and a
  timestamp leave, and the free-form error string — the one field that
  could carry a leaked path or hostname — is excluded by construction, not
  by enumeration."
  (:require
    [clojure.set :as set]))

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

(def ^:private feeder-status->wire
  "The feeder-status allowlist: each internal status keyword
  (adsb.ingest.poll) paired with its wire string. A status not named here
  never reaches the wire and decodes back to nil — the browser treats an
  unnamed status as unknown."
  {:starting "starting"
   :ok       "ok"
   :down     "down"})

(def ^:private wire->feeder-status
  (set/map-invert feeder-status->wire))

(defn feeder->wire
  "Project the feeder status (adsb.ingest.poll/status, :feeder/-namespaced)
  onto the envelope's `feeder` map. An ALLOWLIST, exactly like stats->wire:
  only a named status and the last-success timestamp may leave. The
  free-form :feeder/last-error is EXCLUDED by construction — it could carry
  a leaked path or hostname, and the wire owes the browser reachability, not
  prose (see the ns docstring). An unknown or absent status yields no
  `status` key; a nil last-success is omitted, never zeroed; nil feeder
  yields an empty map."
  [{:feeder/keys [status last-success-ms]}]
  (cond-> {}
          (feeder-status->wire status) (assoc :status (feeder-status->wire status))
          last-success-ms (assoc :last-success last-success-ms)))

(defn crop->wire
  "Project the privacy crop (adsb.ingest.crop) onto the `config` event's
  `crop` map: the declared centre and radius, so the browser can draw the
  boundary of what this app publishes.

  ## Yes, this really is a coordinate on the wire

  It is the ONE coordinate that may be, and the reason is the whole point
  of the crop. This is not the antenna — it is the DECOY centre of the
  disc we chose and announced (adsb.ingest.crop, README 'Hiding the
  antenna'). The crop is public by construction: its entire purpose is
  that the boundary of the published set be a boundary we declared rather
  than the antenna's horizon, whose centroid IS the antenna. A boundary
  that must be secret to work would not be a crop; it would be the leak.
  So drawing it costs nothing that was not already given away by the data
  it bounds.

  What must never happen here is a FALLBACK. A nil crop yields nil — the
  gate is disabled, there is no declared boundary, and there is nothing to
  draw. It must never quietly reach for the receiver position instead;
  that is the one coordinate in this system that is not ours to publish,
  and this namespace has no access to it."
  [{:crop/keys [center radius-m]}]
  (when (and center radius-m)
    {:lat       (:geo/lat center)
     :lon       (:geo/lon center)
     :radius-km (/ radius-m 1000)}))

(defn config-event->wire
  "The static boot config as one `config` event envelope, built at
  `at-ms` — sent ONCE per connection, before the snapshot, because none
  of it can change while the process lives (adsb.stream.broadcast).

  Only the privacy crop rides it today, and an absent crop yields an
  envelope with no `crop` key rather than a null — the browser draws no
  boundary, which is the honest rendering of 'this deployment publishes
  everything it hears'."
  [crop at-ms]
  (cond-> {:at at-ms}
          (crop->wire crop) (assoc :crop (crop->wire crop))))

(defn picture->wire
  "The picture (icao -> aircraft) as one full-picture frame envelope,
  built at `at-ms`. Sent as the connect-time snapshot and as every
  update. Aircraft data ONLY — stats and feeder health travel on their
  own `stats` event (stats-event->wire), never in an aircraft frame
  (ns docstring)."
  [picture at-ms]
  {:at       at-ms
   :aircraft (mapv aircraft->wire (vals picture))})

(defn stats-event->wire
  "The session `stats` (adsb.stats, or nil) and the `feeder` health
  (adsb.ingest.poll/status, or nil) as one `stats` event envelope, built
  at `at-ms` — the ONLY payload either may ride (ns docstring). Both
  projections are the allowlists above, so nothing receiver-relative and
  no error prose can leave here either."
  [stats feeder at-ms]
  {:at     at-ms
   :stats  (stats->wire stats)
   :feeder (feeder->wire feeder)})

(defn upsert->wire
  "One aircraft's full merged state as an `aircraft` upsert envelope,
  built at `at-ms` (ns docstring). The aircraft goes through the SAME
  allowlist projection as a full-picture frame's entries, so the privacy
  guarantees hold per event exactly as they hold per frame."
  [aircraft at-ms]
  {:at       at-ms
   :aircraft (aircraft->wire aircraft)})

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

(defn wire->upsert
  "The inverse projection: a decoded upsert envelope back into the one
  domain aircraft it carries — the same decode a full-picture entry gets,
  so the browser merges it into its picture by :aircraft/icao with no
  second vocabulary."
  [{:keys [aircraft]}]
  (wire->aircraft aircraft))

(defn wire->crop
  "The inverse projection: a decoded `config` envelope's `crop` map back
  into the domain crop vocabulary (adsb.ingest.crop's :crop/-namespaced
  shape), or nil when the event carried none — a deployment with the crop
  disabled, which the browser renders as no boundary at all rather than as
  a boundary of unknown size."
  [{:keys [crop]}]
  (let [{:keys [lat lon radius-km]} crop]
    (when (and lat lon radius-km)
      {:crop/center   {:geo/lat lat :geo/lon lon}
       :crop/radius-m (* radius-km 1000)})))

(defn wire->stats
  "The inverse projection: a decoded envelope's `stats` map back into the
  server-stats vocabulary (:stats/-namespaced) the browser subscribes to.
  Absent keys stay absent — the readout dashes them, never zeroes them."
  [{:keys [stats]}]
  (let [{:keys [max-range-km message-rate]} stats]
    (cond-> {}
            max-range-km (assoc :stats/max-range-km max-range-km)
            message-rate (assoc :stats/message-rate message-rate))))

(defn wire->feeder
  "The inverse projection: a decoded envelope's `feeder` map back into the
  feeder-status vocabulary (:feeder/-namespaced) the browser subscribes to.
  An unnamed or absent status decodes to nil — the browser (adsb.subs)
  treats that as unknown, never a stale claim; an absent last-success stays
  absent. The error string is never present to decode: it never rode the
  wire."
  [{:keys [feeder]}]
  (let [{:keys [status last-success]} feeder
        status-kw (wire->feeder-status status)]
    (cond-> {}
            status-kw (assoc :feeder/status status-kw)
            last-success (assoc :feeder/last-success-ms last-success))))

(defn wire->picture
  "A decoded frame envelope back into the domain picture, icao ->
  aircraft — what the browser holds between frames."
  [{:keys [aircraft]}]
  (into {}
        (map (fn [wire-aircraft]
               (let [domain-aircraft (wire->aircraft wire-aircraft)]
                 [(:aircraft/icao domain-aircraft) domain-aircraft])))
        aircraft))
