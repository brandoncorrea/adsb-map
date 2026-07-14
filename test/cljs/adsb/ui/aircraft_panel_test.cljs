(ns adsb.ui.aircraft-panel-test
  "The selected-aircraft detail panel, rendered in a real browser under React
  Testing Library. Proves the selection lifecycle (select opens it, a picture
  that drops the aircraft closes it, clear-selection closes it), the
  absent-is-not-zero rule, and — the one that matters most — that a hostile
  callsign off the radio renders as inert TEXT, never markup (Boundary 4)."
  (:require
    ["@testing-library/react" :as rtl]
    [adsb.events]
    [adsb.fixtures :as fixtures]
    [adsb.stream]                                 ; registers the :aircraft/picture sub
    [adsb.subs]
    [adsb.ui.aircraft-panel :as panel]
    [cljs.test :refer-macros [deftest testing is use-fixtures async]]
    [clojure.string :as str]
    [day8.re-frame.test :as rf-test]
    [re-frame.core :as rf]
    [reagent.core :as r]))

;; Without cleanup a previous test's panel stays mounted and the queries below
;; find two matches — or the wrong one. Re-seed app-db each test so a prior
;; collapse (adsb-4ca) cannot leave :panel/expanded? false for the next one.
(use-fixtures :each
  {:before (fn [] (rf/dispatch-sync [:app/initialize-db]))
   :after  rtl/cleanup})

;; Seed the picture directly: its owning event lives in adsb.stream and speaks
;; wire JSON, which is noise for a panel test. A tiny local event lets us stand
;; up the exact app-db the panel reads.
(rf/reg-event-db :test/set-picture (fn [db [_ picture]] (assoc db :aircraft/picture picture)))

;; Seed the enrichment cache directly, bypassing the async fetch (adsb.enrich).
(rf/reg-event-db :test/set-shards (fn [db [_ shards]] (assoc db :enrich/shards shards)))

