(ns adsb.ui.legend
  "The map key — a corner overlay that explains what a plane's colour and
  fade MEAN. Reagent chrome, and static: it derefs no subscription and never
  re-renders on traffic.

  ## Why it reads the style namespace directly

  A legend that hard-codes its own swatch colours is a legend that lies the
  moment someone re-skins the ramp (adsb-dgb.5 does exactly that by editing
  adsb.map.style). So this namespace imports adsb.map.style and builds every
  swatch straight from the SAME constants the MapLibre paint expressions are
  built from — `altitude-stops`, `ground-color`, `unknown-color`,
  `emergency-color`, `stale-opacity`. There is one source of style truth and
  this reads it. The test asserts each rendered swatch equals the imported
  constant, so the two literally cannot diverge: change the constant and both
  the map and the legend move together.

  MLAT is intentionally absent: the map layer does not style MLAT distinctly
  today (adsb.map.style has no MLAT knob), so the legend does not claim it
  does. It explains the ramp that actually exists, nothing more.

  Styling is a NEUTRAL PLACEHOLDER (class-name hooks only); the visual pass is
  bead adsb-dgb.5."
  (:require
    [adsb.map.style :as style]))

(defn- swatch
  "One legend row: a colour chip and its label. The chip's colour is CARRIED
  verbatim in `data-color` and painted from that same value, so a test can
  read it back un-normalised and assert it equals the map.style constant.
  `opacity`, when given, both fades the chip and is exposed on `data-opacity`
  — that is how the stale row proves it renders the real `stale-opacity`."
  [{:keys [color opacity label testid]}]
  [:li.adsb-legend-row
   [:span.adsb-legend-swatch
    (cond-> {:data-testid testid
             :data-color  color
             :style       (cond-> {:background-color color}
                            opacity (assoc :opacity opacity))}
      opacity (assoc :data-opacity opacity))]
   [:span.adsb-legend-label label]])

(defn legend
  "The map key: the airborne altitude ramp, then the special states a plane
  can be in. Every swatch is built from an adsb.map.style constant, never a
  duplicated literal."
  []
  [:section.adsb-legend {:aria-label "Map legend"}
   [:div.adsb-legend-group
    [:h2.adsb-legend-heading "Altitude"]
    [:ul.adsb-legend-list
     (for [[feet color] style/altitude-stops]
       ^{:key feet}
       [swatch {:color color
                :label (str feet " ft")
                :testid (str "legend-alt:" feet)}])]]
   [:div.adsb-legend-group
    [:h2.adsb-legend-heading "Status"]
    [:ul.adsb-legend-list
     [swatch {:color style/ground-color
              :label "On ground" :testid "legend-ground"}]
     [swatch {:color style/unknown-color
              :label "No altitude" :testid "legend-unknown"}]
     [swatch {:color style/emergency-color
              :label "Emergency" :testid "legend-emergency"}]
     [swatch {:color style/unknown-color :opacity style/stale-opacity
              :label "Stale (fading)" :testid "legend-stale"}]]]])
