(ns adsb.ui.icon
  "The app's icons — one registry, one component, and no dependency.

  FontAwesome, but VENDORED AS PATH DATA rather than installed. There is no
  npm package here, no webfont, no kit script, no CDN. The reason is the CSP
  (adsb.http.security): `default-src 'none'` refuses every origin the app does
  not name, and a font CDN is not worth an origin. The app already vendors its
  four woff2 faces (adsb.css.tokens) and MapLibre's CSS for exactly this
  reason. An icon is a `d` attribute. It can just live in the source.

  So each entry below is a SLOT: paste the `viewBox` and the path's `d`
  straight out of the icon's .svg and it lights up. Both come from the same
  two attributes of the downloaded file:

      <svg ... viewBox=\"0 0 384 512\"><path d=\"M342.6 150.6 …\"/></svg>
                        ^^^^^^^^^^^^^            ^^^^^^^^^^^^^^^
                        :view-box                :path

  FONT AWESOME FREE 7.3.0, SOLID. CC BY 4.0, which REQUIRES attribution —
  docs/icon-licenses.md is it, and it is not optional. Read it before adding an
  icon.

  FREE ONLY, and the reason is this repo rather than the subscription: a Pro
  licence grants the right to USE the suite, not to REDISTRIBUTE it, and this
  repository is PUBLIC — anyone can clone it and lift a path straight out of
  the map below. So a Pro icon may be used in this project and may not be
  COMMITTED to this file. If one is ever needed it goes the way the enrichment
  database goes (fetched at build time, gitignored, absent-tolerant), or it
  gets drawn by hand the way the follow reticle used to be. The full argument,
  and the escape route, are in docs/icon-licenses.md.

  SIZING. The svg is `1em` square and filled with `currentColor`, which means
  it is NOT styled here. It inherits the font-size and the colour of whatever
  box it lands in, exactly as the character glyph it replaces did — so the
  panel's close button still sizes its mark with `font-size: var(--t1)` and
  still fades it with `color: var(--faded-ink)`, and the hover rule that swaps
  that colour keeps working untouched. Replacing a glyph with an icon should
  cost the stylesheet nothing, and it costs it nothing.

  An UNFILLED slot renders a crossed box, loudly, at the icon's size. Loudly
  missing beats quietly wrong — the same bargain adsb.map.basemap strikes with
  its glyph endpoint. A silent nil here would be an icon-shaped hole that
  nobody notices until it ships."
  (:require [clojure.string :as str]))

