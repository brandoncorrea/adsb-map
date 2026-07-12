(ns adsb.preview-test
  "The #/preview design-options page, in a real browser. Three proofs:
  the two-entry router is the pure table it claims to be (and #/ still
  mounts the app shell, whose map view owns the map); the page renders
  the REAL chrome specimens fed by the cast — the ribbon flying the
  7700, the index card naming the cruiser, the Stack ticking the sky;
  and every dimension's switch lands on the page root's data-attributes
  (the hooks preview.css swaps its custom-property sets off), with the
  edition additionally re-inking the theme-following chrome through
  adsb.map.theme and every switch mirrored into the URL hash."
  (:require
    ["@testing-library/react" :as rtl]
    [adsb.core :as core]
    [adsb.events]
    [adsb.map.style :as style]
    [adsb.map.theme :as theme]
    [adsb.preview :as preview]
    [adsb.stream]                                 ; registers :aircraft/picture
    [adsb.subs]
    [adsb.views :as views]
    [cljs.test :refer-macros [deftest testing is use-fixtures async]]
    [day8.re-frame.test :as rf-test]
    [re-frame.core :as rf]
    [reagent.core :as r]))

(use-fixtures :each
  {:before (fn []
             (rf/dispatch-sync [:app/initialize-db])
             (reset! preview/!mix preview/default-mix))
   :after  (fn []
             (rtl/cleanup)
             (theme/set-theme! :day))})

;; The panel dispatches [:enrich/ensure icao] on render, whose effect would
;; make a real js/fetch. Neutralize it, as the panel's own suite does.
(rf/reg-fx :enrich/fetch! (fn [_] nil))

(defn- render-page! []
  (rtl/cleanup)
  (rtl/render (r/as-element [preview/page])))

(defn- root-el []
  (.getByTestId rtl/screen "preview-root"))

;; ---------------------------------------------------------------------
;; The router — a pure table.

(deftest the-router-names-exactly-two-pages
  (testing "everything that is not #/preview is the app — the default
            page IS the chart, so no hash can ever hide it"
    (is (= :app (core/route-for nil)))
    (is (= :app (core/route-for "")))
    (is (= :app (core/route-for "#/")))
    (is (= :app (core/route-for "#/somewhere-else"))))
  (testing "#/preview and its query-carrying forms are the preview"
    (is (= :preview (core/route-for "#/preview")))
    (is (= :preview (core/route-for "#/preview?typography=annotation")))))

(deftest the-app-route-still-mounts-the-app-shell
  (testing "#/ routes to the same app-root it always did — the shell
            whose map view mounts the map — and #/preview to the page"
    (is (= [views/app-root] (core/page-for :app)))
    (is (= [preview/page] (core/page-for :preview)))))

;; ---------------------------------------------------------------------
;; The mix <-> hash codec — pure, so asserted as data.

(deftest the-mix-round-trips-through-the-hash
  (let [mix (assoc preview/default-mix
                   :typography "annotation"
                   :scale "major-13"
                   :edition "night")]
    (is (= mix (preview/hash->mix (preview/mix->hash mix)))
        "a written hash reads back as the same mix")))

(deftest junk-in-the-hash-degrades-to-the-defaults
  (testing "unknown values, empty values, and a bare #/preview all open
            the default mix — a stale shared link never breaks the page"
    (is (= preview/default-mix
           (preview/hash->mix "#/preview?typography=comic-sans&scale=")))
    (is (= preview/default-mix (preview/hash->mix "#/preview")))
    (is (= preview/default-mix (preview/hash->mix nil)))))

;; ---------------------------------------------------------------------
;; The specimens — real chrome, fed by the cast.

(deftest the-page-renders-every-specimen-section
  (rf-test/run-test-sync
    (rf/dispatch [:preview/seed])
    (render-page!)
    (doseq [id ["header" "alert" "panel" "legend" "stack"
                "type-scale" "spacing" "palette"]]
      (is (some? (.getByTestId rtl/screen (str "specimen:" id)))
          (str "the " id " specimen section renders")))))

