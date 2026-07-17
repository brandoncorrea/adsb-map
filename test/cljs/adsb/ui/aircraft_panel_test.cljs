(ns adsb.ui.aircraft-panel-test
  (:require ["@testing-library/react" :as rtl]
            [adsb.corejs :as cjs]
            [adsb.events]
            [adsb.fixtures :as fixtures]
            [adsb.stream]
            [adsb.subs]
            [adsb.test-dom :as test-dom]
            [adsb.ui.aircraft-panel :as panel]
            [clojure.string :as str]
            [clojure.test :refer-macros [deftest testing is use-fixtures async]]
            [day8.re-frame.test :as rf-test]
            [re-frame.core :as rf]
            [reagent.core :as r]))

(use-fixtures :each
  {:before (fn [] (rf/dispatch-sync [:app/initialize-db]))
   :after  rtl/cleanup})

(rf/reg-event-db :test/set-picture (fn [db [_ picture]] (assoc db :aircraft/picture picture)))
(rf/reg-event-db :test/set-shards (fn [db [_ shards]] (assoc db :enrich/shards shards)))
(rf/reg-fx :enrich/fetch! (fn [_]))

(def ^:private ups fixtures/ups-2717)
(def ^:private ups-icao (:aircraft/icao fixtures/ups-2717))

(defn- render-panel! []
  (test-dom/render! [panel/aircraft-panel]))

(defn- fresh-db! [] (rf/dispatch-sync [:app/initialize-db]))

