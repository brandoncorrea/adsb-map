(ns adsb.ingest.crop
  (:require [adsb.geo :as geo]
            [adsb.schema :as schema]
            [clojure.string :as str]
            [malli.core :as m]))

(def ^:const crop-lat-env "ADSB_CROP_LAT")
(def ^:const crop-lon-env "ADSB_CROP_LON")
(def ^:const crop-radius-km-env "ADSB_CROP_RADIUS_KM")
(def ^:const max-radius-m 400000)

(def ^:private valid-latitude? (m/validator schema/latitude))
(def ^:private valid-longitude? (m/validator schema/longitude))

(defn outside-crop? [aircraft {:crop/keys [center radius-m]}]
  ;; A position-less aircraft can't be proven inside the crop, so an enabled
  ;; crop excludes it from publication — deliberately stricter than the ingest
  ;; rule "no position → keep" (see validation-boundaries.md).
  (if-let [position (:aircraft/position aircraft)]
    (> (geo/distance center position) radius-m)
    true))

(defn gate-crop [batch crop]
  (cond->> batch
           crop
           (into [] (remove #(outside-crop? % crop)))))

(defn- configured? [s]
  (and (string? s)
       (not (str/blank? s))))

(defn- parse-number [s]
  (when (configured? s)
    (parse-double s)))

(defn env-crop [env]
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
                   {:crop/set (into #{}
                                    (comp (filter (comp configured? second))
                                          (map first))
                                    [[crop-lat-env lat-s]
                                     [crop-lon-env lon-s]
                                     [crop-radius-km-env radius-s]])})))
        {:crop/center   {:geo/lat lat :geo/lon lon}
         :crop/radius-m radius-m}))))
