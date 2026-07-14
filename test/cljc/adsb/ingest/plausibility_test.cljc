(ns adsb.ingest.plausibility-test
  (:require
    [adsb.accumulator :as accumulator]
    [adsb.fixtures :as fixtures]
    [adsb.geo :as geo]
    [adsb.ingest.coerce :as coerce]
    [adsb.ingest.plausibility :as plausibility]
    [adsb.schema :as schema]
    [malli.core :as m]
    #?(:clj  [clojure.test :refer [deftest testing is]]
       :cljs [cljs.test :refer-macros [deftest testing is]])))

(def ^:private captured-at-ms 1720713600000)

(def ^:private ups-icao (:aircraft/icao fixtures/ups-2717))

;; The synthetic receiver position of test/resources/receiver-sample
;; .json — in the same region as the cast's positions, so the whole
;; cast sits comfortably inside the default horizon. NOT the real
;; antenna location (see test/resources/README.md).
(def ^:private receiver-position {:geo/lat 28.0 :geo/lon -82.5})

;; ~2,200 km east of the receiver, far past any ADS-B horizon — and an
;; impossible one-second hop from anywhere the cast flies.
(def ^:private mid-atlantic {:geo/lat 28.0 :geo/lon -60.0})

(defn- at-position [aircraft position]
  (assoc aircraft :aircraft/position position))

;; ---------------------------------------------------------------------
;; Range gate

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
  (let [equator-origin {:geo/lat 0 :geo/lon 0}
        one-degree-east {:geo/lat 0 :geo/lon 1}
        aircraft (at-position fixtures/ups-2717 one-degree-east)
        exact-distance-m (geo/distance equator-origin one-degree-east)]
    (testing "a position exactly at the horizon is still inside it —
              the gate drops strictly beyond, never at"
      (is (not (plausibility/beyond-horizon? aircraft equator-origin
                                             exact-distance-m))))

    (testing "one meter past the horizon is beyond it"
      (is (plausibility/beyond-horizon? aircraft equator-origin
                                        (- exact-distance-m 1))))

    (testing "a position-less aircraft is never beyond the horizon"
      (is (not (plausibility/beyond-horizon? fixtures/never-positioned
                                             equator-origin 1))))))

;; ---------------------------------------------------------------------
;; Position-jump detection

(deftest position-jump?
  (let [equator-origin {:geo/lat 0 :geo/lon 0}
        one-degree-east {:geo/lat 0 :geo/lon 1}
        previous {:aircraft/position equator-origin
                  :aircraft/seen-at-ms 0}
        ms-per-hour 3600000
        ;; One degree along the equator in one hour: the implied speed
        ;; in knots is exactly the distance in nautical miles (~60 kt).
        implied-kt (geo/meters->nm (geo/distance equator-origin
                                                 one-degree-east))]
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

;; ---------------------------------------------------------------------
;; Flagging composed with the picture merge

(defn- picture-after
  "The picture after merging each batch in turn, one second apart."
  [& batches]
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
        (is (true? (:aircraft/position-suspect? aircraft)))
        (is (= mid-atlantic (:aircraft/position aircraft)))))

    (testing "a flagged aircraft still satisfies the domain schema"
      (is (m/validate schema/aircraft
                      (stored (picture-after [fixtures/ups-2717]
                                             [teleported])
                              ups-icao))))

    (testing "plausible movement between polls is not flagged"
      (let [;; ~230 m north in one second: a 450 kt cruise, not a jump.
            cruised (update-in fixtures/ups-2717
                               [:aircraft/position :geo/lat] + 0.002)]
        (is (not (contains? (stored (picture-after [fixtures/ups-2717]
                                                   [cruised])
                                    ups-icao)
                            :aircraft/position-suspect?)))))

    (testing "a first-ever position has nothing to jump from and is
              never flagged"
      (is (not (contains? (stored (picture-after [fixtures/ups-2717])
                                  ups-icao)
                          :aircraft/position-suspect?))))

    (testing "the first position after a never-positioned history is not
              a jump"
      (let [icao (:aircraft/icao fixtures/never-positioned)
            positioned (at-position fixtures/never-positioned
                                    mid-atlantic)]
        (is (not (contains? (stored (picture-after
                                      [fixtures/never-positioned]
                                      [positioned])
                                    icao)
                            :aircraft/position-suspect?)))))

    (testing "a subsequent observation consistent with the last stored
              position clears the flag"
      (let [;; Consistent with the (suspect) mid-atlantic position.
            settled (at-position fixtures/ups-2717
                                 (update mid-atlantic :geo/lat + 0.002))]
        (is (not (contains? (stored (picture-after [fixtures/ups-2717]
                                                   [teleported]
                                                   [settled])
                                    ups-icao)
                            :aircraft/position-suspect?)))))

    (testing "a position-less observation inherits the flag along with
              the position — a suspect position does not launder itself
              by falling silent"
      (let [silent (dissoc fixtures/ups-2717 :aircraft/position)
            aircraft (stored (picture-after [fixtures/ups-2717]
                                            [teleported]
                                            [silent])
                             ups-icao)]
        (is (true? (:aircraft/position-suspect? aircraft)))
        (is (= mid-atlantic (:aircraft/position aircraft)))))

    (testing "a position-less observation of a never-suspect aircraft
              stays unflagged"
      (let [silent (dissoc fixtures/ups-2717 :aircraft/position)]
        (is (not (contains? (stored (picture-after [fixtures/ups-2717]
                                                   [silent])
                                    ups-icao)
                            :aircraft/position-suspect?)))))))