(deftest the-specimens-are-the-real-chrome-fed-by-the-cast
  (rf-test/run-test-sync
    (rf/dispatch [:preview/seed])
    (render-page!)
    (testing "the ribbon flies the squawking-7700 cast member"
      (is (some? (.getByTestId rtl/screen "alert:a35a92"))))
    (testing "the index card opens on the seeded selection"
      (is (= "UPS2717"
             (.-textContent (.getByTestId rtl/screen "panel-title")))))
    (testing "the Stack ticks the specimen sky"
      (is (some? (.getByTestId rtl/screen "tick:abc0e4")))
      (is (some? (.getByTestId rtl/screen "tick:a35a92"))))
    (testing "the legend prints its palette-derived swatches"
      (is (some? (.getByTestId rtl/screen "legend-emergency"))))
    (testing "the header reads the seeded stream health"
      (is (= "live" (.getAttribute
                      (.getByTestId rtl/screen "connection-indicator")
                      "data-state"))))))

;; ---------------------------------------------------------------------
;; The dimensions — every switch reaches the root's data-attributes,
;; the readout, and the URL hash.

(deftest every-option-of-every-dimension-lands-on-the-root
  (rf-test/run-test-sync
    (rf/dispatch [:preview/seed])
    (doseq [{:keys [key options]} preview/dimensions
            [value _] options]
      (preview/set-dimension! key value)
      (render-page!)
      (is (= value (.getAttribute (root-el) (str "data-" (name key))))
          (str (name key) "=" value " stamps the page root"))
      (is (.includes (.-textContent (.getByTestId rtl/screen "mix-readout"))
                     (str (name key) "=" value))
          (str (name key) "=" value " reads out in the copyable mix"))
      (is (.includes (.-hash js/location) (str (name key) "=" value))
          (str (name key) "=" value " is recorded in the URL hash")))))

(deftest a-pasted-mix-link-is-adopted-live
  (testing "adopt-hash! re-reads the location hash into the live mix —
            what the router calls for a hash change that stays on the
            preview route (a pasted link never reloads the page)"
    (.replaceState js/history nil ""
                   "#/preview?typography=drafting-room&edition=night")
    (preview/adopt-hash!)
    (is (= "drafting-room" (:typography @preview/!mix)))
    (is (= "night" (:edition @preview/!mix)))
    (is (= :night @theme/!theme)
        "the pasted edition re-inks the theme-following chrome too")
    (.replaceState js/history nil "" "#/")))

(deftest the-edition-override-reinks-the-theme-following-chrome
  (rf-test/run-test-sync
    (rf/dispatch [:preview/seed])
    (preview/set-dimension! :edition "night")
    (render-page!)
    (is (= :night @theme/!theme)
        "the manual override drives the same seam the system query does")
    (is (= (:ground-color (style/palette :night))
           (.getAttribute (.getByTestId rtl/screen "legend-ground")
                          "data-color"))
        "so the legend prints night ink without any OS involvement")
    (preview/set-dimension! :edition "day")
    (is (= :day @theme/!theme) "and day flips it straight back")))

(deftest the-page-opens-on-the-crowned-mix
  (testing "each dimension's default is the §5 winner — the fitting room
            opens on what actually ships (adsb-dgb.5)"
    (is (= {:typography "annotation"
            :labels     "printed-grotesk" ; §5 caption voice (adsb-fon)
            :scale      "major-13"
            :spacing    "compact-4"
            :palette    "wine-pen"
            :edition    "day"}
           preview/default-mix))))

(deftest clicking-an-option-switches-the-live-page
  (rf/dispatch-sync [:preview/seed])
  (render-page!)
  (is (= "annotation" (.getAttribute (root-el) "data-typography"))
      "the page opens on the default — the crowned mix")
  (.click rtl/fireEvent (.getByTestId rtl/screen "opt:typography:marginalia"))
  (async done
    (-> (rtl/waitFor
          (fn []
            (when-not (= "marginalia"
                         (.getAttribute (root-el) "data-typography"))
              (throw (js/Error. "root not re-stamped yet")))))
        (.then (fn [_]
                 (is (= "marginalia"
                        (.getAttribute (root-el) "data-typography"))
                     "a click re-stamps the live page root")
                 (is (= "true" (.getAttribute
                                 (.getByTestId
                                   rtl/screen "opt:typography:marginalia")
                                 "aria-pressed"))
                     "and the pressed state follows the pick")))
        (.catch (fn [e] (is false (str "the click never landed: " e))))
        (.finally done))))