;; The panel dispatches [:enrich/ensure icao] on render, whose effect would
;; make a real js/fetch. Neutralize it: these tests seed the cache directly and
;; never want the network. (adsb.enrich's own suite drives the fetch seam.)
(rf/reg-fx :enrich/fetch! (fn [_] nil))

(def ^:private ups fixtures/ups-2717)
(def ^:private ups-icao (:aircraft/icao fixtures/ups-2717))

(defn- render-panel!
  "Mount a fresh panel. Unmounts any prior render first: rtl/cleanup runs
  only after each deftest, so a deftest with two renders would otherwise
  stack them and the queries would find both."
  []
  (rtl/cleanup)
  (rtl/render (r/as-element [panel/aircraft-panel])))

(defn- fresh-db!
  "A clean app-db without run-test-sync — used by the tests whose assertions
  outlive a synchronous block because they wait on React 18's async commit."
  []
  (rf/dispatch-sync [:app/initialize-db]))

;; ---------------------------------------------------------------------

(deftest escape-deselect-ignores-typing-fields
  (is (true? (panel/escape-deselect?
               #js {:key "Escape" :target #js {:tagName "DIV"}})))
  (is (false? (panel/escape-deselect?
                #js {:key "Escape" :target #js {:tagName "INPUT"}})))
  (is (false? (panel/escape-deselect?
                #js {:key "a" :target #js {:tagName "DIV"}}))))

(deftest escape-clears-the-selection
  (async done
    (fresh-db!)
    (rf/dispatch-sync [:test/set-picture {ups-icao ups}])
    (rf/dispatch-sync [:aircraft/select ups-icao])
    (render-panel!)
    (is (some? (.getByTestId rtl/screen "aircraft-panel")))
    ;; Drive the pure guard's event shape through the real event, so the
    ;; document listener path is what production uses after start-keyboard!.
    (panel/start-keyboard!)
    (.dispatchEvent js/document
                    (js/KeyboardEvent. "keydown" #js {:key "Escape"
                                                      :bubbles true}))
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
  ;; Minimize once; pick another aircraft; the chip stays a chip (adsb-4ca).
  (async done
    (fresh-db!)
    (let [dal fixtures/squawking-7700
          dal-icao (:aircraft/icao dal)]
      (rf/dispatch-sync [:test/set-picture {ups-icao ups dal-icao dal}])
      (rf/dispatch-sync [:aircraft/select ups-icao])
      (render-panel!)
      (is (= "true" (.getAttribute (.getByTestId rtl/screen "aircraft-panel")
                                   "data-expanded")))
      (rf/dispatch-sync [:panel/toggle-expanded])
      (r/flush)
      (-> (rtl/waitFor
            (fn []
              (assert (= "false"
                         (.getAttribute (.getByTestId rtl/screen "aircraft-panel")
                                        "data-expanded")))))
          (.then (fn [_]
                   (rf/dispatch-sync [:aircraft/select dal-icao])
                   (r/flush)
                   (rtl/waitFor
                     (fn []
                       (assert (= "false"
                                  (.getAttribute (.getByTestId rtl/screen "aircraft-panel")
                                                 "data-expanded")))
                       (assert (str/includes?
                                 (.-textContent (.getByTestId rtl/screen "panel-title"))
                                 (or (:aircraft/callsign dal) dal-icao)))))))
          (.then (fn [_]
                   (is (= "false"
                          (.getAttribute (.getByTestId rtl/screen "aircraft-panel")
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
      ;; State was set before the initial mount, which RTL commits
      ;; synchronously — so getBy is safe here, no async needed.
      (is (some? (.getByText rtl/screen "UPS2717")) "callsign as the title")
      (is (some? (.getByText rtl/screen "abc0e4"))   "icao")
      (is (some? (.getByText rtl/screen "34775"))    "altitude, a number")
      ;; The fixture carries the feeder's own precision — 450.5 kt, 97.14° —
      ;; and the panel prints aviation's: whole knots, three-digit bearing.
      (is (some? (.getByText rtl/screen "451"))      "ground speed, whole knots")
      (is (nil? (.queryByText rtl/screen "450.5"))   "never the raw fraction")
      (is (some? (.getByText rtl/screen "097°"))     "track, a three-digit bearing")
      (is (nil? (.queryByText rtl/screen "97.14"))   "never the raw fraction")
      (is (some? (.getByText rtl/screen "6040"))     "squawk")
      (is (some? (.getByText rtl/screen "-960"))     "vertical rate")))

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
      ;; Not on the ground, simply never reported an altitude. Assoc a copy;
      ;; the cast is immutable.
      (let [no-alt (dissoc ups :aircraft/altitude-ft :aircraft/on-ground?)]
        (rf/dispatch [:test/set-picture {ups-icao no-alt}])
        (rf/dispatch [:aircraft/select ups-icao])
        (render-panel!)
        (is (= panel/em-dash
               (.-textContent (.getByTestId rtl/screen "fact:Altitude")))
            "absent altitude is an em-dash")
        (is (nil? (.queryByText rtl/screen "0"))
            "and nowhere does the panel invent a zero")))))

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
      (is (= "Boeing 747-400"
             (.-textContent (.getByTestId rtl/screen "fact:Type")))
          "Type prefers the long description")
      (is (= "N570UP"
             (.-textContent (.getByTestId rtl/screen "fact:Registration"))))
      (is (= "United Parcel Service"
             (.-textContent (.getByTestId rtl/screen "fact:Operator"))))))

  (testing "with only a type code and no description, Type shows the code"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture {ups-icao ups}])
      (rf/dispatch [:test/set-shards {"abc" {"abc0e4" {"t" "B744"}}}])
      (rf/dispatch [:aircraft/select ups-icao])
      (render-panel!)
      (is (= "B744" (.-textContent (.getByTestId rtl/screen "fact:Type"))))
      (is (= panel/em-dash
             (.-textContent (.getByTestId rtl/screen "fact:Registration")))
          "an absent registration dashes")))

  (testing "a hex the database does not know dashes every enrichment row"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture {ups-icao ups}])
      ;; Shard resolved but this hex is absent from it — the missing-DB case
      ;; looks identical (no shard cached at all).
      (rf/dispatch [:test/set-shards {"abc" :absent}])
      (rf/dispatch [:aircraft/select ups-icao])
      (render-panel!)
      (is (= panel/em-dash (.-textContent (.getByTestId rtl/screen "fact:Type"))))
      (is (= panel/em-dash
             (.-textContent (.getByTestId rtl/screen "fact:Registration"))))
      (is (= panel/em-dash
             (.-textContent (.getByTestId rtl/screen "fact:Operator")))))))

(deftest suspect-and-mlat-badges-show-only-when-true
  (testing "a position-suspect, MLAT-derived aircraft flies both badges"
    (rf-test/run-test-sync
      (let [flagged (assoc fixtures/mlat-derived :aircraft/position-suspect? true)
            icao    (:aircraft/icao flagged)]
        (rf/dispatch [:test/set-picture {icao flagged}])
        (rf/dispatch [:aircraft/select icao])
        (render-panel!)
        (is (some? (.getByText rtl/screen "MLAT")))
        (is (some? (.getByText rtl/screen "position suspect"))))))

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
        (is (some? (.getByText rtl/screen "general emergency"))
            "the badge spells out what 7700 means")
        (is (= "7700" (.-textContent (.getByTestId rtl/screen "fact:Squawk")))
            "and the raw squawk is still there in the facts")))))

(deftest seen-age-derives-from-seen-at-and-the-ui-clock
  (testing "seen-age counts seconds since the aircraft was last heard"
    (rf-test/run-test-sync
      (let [heard (assoc ups :aircraft/seen-at-ms 120000)]
        (rf/dispatch [:test/set-picture {ups-icao heard}])
        (rf/dispatch [:aircraft/select ups-icao])
        (rf/dispatch [:ui/tick 123000])           ; 3s later
        (render-panel!)
        (is (= "3s ago" (.-textContent (.getByTestId rtl/screen "fact:Seen"))))))))

(deftest selection-lifecycle-at-the-sub-level
  (testing "selection is an icao that survives updates and dies with the aircraft"
    (rf-test/run-test-sync
      (rf/dispatch [:test/set-picture {ups-icao ups}])
      (rf/dispatch [:aircraft/select ups-icao])
      (is (some? @(rf/subscribe [:aircraft/selected])) "selected while present")

      ;; An update that still carries the aircraft: selection survives.
      (rf/dispatch [:test/set-picture {ups-icao (assoc ups :aircraft/altitude-ft 35000)}])
      (is (= 35000 (:aircraft/altitude-ft @(rf/subscribe [:aircraft/selected])))
          "selection follows the aircraft across a picture update")

      ;; A frame that drops it: the derived sub closes the panel immediately.
      (rf/dispatch [:test/set-picture {}])
      (is (nil? @(rf/subscribe [:aircraft/selected]))
          "the panel's gate goes nil the instant the aircraft leaves the sky")
      (is (= ups-icao @(rf/subscribe [:aircraft/selected-icao]))
          "the icao is still pinned until a clock tick prunes it")

      ;; A tick prunes the now-dangling selection from app-db.
      (rf/dispatch [:ui/tick 999999])
      (is (nil? @(rf/subscribe [:aircraft/selected-icao]))
          "the tick drops the orphaned selection"))))

(deftest picture-that-drops-the-selected-aircraft-closes-the-panel
  (testing "the DOM panel disappears when its subject ages out"
    (fresh-db!)
    (rf/dispatch-sync [:test/set-picture {ups-icao ups}])
    (rf/dispatch-sync [:aircraft/select ups-icao])
    (render-panel!)
    (is (some? (.getByText rtl/screen "UPS2717")) "open first")
    (rf/dispatch-sync [:test/set-picture {}])       ; aircraft gone from the sky
    (async done
      (-> (rtl/waitForElementToBeRemoved
            (fn [] (.queryByText rtl/screen "UPS2717")))
          (.then  (fn [_] (is (nil? (.queryByText rtl/screen "UPS2717"))
                              "the panel closed on its own")))
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
          (.then  (fn [_]
                    (is (nil? (.queryByText rtl/screen "UPS2717")) "panel gone")
                    (is (nil? @(rf/subscribe [:aircraft/selected-icao]))
                        "selection cleared in app-db")))
          (.catch (fn [e] (is false (str "close did not clear: " e))))
          (.finally done)))))

(deftest hostile-callsign-renders-as-text-not-markup
  (testing "a callsign carrying an XSS payload is inert escaped text (Boundary 4)"
    (rf-test/run-test-sync
      ;; The panel does not re-validate — it trusts the boundary and renders
      ;; whatever string it is handed. So we hand it the worst case: an <img>
      ;; onerror payload dressed up as a callsign.
      (let [hostile "<img src=x onerror=alert(1)>"
            evil    (assoc ups :aircraft/callsign hostile)]
        (rf/dispatch [:test/set-picture {ups-icao evil}])
        (rf/dispatch [:aircraft/select ups-icao])
        (render-panel!)
        (let [title (.getByTestId rtl/screen "panel-title")]
          (is (= hostile (.-textContent title))
              "the payload appears verbatim as the title's text")
          (is (nil? (.querySelector title "img"))
              "hiccup escaped it — no img element was parsed from the string"))
        (is (nil? (.querySelector (.-body js/document) "img"))
            "and no injected img exists anywhere in the document")))))
