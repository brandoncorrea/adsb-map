(ns adsb.ingest.crop
  "The privacy crop: this app publishes aircraft inside a DECLARED disc,
  not 'whatever my antenna happened to hear' (adsb-au5).

  WHY THIS EXISTS, given that the wire already hides every
  receiver-relative field (adsb.wire, adsb.ingest.receiver): no field
  allowlist can fix this leak, because the leak is not in a field. It is
  in the SHAPE OF THE OBSERVATION SET. Publish everything the antenna
  hears and the union of those positions is, over a few hours, a disc
  centred on the antenna — take the hull, take the centroid, and you have
  the roof. Low altitude sharpens it (radio horizon goes as ~1.23*sqrt(ft)
  nm, so the 1500 ft targets we can see form a SMALL disc tight around us)
  and terrain shadows carve persistent notches on fixed bearings, which
  are a fingerprint rather than merely a gap.

  So the boundary of our data must be a boundary we CHOSE and ANNOUNCED.
  Crop to a fixed disc and the edge of the published set reveals the
  crop — public by construction — instead of the horizon.

  TWO PROPERTIES THE CROP MUST HAVE, or it is theatre:

    1. The centre is NOT the receiver, and is never derived from it. It
       is an arbitrary public point (a city, an airport). This namespace
       has no access to the receiver position and must never acquire one.

    2. The disc lies strictly INSIDE true coverage in EVERY bearing. A
       crop that pokes outside the real envelope somewhere leaks the
       shortfall in the weakest bearing, and the geometry comes right
       back. Radius and altitude floor trade off through the radio
       horizon: a wide crop needs a high floor before coverage is uniform
       across it; a tight crop keeps the low traffic. Measure the real
       coverage polygon per altitude band before picking either.

  NOT adsb.ingest.plausibility/gate-range, which stays and does a
  different job: that gate is receiver-centred, generous (~400 km), and
  ANTI-SPOOFING — it rejects a track that cannot have come from this
  antenna's sky. This one is decoy-centred, tight, and PRIVACY. Both run;
  neither substitutes for the other.

  Pure: the crop is an argument. Resolving it from the environment is the
  caller's edge (adsb.main)."
  (:require [adsb.geo :as geo]))

(def ^:const crop-lat-env "ADSB_CROP_LAT")
(def ^:const crop-lon-env "ADSB_CROP_LON")
(def ^:const crop-radius-km-env "ADSB_CROP_RADIUS_KM")

(def ^:const max-radius-m
  "A crop wider than the antenna's own plausible horizon
  (adsb.ingest.plausibility/default-max-range-m) cannot sit inside true
  coverage, so it drops nothing and buys nothing — it only lends the
  false comfort of a configured privacy control. Rejected at boot."
  400000)

;; ---------------------------------------------------------------------
;; The gate

(defn- valid-latitude? [lat]
  (and (number? lat) (<= -90 lat 90)))

(defn- valid-longitude? [lon]
  (and (number? lon) (<= -180 lon 180)))

(defn outside-crop?
  "True when this aircraft must not be published under `crop`.

  A POSITION-LESS aircraft is outside — it is dropped. This is stricter
  than gate-range, which passes the position-less on the grounds that
  there is nothing to gate, and the difference is the whole point: the
  crop's promise is that everything we publish sits inside the declared
  disc, and an aircraft we cannot PLACE inside the disc has not met that
  bar. Its bare presence in the feed still says 'this antenna hears it',
  which is the envelope leak wearing a hat.

  This costs nothing in tracking. On the delta path the aircraft arrives
  already merged, so it carries the position it inherited from the
  picture — an altitude-only or velocity-only message from an in-crop
  aircraft is NOT position-less here and is not dropped. On the poll path
  a position-less entry is one aircraft.json never gave a position for
  (Mode S with no ADS-B position), which could not be plotted anyway."
  [aircraft {:crop/keys [center radius-m]}]
  (if-let [position (:aircraft/position aircraft)]
    (> (geo/distance center position) radius-m)
    true))

(defn gate-crop
  "The batch with every aircraft outside the declared disc removed.

  A nil `crop` DISABLES the gate and returns the batch untouched — the
  unconfigured case, which adsb.main warns about loudly at boot, because
  a privacy control that is silently off is how this goes wrong. There is
  no default crop on purpose: a default centre would have to come from
  somewhere, and the only coordinate this system knows is the one we are
  hiding."
  [batch crop]
  (if crop
    (into [] (remove #(outside-crop? % crop)) batch)
    batch))

;; ---------------------------------------------------------------------
;; Configuration
;;
;; Pure given an environment map; reading the real environment, and
;; logging what was resolved, stay at the caller's edge (adsb.main).

(defn- configured?
  "True when an environment entry was SET to something other than blank.
  Presence is judged on the raw string, never on whether it parses:
  ADSB_CROP_LAT=abc is a crop someone meant to configure and typoed, and
  it must fail loudly. Deciding presence by 'did parse-double return a
  number' would read that as 'unset' and silently disable the gate —
  precisely the failure this namespace exists to prevent."
  [s]
  (boolean (and (string? s) (not (re-matches #"\s*" s)))))

(defn- parse-number [s]
  (when (configured? s)
    (parse-double s)))

(defn env-crop
  "The crop declared by ADSB_CROP_LAT / _LON / _RADIUS_KM in an
  environment map, as {:crop/center {:geo/lat _ :geo/lon _}
  :crop/radius-m _}, or nil when NONE of the three is set.

  ALL THREE OR NONE. A partially-configured or unparseable crop THROWS at
  boot rather than falling back to a disabled gate or a default radius,
  on the same reasoning as half a feeder credential
  (adsb.ingest.config): a privacy control that silently degrades to
  'publish everything' while LOOKING configured is worse than one that
  was never configured, because the operator believes it is on.
  Out-of-range coordinates and a radius that is non-positive or wider
  than max-radius-m throw for the same reason — never clamped."
  [env]
  (let [lat-s    (get env crop-lat-env)
        lon-s    (get env crop-lon-env)
        radius-s (get env crop-radius-km-env)]
    (when (some configured? [lat-s lon-s radius-s])
      (let [lat      (parse-number lat-s)
            lon      (parse-number lon-s)
            radius-m (some-> (parse-number radius-s) (* geo/meters-per-km))]
        (when-not (and (valid-latitude? lat)
                       (valid-longitude? lon)
                       (number? radius-m)
                       (pos? radius-m)
                       (<= radius-m max-radius-m))
          (throw (ex-info
                   (str "Invalid privacy crop: " crop-lat-env " / "
                        crop-lon-env " / " crop-radius-km-env
                        " must ALL be set, with a latitude in [-90, 90], a"
                        " longitude in [-180, 180], and a radius in (0, "
                        (geo/meters->km max-radius-m) "] km. A partly"
                        " configured crop would publish every aircraft this"
                        " antenna hears while looking configured.")
                   ;; Which entries were set, never what they were set TO:
                   ;; a fat-fingered crop centre is still a coordinate
                   ;; somebody typed near home, and this reaches the logs.
                   {:crop/set (into #{}
                                    (comp (filter (comp configured? second))
                                          (map first))
                                    [[crop-lat-env lat-s]
                                     [crop-lon-env lon-s]
                                     [crop-radius-km-env radius-s]])})))
        {:crop/center   {:geo/lat lat :geo/lon lon}
         :crop/radius-m radius-m}))))
