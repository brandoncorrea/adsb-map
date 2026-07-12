(ns adsb.ui.sidebar-test
  "The aircraft roster, rendered in a real browser under React Testing
  Library. Proves the list draws from the picture, a row click fires the
  map's select contract, the selected row is marked, the two filters exclude
  correctly (never-positioned vanishes under positioned-only; on-the-ground
  vanishes under airborne-only), and the altitude sort orders high→low with
  the never-reported altitude placed LAST — never as a phantom zero at the
  top."
  (:require
    ["@testing-library/react" :as rtl]
    [adsb.events]
    [adsb.fixtures :as fixtures]
    [adsb.stream]                                 ; registers :aircraft/picture
    [adsb.subs]                                   ; registers :aircraft/selected-icao
    [adsb.ui.sidebar :as sidebar]
    [cljs.test :refer-macros [deftest testing is use-fixtures]]
    [day8.re-frame.test :as rf-test]
    [re-frame.core :as rf]
    [reagent.core :as r]))

;; Without cleanup a previous test's roster stays mounted and the queries
;; below find two matches — or the wrong one.
(use-fixtures :each {:after rtl/cleanup})

;; Seed the picture directly: its owning event lives in adsb.stream and speaks
;; wire JSON, noise for a roster test. A tiny local event stands up the exact
;; app-db the sidebar reads.
(rf/reg-event-db :test/set-picture
  (fn [db [_ picture]] (assoc db :aircraft/picture picture)))

(defn- by-icao
  "A picture map keyed by each aircraft's icao — the shape :aircraft/picture
  holds."
  [aircraft]
  (into {} (map (juxt :aircraft/icao identity)) aircraft))

(defn- render-sidebar! []
  (rtl/cleanup)
  (rtl/render (r/as-element [sidebar/sidebar])))

(defn- row-icaos
  "The data-icao of every rendered row, in DOM order."
  []
  (->> (.getAllByRole rtl/screen "option")
       (map #(.getAttribute % "data-icao"))
       vec))

(def ^:private ups fixtures/ups-2717)
(def ^:private ups-icao (:aircraft/icao fixtures/ups-2717))

;; ---------------------------------------------------------------------

(deftest rows-render-from-the-picture
  (testing "each aircraft in the picture gets a row"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture
                    (by-icao [fixtures/ups-2717
                              fixtures/on-the-ground
                              fixtures/squawking-7700])])
      (render-sidebar!)
      (is (some? (.getByText rtl/screen "UPS2717")) "the cruising 747")
      (is (some? (.getByText rtl/screen "N2173A"))  "the one on the ground")
      (is (some? (.getByText rtl/screen "DAL1275")) "the one squawking 7700")
      (is (= 3 (count (.getAllByRole rtl/screen "option"))) "three rows"))))

(deftest a-row-carries-its-facts
  (testing "altitude is three-state and absent is a dash, never zero"
    (rf-test/run-test-sync
      ;; ups cruising (34775), on-the-ground (ground), and a no-altitude copy.
      (let [no-alt (-> ups
                       (dissoc :aircraft/altitude-ft :aircraft/on-ground?)
                       (assoc :aircraft/icao "aaaaaa" :aircraft/callsign "NOALT"))]
        (rf/dispatch [:test/set-picture
                      (by-icao [ups fixtures/on-the-ground no-alt])])
        (render-sidebar!)
        (is (= "34775" (.-textContent (.getByTestId rtl/screen (str "row-alt:" ups-icao))))
            "a reported altitude shows the number")
        (is (= "ground" (.-textContent (.getByTestId rtl/screen "row-alt:a1d645")))
            "on the tarmac shows ground")
        (is (= sidebar/em-dash (.-textContent (.getByTestId rtl/screen "row-alt:aaaaaa")))
            "a never-reported altitude dashes — never 0")))))

