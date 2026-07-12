(ns adsb.ui.header-test
  "The app bar, rendered in a real browser under React Testing Library. Proves
  the two vital signs are honest: the live counts read the picture and update
  when it turns over, and the connection chip shows each of the stream's three
  states with a distinct data-state AND a text label (colour alone is not
  accessible)."
  (:require
    ["@testing-library/react" :as rtl]
    [adsb.events]
    [adsb.fixtures :as fixtures]
    [adsb.stream]                                 ; registers :aircraft/picture + :stream/connection
    [adsb.subs]
    [adsb.ui.header :as header]
    [cljs.test :refer-macros [deftest testing is use-fixtures async]]
    [day8.re-frame.test :as rf-test]
    [re-frame.core :as rf]
    [reagent.core :as r]))

;; Without cleanup a previous test's header stays mounted and the queries find
;; two matches (or the wrong one).
(use-fixtures :each {:after rtl/cleanup})

;; Seed app-db directly: the real owning events live in adsb.stream and speak
;; wire JSON / drive the live connection, both noise for a header test. Tiny
;; local events stand up the exact keys the header reads.
(rf/reg-event-db :test/set-picture
  (fn [db [_ picture]] (assoc db :aircraft/picture picture)))
(rf/reg-event-db :test/set-connection
  (fn [db [_ status]] (assoc db :stream/connection status)))

;; A positioned aircraft and a never-positioned one: the total counts both,
;; the positioned tally only the first.
(def ^:private positioned fixtures/ups-2717)
(def ^:private positioned-icao (:aircraft/icao fixtures/ups-2717))
(def ^:private unplaced fixtures/never-positioned)
(def ^:private unplaced-icao (:aircraft/icao fixtures/never-positioned))

(defn- render-header! []
  (rtl/cleanup)
  (rtl/render (r/as-element [header/header])))

(defn- fresh-db! []
  (rf/dispatch-sync [:app/initialize-db]))

(defn- text [testid]
  (.-textContent (.getByTestId rtl/screen testid)))

;; ---------------------------------------------------------------------

(deftest counts-render-from-the-picture
  (testing "total counts every aircraft; positioned counts only the placed"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture {positioned-icao positioned
                                       unplaced-icao   unplaced}])
      (render-header!)
      ;; State set before the initial mount, which RTL commits synchronously.
      (is (= "2" (text "count-total"))      "both aircraft in the total")
      (is (= "1" (text "count-positioned")) "only the positioned one is placed"))))

(deftest counts-update-on-picture-replacement
  (testing "when the picture turns over the counts follow it live"
    (fresh-db!)
    (rf/dispatch-sync [:test/set-picture {positioned-icao positioned
                                          unplaced-icao   unplaced}])
    (render-header!)
    (is (= "2" (text "count-total"))      "starts at two")
    (is (= "1" (text "count-positioned")) "one positioned")
    ;; A fresh picture that keeps only the positioned aircraft. React 18
    ;; commits the reagent re-render asynchronously, so poll with waitFor.
    (rf/dispatch-sync [:test/set-picture {positioned-icao positioned}])
    (async done
      (-> (rtl/waitFor
            (fn []
              (when-not (= "1" (text "count-total"))
                (throw (js/Error. "total has not updated yet")))))
          (.then (fn [_]
                   (is (= "1" (text "count-total"))      "total dropped to one")
                   (is (= "1" (text "count-positioned")) "still one positioned")))
          (.catch (fn [e] (is false (str "counts never updated: " e))))
          (.finally done)))))

(deftest connection-indicator-shows-each-state
  (doseq [[state label] [[:live "Live"]
                         [:reconnecting "Reconnecting"]
                         [:down "Feeder down"]]]
    (testing (str "the chip honestly reflects " state)
      (rf-test/run-test-sync
        (rf/dispatch [:test/set-connection state])
        (render-header!)
        (let [chip (.getByTestId rtl/screen "connection-indicator")]
          (is (= (name state) (.getAttribute chip "data-state"))
              "a distinct state hook the visual pass can style")
          (is (some? (.getByText rtl/screen label))
              "and a text label — never colour alone"))))))
