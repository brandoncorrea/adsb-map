(ns adsb.ingest.sbs
  (:require [adsb.ingest.source :as source]
            [adsb.ingest.tcp :as tcp]
            [adsb.schema :as schema]
            [clojure.string :as str]
            [malli.core :as m])
  (:import (java.io BufferedReader InputStream InputStreamReader)
           (java.nio.charset StandardCharsets)))

(def ^:private message-type-field 0)
(def ^:private icao-field 4)
(def ^:private callsign-field 10)
(def ^:private altitude-field 11)
(def ^:private ground-speed-field 12)
(def ^:private track-field 13)
(def ^:private latitude-field 14)
(def ^:private longitude-field 15)
(def ^:private vertical-rate-field 16)
(def ^:private squawk-field 17)
(def ^:private on-ground-field 21)
(def ^:private transmission-message "MSG")
(def ^:private squawk-digit-count 4)
(def ^:private valid-icao? (m/validator schema/icao-address))
(def ^:private valid-callsign? (m/validator [:string {:min 1 :max 8}]))
(def ^:private valid-latitude? (m/validator schema/latitude))
(def ^:private valid-longitude? (m/validator schema/longitude))
(def ^:private valid-squawk? (m/validator schema/squawk))
(def ^:private plausible-altitude? (m/validator schema/plausible-altitude-ft))
(def ^:private plausible-ground-speed? (m/validator schema/plausible-ground-speed-kt))

(defn- field [fields idx]
  (some-> (nth fields idx nil)
          str/trim
          not-empty))

(defn- ->double [s]
  (when-let [n (some-> s parse-double)]
    (when (Double/isFinite n) n)))

(defn- ->long [s]
  (some-> s parse-long))

(defn- ->icao [s]
  (when-let [hex (some-> s str/lower-case)]
    (when (valid-icao? hex) hex)))

(defn- ->callsign [s]
  (when (and s (valid-callsign? s)) s))

(defn- ->position [lat-s lon-s]
  (let [lat (->double lat-s)
        lon (->double lon-s)]
    (when (and lat lon (valid-latitude? lat) (valid-longitude? lon))
      {:geo/lat lat :geo/lon lon})))

(defn- ->altitude-ft [s]
  (when-let [ft (->long s)]
    (when (plausible-altitude? ft) ft)))

(defn- ->ground-speed-kt [s]
  (when-let [kt (->double s)]
    (when (plausible-ground-speed? kt) kt)))

(defn- pad-squawk [s]
  (str (subs "000" 0 (max 0 (- squawk-digit-count (count s)))) s))

(defn- ->squawk [s]
  (when-let [squawk (some-> s pad-squawk)]
    (when (valid-squawk? squawk) squawk)))

(defn- ->on-ground? [s]
  (cond
    (contains? #{"-1" "1"} s) true
    (= "0" s) false))

(defn- msg? [fields]
  (= transmission-message (nth fields message-type-field nil)))

(defn line->delta [line]
  (let [fields (str/split line #"," -1)]
    (when (msg? fields)
      (when-let [icao (->icao (field fields icao-field))]
        (let [callsign  (->callsign (field fields callsign-field))
              position  (->position (field fields latitude-field)
                                    (field fields longitude-field))
              altitude  (->altitude-ft (field fields altitude-field))
              speed     (->ground-speed-kt (field fields ground-speed-field))
              track     (->double (field fields track-field))
              vert-rate (->long (field fields vertical-rate-field))
              squawk    (->squawk (field fields squawk-field))
              on-ground (->on-ground? (field fields on-ground-field))]
          (cond-> {:aircraft/icao icao}
                  callsign (assoc :aircraft/callsign callsign)
                  position (assoc :aircraft/position position)
                  altitude (assoc :aircraft/altitude-ft altitude)
                  speed (assoc :aircraft/ground-speed-kt speed)
                  track (assoc :aircraft/track-deg track)
                  vert-rate (assoc :aircraft/baro-rate-fpm vert-rate)
                  squawk (assoc :aircraft/squawk squawk)
                  (some? on-ground) (assoc :aircraft/on-ground? on-ground)))))))

(defn- consume-line! [{:keys [clock] :as state} line]
  (when-let [delta (line->delta line)]
    (tcp/accumulate! state delta (clock))))

(defn- read-lines! [^BufferedReader reader {:keys [running?] :as state}]
  (loop []
    (when @running?
      (when-let [line (.readLine reader)]
        (consume-line! state line)
        (recur)))))

(defn- consume! [^InputStream in state]
  (let [reader (BufferedReader. (InputStreamReader. in StandardCharsets/US_ASCII))]
    (read-lines! reader state)))

(defrecord SbsSource [host port transport connect-timeout-ms idle-timeout-ms
                      reconnect-ms clock on-delta consume! thread-name
                      picture messages swept-at-ms running? connected?
                      last-error connection reader-thread]
  source/Source
  (open! [this] (tcp/open! this))
  (fetch! [this] (tcp/snapshot-or-throw! this))
  (close! [this] (tcp/close! this))
  source/Metadata
  (last-metadata [this] (tcp/last-metadata this)))

(defn ->source
  ([host port] (->source host port {}))
  ([host port opts]
   (map->SbsSource (tcp/reader-state host port opts consume! "adsb-sbs-reader"))))