(deftest clicking-a-row-selects-that-aircraft
  (testing "a row click fires the map's [:aircraft/select icao] contract"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [ups fixtures/squawking-7700])])
      (render-sidebar!)
      (is (nil? @(rf/subscribe [:aircraft/selected-icao])) "nothing selected yet")
      ;; Click a child span; the delegated handler walks up to the row.
      (.click rtl/fireEvent (.getByText rtl/screen "UPS2717"))
      (is (= ups-icao @(rf/subscribe [:aircraft/selected-icao]))
          "the roster selects the same identity the map would"))))

(deftest the-selected-row-is-marked
  (testing "the row for the selected icao carries aria-selected"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture (by-icao [ups fixtures/squawking-7700])])
      (rf/dispatch [:aircraft/select ups-icao])
      (render-sidebar!)
      (let [selected-row (.getByTestId rtl/screen (str "row:" ups-icao))
            other-row    (.getByTestId rtl/screen "row:a35a92")]
        (is (= "true" (.getAttribute selected-row "aria-selected"))
            "the selected aircraft's row is marked")
        (is (= "false" (.getAttribute other-row "aria-selected"))
            "and no other row is")))))

(deftest positioned-only-filter-hides-the-never-positioned
  (testing "the heard-but-never-located aircraft vanishes under positioned-only"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture
                    (by-icao [ups fixtures/never-positioned])])
      (render-sidebar!)
      (is (some? (.queryByText rtl/screen "a10202"))
          "the never-positioned target is listed by default (icao, no callsign)")
      (rf/dispatch [:ui/sidebar-toggle-positioned])
      (render-sidebar!)
      (is (some? (.queryByText rtl/screen "UPS2717"))
          "the positioned aircraft stays")
      (is (nil? (.queryByText rtl/screen "a10202"))
          "the never-positioned one is gone"))))

(deftest airborne-only-filter-hides-the-grounded
  (testing "the aircraft on the tarmac vanishes under airborne-only"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture
                    (by-icao [ups fixtures/on-the-ground])])
      (render-sidebar!)
      (is (some? (.queryByText rtl/screen "N2173A")) "grounded is listed by default")
      (rf/dispatch [:ui/sidebar-toggle-airborne])
      (render-sidebar!)
      (is (some? (.queryByText rtl/screen "UPS2717")) "the airborne aircraft stays")
      (is (nil? (.queryByText rtl/screen "N2173A")) "the grounded one is gone"))))

(deftest altitude-sort-is-high-to-low-with-absent-last
  (testing "airborne descends by altitude, ground sits below, absent falls last"
    (rf-test/run-test-sync
      (let [no-alt (-> ups
                       (dissoc :aircraft/altitude-ft :aircraft/on-ground?)
                       (assoc :aircraft/icao "aaaaaa" :aircraft/callsign "NOALT"))]
        (rf/dispatch [:test/set-picture
                      (by-icao [fixtures/squawking-7700    ; 10025, airborne
                                ups                         ; 34775, airborne
                                fixtures/on-the-ground      ; "ground"
                                no-alt])])                  ; absent
        (rf/dispatch [:ui/sidebar-sort :altitude])
        (render-sidebar!)
        (is (= ["abc0e4"      ; ups, 34775 — highest
                "a35a92"      ; squawking-7700, 10025
                "a1d645"      ; on-the-ground — below all airborne
                "aaaaaa"]     ; absent altitude — last
               (row-icaos))
            "highest first, ground beneath the airborne, never-reported last")))))

(deftest callsign-sort-is-alphabetical-with-icao-fallback
  (testing "callsign A→Z, and the callsign-less sort by their icao"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture
                    (by-icao [fixtures/ups-2717        ; UPS2717
                              fixtures/squawking-7700  ; DAL1275
                              fixtures/never-positioned])]) ; no callsign → a10202
      (rf/dispatch [:ui/sidebar-sort :callsign])
      (render-sidebar!)
      ;; a10202 (icao fallback) < dal1275 < ups2717, case-folded.
      (is (= ["a10202" "a35a92" "abc0e4"] (row-icaos))
          "sorted by the display label, case-insensitively"))))
