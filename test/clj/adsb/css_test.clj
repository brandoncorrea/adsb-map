(ns adsb.css-test
  (:require [adsb.css.build :as build]
            [adsb.css.tokens :as tokens]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private css (delay (build/render)))

(defn- index-of-decl [css selector prop]
  (let [start (str/index-of css (str selector " {"))
        _     (assert start (str "no rule for " selector))
        end   (str/index-of css "}" start)
        block (subs css start end)]
    (str/index-of block (str prop ":"))))

(deftest declaration-order-survives-compilation
  (testing "a shorthand cannot be emitted after the longhand it would erase"
    (is (< (index-of-decl @css ".adsb-alert" "border")
           (index-of-decl @css ".adsb-alert" "border-bottom"))))

  (testing "`font: inherit` cannot be emitted after the longhands it resets"
    (let [font (index-of-decl @css ".adsb-edge-arrow" "font")]
      (is (< font (index-of-decl @css ".adsb-edge-arrow" "font-size")))
      (is (< font (index-of-decl @css ".adsb-edge-arrow" "font-weight"))))))

(deftest the-caption-voice-wins
  (testing "the caption rule is emitted after every block whose labels it claims"
    (let [claimed [".adsb-fact-label" ".adsb-mayday-label"
                   ".adsb-roster-cols" ".adsb-flight-label"]
          caption (str/index-of @css (str (str/join ", " claimed) " {"))]
      (is (some? caption))
      (when caption
        (doseq [sel claimed]
          (let [own-block (str/index-of @css (str sel " {"))]
            (is (some? own-block))
            (when own-block
              (is (< own-block caption)))))))))

(defn- blocks-for [css selector]
  (loop [from 0 acc []]
    (if-let [start (str/index-of css (str selector " {") from)]
      (let [end (str/index-of css "}" start)]
        (recur (inc end) (conj acc (subs css start end))))
      acc)))

(deftest reduced-motion-wins-every-tie
  (let [reduce-at (str/index-of @css "prefers-reduced-motion")]
    (is (some? reduce-at))
    (doseq [motion ["animation: adsb-settle"
                    "animation: adsb-breathe"
                    "animation: adsb-ring-draw"
                    "transition: width"
                    "transition: height"]]
      (is (< (str/last-index-of @css motion) reduce-at)))))

(deftest the-roster-dock-clears-the-map-edge
  (let [roster-blocks (blocks-for @css ".adsb-roster")
        alerts-right  (index-of-decl @css ".adsb-alerts" "right")]
    (is (seq roster-blocks))
    (is (some? alerts-right))
    (is (str/includes? (first roster-blocks) "var(--roster-w)"))))

(deftest the-find-field-does-not-zoom-mobile-safari
  (let [phone (subs @css (str/index-of @css "@media (max-width: 640px)"))
        block (blocks-for phone ".adsb-roster-search-input")]
    (is (seq block))
    (is (str/includes? (first block) "font-size: 16px"))))

(deftest the-watch-reloads-our-tree-and-only-ours-in-dependency-order
  (let [sources (keys (#'build/sources))
        ordered (vec (#'build/in-dependency-order sources))
        at      (fn [file]
                  (first (keep-indexed
                           (fn [i path] (when (str/ends-with? path file) i))
                           ordered)))]
    (is (= (count sources) (inc (count ordered))))
    (is (nil? (at "build.clj")))
    (is (< (at "decl.clj") (at "tokens.clj")))
    (is (< (at "card.clj") (at "panel.clj")))
    (is (< (at "roster.clj") (at "app.clj")))
    (is (< (at "app.clj") (count ordered)))))

(deftest the-night-edition-only-repoints-the-day
  (let [day   (set (keys tokens/day-tokens))
        night (set (keys tokens/night-tokens))]
    (is (empty? (set/difference night day)))))

(deftest safe-area-tokens-clear-every-edge
  (let [day (set (keys tokens/day-tokens))
        css @css]
    (doseq [tok [:--safe-top :--safe-right :--safe-bottom :--safe-left]]
      (is (contains? day tok)))
    (doseq [name ["--safe-top" "--safe-right" "--safe-bottom" "--safe-left"]]
      (is (str/includes? css name)))
    (is (str/includes? css "var(--safe-top)"))
    (is (str/includes? css "var(--safe-bottom)"))
    (is (str/includes? css "var(--safe-left)"))
    (is (str/includes? css "var(--safe-right)"))))