;; The icons the chrome asks for, and every one of them is a Free Solid icon.
;;
;;   :xmark             the index card's way out       (adsb.ui.aircraft-panel)
;;   :chevron-down      that card, expanded            (adsb.ui.aircraft-panel)
;;   :chevron-right     that card, collapsed           (adsb.ui.aircraft-panel)
;;   :crosshairs        the chart's Free/Follow mark   (adsb.ui.follow)
;;   :magnifying-glass  the roster's find field        (adsb.ui.roster)
;;
;; The chevrons are a MATCHED PAIR and that is the entire point of taking them
;; from a typeface designer rather than from Unicode: the ▾/▸ they replace were
;; two unrelated geometric characters with different optical weights and
;; different vertical centring, so the control visibly twitched when it toggled.
;; Paste both from the same style or the twitch comes back.
(def icons
  {:xmark
   {:view-box "0 0 384 512"
    :path     "M55.1 73.4c-12.5-12.5-32.8-12.5-45.3 0s-12.5 32.8 0 45.3L147.2 256 9.9 393.4c-12.5 12.5-12.5 32.8 0 45.3s32.8 12.5 45.3 0L192.5 301.3 329.9 438.6c12.5 12.5 32.8 12.5 45.3 0s12.5-32.8 0-45.3L237.8 256 375.1 118.6c12.5-12.5 12.5-32.8 0-45.3s-32.8-12.5-45.3 0L192.5 210.7 55.1 73.4z"}

   :chevron-down
   {:view-box "0 0 448 512"
    :path     "M201.4 406.6c12.5 12.5 32.8 12.5 45.3 0l192-192c12.5-12.5 12.5-32.8 0-45.3s-32.8-12.5-45.3 0L224 338.7 54.6 169.4c-12.5-12.5-32.8-12.5-45.3 0s-12.5 32.8 0 45.3l192 192z"}

   :chevron-right
   {:view-box "0 0 320 512"
    :path     "M311.1 233.4c12.5 12.5 12.5 32.8 0 45.3l-192 192c-12.5 12.5-32.8 12.5-45.3 0s-12.5-32.8 0-45.3L243.2 256 73.9 86.6c-12.5-12.5-12.5-32.8 0-45.3s32.8-12.5 45.3 0l192 192z"}

   :crosshairs
   {:view-box "0 0 576 512"
    :path     "M288-16c17.7 0 32 14.3 32 32l0 18.3c98.1 14 175.7 91.6 189.7 189.7l18.3 0c17.7 0 32 14.3 32 32s-14.3 32-32 32l-18.3 0c-14 98.1-91.6 175.7-189.7 189.7l0 18.3c0 17.7-14.3 32-32 32s-32-14.3-32-32l0-18.3C157.9 463.7 80.3 386.1 66.3 288L48 288c-17.7 0-32-14.3-32-32s14.3-32 32-32l18.3 0C80.3 125.9 157.9 48.3 256 34.3L256 16c0-17.7 14.3-32 32-32zM131.2 288c12.7 62.7 62.1 112.1 124.8 124.8l0-12.8c0-17.7 14.3-32 32-32s32 14.3 32 32l0 12.8c62.7-12.7 112.1-62.1 124.8-124.8L432 288c-17.7 0-32-14.3-32-32s14.3-32 32-32l12.8 0C432.1 161.3 382.7 111.9 320 99.2l0 12.8c0 17.7-14.3 32-32 32s-32-14.3-32-32l0-12.8C193.3 111.9 143.9 161.3 131.2 224l12.8 0c17.7 0 32 14.3 32 32s-14.3 32-32 32l-12.8 0zM288 208a48 48 0 1 1 0 96 48 48 0 1 1 0-96z"}

   :magnifying-glass
   {:view-box "0 0 512 512"
    :path     "M416 208c0 45.9-14.9 88.3-40 122.7L502.6 457.4c12.5 12.5 12.5 32.8 0 45.3s-32.8 12.5-45.3 0L330.7 376C296.3 401.1 253.9 416 208 416 93.1 416 0 322.9 0 208S93.1 0 208 0 416 93.1 416 208zM208 352a144 144 0 1 0 0-288 144 144 0 1 0 0 288z"}})

(def ^:const placeholder-view-box
  "The box the crossed-box placeholder is drawn in. Its own — an unfilled slot
  has no viewBox of its own to borrow."
  "0 0 16 16")

(defn filled?
  "Has this slot been pasted in yet? Both halves or neither: a `d` without the
  `viewBox` it was drawn against scales to nonsense."
  [{:keys [view-box path]}]
  (and (not (str/blank? view-box))
       (not (str/blank? path))))

(defn- placeholder
  "An unpasted icon: a crossed box, in the ink of whatever asked for it. Sized
  like any other icon, so the layout it lands in is the layout it will keep."
  []
  [:g {:fill "none" :stroke "currentColor" :stroke-width 1.5}
   [:rect {:x 1 :y 1 :width 14 :height 14}]
   [:path {:d "M1 1 L15 15 M15 1 L1 15"}]])

(defn icon
  "One icon, as hiccup — `[icon :xmark]`.

  Decorative by default and so `aria-hidden`: every call site in this app sits
  inside a control that already carries its own `aria-label`, and an icon that
  names itself a second time is a screen reader saying everything twice. If a
  future icon is ever the ONLY thing naming its control, that control is
  missing a label — fix it there, not here.

  `focusable=false` is not redundant with aria-hidden: legacy Edge/IE put SVG
  in the tab order regardless, and a focus stop on a decoration is a keyboard
  trap that no reader can see."
  [k]
  (let [{:keys [view-box path] :as entry} (get icons k)
        ready? (filled? entry)]
    [:svg
     {:class       (str "adsb-icon adsb-icon-" (name k))
      :viewBox     (if ready? view-box placeholder-view-box)
      :aria-hidden true
      :focusable   "false"
      :data-icon   (name k)}
     (if ready?
       [:path {:d path}]
       [placeholder])]))
