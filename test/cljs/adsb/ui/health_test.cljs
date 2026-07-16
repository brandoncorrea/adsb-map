(ns adsb.ui.health-test
  (:require ["@testing-library/react" :as rtl]
            [adsb.corejs :as cjs]
            [adsb.events]
            [adsb.stream :as stream]
            [adsb.stream.source :as source]
            [adsb.subs]
            [adsb.ui.health :as health]
            [clojure.test :refer-macros [deftest testing is use-fixtures]]
            [day8.re-frame.test :as rf-test]
            [re-frame.core :as rf]
            [reagent.core :as r]))

(use-fixtures :each {:after rtl/cleanup})

(rf/reg-event-db :test/set-connection
  (fn [db [_ status]] (assoc db :stream/connection status)))
(rf/reg-event-db :test/set-feeder
  (fn [db [_ status]] (assoc db :feeder/status status)))
(rf/reg-event-db :test/set-silent-frames
  (fn [db [_ n]] (assoc db :feeder/silent-frames n)))

(defn- render-header! []
  (rtl/cleanup)
  (rtl/render (r/as-element [health/health])))

(deftest a-reachable-feeder-that-hears-nothing-is-not-ok
  (testing "a feeder claiming :ok while its messages have stopped reads :silent"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-connection :live])
      (rf/dispatch [:test/set-feeder :ok])
      (rf/dispatch [:test/set-silent-frames stream/silent-after-frames])
      (render-header!)
      (is (= :silent @(rf/subscribe [:feeder/health])))
      (let [chip (.getByTestId rtl/screen "feeder-indicator")]
        (is (= "silent" (cjs/get-attribute chip "data-state")))
        (let [label (.getByText rtl/screen "No messages")]
          (is (some? label))
          (is (not (.contains (.-classList label) "adsb-vh")))))))

  (testing "below the threshold it stays :ok — the light must not blink. The
            server rounds the rate to a whole number, so a dribbling feeder
            samples as zero now and then, and a dot that flipped on one such
            sample would teach the reader to ignore it"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-connection :live])
      (rf/dispatch [:test/set-feeder :ok])
      (rf/dispatch [:test/set-silent-frames (dec stream/silent-after-frames)])
      (render-header!)
      (is (= :ok @(rf/subscribe [:feeder/health])))))

  (testing "a feeder that is DOWN stays down — silence never masks a worse fact"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-connection :live])
      (rf/dispatch [:test/set-feeder :down])
      (rf/dispatch [:test/set-silent-frames 999])
      (render-header!)
      (is (= :down @(rf/subscribe [:feeder/health]))))))

(deftest connection-indicator-shows-each-state
  (doseq [[state label] [[:reconnecting "Reconnecting"]
                         [:down "Disconnected"]]]
    (testing (str "the stream chip honestly reflects " state)
      (rf-test/run-test-sync
        (rf/dispatch [:test/set-connection state])
        (render-header!)
        (let [chip (.getByTestId rtl/screen "connection-indicator")]
          (is (= (name state) (cjs/get-attribute chip "data-state")))
          (is (some? (.getByText rtl/screen label))))))))

(deftest a-refresh-does-not-open-on-a-failure-it-has-not-had
  (testing "the boot state is :connecting — never connected, nothing failed —
            and neither signal says a word (adsb-33i). The app used to seed
            :reconnecting at start, claiming a recovery from a failure that
            never happened, and :feeder/health faithfully turned that lie into
            a flash of 'Feeder unknown' on every single refresh"
    (rf-test/run-test-sync
      (with-redefs [source/connect! (fn [_url _cbs] nil)]
        (rf/dispatch [:stream/start]))
      (render-header!)
      (is (= :connecting @(rf/subscribe [:stream/connection])))
      (is (nil? @(rf/subscribe [:feeder/health])))
      (is (nil? (.queryByTestId rtl/screen "connection-indicator")))
      (is (nil? (.queryByTestId rtl/screen "feeder-indicator")))
      (is (nil? (.queryByText rtl/screen "Feeder unknown"))))))

(deftest a-live-stream-says-nothing-at-all
  (testing "the header reports EXCEPTIONS, not confirmations (adsb-33i): a
            healthy link is not news, and the chip that used to announce it
            every second of every session is simply absent. The states that
            ARE news keep their chip — asserted just above — so a frozen map
            still cannot read as a quiet one"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-connection :live])
      (render-header!)
      (is (nil? (.queryByTestId rtl/screen "connection-indicator")))
      (is (nil? (.queryByText rtl/screen "Live"))))))

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
          (is (= (name feeder-status) (cjs/get-attribute chip "data-state")))
          (is (some? (.getByText rtl/screen label))))))))

(deftest colour-alone-may-say-fine-and-may-never-say-broken
  (testing "a healthy feeder is a bare dot — its label leaves the VISUAL
            channel and stays in the ACCESSIBLE one, so the eye gets a green
            dot and a screen reader still hears the words"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-connection :live])
      (rf/dispatch [:test/set-feeder :ok])
      (render-header!)
      (let [label (.getByText rtl/screen "Feeder OK")]
        (is (some? label))
        (is (cjs/has-class? label "adsb-vh")))))

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
          (is (not (cjs/has-class? el "adsb-vh"))
              (str (name status) " says so in words, not in colour alone")))))))

(deftest feeder-indicator-is-unknown-when-stream-not-live
  (doseq [stream-state [:reconnecting :down]]
    (testing (str "a stale feeder claim is suppressed while the stream is "
                  stream-state)
      (rf-test/run-test-sync
        (rf/dispatch [:test/set-connection stream-state])
        (rf/dispatch [:test/set-feeder :ok])
        (render-header!)
        (let [chip (.getByTestId rtl/screen "feeder-indicator")]
          (is (= "unknown" (cjs/get-attribute chip "data-state")))
          (is (some? (.getByText rtl/screen "Feeder unknown"))))))))
