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
    ;; The anchor is the claimed list itself, joined — not a hand-copied prefix
    ;; of it. Two selectors have now left this group (the corner legend's
    ;; .adsb-count-unit, then the header stats' .adsb-stats-label), and each
    ;; time a hand-copied anchor went stale it took the whole deftest down with
    ;; a nil. Both the anchor and the per-selector lookups are checked for
    ;; presence before they are compared, so a changed claim reports itself as
    ;; ONE named missing selector instead of a pile of NullPointerExceptions.
    (let [claimed [".adsb-fact-label" ".adsb-mayday-label"
                   ".adsb-roster-search-label" ".adsb-roster-cols"
                   ".adsb-flight-label"]
          caption (str/index-of @css (str (str/join ", " claimed) " {"))]
      (is (some? caption)
          "the grouped caption rule is in the stylesheet, claiming exactly these
           selectors — if this is nil, adsb.css.captions no longer names the
           list above and the list is what must be updated")
      (when caption
        (doseq [sel claimed]
          (let [own-block (str/index-of @css (str sel " {"))]
            (is (some? own-block)
                (str sel " has a block of its own for the caption rule to override"))
            (when own-block
              (is (< own-block caption)
                  (str sel " must be styled before the caption rule overrides it")))))))))

(defn- blocks-for
  "Every declaration block for exactly `selector` (grouped selectors don't
  match — the selector must sit immediately before its own brace)."
  [css selector]
  (loop [from 0 acc []]
    (if-let [start (str/index-of css (str selector " {") from)]
      (let [end (str/index-of css "}" start)]
        (recur (inc end) (conj acc (subs css start end))))
      acc)))

(deftest reduced-motion-wins-every-tie
  ;; Media queries add no specificity, so the reduce block beats the rules
  ;; that SET motion only by coming after them. Emitted early it was dead:
  ;; the panel settled, the dots breathed and the ticks drifted for exactly
  ;; the readers who asked them not to (adsb-b1j).
  (let [reduce-at (str/index-of @css "prefers-reduced-motion")]
    (is (some? reduce-at) "the reduce block is in the stylesheet")
    (doseq [motion ["animation: adsb-settle"
                    "animation: adsb-breathe"
                    "animation: adsb-ring-draw"
                    "transition: width"
                    "transition: height"]]
      (is (< (str/last-index-of @css motion) reduce-at)
          (str "every `" motion "` must be emitted before the block that
               disables it, or the block loses the tie and the motion plays")))))

(deftest the-roster-dock-clears-the-map-edge
  ;; Search + Sheet (adsb-66h): the dock owns the right edge; the NOTAM strip
  ;; and attribution must clear it by the same token.
  (let [roster-blocks (blocks-for @css ".adsb-roster")
        alerts-right  (index-of-decl @css ".adsb-alerts" "right")]
    (is (seq roster-blocks) "the roster is styled")
    (is (some? alerts-right) "the NOTAM strip declares a right edge")
    (is (str/includes? (first roster-blocks) "var(--roster-w)")
        "the dock's width is the shared roster token")))

(deftest the-watch-reloads-our-tree-and-only-ours-in-dependency-order
  ;; The watch must NEVER reload garden itself: re-evaluating garden.types
  ;; mints a new CSSAtRule class each pass while garden.compiler keeps its
  ;; protocols extended on the original, and every at-media record then dies
  ;; on `(empty record)` — "Can't create empty: garden.types.CSSAtRule", on
  ;; every save, forever (adsb-qil). So it load-files OUR sources instead,
  ;; ordered by the requires read from their own ns forms. This holds that
  ;; order to the facts it must respect.
  (let [sources (keys (#'build/sources))
        ordered (vec (#'build/in-dependency-order sources))
        at      (fn [file]
                  (first (keep-indexed
                           (fn [i path] (when (str/ends-with? path file) i))
                           ordered)))]
    (is (= (count sources) (inc (count ordered)))
        "every Garden source is loaded except one")
    (is (nil? (at "build.clj"))
        "…and the exception is the watch itself: it must not re-evaluate the
        very loop it is running")
    (is (< (at "decl.clj") (at "tokens.clj")) "decl loads before its users")
    (is (< (at "card.clj") (at "panel.clj")) "the shared card face precedes the panel")
    (is (< (at "roster.clj") (at "app.clj")) "the roster joins the cascade")
    (is (< (at "app.clj") (count ordered)) "the cascade assembles")))

(deftest the-night-edition-only-repoints-the-day
  ;; The two editions are one mechanism (adsb-dgb.7). A variable that exists
  ;; only under prefers-color-scheme: dark resolves to NOTHING in the day
  ;; edition — the property it feeds is simply dropped. That is invisible to
  ;; anyone developing in dark mode, which is exactly why it is a test.
  (let [day   (set (keys tokens/day-tokens))
        night (set (keys tokens/night-tokens))]
    (is (empty? (set/difference night day))
        "every night token must also be defined on :root for the day edition")))

(deftest safe-area-tokens-clear-every-edge
  ;; Chrome must clear notch / home indicator / landscape rounded corners.
  ;; Tokens exist on :root; floating chrome references them (not raw env()).
  (let [day (set (keys tokens/day-tokens))
        css @css]
    (doseq [tok [:--safe-top :--safe-right :--safe-bottom :--safe-left]]
      (is (contains? day tok) (str tok " is a day token")))
    (doseq [name ["--safe-top" "--safe-right" "--safe-bottom" "--safe-left"]]
      (is (str/includes? css name)
          (str name " is emitted into the stylesheet")))
    (is (str/includes? css "var(--safe-top)")
        "the NOTAM / panel top path uses the token")
    (is (str/includes? css "var(--safe-bottom)")
        "the drawer / attribution bottom path uses the token")
    (is (str/includes? css "var(--safe-left)")
        "the panel / strip left path uses the token")
    (is (str/includes? css "var(--safe-right)")
        "the dock / strip right path uses the token")))