(deftest escape-deselect-ignores-typing-fields
  (is (panel/escape-deselect?
        #js {:key "Escape" :target #js {:tagName "DIV"}}))
  (is (not (panel/escape-deselect?
             #js {:key "Escape" :target #js {:tagName "INPUT"}})))
  (is (not (panel/escape-deselect?
             #js {:key "a" :target #js {:tagName "DIV"}}))))

(deftest escape-clears-the-selection
  (async done
    (fresh-db!)
    (rf/dispatch-sync [:test/set-picture {ups-icao ups}])
    (rf/dispatch-sync [:aircraft/select ups-icao])
    (render-panel!)
    (is (some? (.getByTestId rtl/screen "aircraft-panel")))
    (panel/start-keyboard!)
    (.dispatchEvent js/document (js/KeyboardEvent. "keydown" #js {:key "Escape" :bubbles true}))
    (r/flush)
    (-> (rtl/waitFor
          (fn []
            (assert (nil? (.queryByTestId rtl/screen "aircraft-panel")))))
        (.then (fn [_]
                 (is (nil? (.queryByTestId rtl/screen "aircraft-panel"))
                     "Escape deselects and closes the panel")
                 (done)))
        (.catch (fn [err]
                  (is false (str "Escape did not close panel: " err))
                  (done))))))

(deftest collapsed-panel-stays-collapsed-when-switching-flights
  (async done
    (fresh-db!)
    (let [dal      fixtures/squawking-7700
          dal-icao (:aircraft/icao dal)]
      (rf/dispatch-sync [:test/set-picture {ups-icao ups dal-icao dal}])
      (rf/dispatch-sync [:aircraft/select ups-icao])
      (render-panel!)
      (is (= "true" (cjs/get-attribute (.getByTestId rtl/screen "aircraft-panel")
                                       "data-expanded")))
      (rf/dispatch-sync [:panel/toggle-expanded])
      (r/flush)
      (-> (rtl/waitFor
            (fn []
              (assert (= "false"
                         (cjs/get-attribute (.getByTestId rtl/screen "aircraft-panel")
                                            "data-expanded")))))
          (.then (fn [_]
                   (rf/dispatch-sync [:aircraft/select dal-icao])
                   (r/flush)
                   (rtl/waitFor
                     (fn []
                       (assert (= "false"
                                  (cjs/get-attribute (.getByTestId rtl/screen "aircraft-panel")
                                                     "data-expanded")))
                       (assert (str/includes?
                                 (.-textContent (.getByTestId rtl/screen "panel-title"))
                                 (or (:aircraft/callsign dal) dal-icao)))))))
          (.then (fn [_]
                   (is (= "false"
                          (cjs/get-attribute (.getByTestId rtl/screen "aircraft-panel")
                                             "data-expanded"))
                       "switching flights does not re-expand a minimized panel")
                   (done)))
          (.catch (fn [err]
                    (is false (str "collapse did not stick: " err))
                    (done)))))))

(deftest selecting-an-aircraft-renders-its-fields
  (testing "the panel shows the selected aircraft's facts"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture {ups-icao ups}])
      (rf/dispatch [:aircraft/select ups-icao])
      (render-panel!)
      (is (some? (.getByText rtl/screen "UPS2717")))
      (is (some? (.getByText rtl/screen "abc0e4")))
      (is (some? (.getByText rtl/screen "34775")))
      (is (some? (.getByText rtl/screen "451")))
      (is (nil? (.queryByText rtl/screen "450.5")))
      (is (some? (.getByText rtl/screen "097°")))
      (is (nil? (.queryByText rtl/screen "97.14")))
      (is (some? (.getByText rtl/screen "6040")))
      (is (some? (.getByText rtl/screen "-960")))))

  (testing "with no callsign the title falls back to the icao"
    (rf-test/run-test-sync
      (let [anon (dissoc ups :aircraft/callsign)]
        (rf/dispatch [:test/set-picture {ups-icao anon}])
        (rf/dispatch [:aircraft/select ups-icao])
        (render-panel!)
        (is (= "abc0e4" (.-textContent (.getByTestId rtl/screen "panel-title"))))))))

(deftest absent-altitude-renders-a-dash-never-zero
  (testing "an aircraft with no reported altitude dashes the field — never 0"
    (rf-test/run-test-sync
      (let [no-alt (dissoc ups :aircraft/altitude-ft :aircraft/on-ground?)]
        (rf/dispatch [:test/set-picture {ups-icao no-alt}])
        (rf/dispatch [:aircraft/select ups-icao])
        (render-panel!)
        (is (= panel/em-dash (.-textContent (.getByTestId rtl/screen "fact:Altitude"))))
        (is (nil? (.queryByText rtl/screen "0")))))))

(deftest enrichment-rows-render-and-degrade-to-a-dash
  (testing "a hex the database knows shows type, registration, and operator"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture {ups-icao ups}])
      (rf/dispatch [:test/set-shards
                    {"abc" {"abc0e4" {"t" "B744"
                                      "d" "Boeing 747-400"
                                      "r" "N570UP"
                                      "o" "United Parcel Service"}}}])
      (rf/dispatch [:aircraft/select ups-icao])
      (render-panel!)
      (is (= "Boeing 747-400" (.-textContent (.getByTestId rtl/screen "fact:Type")))
          "Type prefers the long description")
      (is (= "N570UP" (.-textContent (.getByTestId rtl/screen "fact:Registration"))))
      (is (= "United Parcel Service" (.-textContent (.getByTestId rtl/screen "fact:Operator"))))))

  (testing "with only a type code and no description, Type shows the code"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture {ups-icao ups}])
      (rf/dispatch [:test/set-shards {"abc" {"abc0e4" {"t" "B744"}}}])
      (rf/dispatch [:aircraft/select ups-icao])
      (render-panel!)
      (is (= "B744" (.-textContent (.getByTestId rtl/screen "fact:Type"))))
      (is (= panel/em-dash (.-textContent (.getByTestId rtl/screen "fact:Registration"))))))

  (testing "a hex the database does not know dashes every enrichment row"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture {ups-icao ups}])
      (rf/dispatch [:test/set-shards {"abc" :absent}])
      (rf/dispatch [:aircraft/select ups-icao])
      (render-panel!)
      (is (= panel/em-dash (.-textContent (.getByTestId rtl/screen "fact:Type"))))
      (is (= panel/em-dash (.-textContent (.getByTestId rtl/screen "fact:Registration"))))
      (is (= panel/em-dash (.-textContent (.getByTestId rtl/screen "fact:Operator")))))))

(deftest suspect-and-mlat-badges-show-only-when-true
  (testing "a position-suspect, MLAT-derived aircraft flies both badges"
    (rf-test/run-test-sync
      (let [flagged (assoc fixtures/mlat-derived :aircraft/position-suspect? true)
            icao    (:aircraft/icao flagged)]
        (rf/dispatch [:test/set-picture {icao flagged}])
        (rf/dispatch [:aircraft/select icao])
        (render-panel!)
        (is (.getByText rtl/screen "MLAT"))
        (is (.getByText rtl/screen "position suspect")))))

  (testing "an ordinary aircraft flies neither"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture {ups-icao ups}])
      (rf/dispatch [:aircraft/select ups-icao])
      (render-panel!)
      (is (nil? (.queryByText rtl/screen "MLAT")))
      (is (nil? (.queryByText rtl/screen "position suspect"))))))

