(ns adsb.css.decl
  "Declarations that keep the order they were written in.

  Order is load-bearing in CSS wherever one declaration overwrites another
  inside the same rule:

    border: none;  border-bottom: 1px solid ...   ; shorthand, then longhand
    font: inherit; font-size: var(--t0);          ; `font` resets font-size

  Scrambled, those rules quietly mean the opposite of what they say. It is
  not hypothetical: both examples above are real rules in this stylesheet
  (.adsb-alert, .adsb-edge-arrow), and both came out wrong before this
  namespace existed — the NOTAM row rules erased by their own `border: none`,
  the edge arrow's bold erased by its own `font: inherit`.

  Two things conspire to lose the order, and a plain map literal defeats
  neither:

    the reader   turns a literal of MORE THAN EIGHT PAIRS into a hash map,
                 whose seq order is arbitrary. This happens at READ time, so
                 no macro can recover the source order — the fix has to be a
                 function that takes its pairs as arguments.
    garden       rebuilds every declaration with `(reduce assoc (empty m) m)`
                 (garden.compiler/expand-declaration-1). `empty` PRESERVES
                 THE MAP TYPE, so the fix has to be a map type that stays
                 ordered under assoc at any size. array-map is not one: it
                 promotes itself to a hash map past eight pairs.

  flatland.ordered satisfies both. Use `decl` for EVERY rule, not just the
  long ones: there is no cliff to remember, and adding a ninth declaration to
  a rule can never silently reorder the eight above it."
  (:require
    [flatland.ordered.map :refer [ordered-map]]))

(defn decl
  "CSS declarations, in the order written."
  [& kvs]
  (apply ordered-map kvs))
