(ns adsb.ingest.plausibility-test
  (:require [adsb.accumulator :as accumulator]
            [adsb.aircraft :as aircraft]
            [adsb.fixtures :as fixtures]
            [adsb.geo :as geo]
            [adsb.ingest.coerce :as coerce]
            [adsb.ingest.plausibility :as plausibility]
            [adsb.schema :as schema]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]))

(def ^:private captured-at-ms 1720713600000)
(def ^:private ups-icao (:aircraft/icao fixtures/ups-2717))
(def ^:private receiver-position {:geo/lat 28.0 :geo/lon -82.5})
(def ^:private mid-atlantic {:geo/lat 28.0 :geo/lon -60.0})

(defn- at-position [aircraft position]
  (assoc aircraft :aircraft/position position))

(deftest gate-range
  (testing "keeps the whole cast — every real position is inside the
            default horizon of its own receiver"
    (is (= fixtures/all
           (plausibility/gate-range fixtures/all receiver-position
                                    plausibility/default-max-range-m))))

  (testing "drops an aircraft reporting a position beyond the horizon —
            it did not come from the sky this antenna can see"
    (let [teleported (at-position fixtures/ups-2717 mid-atlantic)]
      (is (= [fixtures/on-the-ground]
             (plausibility/gate-range [teleported fixtures/on-the-ground]
                                      receiver-position
                                      plausibility/default-max-range-m)))))

  (testing "passes position-less aircraft even with the gate enabled —
            there is nothing to gate"
    (is (= [fixtures/never-positioned]
           (plausibility/gate-range [fixtures/never-positioned]
                                    receiver-position
                                    plausibility/default-max-range-m))))

  (testing "a nil receiver position disables the gate: the batch passes
            untouched rather than being gated against a guess"
    (let [teleported (at-position fixtures/ups-2717 mid-atlantic)]
      (is (= [teleported]
             (plausibility/gate-range [teleported] nil
                                      plausibility/default-max-range-m))))))

(deftest beyond-horizon?
  (let [equator-origin   {:geo/lat 0 :geo/lon 0}
        one-degree-east  {:geo/lat 0 :geo/lon 1}
        aircraft         (at-position fixtures/ups-2717 one-degree-east)
        exact-distance-m (geo/distance equator-origin one-degree-east)]
    (testing "a position exactly at the horizon is still inside it —
              the gate drops strictly beyond, never at"
      (is (not (plausibility/beyond-horizon? aircraft equator-origin exact-distance-m))))

    (testing "one meter past the horizon is beyond it"
      (is (plausibility/beyond-horizon? aircraft equator-origin (- exact-distance-m 1))))

    (testing "a position-less aircraft is never beyond the horizon"
      (is (not (plausibility/beyond-horizon? fixtures/never-positioned equator-origin 1))))))

(deftest position-jump?
  (let [equator-origin  {:geo/lat 0 :geo/lon 0}
        one-degree-east {:geo/lat 0 :geo/lon 1}
        previous        {:aircraft/position       equator-origin
                         :aircraft/position-at-ms 0
                         :aircraft/seen-at-ms     3599999}
        ms-per-hour     3600000
        implied-kt      (geo/meters->nm (geo/distance equator-origin one-degree-east))]
    (testing "an implied speed strictly above the threshold is a jump"
      (is (plausibility/position-jump? previous one-degree-east
                                       ms-per-hour
                                       (- implied-kt 0.001))))

    (testing "an implied speed exactly at the threshold is not a jump —
              flag strictly above, never at"
      (is (not (plausibility/position-jump? previous one-degree-east
                                            ms-per-hour implied-kt))))

    (testing "a changed position with zero elapsed time is the most
              impossible jump of all"
      (is (plausibility/position-jump? previous one-degree-east 0
                                       plausibility/default-max-implied-speed-kt)))

    (testing "an unchanged position with zero elapsed time is the feeder
              repeating itself, not a jump"
      (is (not (plausibility/position-jump? previous equator-origin 0
                                            plausibility/default-max-implied-speed-kt))))))

(defn- picture-after [& batches]
  (reduce (fn [[picture at-ms] batch]
            [(plausibility/merge-batch-flagging-jumps picture batch at-ms)
             (+ at-ms 1000)])
          [{} captured-at-ms]
          batches))

(defn- stored [batches-result icao]
  (get (first batches-result) icao))

