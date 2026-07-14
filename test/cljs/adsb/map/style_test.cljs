(ns adsb.map.style-test
  "The aircraft styling is DATA, so the tests read it as data — no map,
  no DOM, no browser needed to prove the contract. We assert the SHAPE of
  the MapLibre expressions: the layer is a symbol layer rotated by track;
  the altitude colour peels its three states (number / \"ground\" /
  absent) apart correctly; emergency overrides colour and size; stale
  fades opacity. The palette is two printed editions (adsb-dgb.7), so the
  structural assertions run over BOTH — the wiring is edition-free, only
  the ink changes — and a dedicated test pins the two-edition discipline:
  same keys, same feet, different ink, never a shared leftover."
  (:require
    [adsb.aircraft :as aircraft]
    [adsb.map.style :as style]
    [adsb.schema :as schema]
    [cljs.test :refer-macros [deftest is testing]]))

(def ^:private themes [:day :night])

(def ^:private icon-ids
  "Every silhouette the style layer may name. adsb.map.aircraft-layer
  registers exactly these (its own test holds it to that), so an icon in
  this set is an icon that will actually be drawn."
  #{style/plane-icon-id style/heavy-icon-id style/light-icon-id
    style/rotorcraft-icon-id style/vehicle-icon-id style/dot-icon-id})

(deftest layer-is-a-symbol-rotated-by-track
  (doseq [theme themes]
    (let [spec (style/aircraft-layer-spec theme "aircraft" "aircraft")]
      (is (= "symbol" (:type spec)) "a symbol layer — a plane, not a circle")
      (testing "the icon rotates with the reported track, pinned to the ground"
        (is (= ["get" "track"] (get-in spec [:layout :icon-rotate])))
        (is (= "map" (get-in spec [:layout :icon-rotation-alignment]))))
      (testing "the icon is chosen per feature (the symbology — see
                icon-image-is-keyed-on-category below)"
        (is (= (style/icon-image-expression)
               (get-in spec [:layout :icon-image]))))
      (testing "no aircraft is dropped by label collision"
        (is (true? (get-in spec [:layout :icon-allow-overlap]))))
      (testing "the halo is the edition's own paper — ink survives a busy chart"
        (is (= (:halo-color (style/palette theme))
               (get-in spec [:paint :icon-halo-color])))))))

;; ---------------------------------------------------------------------
;; The symbology (adsb-rnp). Asserting the expression's literal shape
;; would pin the wrong thing — what matters is which silhouette a FEATURE
;; ends up with, so we evaluate the expression the way MapLibre would.

(defn- evaluate
  "The slice of the MapLibre expression language `icon-image-expression`
  uses — case / match / has / get / ! — over one feature's `props`.
  Small enough to be obviously right, which is the point: it lets the
  tests below assert the SYMBOLOGY (this feature draws that silhouette)
  rather than the vector we happened to build it from."
  [expr props]
  (if-not (vector? expr)
    expr
    (let [[op & args] expr]
      (case op
        "get"  (get props (first args))
        "has"  (contains? props (first args))
        "!"    (not (evaluate (first args) props))
        "case" (loop [args args]
                 (if (= 1 (count args))
                   (evaluate (first args) props)   ; the fallback
                   (let [[test result & more] args]
                     (if (evaluate test props)
                       (evaluate result props)
                       (recur more)))))
        "match" (let [input    (evaluate (first args) props)
                      fallback (last args)
                      pairs    (partition 2 (butlast (rest args)))]
                  (or (some (fn [[label output]]
                              (when (= label input) output))
                            pairs)
                      fallback))))))

(defn- icon-for
  "The silhouette MapLibre would draw for a feature with these properties.
  STRING keys, because that is what a GeoJSON feature's properties are by
  the time MapLibre evaluates `[\"has\" \"track\"]` against them — clj->js
  at the seam (adsb.map.maplibre) turns adsb.geo's keywords into exactly
  these names."
  [props]
  (evaluate (style/icon-image-expression) props))

