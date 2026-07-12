(ns adsb.ingest.replay-test
  "The fixture-replay Source, driven by a fake clock — time is an
  argument, so the same clock replays the same sky (docs/CLAUDE.md: the
  sky is not a fixture; replay a recording)."
  (:require
    [adsb.fixtures :as fixtures]
    [adsb.geo :as geo]
    [adsb.ingest.replay :as replay]
    [adsb.ingest.source :as source]
    [clojure.test :refer [deftest testing is]]))

(def ^:private flock
  "A small replay set: a fast mover, a slow ground mover, and a
  heard-but-never-positioned target — enough to cover movement and its
  absence."
  [fixtures/ups-2717 fixtures/on-the-ground fixtures/never-positioned])

(defn- replay-source
  "A replay Source over `batch` on `clock`, opened at clock 0. loop-ms
  and age-rate are pinned so the assertions read plainly."
  [batch clock & {:keys [loop-ms age-rate]
                  :or   {loop-ms 240000 age-rate 0.5}}]
  (reset! clock 0)
  (doto (replay/->source {:batch    batch
                          :clock    #(deref clock)
                          :loop-ms  loop-ms
                          :age-rate age-rate})
    (source/open!)))

(defn- fetch-at [src clock ms]
  (reset! clock ms)
  (source/fetch! src))

(defn- by-icao [batch icao]
  (some #(when (= icao (:aircraft/icao %)) %) batch))

(deftest advances-seen-against-the-replay-clock
  (testing "seen age accrues at the age-rate, so a fresh aircraft drifts
            toward the stale line as replay time passes"
    (let [clock  (atom 0)
          src    (replay-source flock clock :age-rate 0.5)
          before (by-icao (fetch-at src clock 0) "abc0e4")
          after  (by-icao (fetch-at src clock 30000) "abc0e4")]
      (is (= (:aircraft/seen-s fixtures/ups-2717) (:aircraft/seen-s before))
          "at lap start the fixture's own seen is reported unchanged")
      ;; 0.4 fixture seen + 0.5 * 30 s elapsed
      (is (= 15.4 (:aircraft/seen-s after))))))

(deftest moves-positioned-aircraft-along-its-track
  (testing "a positioned aircraft with a ground speed and track dead-
            reckons forward along that track as replay time passes"
    (let [clock (atom 0)
          src   (replay-source flock clock)
          start (:aircraft/position
                  (by-icao (fetch-at src clock 0) "abc0e4"))
          later (:aircraft/position
                  (by-icao (fetch-at src clock 60000) "abc0e4"))]
      (is (not= start later) "the position advances")
      (is (< (Math/abs (- (geo/bearing start later)
                          (:aircraft/track-deg fixtures/ups-2717)))
             1.0)
          "and it advances along the aircraft's own track"))))

(deftest leaves-motionless-aircraft-in-place
  (testing "an aircraft with no position, or no ground speed and track,
            never moves — a position-less target never gains one"
    (let [no-speed (dissoc fixtures/ups-2717 :aircraft/ground-speed-kt)
          clock    (atom 0)
          src      (replay-source [no-speed fixtures/never-positioned] clock)
          batch    (fetch-at src clock 60000)]
      (is (= (:aircraft/position no-speed)
             (:aircraft/position (by-icao batch "abc0e4")))
          "no ground speed means no dead reckoning")
      (is (not (contains? (by-icao batch "a10202") :aircraft/position))
          "the position-less aircraft stays position-less"))))

(deftest loops-back-to-the-fixture-at-the-lap-boundary
  (testing "a full lap folds back to the fixture: positions and ages at
            loop-ms match those at the start of the lap"
    (let [clock     (atom 0)
          loop-ms   120000
          src       (replay-source flock clock :loop-ms loop-ms)
          lap-start (fetch-at src clock 0)
          lap-end   (fetch-at src clock loop-ms)]
      (is (= lap-start lap-end)
          "the sky at the lap boundary is the fixture again"))))

(deftest replays-deterministically-for-a-given-clock
  (testing "the same clock instant yields byte-identical aircraft — the
            Source holds no hidden state between fetches"
    (let [clock (atom 0)
          src   (replay-source flock clock)]
      (is (= (fetch-at src clock 45000)
             (fetch-at src clock 45000))))))

(deftest keeps-serving-the-whole-picture-forever
  (testing "fetch! never exhausts: many laps later it still returns the
            full batch"
    (let [clock (atom 0)
          src   (replay-source flock clock :loop-ms 30000)]
      (is (= (count flock) (count (fetch-at src clock (* 500 30000))))
          "500 laps in, the picture is still whole"))))

(deftest reads-and-coerces-the-recorded-fixture
  (testing "with no injected batch, ->source reads the recorded payload
            and fetch! yields coerced domain aircraft, positions and all"
    (let [src   (doto (replay/->source) (source/open!))
          batch (source/fetch! src)]
      (is (= 51 (count batch)) "every recorded aircraft is replayed")
      (is (= 39 (count (filter :aircraft/position batch)))
          "the 39 positioned aircraft carry a position")
      (is (every? :aircraft/icao batch)
          "every entry is a coerced, namespaced domain aircraft"))))
