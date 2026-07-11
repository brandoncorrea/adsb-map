(ns adsb.map.maplibre
  "The seam over MapLibre GL JS — a thin, fakeable interop wrapper, and the
  ONLY place the app touches the raw library.

  Why a seam at all? Tests run in real headless Chromium, but headless WebGL
  is slow and flaky, and MapLibre's rendering is MapLibre's contract, not
  ours. So tests never construct a real map: they `with-redefs` `create!` to
  capture the options and hand back a fake `Map`. See docs/testing-setup.md,
  \"The Map Seam\"."
  (:require
    ["maplibre-gl" :as maplibre]))

(defprotocol Map
  "The narrow surface the app needs from a live map instance. It grows only
  as beads land — the imperative aircraft GeoJSON layer (adsb-2yu.4) will add
  `set-source-data!` here. Keep it small; every method is a thing the fake in
  a test must also implement."
  (destroy! [this]
    "Tear the map down and release its GL context. Call on unmount."))

(deftype MapLibreMap [^js gl-map]
  Map
  (destroy! [_] (.remove gl-map)))

(defn create!
  "Construct a real MapLibre map inside `container` (a DOM node) with `opts`
  — a Clojure map of MapLibre map options (`:style`, `:center` as [lon lat],
  `:zoom`, `:attributionControl`). Returns a value satisfying `Map`.

  This is the seam. It is deliberately a thin translation with no logic worth
  testing — a unit test redefines it to record `opts` and never touches the
  GPU. Everything interesting to assert (the default center, the token-free
  style) lives in `adsb.map.view/default-map-opts`, which the fake still runs."
  [container opts]
  (->MapLibreMap
    (maplibre/Map. (clj->js (assoc opts :container container)))))
