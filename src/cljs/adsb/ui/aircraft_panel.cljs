(ns adsb.ui.aircraft-panel
  "The selected-aircraft detail panel — Reagent chrome, and unlike the map
  layer this genuinely IS React territory: it is low-churn (it changes when
  the user clicks something, not sixty times a second), so a component tree
  is exactly right here.

  BOUNDARY 4 (docs/validation-boundaries.md). Every string this panel shows —
  callsign, icao, squawk — arrived off an unauthenticated radio. It passed
  the ingest schema, so it is well-TYPED; it did not become trust-WORTHY on
  the way. We render it as plain hiccup text and let Reagent escape it. No
  `:dangerouslySetInnerHTML`, no href built from feeder data, no escape hatch
  of any kind. A hostile callsign is a string on the screen, never markup.

  ABSENT IS NOT ZERO. A fact the sky never reported renders as an em-dash —
  never a fabricated 0, which would draw a 747 parked at sea level. A genuine
  reported 0 is shown; only true absence dashes.

  Styling is a NEUTRAL PLACEHOLDER: class names are the re-skin hooks and the
  visual pass is bead adsb-dgb.5. This namespace commits to structure, not a
  look."
  (:require
    [re-frame.core :as rf]))

;; The em-dash stands in for every fact the sky never reported.
(def ^:const em-dash "—")
;; The close glyph — a multiplication sign, distinct from the em-dash.
(def ^:const close-glyph "×")
(def ^:const clock-interval-ms 1000)

;; ---------------------------------------------------------------------
;; The coarse UI clock. Time is not allowed into the pure domain, but the
;; UI edge is exactly where a wall clock belongs: it drives seen-age and,
;; on each tick, prunes a selection whose aircraft has left the sky (see
;; :ui/tick in adsb.events). One interval for the app's lifetime.

(defonce ^:private !clock (atom nil))

(defn start-clock!
  "Start the 1 Hz UI clock. Idempotent — a second call is a no-op, so hot
  reload never stacks intervals. Called once from adsb.core/init!; tests
  never call it and drive :ui/tick explicitly instead."
  []
  (when (nil? @!clock)
    (rf/dispatch [:ui/tick (js/Date.now)])
    (reset! !clock
            (js/setInterval #(rf/dispatch [:ui/tick (js/Date.now)])
                            clock-interval-ms))))

;; ---------------------------------------------------------------------
;; Presentation — pure. Absent (nil) becomes the em-dash; everything else
;; is stringified and handed to hiccup, which escapes it.

(defn- altitude-display
  "Altitude for the panel: \"ground\" on the tarmac, the number when the
  sky reported one, nil (→ dash) when it never did. A missing altitude is
  never coerced to 0."
  [{:aircraft/keys [on-ground? altitude-ft]}]
  (cond
    on-ground?         "ground"
    (some? altitude-ft) altitude-ft
    :else              nil))

(defn- seen-age-s
  "Whole seconds since the aircraft was last heard, from its durable
  :aircraft/seen-at-ms against the coarse UI `now-ms`. nil (→ dash) when
  either is absent — we do not invent a freshness we cannot measure.
  Clamped at 0 so clock skew never shows a negative age."
  [{:aircraft/keys [seen-at-ms]} now-ms]
  (when (and seen-at-ms now-ms)
    (max 0 (quot (- now-ms seen-at-ms) 1000))))

(defn- close!
  "Dismiss the panel by clearing the selection."
  [_event]
  (rf/dispatch [:aircraft/clear-selection]))

(defn- fact
  "One labelled fact row. A nil value renders as the em-dash; any other
  value is stringified and escaped as hiccup text. The value carries a
  data-testid so a test can pin one specific field's rendering — the only
  clean way to target a single value inside the list."
  [label value]
  [:div.adsb-fact
   [:span.adsb-fact-label label]
   [:span.adsb-fact-value {:data-testid (str "fact:" label)}
    (if (nil? value) em-dash (str value))]])

(defn- badge
  "A small status flag, shown only when its condition is true. `kind` is a
  class-name hook for the visual pass; `label` is escaped text."
  [kind label]
  [:span.adsb-badge {:class (str "adsb-badge-" kind) :role "status"} label])

(defn- panel-body
  "The panel for one selected aircraft. Every string here is feeder-origin
  and rendered as escaped text (Boundary 4)."
  [aircraft now-ms]
  (let [{:aircraft/keys [icao callsign ground-speed-kt track-deg squawk
                         baro-rate-fpm position-suspect? mlat?]} aircraft
        seen (seen-age-s aircraft now-ms)]
    [:aside.adsb-panel {:role "complementary" :aria-label "Aircraft detail"}
     [:div.adsb-panel-header
      ;; Callsign when the sky gave one, otherwise the bare icao — never blank.
      [:span.adsb-panel-title {:data-testid "panel-title"}
       (or callsign icao)]
      [:button.adsb-panel-close
       {:type "button" :aria-label "Close" :on-click close!}
       close-glyph]]
     (when (or position-suspect? mlat?)
       [:div.adsb-panel-badges
        (when position-suspect? [badge "suspect" "position suspect"])
        (when mlat? [badge "mlat" "MLAT"])])
     [:div.adsb-panel-facts
      [fact "ICAO" icao]
      [fact "Altitude" (altitude-display aircraft)]
      [fact "Ground speed" ground-speed-kt]
      [fact "Track" track-deg]
      [fact "Squawk" squawk]
      [fact "Vertical rate" baro-rate-fpm]
      [fact "Seen" (when seen (str seen "s ago"))]]]))

(defn aircraft-panel
  "The detail panel, mounted permanently in the app root. Renders nothing
  when no aircraft is selected OR the selected aircraft has left the
  picture — the derived :aircraft/selected sub is the single gate, so the
  panel closes the instant its subject ages out of the sky. A form-2
  component: subscribe once, deref per render."
  []
  (let [selected (rf/subscribe [:aircraft/selected])
        now      (rf/subscribe [:ui/now-ms])]
    (fn []
      (when-let [aircraft @selected]
        (panel-body aircraft @now)))))
