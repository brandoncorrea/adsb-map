(ns adsb.css.card
  (:require [adsb.css.decl :refer [decl]]))

(def face
  (decl :background "var(--paper-veil)"
        :border "1px solid var(--rule-strong)"
        :border-radius "2px"
        :box-shadow "2px 2px 0 var(--rule-faint)"
        :color "var(--ink)"
        :box-sizing "border-box"
        :animation "adsb-settle 180ms ease-out"))

(def title
  (decl :font-size "var(--t1)"
        :font-weight 700
        :letter-spacing "0.04em"
        :overflow-wrap "anywhere"))

(def head
  (decl :display "flex"
        :align-items "baseline"
        :justify-content "space-between"
        :gap "var(--s2)"
        :padding "var(--s3) var(--s3) var(--s2)"
        :border-bottom "1px solid var(--ink)"))

(def close
  (decl :background "none"
        :border "none"
        :padding 0
        :font "inherit"
        :font-size "var(--t1)"
        :line-height 1
        :color "var(--faded-ink)"
        :cursor "pointer"))
