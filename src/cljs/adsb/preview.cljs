(ns adsb.preview
  "The design-options preview page — #/preview (bead adsb-dgb.11).

  The typography/scale/spacing/palette pick (adsb-dgb.10) is made by
  BROWSING AND MIXING, not by comparing static plates: this page renders
  the app's REAL chrome — the header, the emergency ribbon, the detail
  panel, the legend, a Stack excerpt — as specimens under five
  independently switchable dimensions:

    :typography   the three bake-off systems (Marginalia / Drafting
                  Room / The Annotation)
    :scale        modular type scales — the candidates' natives plus
                  their neighbors
    :spacing      spacing scales — 4px airy / 8px strict / compact
    :palette      the settled Sectional accents plus two restrained
                  pen variations
    :edition      a MANUAL day/night override (the app itself follows
                  prefers-color-scheme; the preview must not make the
                  Overseer toggle his OS to compare prints)

  MECHANISM. Each dimension is a data-attribute on the page root;
  preview.css swaps custom-property SETS off those attributes — exactly
  the mechanism the winning mix will ship through (app.css custom
  properties). The one exception is the edition, whose cljs-side
  surfaces (legend swatches, the Stack's gradient fill) follow
  adsb.map.theme — so the edition control also sets that ratom, the
  same seam the theme tests drive.

  THE MIX IS THE URL. Every switch is written into the location hash
  (#/preview?typography=…&scale=…), so a mix can be shared, recorded,
  and reported verbatim; the readout under the controls shows the same
  five picks as one copyable line. Unknown or junk hash values degrade
  to the defaults — a stale link never breaks the page.

  THE SPECIMENS ARE FED BY THE CAST (adsb.fixtures — on the classpath
  via the :cljs alias): schema-derived through the real ingest boundary,
  so the specimen sky cannot drift into fiction. The seed includes the
  squawking-7700 member precisely so the ribbon, the red badge, and the
  hatched Stack tick all show. NO STREAM and NO MAP mount here —
  adsb.core boots this page instead of the shell, never alongside it."
  (:require
    [adsb.fixtures :as fixtures]
    [adsb.map.style :as style]
    [adsb.map.theme :as theme]
    [adsb.ui.aircraft-panel :as aircraft-panel]
    [adsb.ui.alert :as alert]
    [adsb.ui.header :as header]
    [adsb.ui.legend :as legend]
    [adsb.ui.stack :as stack]
    [clojure.string :as str]
    [re-frame.core :as rf]
    [reagent.core :as r]))

;; ---------------------------------------------------------------------
;; The five dimensions. Vector order is control order; each dimension's
;; FIRST option is its default — together they spell the direction doc's
;; own §5 hypothesis, so the page opens on the status quo ante.

(def dimensions
  [{:key     :typography
    :label   "Typography"
    :options [["marginalia" "Marginalia"]
              ["drafting-room" "Drafting Room"]
              ["annotation" "The Annotation"]]}
   {:key     :scale
    :label   "Scale"
    :options [["fourth-13" "1.333 @ 13px"]
              ["third-12" "1.2 @ 12px"]
              ["major-12" "1.25 @ 12px"]
              ["major-13" "1.25 @ 13px"]]}
   {:key     :spacing
    :label   "Spacing"
    :options [["airy-4" "4px airy"]
              ["strict-8" "8px strict"]
              ["compact-4" "compact"]]}
   {:key     :palette
    :label   "Palette"
    :options [["settled" "Sectional (settled)"]
              ["wine-pen" "Wine pen"]
              ["plum-pen" "Plum pen"]]}
   {:key     :edition
    :label   "Edition"
    :options [["day" "Day"]
              ["night" "Night"]]}])

(def default-mix
  (into {} (map (juxt :key (comp ffirst :options))) dimensions))

;; ---------------------------------------------------------------------
;; The mix <-> hash codec. Pure, so the round-trip is a plain assertion.

(def ^:const hash-prefix "#/preview")

(defn mix->hash
  "The location hash naming `mix`, e.g.
  #/preview?typography=marginalia&scale=fourth-13&…"
  [mix]
  (str hash-prefix "?"
       (str/join "&" (for [{:keys [key]} dimensions]
                       (str (name key) "=" (get mix key))))))

(defn- hash-pairs
  "The k=v query pairs of a location `hash`, as a string map."
  [hash]
  (when-let [query (second (str/split (or hash "") #"\?" 2))]
    (into {} (keep (fn [pair]
                     (let [[k v] (str/split pair #"=" 2)]
                       (when (seq v) [k v]))))
          (str/split query #"&"))))

(defn hash->mix
  "The mix a location `hash` names. Every dimension falls back to its
  default when the hash omits it or names a value that does not exist —
  a shared link from a dead option set still opens the page."
  [hash]
  (let [pairs (hash-pairs hash)]
    (into {} (for [{:keys [key options]} dimensions
                   :let [valid? (into #{} (map first) options)]]
               [key (or (valid? (get pairs (name key)))
                        (default-mix key))]))))

;; ---------------------------------------------------------------------
;; State. The mix lives in one ratom (the page root re-stamps its
;; data-attributes from it) and is mirrored into the URL hash on every
;; switch. Public so the browser tests can pin it to a known state.

(defonce !mix (r/atom default-mix))

(defn- write-hash!
  "Mirror `mix` into the location hash without adding a history entry —
  replaceState never fires hashchange, so the router stays quiet."
  [mix]
  (.replaceState js/history nil "" (mix->hash mix)))

(defn- apply-edition!
  "Print the cljs-side surfaces (legend swatches, Stack fill) in the
  mix's edition via the same adsb.map.theme seam the theme tests drive."
  [mix]
  (theme/set-theme! (keyword (:edition mix))))

(defn set-dimension!
  "Switch one `dimension` of the mix to `value`: the root re-stamps its
  data-attribute, the hash records the mix, and an edition switch
  re-inks the theme-following chrome."
  [dimension value]
  (let [mix (swap! !mix assoc dimension value)]
    (write-hash! mix)
    (when (= :edition dimension)
      (apply-edition! mix))))

;; ---------------------------------------------------------------------
;; The specimen sky — the cast, keyed like :aircraft/picture, with the
;; cruiser selected so the index card opens and the 7700 aboard so the
;; ribbon flies. Stats are literals in the wire's shape: the readout
;; needs plausible numbers, not a live antenna.

(def specimen-picture
  (into {} (map (juxt :aircraft/icao identity))
        [fixtures/ups-2717 fixtures/on-the-ground fixtures/never-positioned
         fixtures/squawking-7700 fixtures/mlat-derived]))

(rf/reg-event-db
  :preview/seed
  (fn [db _]
    (assoc db
           :aircraft/picture specimen-picture
           :aircraft/selected-icao (:aircraft/icao fixtures/ups-2717)
           :stream/connection :live
           :feeder/status :ok
           :stats/session #:stats{:max-range-km 287.4 :message-rate 642})))

(defn adopt-hash!
  "Re-read the location hash into the live mix — the page's answer to a
  PASTED mix link while the preview is already open, since a hash-only
  navigation never reloads the page (adsb.core routes it here). The
  page's own switches never loop through this: they write the hash via
  replaceState, which fires no hashchange at all."
  []
  (let [mix (hash->mix (.-hash js/location))]
    (reset! !mix mix)
    (apply-edition! mix)))

(defn start!
  "Boot the preview page's world from the location hash: adopt the
  shared mix, normalize the hash, print the requested edition, and seed
  the specimen sky. Called by adsb.core/init! on the #/preview route
  only — the app path never runs any of this."
  []
  (adopt-hash!)
  (write-hash! @!mix)
  (rf/dispatch-sync [:preview/seed]))

;; ---------------------------------------------------------------------
;; Controls. One delegated click handler for every option button — the
;; same pattern the ribbon and the Stack use, so no closure is rebuilt
;; per render.

(defn- on-controls-click!
  [event]
  (when-let [option (some-> (.-target event) (.closest "[data-dimension]"))]
    (set-dimension! (keyword (.getAttribute option "data-dimension"))
                    (.getAttribute option "data-value"))))

(defn- control-group
  "One dimension's row of option buttons; the current pick is pressed."
  [{:keys [key label options]} mix]
  [:fieldset.adsb-preview-control
   [:legend.adsb-preview-control-legend label]
   (for [[value option-label] options]
     ^{:key value}
     [:button.adsb-preview-option
      {:type           "button"
       :data-dimension (name key)
       :data-value     value
       :data-testid    (str "opt:" (name key) ":" value)
       :aria-pressed   (= value (get mix key))}
      option-label])])

(defn- controls [mix]
  [:div.adsb-preview-controls {:on-click on-controls-click!}
   (for [dimension dimensions]
     ^{:key (:key dimension)} [control-group dimension mix])])

(defn mix-text
  "The current mix as one copyable line — how a pick is reported."
  [mix]
  (str/join " " (for [{:keys [key]} dimensions]
                  (str (name key) "=" (get mix key)))))

(defn- mix-readout [mix]
  [:div.adsb-preview-mix
   [:span.adsb-preview-mix-label "Current mix"]
   [:code.adsb-preview-mix-value {:data-testid "mix-readout"}
    (mix-text mix)]
   [:span.adsb-preview-mix-hint
    "— the URL carries the same mix; copy either to report a pick"]])

;; ---------------------------------------------------------------------
;; Specimens.

(defn- specimen
  "One titled specimen section: a heading and a paper stage the sample
  sits on. `id` keys the testid and the stage's layout class."
  [id title & body]
  [:section.adsb-preview-specimen {:data-testid (str "specimen:" id)}
   [:h2.adsb-preview-specimen-title title]
   (into [:div.adsb-preview-stage
          {:class (str "adsb-preview-stage-" id)}]
         body)])

(def ^:private type-samples
  ;; Slot name, the specimen class preview.css styles per typography
  ;; system, and a sample line in that slot's own voice.
  [["display · --t2" "adsb-preview-type-display"
    "The Sectional, Day & Night"]
   ["heading · --t1" "adsb-preview-type-heading"
    "Altitude — flight levels"]
   ["body · --t0" "adsb-preview-type-body"
    "Every aircraft glides, and trails a fading ribbon of ink."]
   ["caption · --t-1" "adsb-preview-type-caption"
    "as annotated in the chart's margin"]
   ["data · --t-1" "adsb-preview-type-data"
    "FL347 · 450.5 kt · squawk 6040 · −960 fpm"]])

(defn- type-scale-specimen []
  [:div
   (for [[slot class sample] type-samples]
     ^{:key slot}
     [:div.adsb-preview-type-row
      [:span.adsb-preview-type-slot slot]
      [:span {:class class} sample]])])

(def ^:private space-tokens
  ["--s1" "--s2" "--s3" "--s4" "--s5" "--header-h"])

(defn- spacing-specimen []
  [:div
   (for [token space-tokens]
     ^{:key token}
     [:div.adsb-preview-space-row
      [:span.adsb-preview-space-token token]
      [:div.adsb-preview-space-bar
       {:style {:width (str "var(" token ")")}}]])])

(def ^:private chrome-swatches
  ;; Label + the app.css custom property the chip paints from, so the
  ;; row re-inks with the edition and palette dimensions for free.
  [["paper" "--paper"]
   ["chrome" "--paper-chrome"]
   ["ink" "--ink"]
   ["faded ink" "--faded-ink"]
   ["contour" "--contour"]
   ["magenta pen" "--magenta"]
   ["aero blue" "--aero"]
   ["emergency" "--emergency"]])

(defn- palette-specimen
  "Chrome accents from the live custom properties, and the aircraft
  altitude ramp from adsb.map.style — settled data the palette dimension
  deliberately does NOT vary, shown for context against the accents."
  []
  [:div
   [:div.adsb-preview-swatch-group
    [:div.adsb-preview-swatch-caption "chrome inks (follow the mix)"]
    [:div.adsb-preview-swatch-row
     (for [[label property] chrome-swatches]
       ^{:key property}
       [:span.adsb-preview-swatch
        [:span.adsb-preview-swatch-chip
         {:style {:background (str "var(" property ")")}}]
        label])]]
   [:div.adsb-preview-swatch-group
    [:div.adsb-preview-swatch-caption
     "aircraft altitude ramp (settled — follows the edition only)"]
    [:div.adsb-preview-swatch-row
     (for [[feet color] (:altitude-stops (style/palette @theme/!theme))]
       ^{:key feet}
       [:span.adsb-preview-swatch
        [:span.adsb-preview-swatch-chip {:style {:background color}}]
        (str feet " ft")])]]])

;; ---------------------------------------------------------------------
;; The page.

(defn- root-attributes [mix]
  (into {:data-testid "preview-root"}
        (map (fn [{:keys [key]}]
               [(keyword (str "data-" (name key))) (get mix key)]))
        dimensions))

(defn page
  "The preview page root. Its data-attributes ARE the mix; preview.css
  swaps every custom-property set off them."
  []
  (let [mix @!mix]
    [:div.adsb-preview (root-attributes mix)
     [:header.adsb-preview-masthead
      [:h1.adsb-preview-title "The Sectional — design options"]
      [:a.adsb-preview-back {:href "#/"} "← back to the chart"]]
     [controls mix]
     [mix-readout mix]
     [specimen "header" "Header — the chart title block"
      [header/header]]
     [specimen "alert" "Emergency ribbon — the NOTAM strip"
      [alert/alert-ribbon]]
     [specimen "panel" "Detail panel — the index card"
      [aircraft-panel/aircraft-panel]]
     [specimen "legend" "Legend — the map key"
      [legend/legend]]
     [specimen "stack" "The Stack — excerpt"
      [stack/stack]]
     [specimen "type-scale" "Type scale"
      [type-scale-specimen]]
     [specimen "spacing" "Spacing scale"
      [spacing-specimen]]
     [specimen "palette" "Palette"
      [palette-specimen]]]))
