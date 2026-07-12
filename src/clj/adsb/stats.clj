(ns adsb.stats
  "Session statistics — numbers only, never a position. The server knows
  the receiver's true location (adsb.ingest.receiver); this namespace
  measures FROM it and emits only scalars, so nothing here can reveal
  where the antenna is (privacy: adsb-6wd.4 / adsb-kbm.2).

  A statistics accumulator is a small bundle of session state:

    max-range-m     the running maximum great-circle distance from the
                    receiver to any aircraft heard this session, in
                    meters. Monotonic — a record does not un-set. nil
                    until the first positioned aircraft is measured.
    prev-messages   the previous sample of the feeder's cumulative
                    message counter, {:messages n :at-ms t}, for
                    differencing into a per-second rate.

  compute! folds the current picture (plus the receiver position and the
  latest feeder message count) into that state and returns a stats map.
  It is called once per broadcast tick, on the single broadcast thread,
  so the read-then-swap in each helper has one writer.

  The receiver position is an ARGUMENT, resolved once at boot and closed
  over by the composition root (adsb.main) — never stored here, never
  logged, never serialized. When it is nil the range measurement is
  simply absent."
  (:require [adsb.aircraft :as aircraft]
            [adsb.geo :as geo]))

(def ^:const ^:private ms-per-second 1000)

(defn create
  "A fresh statistics accumulator (see the ns docstring). One per running
  system; tests make their own so the running max never leaks between
  cases."
  []
  {:stats/max-range-m   (atom nil)
   :stats/prev-messages (atom nil)})

(defn- range-m
  "Great-circle distance in meters from the receiver to one positioned
  aircraft."
  [receiver-position aircraft]
  (geo/distance receiver-position (:aircraft/position aircraft)))

(defn- max-range-km!
  "Advance and return the running max range in WHOLE km, or nil when
  there is nothing to report: no receiver position (no reference to
  measure from), or no aircraft has ever been positioned this session.
  The running max is monotonic — a quiet tick with no positioned
  aircraft neither lowers nor clears it."
  [max-range-m receiver-position positioned]
  (when receiver-position
    (let [batch-max (when (seq positioned)
                      (reduce max (map #(range-m receiver-position %) positioned)))
          running   (swap! max-range-m
                           (fn [current]
                             (cond
                               (nil? batch-max) current
                               (nil? current)   batch-max
                               :else            (max current batch-max))))]
      (when running
        (Math/round (double (geo/meters->km running)))))))

(defn- message-rate!
  "Feeder messages per second since the previous sample, rounded to a
  whole number, or nil when unknown: the feeder reports no cumulative
  count, this is the first sample (nothing to difference), no wall-clock
  time has elapsed, or the counter went backwards (a feeder restart).
  Advances the stored sample whenever a count is present."
  [prev-messages messages now-ms]
  (when messages
    (let [prev @prev-messages]
      (reset! prev-messages {:messages messages :at-ms now-ms})
      (when-let [{prev-count :messages prev-at-ms :at-ms} prev]
        (let [delta-msgs (- messages prev-count)
              elapsed-ms (- now-ms prev-at-ms)]
          (when (and (pos? elapsed-ms) (not (neg? delta-msgs)))
            (Math/round (/ (double delta-msgs)
                           (/ elapsed-ms ms-per-second)))))))))

(defn compute!
  "The session stats for the current picture, advancing the accumulator.
  Returns:

    :stats/aircraft-count    total aircraft tracked
    :stats/positioned-count  those with a position
    :stats/max-range-km      whole km to the furthest aircraft heard from
                             the receiver this session, or nil when the
                             receiver position is unavailable
    :stats/message-rate      feeder messages per second, or nil when
                             unknown

  Options: :picture (icao -> aircraft), :receiver-position (or nil),
  :now-ms, :messages (the feeder's cumulative counter, or nil). Only the
  two scalars reach the wire (adsb.wire/stats->wire); the counts ride
  along for completeness — logging, health, the REPL."
  [{:stats/keys [max-range-m prev-messages]}
   {:keys [picture receiver-position now-ms messages]}]
  (let [aircraft   (vals picture)
        positioned (filter aircraft/positioned? aircraft)]
    {:stats/aircraft-count   (count aircraft)
     :stats/positioned-count (count positioned)
     :stats/max-range-km     (max-range-km! max-range-m receiver-position
                                            positioned)
     :stats/message-rate     (message-rate! prev-messages messages now-ms)}))
