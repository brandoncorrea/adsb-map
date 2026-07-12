(ns adsb.map.theme
  "Which printed edition of the chart is on the table — `:day` or `:night`.

  The design direction (docs/design-direction.md §1–2) commits to TWO
  printed editions of the same chart, switched by the SYSTEM theme
  (`prefers-color-scheme`), never by an in-app toggle. This namespace is
  the one place the frontend reads that media query:

    * `system-theme` asks the browser which edition the user's system
      wants right now.
    * `!theme` is the current edition as a Reagent atom — the ONE value
      the chrome derefs (the legend paints its swatches per edition, so
      it must re-render on a flip; a flip is a rare, human-scale event,
      nothing like aircraft traffic).
    * `watch-system!` subscribes a callback to live scheme changes, so
      the map can re-print itself when the OS slides from day to night.

  The CSS chrome needs none of this — app.css carries its own
  `@media (prefers-color-scheme: dark)` custom-properties layer and the
  browser flips it for free. This namespace exists for the two surfaces
  CSS cannot reach: the MapLibre basemap style and the aircraft-layer
  paint expressions (adsb.map.basemap, adsb.map.style).

  `match-media` is a seam: tests redef it to a fake MediaQueryList and
  drive the whole edition switch without an OS in the loop."
  (:require [reagent.core :as r]))

(def ^:const dark-scheme-query
  "The media query that selects the night edition."
  "(prefers-color-scheme: dark)")

(defn match-media
  "The browser's MediaQueryList for `query`. A seam over
  js/window.matchMedia so tests hand back a fake with a scripted
  `matches` and captured listeners."
  [query]
  (.matchMedia js/window query))

(defn system-theme
  "The edition the system prefers RIGHT NOW: `:night` when the dark
  scheme matches, else `:day`."
  []
  (if (.-matches (match-media dark-scheme-query)) :night :day))

(defonce !theme
  ;; The current edition, as chrome-visible state. A plain Reagent atom,
  ;; NOT app-db: the theme is an ambient fact of the host system, not
  ;; domain state, and no event ever sets it — only the media query does.
  (r/atom :day))

(defn set-theme!
  "Make `theme` the current edition. Called by the map view when the
  system scheme flips (and by tests); everything derefing `!theme`
  follows."
  [theme]
  (reset! !theme theme))

(defn sync!
  "Align `!theme` with the system's current preference and return the
  resulting theme. Called once at map mount, before the first print."
  []
  (let [theme (system-theme)]
    (set-theme! theme)
    theme))

(defn watch-system!
  "Call `f` with the new theme (`:day`/`:night`) whenever the system
  scheme changes. Returns a zero-arg unlisten fn — call it on unmount so
  a dead component never re-prints the map."
  [f]
  (let [mql     (match-media dark-scheme-query)
        handler (fn [_e] (f (if (.-matches mql) :night :day)))]
    (.addEventListener mql "change" handler)
    (fn [] (.removeEventListener mql "change" handler))))
