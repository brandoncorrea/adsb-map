(ns adsb.ui.header-test
  "The app bar, rendered in a real browser under React Testing Library. Proves
  the vital signs are honest: the live counts read the picture and update when
  it turns over; the STREAM chip shows each of the stream's three states; the
  FEEDER chip shows the server's reported feeder health while the stream is
  live and a neutral unknown when it is not (the unknowable rule). Each chip
  carries a distinct data-state AND a text label (colour alone is not
  accessible)."
  (:require
    ["@testing-library/react" :as rtl]
    [adsb.events]
    [adsb.stream]                                 ; registers :aircraft/picture + :stream/connection
    [adsb.stream.source :as source]               ; the connect! seam, stubbed at boot
    [adsb.subs]
    [adsb.ui.header :as header]
    [cljs.test :refer-macros [deftest testing is use-fixtures]]
    [day8.re-frame.test :as rf-test]
    [re-frame.core :as rf]
    [reagent.core :as r]))

;; Without cleanup a previous test's header stays mounted and the queries find
;; two matches (or the wrong one).
(use-fixtures :each {:after rtl/cleanup})

;; Seed app-db directly: the real owning events live in adsb.stream and speak
;; wire JSON / drive the live connection, both noise for a header test. Tiny
;; local events stand up the exact keys the header reads.
(rf/reg-event-db :test/set-connection
  (fn [db [_ status]] (assoc db :stream/connection status)))
(rf/reg-event-db :test/set-feeder
  (fn [db [_ status]] (assoc db :feeder/status status)))
(rf/reg-event-db :test/set-stats
  (fn [db [_ stats]] (assoc db :stats/session stats)))

(defn- render-header! []
  (rtl/cleanup)
  (rtl/render (r/as-element [header/header])))

;; ---------------------------------------------------------------------

(deftest the-session-scalars-read-from-the-header
  (testing "max range and message rate are vitals, and they now sit with the
            other vitals rather than in a bordered chip over the map
            (adsb-33i). The readout is adsb.ui.stats' own component, subs and
            absent-is-a-dash rule intact — only its address changed"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-stats #:stats{:max-range-km 256 :message-rate 382}])
      (render-header!)
      (let [header-el (.getByRole rtl/screen "banner")]
        (is (some? (.querySelector header-el "[data-testid='stats-max-range']"))
            "max range reads from inside the header")
        (is (some? (.querySelector header-el "[data-testid='stats-message-rate']"))
            "and so does the message rate")
        (is (some? (.getByText rtl/screen "256 km")) "the range, with its unit")
        (is (some? (.getByText rtl/screen "382/s")) "the rate, with its unit")))))

(deftest connection-indicator-shows-each-state
  ;; The STREAM chip: :down reads "Disconnected" — it measures the
  ;; browser-to-server stream, not the feeder (which owns "Feeder down").
  (doseq [[state label] [[:reconnecting "Reconnecting"]
                         [:down "Disconnected"]]]
    (testing (str "the stream chip honestly reflects " state)
      (rf-test/run-test-sync
        (rf/dispatch [:test/set-connection state])
        (render-header!)
        (let [chip (.getByTestId rtl/screen "connection-indicator")]
          (is (= (name state) (.getAttribute chip "data-state"))
              "a distinct state hook the visual pass can style")
          (is (some? (.getByText rtl/screen label))
              "and a text label — never colour alone"))))))

(deftest a-refresh-does-not-open-on-a-failure-it-has-not-had
  (testing "the boot state is :connecting — never connected, nothing failed —
            and neither signal says a word (adsb-33i). The app used to seed
            :reconnecting at start, claiming a recovery from a failure that
            never happened, and :feeder/health faithfully turned that lie into
            a flash of 'Feeder unknown' on every single refresh"
    (rf-test/run-test-sync
      ;; :stream/start's own effect opens a real EventSource; this test is
      ;; about the state it SEEDS, not the socket it opens (the same seam
      ;; adsb.stream-test drives the state machine through).
      (with-redefs [source/connect! (fn [_url _cbs] nil)]
        (rf/dispatch [:stream/start]))
      (render-header!)
      (is (= :connecting @(rf/subscribe [:stream/connection]))
          "boot is connecting, not reconnecting")
      (is (nil? @(rf/subscribe [:feeder/health]))
          "and the feeder is unasked, not unknowable")
      (is (nil? (.queryByTestId rtl/screen "connection-indicator"))
          "so the stream says nothing")
      (is (nil? (.queryByTestId rtl/screen "feeder-indicator"))
          "and the feeder says nothing")
      (is (nil? (.queryByText rtl/screen "Feeder unknown"))
          "no flash of a stale claim across the refresh"))))

