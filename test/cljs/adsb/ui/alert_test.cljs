(ns adsb.ui.alert-test
  (:require ["@testing-library/react" :as rtl]
            [adsb.corejs :as cjs]
            [adsb.events]
            [adsb.fixtures :as fixtures]
            [adsb.stream]
            [adsb.subs]
            [adsb.test-dom :as test-dom]
            [adsb.ui.alert :as alert]
            [clojure.test :refer-macros [deftest testing is use-fixtures]]
            [day8.re-frame.test :as rf-test]
            [re-frame.core :as rf]))

(use-fixtures :each {:after rtl/cleanup})
(rf/reg-event-db :test/set-picture
  (fn [db [_ picture]] (assoc db :aircraft/picture picture)))

(defn- by-icao [aircraft]
  (into {} (map (juxt :aircraft/icao identity)) aircraft))

(defn- render-ribbon! []
  (test-dom/render! [alert/alert-ribbon]))

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
      (is (.getByTestId rtl/screen "alert-ribbon"))
      (is (.getByText rtl/screen "DAL1275"))
      (is (.getByText rtl/screen "7700"))
      (is (.getByText rtl/screen "general emergency"))))

  (testing "the banner is announced — role=alert"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [fixtures/squawking-7700])])
      (render-ribbon!)
      (is (= "alert" (cjs/get-attribute (.getByTestId rtl/screen "alert-ribbon") "role")))))

  (testing "the strip is a NOTAM (design direction §7): the stamped tab is
            drawn for the sighted reader and hidden from assistive tech,
            which hears each row's aria-label sentence instead"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [fixtures/squawking-7700])])
      (render-ribbon!)
      (let [stamp (cjs/select (.getByTestId rtl/screen "alert-ribbon") ".adsb-alert-stamp")]
        (is (= "NOTAM" (.-textContent stamp)))
        (is (= "true" (cjs/get-attribute stamp "aria-hidden")))))))

(deftest the-sky-is-calm-so-the-ribbon-is-absent
  (testing "no distress squawk in the picture — the banner renders nothing"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture
                    (by-icao [fixtures/ups-2717 fixtures/on-the-ground])])
      (render-ribbon!)
      (is (nil? (.queryByTestId rtl/screen "alert-ribbon"))))))

(deftest clicking-an-alert-selects-that-aircraft
  (testing "an alert click fires the map's [:aircraft/select icao] contract"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [fixtures/squawking-7700])])
      (render-ribbon!)
      (is (nil? @(rf/subscribe [:aircraft/selected-icao])) "nothing selected yet")
      (.click rtl/fireEvent (.getByText rtl/screen "DAL1275"))
      (is (= dal-icao @(rf/subscribe [:aircraft/selected-icao]))))))

(deftest the-ribbon-clears-when-the-emergency-resolves
  (testing "when the squawk drops out of the picture the banner disappears"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [fixtures/squawking-7700])])
      (render-ribbon!)
      (is (.queryByTestId rtl/screen "alert-ribbon"))
      (rf/dispatch [:test/set-picture (by-icao [fixtures/ups-2717])])
      (render-ribbon!)
      (is (nil? (.queryByTestId rtl/screen "alert-ribbon"))))))

(deftest multiple-emergencies-each-get-a-stacked-row
  (testing "two simultaneous emergencies both show — stacked, never cycled"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [fixtures/ups-2717 fixtures/squawking-7700 hijack])])
      (render-ribbon!)
      (is (.getByText rtl/screen "DAL1275"))
      (is (.getByText rtl/screen "HIJACK1"))
      (is (.getByText rtl/screen "general emergency"))
      (is (.getByText rtl/screen "hijacking"))
      (is (= 2 (count (.getAllByRole rtl/screen "button")))))))