(deftest merge-batch-flagging-jumps
  (let [teleported (at-position fixtures/ups-2717 mid-atlantic)]
    (testing "an impossible jump is flagged, never dropped — and the
              jumped position is stored as reported, never clamped"
      (let [aircraft (stored (picture-after [fixtures/ups-2717]
                                            [teleported])
                             ups-icao)]
        (is (:aircraft/position-suspect? aircraft))
        (is (= mid-atlantic (:aircraft/position aircraft)))))

    (testing "a flagged aircraft still satisfies the domain schema"
      (is (m/validate schema/aircraft
                      (stored (picture-after [fixtures/ups-2717]
                                             [teleported])
                              ups-icao))))

    (testing "plausible movement between polls is not flagged"
      (let [cruised (update-in fixtures/ups-2717 [:aircraft/position :geo/lat] + 0.002)]
        (is (not (contains? (stored (picture-after [fixtures/ups-2717]
                                                   [cruised])
                                    ups-icao)
                            :aircraft/position-suspect?)))))

    (testing "a first-ever position has nothing to jump from and is never flagged"
      (is (not (contains? (stored (picture-after [fixtures/ups-2717]) ups-icao)
                          :aircraft/position-suspect?))))

    (testing "the first position after a never-positioned history is not
              a jump"
      (let [icao       (:aircraft/icao fixtures/never-positioned)
            positioned (at-position fixtures/never-positioned mid-atlantic)]
        (is (not (contains? (stored (picture-after
                                      [fixtures/never-positioned]
                                      [positioned])
                                    icao)
                            :aircraft/position-suspect?)))))

    (testing "the flag STICKS across every later snapshot, exactly as it
              does on the streaming path (adsb-caf): a track that settled
              down could otherwise launder a spoof with one plausible
              position, and the operator would never see the teleport"
      (let [settled  (at-position fixtures/ups-2717
                                  (update mid-atlantic :geo/lat + 0.002))
            settled' (at-position fixtures/ups-2717
                                  (update mid-atlantic :geo/lat + 0.004))]
        (is (true? (:aircraft/position-suspect?
                     (stored (picture-after [fixtures/ups-2717]
                                            [teleported]
                                            [settled]
                                            [settled'])
                             ups-icao))))))

    (testing "a position-less observation inherits the flag along with
              the position — a suspect position does not launder itself
              by falling silent"
      (let [silent   (dissoc fixtures/ups-2717 :aircraft/position)
            aircraft (stored (picture-after [fixtures/ups-2717]
                                            [teleported]
                                            [silent])
                             ups-icao)]
        (is (:aircraft/position-suspect? aircraft))
        (is (= mid-atlantic (:aircraft/position aircraft)))))

    (testing "a position-less observation of a never-suspect aircraft
              stays unflagged"
      (let [silent (dissoc fixtures/ups-2717 :aircraft/position)]
        (is (not (contains? (stored (picture-after [fixtures/ups-2717] [silent]) ups-icao)
                            :aircraft/position-suspect?)))))))

(deftest the-poll-path-measures-from-seen-pos-not-seen
  (let [origin   {:geo/lat 28.0 :geo/lon -82.5}
        moved    (update origin :geo/lat + 0.1)
        polled   (fn [position seen-s position-seen-s]
                   (-> fixtures/ups-2717
                       (assoc :aircraft/position position
                              :aircraft/seen-s seen-s
                              :aircraft/position-seen-s position-seen-s)))
        picture  (-> {}
                     (plausibility/merge-batch-flagging-jumps
                       [(polled origin 0.5 50.5)] captured-at-ms)
                     (plausibility/merge-batch-flagging-jumps
                       [(polled moved 0.5 0.5)] (+ captured-at-ms 50000)))
        aircraft (get picture ups-icao)]

    (testing "6 nm in 50 s is an airliner, not a teleport — even though only
              0.5 s elapsed since the last MESSAGE"
      (is (not (contains? aircraft :aircraft/position-suspect?))))

    (testing "the stored position stamp is when the position moved, which is
              older than when the aircraft was last heard"
      (is (= (+ captured-at-ms 49500) (:aircraft/position-at-ms aircraft)))
      (is (= (+ captured-at-ms 49500) (:aircraft/seen-at-ms aircraft))))

    (testing "the capture-relative fields do not survive the merge — they rot
              the moment the poll ends"
      (is (not (contains? aircraft :aircraft/seen-s)))
      (is (not (contains? aircraft :aircraft/position-seen-s))))

    (testing "a real teleport across the same poll gap still flags"
      (is (:aircraft/position-suspect?
            (-> picture
                (plausibility/merge-batch-flagging-jumps
                  [(polled mid-atlantic 0.5 0.5)]
                  (+ captured-at-ms 51000))
                (get ups-icao)))))))

(deftest a-position-less-observation-keeps-its-position-stamp
  (let [origin   {:geo/lat 28.0 :geo/lon -82.5}
        silent   (-> fixtures/ups-2717
                     (dissoc :aircraft/position :aircraft/position-seen-s)
                     (assoc :aircraft/seen-s 0.5))
        picture  (-> {}
                     (plausibility/merge-batch-flagging-jumps
                       [(assoc fixtures/ups-2717
                          :aircraft/position origin
                          :aircraft/seen-s 0.5
                          :aircraft/position-seen-s 0.5)]
                       captured-at-ms)
                     (plausibility/merge-batch-flagging-jumps
                       [silent] (+ captured-at-ms 30000)))
        aircraft (get picture ups-icao)]

    (testing "the inherited position keeps the stamp it was true at, not the
              instant it was inherited"
      (is (= origin (:aircraft/position aircraft)))
      (is (= (- captured-at-ms 500) (:aircraft/position-at-ms aircraft))))

    (testing "so the next real position is measured over the whole silence —
              30 s of flight, not an instant"
      (is (not (contains?
                 (-> picture
                     (plausibility/merge-batch-flagging-jumps
                       [(assoc fixtures/ups-2717
                          :aircraft/position (update origin :geo/lat + 0.06)
                          :aircraft/seen-s 0.5
                          :aircraft/position-seen-s 0.5)]
                       (+ captured-at-ms 60000))
                     (get ups-icao))
                 :aircraft/position-suspect?))))))

(deftest age-out-is-the-only-thing-that-clears-the-flag
  (let [teleported (at-position fixtures/ups-2717 mid-atlantic)
        suspect    (first (picture-after [fixtures/ups-2717] [teleported]))
        long-after (+ captured-at-ms aircraft/age-out-threshold-ms 1000)]

    (testing "the sweep evicts the suspect track like any other"
      (is (:aircraft/position-suspect? (get suspect ups-icao)))
      (is (empty? (aircraft/age-out suspect long-after))))

    (testing "an aircraft re-acquired after the sweep is a new track and
              starts clean — its first position has nothing to jump from"
      (let [swept   (aircraft/age-out suspect long-after)
            reheard (plausibility/merge-batch-flagging-jumps
                      swept [teleported] long-after)]
        (is (not (contains? (get reheard ups-icao) :aircraft/position-suspect?)))))))

(defn- streamed [position heard-at-ms]
  (-> fixtures/ups-2717
      (dissoc :aircraft/seen-s)
      (at-position position)
      (assoc :aircraft/seen-at-ms heard-at-ms)))

(deftest merge-batch-flagging-jumps-on-streamed-snapshots
  (let [origin   {:geo/lat 28.0 :geo/lon -82.5}
        moved-on (update origin :geo/lat + 0.2)
        heard-at captured-at-ms
        gap-ms   100000
        picture  (-> {}
                     (plausibility/merge-batch-flagging-jumps
                       [(streamed origin heard-at)] heard-at)
                     (plausibility/merge-batch-flagging-jumps
                       [(streamed origin heard-at)] (+ heard-at 99000))
                     (plausibility/merge-batch-flagging-jumps
                       [(streamed moved-on (+ heard-at gap-ms))]
                       (+ heard-at gap-ms)))
        aircraft (get picture ups-icao)]

    (testing "the stored stamp is when the aircraft was heard, not when
              the snapshot was taken"
      (is (= (+ heard-at gap-ms) (:aircraft/seen-at-ms aircraft))))

    (testing "a 100 s reception gap on a moving aircraft is ordinary
              flight, not a jump"
      (is (not (contains? aircraft :aircraft/position-suspect?))))

    (testing "a genuinely impossible hop across a streamed gap is still
              flagged — honest stamps sharpen the detector, not blunt it"
      (let [jumped (-> picture
                       (plausibility/merge-batch-flagging-jumps
                         [(streamed mid-atlantic (+ heard-at gap-ms 1000))]
                         (+ heard-at gap-ms 1000))
                       (get ups-icao))]
        (is (:aircraft/position-suspect? jumped))))))

(def ^:private tampa {:geo/lat 28.0 :geo/lon -82.4})

(defn- delta [& {:as fields}]
  (assoc fields :aircraft/icao ups-icao))

(deftest accumulate-flagging-jumps-flags-the-delta-path
  (let [heard-at 1000
        origin   (plausibility/accumulate-flagging-jumps
                   {} (delta :aircraft/position tampa) heard-at)
        jumped   (plausibility/accumulate-flagging-jumps
                   origin (delta :aircraft/position mid-atlantic)
                   (+ heard-at 1000))]

    (testing "a first-heard position has nothing to jump from"
      (is (not (contains? (get origin ups-icao) :aircraft/position-suspect?))))

    (testing "a teleporting delta is flagged as it folds in — the merged
              aircraft the broadcaster upserts carries the mark"
      (is (:aircraft/position-suspect? (get jumped ups-icao))))

    (testing "the flag STICKS across every later message: a delta says
              only what changed and never says 'no longer suspect', so a
              spoofed track cannot launder its flag away by sending a
              velocity — the upsert that follows would have erased it"
      (let [velocity (plausibility/accumulate-flagging-jumps
                       jumped (delta :aircraft/ground-speed-kt 430.0)
                       (+ heard-at 2000))
            settled  (plausibility/accumulate-flagging-jumps
                       velocity (delta :aircraft/position (update mid-atlantic :geo/lat + 0.002))
                       (+ heard-at 3000))]
        (is (true? (:aircraft/position-suspect? (get velocity ups-icao))))
        (is (true? (:aircraft/position-suspect? (get settled ups-icao))))))

    (testing "a legitimate reception gap — a hill, a banked turn, an
              antenna null — is ordinary flight, not a jump: the previous
              entry's stamp is when it was HEARD (adsb-0g0)"
      (let [moved-on (plausibility/accumulate-flagging-jumps
                       origin
                       (delta :aircraft/position
                              (update tampa :geo/lat + 0.2))
                       (+ heard-at 100000))
            aircraft (get moved-on ups-icao)]
        (is (= (+ heard-at 100000) (:aircraft/seen-at-ms aircraft)))
        (is (not (contains? aircraft :aircraft/position-suspect?)))))

    (testing "the flagged aircraft still validates — the flag is a domain
              field, not a smuggled one"
      (is (m/validate schema/aircraft (get jumped ups-icao))))

    (testing "a NON-POSITIONAL message between two positions does not
              fabricate a jump (adsb-zxk). 61% of a real SBS stream is
              velocity, callsign and squawk — each refreshes when we HEARD
              the aircraft without moving it an inch. Divide the hop by
              time-since-last-MESSAGE and this airliner implies 27,000 kt;
              divide it by time-since-the-position-MOVED and it implies the
              450 kt it is actually flying."
      (let [p2       (update tampa :geo/lat + 0.00102)
            velocity (plausibility/accumulate-flagging-jumps
                       origin (delta :aircraft/ground-speed-kt 451.2)
                       (+ heard-at 992))
            moved    (plausibility/accumulate-flagging-jumps
                       velocity (delta :aircraft/position p2)
                       (+ heard-at 1000))]
        (is (not (contains? (get moved ups-icao) :aircraft/position-suspect?)))
        (testing "— because the velocity message advanced 'when we heard it'
                  and left 'when it moved' alone: two different questions"
          (let [ac (get velocity ups-icao)]
            (is (= (+ heard-at 992) (:aircraft/seen-at-ms ac)))
            (is (= heard-at (:aircraft/position-at-ms ac)))))))

    (testing "and the detector is not merely deaf now: a genuine teleport
              still flags across an intervening non-positional message"
      (let [velocity (plausibility/accumulate-flagging-jumps
                       origin (delta :aircraft/ground-speed-kt 451.2)
                       (+ heard-at 992))
            spoofed  (plausibility/accumulate-flagging-jumps
                       velocity (delta :aircraft/position mid-atlantic)
                       (+ heard-at 1000))]
        (is (:aircraft/position-suspect? (get spoofed ups-icao)))))

    (testing "the flag the deltas earned rides the snapshot the poll loop
              takes, and the state merge honours it rather than
              recomputing it away"
      (let [aircraft (-> {}
                         (plausibility/merge-batch-flagging-jumps
                           (accumulator/snapshot jumped (+ heard-at 1000))
                           (+ heard-at 1000))
                         (plausibility/merge-batch-flagging-jumps
                           (accumulator/snapshot jumped (+ heard-at 2000))
                           (+ heard-at 2000))
                         (get ups-icao))]
        (is (:aircraft/position-suspect? aircraft))))))

(deftest receiver-privacy
  (testing "the full ingest pipeline — coerce, range-gate, flag, merge —
            yields aircraft carrying only aircraft-namespaced keys: no
            r_dst/r_dir, no receiver position"
    (let [raw      (assoc fixtures/ups-2717-raw :r_dst 39.887 :r_dir 231.3)
          batch    (-> (:aircraft (coerce/->aircraft-batch [raw]))
                       (plausibility/gate-range
                         receiver-position
                         plausibility/default-max-range-m))
          aircraft (stored (picture-after batch) ups-icao)]
      (is (= 1 (count batch)))
      (is (every? #(= "aircraft" (namespace %)) (keys aircraft)))
      (is (= #{:geo/lat :geo/lon} (set (keys (:aircraft/position aircraft)))))
      (is (= (:aircraft/position fixtures/ups-2717)
             (:aircraft/position aircraft))))))