(deftest icon-image-is-keyed-on-category
  (testing "the category chooses the silhouette — a helicopter is not
            drawn as a small airliner"
    (is (= style/rotorcraft-icon-id (icon-for {"track" 90 "category" "A7"})))
    (is (= style/heavy-icon-id      (icon-for {"track" 90 "category" "A5"})))
    (is (= style/heavy-icon-id      (icon-for {"track" 90 "category" "A4"})))
    (is (= style/light-icon-id      (icon-for {"track" 90 "category" "A1"})))
    (is (= style/vehicle-icon-id    (icon-for {"track" 90 "category" "C2"}))))

  (testing "NO HEADING BEATS EVERY CATEGORY. A rotorcraft we cannot point
            is a dot, not a rotorcraft: a symbol rotated to a heading we do
            not have is a lie, and it is the same lie whichever silhouette
            tells it."
    (is (= style/dot-icon-id (icon-for {"category" "A7"})))
    (is (= style/dot-icon-id (icon-for {"category" "C2"})))
    (is (= style/dot-icon-id (icon-for {}))))

  (testing "an absent category falls back to the generic plane — no
            aircraft goes undrawn for want of a classification"
    (is (= style/plane-icon-id (icon-for {"track" 90}))))

  (testing "a category the symbology does not distinguish is the generic
            plane too — A3 (large) and A2 (small) are what the plane
            already draws honestly"
    (is (= style/plane-icon-id (icon-for {"track" 90 "category" "A3"})))
    (is (= style/plane-icon-id (icon-for {"track" 90 "category" "A2"}))))

  (testing "EVERY category the domain can carry resolves to a silhouette —
            over the whole closed enum, so no member of it can ever leave a
            positioned aircraft unrendered"
    (doseq [category (rest schema/emitter-category)]
      (let [icon (icon-for {"track" 90 "category" category})]
        (is (contains? icon-ids icon)
            (str category " must draw something we registered"))))))

(deftest altitude-colour-handles-its-three-states
  (doseq [theme themes]
    (let [expr    (style/altitude-color-expression theme)
          palette (style/palette theme)]
      (is (= "case" (first expr)) "a case, because interpolate is numeric-only")
      (testing "absent altitude -> unknown treatment, guarded by `has` (missing, not zero)"
        (is (= ["!" ["has" "altitude"]] (nth expr 1)))
        (is (= (:unknown-color palette) (nth expr 2))))
      (testing "the string \"ground\" -> its own ground treatment"
        (is (= ["==" ["get" "altitude"] "ground"] (nth expr 3)))
        (is (= (:ground-color palette) (nth expr 4))))
      (testing "the numeric branch is a linear interpolate over the altitude number"
        (let [ramp (nth expr 5)]
          (is (= "interpolate" (first ramp)))
          (is (= ["linear"] (nth ramp 1)))
          (is (= ["get" "altitude"] (nth ramp 2)))
          (let [stops (drop 3 ramp)]
            (is (even? (count stops)) "stop/colour pairs")
            (is (= (map first (:altitude-stops palette))
                   (take-nth 2 stops))
                "the numeric stops come straight from the edition's ramp data")))))))

(deftest emergency-overrides-colour-and-size
  (testing "colour: emergency red beats the altitude ramp, in both editions"
    (doseq [theme themes]
      (let [expr (style/icon-color-expression theme)]
        (is (= "case" (first expr)))
        (is (= ["get" "emergency"] (nth expr 1)))
        (is (= (:emergency-color (style/palette theme)) (nth expr 2)))
        (is (= (style/altitude-color-expression theme) (nth expr 3))
            "otherwise the three-state altitude ramp"))))
  (testing "size: emergency wins first and absolutely — a distressed plane
            is never allowed to look far away"
    (let [expr (style/icon-size-expression)]
      (is (= "case" (first expr)))
      (is (= ["get" "emergency"] (nth expr 1)))
      (is (= style/emergency-icon-size (nth expr 2)))
      (is (> style/emergency-icon-size
             (apply max (map second style/perspective-size-stops)))
          "emergency out-draws even the largest perspective size"))))

;; ---------------------------------------------------------------------
;; Size is the instinct-altitude channel (adsb-dgb.12, Overseer pick):
;; perspective — low is near is LARGE, high is far is small. Colour
;; stays the precise channel; size carries the glance.