(deftest emergency-badge-shows-the-squawk-meaning
  (testing "a selected emergency aircraft flies a badge naming the MEANING —
            the raw squawk still shows in the facts, the words in the badge"
    (rf-test/run-test-sync
      (let [icao (:aircraft/icao fixtures/squawking-7700)]
        (rf/dispatch [:test/set-picture {icao fixtures/squawking-7700}])
        (rf/dispatch [:aircraft/select icao])
        (render-panel!)
        (is (some? (.getByText rtl/screen "general emergency")))
        (is (= "7700" (.-textContent (.getByTestId rtl/screen "fact:Squawk"))))))))

(deftest seen-age-derives-from-seen-at-and-the-ui-clock
  (testing "seen-age counts seconds since the aircraft was last heard"
    (rf-test/run-test-sync
      (let [heard (assoc ups :aircraft/seen-at-ms 120000)]
        (rf/dispatch [:test/set-picture {ups-icao heard}])
        (rf/dispatch [:aircraft/select ups-icao])
        (rf/dispatch [:ui/tick 123000])
        (render-panel!)
        (is (= "3s ago" (.-textContent (.getByTestId rtl/screen "fact:Seen"))))))))

(deftest selection-lifecycle-at-the-sub-level
  (testing "selection is an icao that survives updates and dies with the aircraft"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture {ups-icao ups}])
      (rf/dispatch [:aircraft/select ups-icao])
      (is @(rf/subscribe [:aircraft/selected]))
      (rf/dispatch [:test/set-picture {ups-icao (assoc ups :aircraft/altitude-ft 35000)}])
      (is (= 35000 (:aircraft/altitude-ft @(rf/subscribe [:aircraft/selected]))))
      (rf/dispatch [:test/set-picture {}])
      (is (nil? @(rf/subscribe [:aircraft/selected])))
      (is (= ups-icao @(rf/subscribe [:aircraft/selected-icao])))
      (rf/dispatch [:ui/tick 999999])
      (is (nil? @(rf/subscribe [:aircraft/selected-icao]))))))

(deftest picture-that-drops-the-selected-aircraft-closes-the-panel
  (testing "the DOM panel disappears when its subject ages out"
    (fresh-db!)
    (rf/dispatch-sync [:test/set-picture {ups-icao ups}])
    (rf/dispatch-sync [:aircraft/select ups-icao])
    (render-panel!)
    (is (.getByText rtl/screen "UPS2717"))
    (rf/dispatch-sync [:test/set-picture {}])
    (async done
      (-> (rtl/waitForElementToBeRemoved
            (fn [] (.queryByText rtl/screen "UPS2717")))
          (.then (fn [_] (is (nil? (.queryByText rtl/screen "UPS2717")))))
          (.catch (fn [e] (is false (str "panel never closed: " e))))
          (.finally done)))))

(deftest close-button-clears-the-selection
  (testing "clicking Close dismisses the panel and clears the selection"
    (fresh-db!)
    (rf/dispatch-sync [:test/set-picture {ups-icao ups}])
    (rf/dispatch-sync [:aircraft/select ups-icao])
    (render-panel!)
    (.click rtl/fireEvent (.getByRole rtl/screen "button" #js {:name "Close"}))
    (async done
      (-> (rtl/waitForElementToBeRemoved
            (fn [] (.queryByText rtl/screen "UPS2717")))
          (.then (fn [_]
                   (is (nil? (.queryByText rtl/screen "UPS2717")))
                   (is (nil? @(rf/subscribe [:aircraft/selected-icao])))))
          (.catch (fn [e] (is false (str "close did not clear: " e))))
          (.finally done)))))

(deftest hostile-callsign-renders-as-text-not-markup
  (testing "a callsign carrying an XSS payload is inert escaped text (Boundary 4)"
    (rf-test/run-test-sync
      (let [hostile "<img src=x onerror=alert(1)>"
            evil    (assoc ups :aircraft/callsign hostile)]
        (rf/dispatch [:test/set-picture {ups-icao evil}])
        (rf/dispatch [:aircraft/select ups-icao])
        (render-panel!)
        (let [title (.getByTestId rtl/screen "panel-title")]
          (is (= hostile (.-textContent title)))
          (is (nil? (cjs/select title "img"))))
        (is (nil? (cjs/select (.-body js/document) "img")))))))