(deftest a-live-stream-says-nothing-at-all
  (testing "the header reports EXCEPTIONS, not confirmations (adsb-33i): a
            healthy link is not news, and the chip that used to announce it
            every second of every session is simply absent. The states that
            ARE news keep their chip — asserted just above — so a frozen map
            still cannot read as a quiet one"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-connection :live])
      (render-header!)
      (is (nil? (.queryByTestId rtl/screen "connection-indicator"))
          "nothing to report, so nothing is reported")
      (is (nil? (.queryByText rtl/screen "Live"))
          "and the word is gone with it"))))

(deftest feeder-indicator-shows-each-state
  ;; The FEEDER chip, distinct from the stream chip: while the stream is live
  ;; it reflects the server's reported feeder health.
  (doseq [[feeder-status label] [[:ok "Feeder OK"]
                                 [:starting "Feeder starting"]
                                 [:down "Feeder down"]]]
    (testing (str "the feeder chip honestly reflects " feeder-status)
      (rf-test/run-test-sync
        (rf/dispatch [:test/set-connection :live])
        (rf/dispatch [:test/set-feeder feeder-status])
        (render-header!)
        (let [chip (.getByTestId rtl/screen "feeder-indicator")]
          (is (= (name feeder-status) (.getAttribute chip "data-state"))
              "a distinct state hook, separate from the stream chip")
          (is (some? (.getByText rtl/screen label))
              "and a text label — never colour alone"))))))

(deftest colour-alone-may-say-fine-and-may-never-say-broken
  ;; The compaction that is allowed, and the line it must not cross (adsb-33i).
  (testing "a healthy feeder is a bare dot — its label leaves the VISUAL
            channel and stays in the ACCESSIBLE one, so the eye gets a green
            dot and a screen reader still hears the words"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-connection :live])
      (rf/dispatch [:test/set-feeder :ok])
      (render-header!)
      (let [label (.getByText rtl/screen "Feeder OK")]
        (is (some? label)
            "the words are still in the document, not deleted from it")
        (is (.contains (.-classList label) "adsb-vh")
            "and hidden from sight only"))))

  (testing "every state that is a PROBLEM keeps its words in plain sight — a
            colour-blind reader must never be the only one who cannot see
            that the antenna is dead"
    (doseq [[status label] [[:starting "Feeder starting"]
                            [:down "Feeder down"]]]
      (rf-test/run-test-sync
        (rf/dispatch [:test/set-connection :live])
        (rf/dispatch [:test/set-feeder status])
        (render-header!)
        (let [el (.getByText rtl/screen label)]
          (is (not (.contains (.-classList el) "adsb-vh"))
              (str (name status) " says so in words, not in colour alone")))))))

(deftest feeder-indicator-is-unknown-when-stream-not-live
  ;; The unknowable rule: a feeder claim only means something while the stream
  ;; is live. When the stream is not live the feeder chip shows a neutral
  ;; :unknown rather than a stale :ok/:down.
  (doseq [stream-state [:reconnecting :down]]
    (testing (str "a stale feeder claim is suppressed while the stream is "
                  stream-state)
      (rf-test/run-test-sync
        (rf/dispatch [:test/set-connection stream-state])
        (rf/dispatch [:test/set-feeder :ok])   ; a claim from before the drop
        (render-header!)
        (let [chip (.getByTestId rtl/screen "feeder-indicator")]
          (is (= "unknown" (.getAttribute chip "data-state"))
              "the stale ok is not asserted")
          (is (some? (.getByText rtl/screen "Feeder unknown"))
              "the chip reads unknown, not a stale claim"))))))