(deftest size-is-perspective-altitude
  (let [expr (style/perspective-size-expression)]
    (testing "the three altitude states peel apart exactly as colour's do"
      (is (= "case" (first expr)))
      (is (= ["!" ["has" "altitude"]] (nth expr 1)))
      (is (= style/base-icon-size (nth expr 2))
          "absent altitude takes the neutral base — no height claim")
      (is (= ["==" ["get" "altitude"] "ground"] (nth expr 3)))
      (is (= style/ground-icon-size (nth expr 4))))
    (testing "the numeric branch interpolates the perspective stops"
      (let [ramp (nth expr 5)]
        (is (= ["interpolate" ["linear"] ["get" "altitude"]] (take 3 ramp)))
        (is (= (mapcat identity style/perspective-size-stops)
               (drop 3 ramp))))))

  (testing "the ramp is perspective: strictly LARGER low than high, and
            front-loaded so the low sky keeps its resolution"
    (let [sizes (map second style/perspective-size-stops)]
      (is (apply > sizes) "size only ever falls as altitude climbs")))

  (testing "the ground size sits beneath the whole flying ramp — a parked
            plane defers to every airborne one"
    (is (< style/ground-icon-size
           (apply min (map second style/perspective-size-stops)))))

  (testing "mlat demotion MULTIPLIES the perspective size instead of
            replacing it — lower confidence reads a touch smaller at every
            altitude without stealing the altitude channel"
    (let [expr (style/icon-size-expression)
          body (nth expr 3)]
      (is (= ["*"
              ["case" ["get" "mlat"] style/mlat-size-factor 1.0]
              (style/perspective-size-expression)]
             body))
      (is (< 0 style/mlat-size-factor 1) "a demotion, never an erasure"))))

(deftest age-fades-opacity-continuously
  (testing "opacity interpolates over age-s, guarded for the absent case"
    (let [expr (style/icon-opacity-expression)]
      (is (= "case" (first expr)))
      (testing "an un-judged aircraft (no age-s) stays full opacity"
        (is (= ["has" "age-s"] (nth expr 1)))
        (is (= style/base-opacity (nth expr 3))))
      (testing "the fade is a linear interpolate from the stale line to the
                age-out line — not a single binary step"
        (let [ramp (nth expr 2)]
          (is (= "interpolate" (first ramp)))
          (is (= ["linear"] (nth ramp 1)))
          (is (= ["get" "age-s"] (nth ramp 2)))
          (is (= [style/stale-threshold-s   style/base-opacity
                  style/age-out-threshold-s style/aged-out-opacity]
                 (drop 3 ramp))
              "full at the stale line, nearly gone at the age-out line")))))

  (testing "the fade bounds are the domain thresholds, in seconds — the two
            sides can never disagree about where the fade begins or ends"
    (is (= (/ aircraft/stale-threshold-ms 1000) style/stale-threshold-s))
    (is (= (/ aircraft/age-out-threshold-ms 1000) style/age-out-threshold-s))
    (is (< style/aged-out-opacity style/base-opacity)
        "aged reads dimmer, not gone")))

;; ---------------------------------------------------------------------
;; The two printed editions (adsb-dgb.7 / design-direction §2): one plate,
;; two inks. Same roles, same feet, no colour carried over unexamined.

(deftest the-palette-is-two-editions-of-one-plate
  (let [day   (style/palette :day)
        night (style/palette :night)]
    (testing "same roles in both editions — a print cannot be missing an ink"
      (is (= (set (keys day)) (set (keys night)))))
    (testing "the altitude feet are identical — the SEMANTICS are shared"
      (is (= (map first (:altitude-stops day))
             (map first (:altitude-stops night)))))
    (testing "every ink was re-reasoned for its paper — no colour survives
              the edition switch unchanged (a shared hex would be the first
              symptom of an invert-and-forget)"
      (doseq [role [:ground-color :unknown-color :emergency-color
                    :halo-color :trail-rgb]]
        (is (not= (role day) (role night)) (str role)))
      (is (= [] (filter identity
                        (map (fn [[_ d] [_ n]] (when (= d n) d))
                             (:altitude-stops day) (:altitude-stops night))))
          "every ramp stop differs between editions"))
    (testing "each edition's halo is its OWN paper (design-direction §2/§4)"
      (is (= "#F5EFDF" (:halo-color day)))
      (is (= "#151B26" (:halo-color night))))
    (testing "an unknown theme falls back to the day edition — a chart is
              always on the table"
      (is (= day (style/palette :sepia))))))

(deftest trail-gradient-is-the-editions-ink
  (doseq [theme themes]
    (let [expr (style/trail-gradient-expression theme)
          rgb  (:trail-rgb (style/palette theme))]
      (is (= ["interpolate" ["linear"] ["line-progress"]] (take 3 expr)))
      (testing "tail fully transparent, head capped at the quiet-echo alpha"
        (is (= (str "rgba(" rgb ", 0)") (nth expr 4)))
        (is (= (str "rgba(" rgb ", " style/trail-head-opacity ")")
               (nth expr 6))))))
  (is (<= style/trail-head-opacity 0.5)
      "history is a quiet ink echo — the direction caps the head at 0.5"))
