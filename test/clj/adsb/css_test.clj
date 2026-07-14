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
                   ".adsb-stack-shelf-label"]
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
                    "transition: bottom"]]
      (is (< (str/last-index-of @css motion) reduce-at)
          (str "every `" motion "` must be emitted before the block that
               disables it, or the block loses the tie and the motion plays")))))

(deftest the-census-count-prints-in-both-stances
  ;; EMG never draws a dot cluster and PLOTTED's whole fact is its fraction:
  ;; a display:none outside the phone block rendered both as bare words on a
  ;; desktop, and EMG's stated zero was stated invisibly (adsb-be2). A stance
  ;; may re-justify the count; none may hide it.
  (let [blocks (blocks-for @css ".adsb-stack-shelf-count")]
    (is (seq blocks) "the count is styled at all")
    (doseq [b blocks]
      (is (not (str/includes? b "display"))
          "no stance hides (or needs to un-hide) the count"))))

(deftest the-drawer-is-a-card-on-desktop-and-a-sheet-on-phone
  ;; adsb-l4m: two surfaces floating over one chart must read as two of the
  ;; same thing. The drawer's base stance is the index card's own face; the
  ;; phone block re-pins it flush into the corner it slides from.
  (let [[base phone & extra] (blocks-for @css ".adsb-stack-drawer")]
    (is (some? base) "the drawer has a base rule")
    (is (str/includes? base "top: var(--s3)") "the card floats clear of the corner")
    (is (str/includes? base "border-radius: 2px") "with the card's corners")
    (is (str/includes? base "background: var(--paper-veil)") "on the card's paper")
    (is (str/includes? base "animation: adsb-settle") "settling in like one")
    (is (some? phone) "and the phone stance re-pins it")
    (is (str/includes? phone "top: auto") "unpinned from the panel's corner")
    (is (str/includes? phone "bottom: calc(var(--stack-w)")
        "a sheet standing on the recumbent Stack — it rises from the bar
        that holds the caption the finger tapped (adsb-88m)")
    (is (str/includes? phone "border-radius: 0") "square again")
    (is (str/includes? phone "animation: adsb-settle-up")
        "and it settles UP out of the bar, not down out of the sky")
    (is (empty? extra) "two stances, two rules, nothing else claiming it")))

(deftest the-night-edition-only-repoints-the-day
  ;; The two editions are one mechanism (adsb-dgb.7). A variable that exists
  ;; only under prefers-color-scheme: dark resolves to NOTHING in the day
  ;; edition — the property it feeds is simply dropped. That is invisible to
  ;; anyone developing in dark mode, which is exactly why it is a test.
  (let [day   (set (keys tokens/day-tokens))
        night (set (keys tokens/night-tokens))]
    (is (empty? (set/difference night day))
        "every night token must also be defined on :root for the day edition")))
