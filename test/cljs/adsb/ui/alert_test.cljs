(ns adsb.ui.alert-test
  "The emergency ribbon, rendered in a real browser under React Testing
  Library. Proves the banner appears the moment the picture carries a
  distress squawk, names the aircraft (callsign, squawk, and the MEANING in
  words), that a click fires the map's [:aircraft/select icao] contract, that
  it vanishes when the emergency clears, and that MULTIPLE simultaneous
  emergencies each get their own stacked row — never one hidden behind a
  timer."
  (:require
    ["@testing-library/react" :as rtl]
    [adsb.events]
    [adsb.fixtures :as fixtures]
    [adsb.stream]                                 ; registers :aircraft/picture
    [adsb.subs]                                   ; registers :aircraft/emergencies
    [adsb.ui.alert :as alert]
    [cljs.test :refer-macros [deftest testing is use-fixtures]]
    [day8.re-frame.test :as rf-test]
    [re-frame.core :as rf]
    [reagent.core :as r]))

;; Without cleanup a previous test's ribbon stays mounted and the queries
;; below find two matches — or the wrong one.
(use-fixtures :each {:after rtl/cleanup})

;; Seed the picture directly: its owning event lives in adsb.stream and speaks
;; wire JSON, noise for a ribbon test. A tiny local event stands up the exact
;; app-db the ribbon reads.
(rf/reg-event-db :test/set-picture
  (fn [db [_ picture]] (assoc db :aircraft/picture picture)))

(defn- by-icao
  "A picture map keyed by each aircraft's icao — the shape :aircraft/picture
  holds."
  [aircraft]
  (into {} (map (juxt :aircraft/icao identity)) aircraft))

(defn- render-ribbon! []
  (rtl/cleanup)
  (rtl/render (r/as-element [alert/alert-ribbon])))

;; A hijack (7500), built by assoc-ing a copy of the happy-path cast member —
;; a real class of input the fixtures don't otherwise cover. Immutable cast:
;; assoc a copy, never mutate.
(def ^:private hijack
  (assoc fixtures/ups-2717
         :aircraft/icao "b00b00"
         :aircraft/callsign "HIJACK1"
         :aircraft/squawk "7500"))

(def ^:private dal-icao (:aircraft/icao fixtures/squawking-7700))

;; ---------------------------------------------------------------------

(deftest ribbon-appears-and-names-the-emergency
  (testing "with a 7700 in the picture the banner shows, naming who, the
            squawk, and the meaning in words"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture
                    (by-icao [fixtures/ups-2717 fixtures/squawking-7700])])
      (render-ribbon!)
      (is (some? (.getByTestId rtl/screen "alert-ribbon")) "the banner is present")
      (is (some? (.getByText rtl/screen "DAL1275")) "names the aircraft")
      (is (some? (.getByText rtl/screen "7700")) "shows the raw squawk")
      (is (some? (.getByText rtl/screen "general emergency"))
          "and spells out the MEANING, not just the number")))

  (testing "the banner is announced — role=alert"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [fixtures/squawking-7700])])
      (render-ribbon!)
      (is (= "alert" (.getAttribute (.getByTestId rtl/screen "alert-ribbon") "role"))))))

(deftest the-sky-is-calm-so-the-ribbon-is-absent
  (testing "no distress squawk in the picture — the banner renders nothing"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture
                    (by-icao [fixtures/ups-2717 fixtures/on-the-ground])])
      (render-ribbon!)
      (is (nil? (.queryByTestId rtl/screen "alert-ribbon"))
          "an empty banner would be clutter — there is none"))))

(deftest clicking-an-alert-selects-that-aircraft
  (testing "an alert click fires the map's [:aircraft/select icao] contract"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [fixtures/squawking-7700])])
      (render-ribbon!)
      (is (nil? @(rf/subscribe [:aircraft/selected-icao])) "nothing selected yet")
      ;; Click a child span; the delegated handler walks up to the alert.
      (.click rtl/fireEvent (.getByText rtl/screen "DAL1275"))
      (is (= dal-icao @(rf/subscribe [:aircraft/selected-icao]))
          "the banner selects the same identity the map would"))))

(deftest the-ribbon-clears-when-the-emergency-resolves
  (testing "when the squawk drops out of the picture the banner disappears"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [fixtures/squawking-7700])])
      (render-ribbon!)
      (is (some? (.queryByTestId rtl/screen "alert-ribbon")) "up while the emergency is live")
      ;; The aircraft stops squawking distress (a calm sky replaces it).
      (rf/dispatch [:test/set-picture (by-icao [fixtures/ups-2717])])
      (render-ribbon!)
      (is (nil? (.queryByTestId rtl/screen "alert-ribbon"))
          "and gone the instant the emergency clears"))))

(deftest multiple-emergencies-each-get-a-stacked-row
  (testing "two simultaneous emergencies both show — stacked, never cycled"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture
                    (by-icao [fixtures/ups-2717 fixtures/squawking-7700 hijack])])
      (render-ribbon!)
      (is (some? (.getByText rtl/screen "DAL1275")) "the general emergency")
      (is (some? (.getByText rtl/screen "HIJACK1")) "the hijack, at the same time")
      (is (some? (.getByText rtl/screen "general emergency")))
      (is (some? (.getByText rtl/screen "hijacking")))
      (is (= 2 (count (.getAllByRole rtl/screen "button")))
          "each emergency gets its own clickable row"))))