;; ---------------------------------------------------------------------
;; The streaming path: snapshots, not polls (adsb-0g0)
;;
;; SBS and Beast reach merge-batch through adsb.accumulator, whose
;; snapshots carry an ABSOLUTE :aircraft/seen-at-ms and no
;; :aircraft/seen-s — and whose aircraft are repeated, unchanged, in
;; every snapshot the poll loop takes while the radio is quiet. So the
;; previous picture's stamp must be the instant the aircraft was HEARD,
;; not the instant it was last snapshotted; otherwise a reception gap —
;; a hill, a banked turn, an antenna null — collapses to a one-second
;; elapsed and the jump detector reads ordinary flight as a teleport.

(defn- streamed
  "An accumulator-shaped observation: positioned, absolutely stamped,
  and carrying no capture-relative seen."
  [position heard-at-ms]
  (-> fixtures/ups-2717
      (dissoc :aircraft/seen-s)
      (at-position position)
      (assoc :aircraft/seen-at-ms heard-at-ms)))

(deftest merge-batch-flagging-jumps-on-streamed-snapshots
  (let [origin     {:geo/lat 28.0 :geo/lon -82.5}
        ;; ~12 nm north: 100 s of flight at ~430 kt, or one impossible
        ;; second at ~43,000 kt. Which one it reads as is the bug.
        moved-on   (update origin :geo/lat + 0.2)
        heard-at   captured-at-ms
        gap-ms     100000
        ;; Heard, then 99 s of silence — the accumulator repeats the same
        ;; stamped aircraft in every snapshot — then heard again, moved.
        picture    (-> {}
                       (plausibility/merge-batch-flagging-jumps
                         [(streamed origin heard-at)] heard-at)
                       (plausibility/merge-batch-flagging-jumps
                         [(streamed origin heard-at)] (+ heard-at 99000))
                       (plausibility/merge-batch-flagging-jumps
                         [(streamed moved-on (+ heard-at gap-ms))]
                         (+ heard-at gap-ms)))
        aircraft   (get picture ups-icao)]

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
        (is (true? (:aircraft/position-suspect? jumped)))))))

;; ---------------------------------------------------------------------
;; The streaming fold: flagging per MESSAGE, not per snapshot (adsb-b36)
;;
;; The streaming Sources hand each merged delta straight to the
;; broadcaster, so an unflagged delta is a teleport broadcast as a clean
;; aircraft — and because a client upsert is a full-state replacement, an
;; unflagged delta also ERASES a flag the connect-time snapshot delivered.
;; accumulate-flagging-jumps is the fold that closes both halves.

(def ^:private tampa {:geo/lat 28.0 :geo/lon -82.4})

(defn- delta
  "A streaming delta: only what the message said, never a stamp of its
  own (the accumulator stamps it at arrival)."
  [& {:as fields}]
  (assoc fields :aircraft/icao ups-icao))

