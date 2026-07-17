(ns adsb.palette
  "The single source of the theme palette — every theme colour, named once,
   in its day (Moss) and night editions.

   Three media read this one table:
     - adsb.css.tokens   the CSS custom properties (Garden build)
     - adsb.map.basemap  the map plate
     - adsb.map.style    the aircraft layer paint + altitude ramp

   Before this namespace those hexes were triplicated, so a re-ink — see the
   Moss commit d47fc3e — meant three synchronised edits. Now it is one edit
   here.

   Pure .cljc: string arithmetic only, no I/O, no deps. That purity is what
   lets the :css alias load it with only garden + ordered on the classpath.")

(defn- byte16 [s]
  #?(:clj  (Integer/parseInt s 16)
     :cljs (js/parseInt s 16)))

(defn hex->rgb
  "\"#1B2A1D\" -> \"27, 42, 29\", the body of a CSS rgb()/rgba()."
  [hex]
  (let [h (subs hex 1)]
    (str (byte16 (subs h 0 2)) ", "
         (byte16 (subs h 2 4)) ", "
         (byte16 (subs h 4 6)))))

(def swatches
  "role -> {:day hex :night hex}. Every colour is re-reasoned for its paper —
   no hex survives the edition switch unchanged."
  {:paper        {:day "#E2E8DE" :night "#151B26"}
   :paper-chrome {:day "#ECF1E8" :night "#1B2330"}
   :ink          {:day "#1B2A1D" :night "#E9E2CE"}
   :faded-ink    {:day "#506049" :night "#8D96A8"}
   :contour      {:day "#A6BF9E" :night "#2E3A49"}
   :terrain-1    {:day "#C2D7B4" :night "#1D2634"}
   :terrain-2    {:day "#A2C193" :night "#232E40"}
   :water-fill   {:day "#A6C7BE" :night "#101823"}
   :water-line   {:day "#2A6358" :night "#7FA3D4"}
   :road         {:day "#5C6E56" :night "#6B5540"}
   :aeroway      {:day "#CFDFC4" :night "#1D2634"}
   :magenta      {:day "#A5385C" :night "#E77E9B"}
   :aero         {:day "#2A6358" :night "#8BA9D6"}
   :emergency    {:day "#CE2029" :night "#FF5A4D"}
   :on-emergency {:day "#FBF3E4" :night "#1C1210"}
   :ok           {:day "#55722F" :night "#8FBF6F"}
   :warn         {:day "#8F6318" :night "#D9A648"}
   :alt-ground   {:day "#8A8374" :night "#6E7686"}
   :alt-unknown  {:day "#9A937F" :night "#7C8494"}})

(def altitude-ramp
  "The altitude colour ramp, feet -> hex, per edition. The feet (SEMANTICS)
   are shared; the inks are re-reasoned per paper."
  {:day   [[0 "#A0622D"] [10000 "#C2447C"] [20000 "#7A4F86"] [30000 "#3D5E8C"] [40000 "#2A3F66"]]
   :night [[0 "#C98A54"] [10000 "#E06A9F"] [20000 "#A98BC4"] [30000 "#7FA3D4"] [40000 "#5F7FB8"]]})

(defn swatch
  "The hex for a role in an edition, e.g. (swatch :day :paper) -> \"#E2E8DE\"."
  [theme role]
  (get-in swatches [role theme]))

(defn rgb
  "The rgb() body of a role's hex, e.g. (rgb :day :ink) -> \"27, 42, 29\"."
  [theme role]
  (hex->rgb (swatch theme role)))

(defn rgba
  "A role's hex as an rgba() string at the given alpha,
   e.g. (rgba :day :ink 0.3) -> \"rgba(27, 42, 29, 0.3)\"."
  [theme role alpha]
  (str "rgba(" (rgb theme role) ", " alpha ")"))
