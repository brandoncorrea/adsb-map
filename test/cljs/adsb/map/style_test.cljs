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
    [cljs.test :refer-macros [deftest is testing]]))

(def ^:private themes [:day :night])

(deftest layer-is-a-symbol-rotated-by-track
  (doseq [theme themes]
    (let [spec (style/aircraft-layer-spec theme "aircraft" "aircraft")]
      (is (= "symbol" (:type spec)) "a symbol layer — a plane, not a circle")
      (testing "the icon rotates with the reported track, pinned to the ground"
        (is (= ["get" "track"] (get-in spec [:layout :icon-rotate])))
        (is (= "map" (get-in spec [:layout :icon-rotation-alignment]))))
      (testing "track-less aircraft fall back to a non-directional dot"
        (is (= ["case" ["has" "track"] style/plane-icon-id style/dot-icon-id]
               (get-in spec [:layout :icon-image]))))
      (testing "no aircraft is dropped by label collision"
        (is (true? (get-in spec [:layout :icon-allow-overlap]))))
      (testing "the halo is the edition's own paper — ink survives a busy chart"
        (is (= (:halo-color (style/palette theme))
               (get-in spec [:paint :icon-halo-color])))))))

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
  (testing "size: emergency wins first, then mlat is demoted, else base"
    (let [expr (style/icon-size-expression)]
      (is (= ["case"
              ["get" "emergency"] style/emergency-icon-size
              ["get" "mlat"]      style/mlat-icon-size
              style/base-icon-size]
             expr))
      (is (> style/emergency-icon-size style/base-icon-size)
          "emergency is unmissably large")
      (is (< style/mlat-icon-size style/base-icon-size)
          "an mlat fix reads a touch smaller — lower confidence"))))

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
;; The cast shadow (design-direction §8, adsb-dgb.8) — a prototype behind
;; a toggle. The spec is DATA, so the contract is proved as data: the
;; layer draws only shadow-bearing features, reads the geo-computed
;; offset, wears the edition's shadow ink, fades with altitude AND age,
;; and softens as the plane climbs.

(deftest shadow-prototype-is-behind-a-toggle
  (is (boolean? style/shadows-enabled?)
      "the visual pass accepts or rejects the invention by flipping ONE
       constant"))

(deftest shadow-layer-draws-only-what-casts-a-shadow
  (doseq [theme themes]
    (let [spec (style/shadow-layer-spec theme "aircraft-shadows" "aircraft")]
      (is (= "symbol" (:type spec)))
      (is (= "aircraft" (:source spec))
          "the SAME source as the aircraft layer — no second setData")
      (testing "the filter admits only features carrying shadow-offset —
                on-ground and altitude-unknown aircraft cast NOTHING
                (adsb.geo omits the property; absent is not zero)"
        (is (= ["has" "shadow-offset"] (:filter spec))))
      (testing "the offset is the geo-computed [dx dy], read back as the
                two-number array icon-offset demands"
        (is (= ["array" "number" 2 ["get" "shadow-offset"]]
               (get-in spec [:layout :icon-offset]))))
      (testing "the shadow is the plane's true silhouette: same rotation,
                same size treatment, pinned to the map like the plane"
        (is (= ["get" "track"] (get-in spec [:layout :icon-rotate])))
        (is (= "map" (get-in spec [:layout :icon-rotation-alignment])))
        (is (= (style/icon-size-expression)
               (get-in spec [:layout :icon-size]))))
      (testing "the soft (pre-blurred) silhouettes, mirroring the
                plane/dot choice"
        (is (= ["case" ["has" "track"]
                style/shadow-plane-icon-id style/shadow-dot-icon-id]
               (get-in spec [:layout :icon-image]))))
      (testing "no shadow is dropped by collision — it belongs to a plane
                that is always drawn"
        (is (true? (get-in spec [:layout :icon-allow-overlap])))))))

(deftest shadow-wears-the-editions-ink
  (doseq [theme themes]
    (let [spec (style/shadow-layer-spec theme "aircraft-shadows" "aircraft")
          ink  (:shadow-ink (style/palette theme))]
      (is (some? ink) "each edition carries a shadow ink")
      (is (= ink (get-in spec [:paint :icon-color])))
      (is (= ink (get-in spec [:paint :icon-halo-color]))
          "the penumbra is the same ink, softened — never a second colour"))))

(deftest shadow-opacity-falls-with-altitude-and-fades-with-age
  (doseq [theme themes]
    (let [expr  (style/shadow-opacity-expression theme)
          stops (:shadow-opacity-stops (style/palette theme))]
      (is (= "*" (first expr)) "altitude base × age fade, multiplied")
      (testing "the altitude base falls as the plane climbs — a high
                shadow is fainter, never a rival glyph"
        (let [ramp (nth expr 1)]
          (is (= ["interpolate" ["linear"] ["get" "altitude"]] (take 3 ramp)))
          (is (= (mapcat identity stops) (drop 3 ramp)))
          (is (> (second (first stops)) (second (last stops)))
              "alpha at the deck exceeds alpha at the cap")))
      (testing "the age factor is the SAME continuous fade the aircraft
                wears — the shadow fades with its plane, never outlives it"
        (is (= (style/icon-opacity-expression) (nth expr 2)))))))

(deftest shadow-softens-as-the-plane-climbs
  (let [expr (style/shadow-softness-expression)]
    (is (= ["interpolate" ["linear"] ["get" "altitude"]] (take 3 expr)))
    (let [[[low-ft low-px] [high-ft high-px]] style/shadow-softness-stops]
      (is (< low-ft high-ft))
      (is (< low-px high-px) "the penumbra deepens with altitude"))
    (doseq [theme themes]
      (let [spec (style/shadow-layer-spec theme "s" "a")]
        (is (= expr (get-in spec [:paint :icon-halo-width])))
        (is (= expr (get-in spec [:paint :icon-halo-blur])))))))

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
                    :halo-color :trail-rgb
                    ;; the shadow was re-reasoned for dark stock, not
                    ;; inherited: different ink AND different alphas
                    :shadow-ink :shadow-opacity-stops]]
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
