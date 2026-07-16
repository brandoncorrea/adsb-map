(ns adsb.ingest.coerce
  (:require [adsb.schema :as schema]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]
            #?(:clj [clojure.tools.logging :as log])))

(def ^:private valid-raw? (m/validator schema/raw-aircraft))
(def ^:private explain-raw (m/explainer schema/raw-aircraft))
(def ^:private decode-raw (m/decoder schema/raw-aircraft (mt/json-transformer)))

(defn- coerce-raw [raw]
  (when (map? raw)
    (let [entry (decode-raw raw)]
      (when (valid-raw? entry)
        entry))))

(defn- mlat-derived? [{:keys [type mlat]}]
  (or (= "mlat" type)
      (and (sequential? mlat) (seq mlat))))

(def ^:private known-category?
  (m/validator schema/emitter-category))

(defn- emitter-category [category]
  (when (known-category? category) category))

(defn- raw->aircraft [raw]
  (let [{:keys [hex flight alt_baro lat lon squawk gs track baro_rate
                seen seen_pos rssi category]} raw
        callsign (some-> flight str/trim not-empty)
        category (emitter-category category)]
    (cond-> {:aircraft/icao (str/lower-case hex)}
            callsign (assoc :aircraft/callsign callsign)
            category (assoc :aircraft/category category)
            (and lat lon) (assoc :aircraft/position {:geo/lat lat :geo/lon lon})
            (number? alt_baro) (assoc :aircraft/altitude-ft alt_baro)
            (= "ground" alt_baro) (assoc :aircraft/on-ground? true)
            squawk (assoc :aircraft/squawk squawk)
            gs (assoc :aircraft/ground-speed-kt gs)
            track (assoc :aircraft/track-deg track)
            baro_rate (assoc :aircraft/baro-rate-fpm baro_rate)
            seen (assoc :aircraft/seen-s seen)
            seen_pos (assoc :aircraft/position-seen-s seen_pos)
            rssi (assoc :aircraft/rssi rssi)
            (mlat-derived? raw) (assoc :aircraft/mlat? true))))

(def ^:private plausible-altitude?
  (m/validator schema/plausible-altitude-ft))

(def ^:private plausible-ground-speed?
  (m/validator schema/plausible-ground-speed-kt))

(defn- drop-implausible-fields [aircraft]
  (let [{:aircraft/keys [altitude-ft ground-speed-kt]} aircraft]
    (cond-> aircraft
            (and altitude-ft (not (plausible-altitude? altitude-ft)))
            (dissoc :aircraft/altitude-ft)

            (and ground-speed-kt (not (plausible-ground-speed? ground-speed-kt)))
            (dissoc :aircraft/ground-speed-kt))))

(defn ->aircraft [raw]
  (some-> raw
          coerce-raw
          raw->aircraft
          drop-implausible-fields))

(def ^:private max-logged-hex-chars 24)
(def ^:private max-logged-reason-chars 240)

(defn- bounded [x limit]
  (let [s (str x)]
    (subs s 0 (min (count s) limit))))

(defn- rejection-reason [raw]
  (-> raw explain-raw me/humanize))

(defn- log-rejection! [raw reason]
  (let [context {:hex   (bounded (when (map? raw) (:hex raw))
                                 max-logged-hex-chars)
                 :error (bounded reason max-logged-reason-chars)}]
    #?(:clj  (log/warn "Rejected aircraft" context)
       :cljs (.warn js/console "Rejected aircraft" (pr-str context)))
    nil))

(defn- ->aircraft-or-log! [raw]
  (try
    (or (->aircraft raw)
        (log-rejection! raw (rejection-reason raw)))
    (catch #?(:clj Exception :cljs :default) e
      (log-rejection! raw (ex-message e)))))

(defn ->aircraft-batch [raw-entries]
  (->> raw-entries
       (keep ->aircraft-or-log!)
       vec))