(deftest accumulate-flagging-jumps-flags-the-delta-path
  (let [heard-at 1000
        origin   (plausibility/accumulate-flagging-jumps
                   {} (delta :aircraft/position tampa) heard-at)
        ;; Tampa to mid-Atlantic in one second: ~2,000 kt-per-second short
        ;; of physics, and the fingerprint of a spoof.
        jumped   (plausibility/accumulate-flagging-jumps
                   origin (delta :aircraft/position mid-atlantic)
                   (+ heard-at 1000))]

    (testing "a first-heard position has nothing to jump from"
      (is (not (contains? (get origin ups-icao)
                          :aircraft/position-suspect?))))

    (testing "a teleporting delta is flagged as it folds in — the merged
              aircraft the broadcaster upserts carries the mark"
      (is (true? (:aircraft/position-suspect? (get jumped ups-icao)))))

    (testing "the flag STICKS across every later message: a delta says
              only what changed and never says 'no longer suspect', so a
              spoofed track cannot launder its flag away by sending a
              velocity — the upsert that follows would have erased it"
      (let [velocity (plausibility/accumulate-flagging-jumps
                       jumped (delta :aircraft/ground-speed-kt 430.0)
                       (+ heard-at 2000))
            settled  (plausibility/accumulate-flagging-jumps
                       velocity
                       (delta :aircraft/position
                              (update mid-atlantic :geo/lat + 0.002))
                       (+ heard-at 3000))]
        (is (true? (:aircraft/position-suspect? (get velocity ups-icao))))
        (is (true? (:aircraft/position-suspect? (get settled ups-icao)))
            "not even a position consistent with the spoofed one clears it")))

    (testing "a legitimate reception gap — a hill, a banked turn, an
              antenna null — is ordinary flight, not a jump: the previous
              entry's stamp is when it was HEARD (adsb-0g0)"
      (let [moved-on (plausibility/accumulate-flagging-jumps
                       origin
                       (delta :aircraft/position
                              (update tampa :geo/lat + 0.2)) ; ~12 nm
                       (+ heard-at 100000))                  ; over 100 s
            aircraft (get moved-on ups-icao)]
        (is (= (+ heard-at 100000) (:aircraft/seen-at-ms aircraft)))
        (is (not (contains? aircraft :aircraft/position-suspect?)))))

    (testing "the flagged aircraft still validates — the flag is a domain
              field, not a smuggled one"
      (is (m/validate schema/aircraft (get jumped ups-icao))))

    (testing "the flag the deltas earned rides the snapshot the poll loop
              takes, and the state merge honours it rather than
              recomputing it away"
      (let [aircraft (-> {}
                         (plausibility/merge-batch-flagging-jumps
                           (accumulator/snapshot jumped (+ heard-at 1000))
                           (+ heard-at 1000))
                         ;; ...and the next snapshot, consistent with the
                         ;; spoofed position, must not clear it either.
                         (plausibility/merge-batch-flagging-jumps
                           (accumulator/snapshot jumped (+ heard-at 2000))
                           (+ heard-at 2000))
                         (get ups-icao))]
        (is (true? (:aircraft/position-suspect? aircraft)))))))

;; ---------------------------------------------------------------------
;; Privacy: the receiver's location must never reach a domain aircraft
;; (bead adsb-nqf.3 — one aircraft's position plus its r_dst/r_dir
;; locates the antenna exactly).

(deftest receiver-privacy
  (testing "the full ingest pipeline — coerce, range-gate, flag, merge —
            yields aircraft carrying only aircraft-namespaced keys: no
            r_dst/r_dir, no receiver position"
    (let [raw (assoc fixtures/ups-2717-raw :r_dst 39.887 :r_dir 231.3)
          batch (-> (coerce/->aircraft-batch [raw])
                    (plausibility/gate-range
                      receiver-position
                      plausibility/default-max-range-m))
          aircraft (stored (picture-after batch) ups-icao)]
      (is (= 1 (count batch)) "the gated aircraft survives ingest")
      (is (every? #(= "aircraft" (namespace %)) (keys aircraft))
          "every key is aircraft-namespaced — nothing raw leaks through")
      (is (= #{:geo/lat :geo/lon} (set (keys (:aircraft/position aircraft))))
          "the position is the aircraft's own lat/lon and nothing more")
      (is (= (:aircraft/position fixtures/ups-2717)
             (:aircraft/position aircraft))
          "the stored position is the aircraft's, not the receiver's"))))
