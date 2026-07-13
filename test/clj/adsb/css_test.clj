(ns adsb.css-test
  "The stylesheet is compiled now (adsb.css.build), so it can be tested.

  These are not snapshot tests — they do not care what the CSS looks like.
  They pin the three things about it that are LOAD-BEARING and that a
  reasonable-looking edit can silently break."
  (:require
    [adsb.css.build :as build]
    [adsb.css.tokens :as tokens]
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]))

(def ^:private css (delay (build/render)))

(defn- index-of-decl
  "Where `prop:` is declared inside the block for `selector`. nil if absent."
  [css selector prop]
  (let [start (str/index-of css (str selector " {"))
        _     (assert start (str "no rule for " selector))
        end   (str/index-of css "}" start)
        block (subs css start end)]
    (when-let [i (str/index-of block (str prop ":"))]
      i)))

(deftest declaration-order-survives-compilation
  ;; The regression this guards is real: Garden rebuilds every declaration map
  ;; with `(reduce assoc (empty m) m)`, so a plain map literal of more than
  ;; eight pairs arrives as a HASH MAP and comes out in arbitrary order. Both
  ;; rules below were wrong that way before adsb.css.decl existed. If someone
  ;; "simplifies" `decl` back to `{}`, these fail.
  (testing "a shorthand cannot be emitted after the longhand it would erase"
    ;; `border: none` must precede `border-bottom`, or it erases the row rule
    ;; that separates stacked NOTAM entries.
    (is (< (index-of-decl @css ".adsb-alert" "border")
           (index-of-decl @css ".adsb-alert" "border-bottom"))))

  (testing "`font: inherit` cannot be emitted after the longhands it resets"
    ;; `font` is a shorthand: it resets font-size and font-weight to their
    ;; defaults. Both longhands must come after it or the edge arrow loses its
    ;; size and its bold.
    (let [font (index-of-decl @css ".adsb-edge-arrow" "font")]
      (is (< font (index-of-decl @css ".adsb-edge-arrow" "font-size")))
      (is (< font (index-of-decl @css ".adsb-edge-arrow" "font-weight"))))))

(deftest the-caption-voice-wins
  ;; §5's caption rule names selectors that the chrome namespaces also style,
  ;; at EQUAL specificity — it applies only because it comes later. Reorder
  ;; adsb.css.app and the smallest labels silently stop printing in Grotesk.
  (testing "the caption rule is emitted after every block whose labels it claims"
    (let [caption (str/index-of @css ".adsb-count-unit, .adsb-stats-label")]
      (is (some? caption) "the grouped caption rule is in the stylesheet")
      (doseq [claimed [".adsb-stats-label" ".adsb-fact-label"
                       ".adsb-mayday-label" ".adsb-stack-shelf-label"]]
        (is (< (str/index-of @css (str claimed " {")) caption)
            (str claimed " must be styled before the caption rule overrides it"))))))

(deftest the-night-edition-only-repoints-the-day
  ;; The two editions are one mechanism (adsb-dgb.7). A variable that exists
  ;; only under prefers-color-scheme: dark resolves to NOTHING in the day
  ;; edition — the property it feeds is simply dropped. That is invisible to
  ;; anyone developing in dark mode, which is exactly why it is a test.
  (let [day   (set (keys tokens/day-tokens))
        night (set (keys tokens/night-tokens))]
    (is (empty? (set/difference night day))
        "every night token must also be defined on :root for the day edition")))
