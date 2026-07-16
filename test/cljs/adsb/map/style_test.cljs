(ns adsb.map.style-test
  (:require [adsb.aircraft :as aircraft]
            [adsb.map.style :as style]
            [adsb.schema :as schema]
            [clojure.test :refer-macros [deftest is testing]]))

(def ^:private themes [:day :night])

(def ^:private icon-ids
  #{style/plane-icon-id
    style/heavy-icon-id
    style/light-icon-id
    style/rotorcraft-icon-id
    style/vehicle-icon-id
    style/dot-icon-id})

(deftest layer-is-a-symbol-rotated-by-track
  (doseq [theme themes]
    (let [spec (style/aircraft-layer-spec theme "aircraft" "aircraft")]
      (is (= "symbol" (:type spec)))
      (testing "the icon rotates with the reported track, pinned to the ground"
        (is (= ["get" "track"] (get-in spec [:layout :icon-rotate])))
        (is (= "map" (get-in spec [:layout :icon-rotation-alignment]))))
      (testing "the icon is chosen per feature (the symbology — see
                icon-image-is-keyed-on-category below)"
        (is (= (style/icon-image-expression)
               (get-in spec [:layout :icon-image]))))
      (testing "no aircraft is dropped by label collision"
        (is (get-in spec [:layout :icon-allow-overlap])))
      (testing "the halo is the edition's own paper — ink survives a busy chart"
        (is (= (:halo-color (style/palette theme))
               (get-in spec [:paint :icon-halo-color])))))))

(defn- evaluate [expr props]
  (if-not (vector? expr)
    expr
    (let [[op & args] expr]
      (case op
        "get" (get props (first args))
        "has" (contains? props (first args))
        "!" (not (evaluate (first args) props))
        "case" (loop [args args]
                 (if (= 1 (count args))
                   (evaluate (first args) props)
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

(defn- icon-for [props]
  (evaluate (style/icon-image-expression) props))

(deftest icon-image-is-keyed-on-category
  (testing "the category chooses the silhouette — a helicopter is not
            drawn as a small airliner"
    (is (= style/rotorcraft-icon-id (icon-for {"track" 90 "category" "A7"})))
    (is (= style/heavy-icon-id (icon-for {"track" 90 "category" "A5"})))
    (is (= style/heavy-icon-id (icon-for {"track" 90 "category" "A4"})))
    (is (= style/light-icon-id (icon-for {"track" 90 "category" "A1"})))
    (is (= style/vehicle-icon-id (icon-for {"track" 90 "category" "C2"}))))

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
        (is (contains? icon-ids icon))))))

(deftest altitude-colour-handles-its-three-states
  (doseq [theme themes]
    (let [expr    (style/altitude-color-expression theme)
          palette (style/palette theme)]
      (is (= "case" (first expr)))
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
            (is (= (map first (:altitude-stops palette)) (take-nth 2 stops)))))))))

(deftest emergency-overrides-colour-and-size
  (testing "colour: emergency red beats the altitude ramp, in both editions"
    (doseq [theme themes]
      (let [expr (style/icon-color-expression theme)]
        (is (= "case" (first expr)))
        (is (= ["get" "emergency"] (nth expr 1)))
        (is (= (:emergency-color (style/palette theme)) (nth expr 2)))
        (is (= (style/altitude-color-expression theme) (nth expr 3))))))
  (testing "size: emergency wins first and absolutely — a distressed plane
            is never allowed to look far away"
    (let [expr (style/icon-size-expression)]
      (is (= "case" (first expr)))
      (is (= ["get" "emergency"] (nth expr 1)))
      (is (= style/emergency-icon-size (nth expr 2)))
      (is (> style/emergency-icon-size
             (apply max (map second style/perspective-size-stops)))))))

(deftest size-is-perspective-altitude
  (let [expr (style/perspective-size-expression)]
    (testing "the three altitude states peel apart exactly as colour's do"
      (is (= "case" (first expr)))
      (is (= ["!" ["has" "altitude"]] (nth expr 1)))
      (is (= style/base-icon-size (nth expr 2)))
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
      (is (apply > sizes))))

  (testing "the ground size sits beneath the whole flying ramp — a parked
            plane defers to every airborne one"
    (is (< style/ground-icon-size (apply min (map second style/perspective-size-stops)))))

  (testing "mlat demotion MULTIPLIES the perspective size instead of
            replacing it — lower confidence reads a touch smaller at every
            altitude without stealing the altitude channel"
    (let [expr (style/icon-size-expression)
          body (nth expr 3)]
      (is (= ["*"
              ["case" ["get" "mlat"] style/mlat-size-factor 1.0]
              (style/perspective-size-expression)]
             body))
      (is (< 0 style/mlat-size-factor 1)))))

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
          (is (= [style/stale-threshold-s style/base-opacity
                  style/age-out-threshold-s style/aged-out-opacity]
                 (drop 3 ramp)))))))

  (testing "the fade bounds are the domain thresholds, in seconds — the two
            sides can never disagree about where the fade begins or ends"
    (is (= (/ aircraft/stale-threshold-ms 1000) style/stale-threshold-s))
    (is (= (/ aircraft/age-out-threshold-ms 1000) style/age-out-threshold-s))
    (is (< style/aged-out-opacity style/base-opacity))))

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
        (is (not= (role day) (role night))))
      (is (= [] (filter identity
                        (map (fn [[_ d] [_ n]] (when (= d n) d))
                             (:altitude-stops day) (:altitude-stops night))))))
    (testing "each edition's halo is its OWN paper (design-direction §2/§4)"
      (is (= "#E2E8DE" (:halo-color day)))
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
  (is (<= style/trail-head-opacity 0.5)))
