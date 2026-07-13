(ns adsb.test-dom
  "Mounting a Reagent tree in a test, through the SAME door the app uses.

  adsb.core mounts with reagent.dom.client — a React 18 root. The tests that
  mount the whole shell used reagent.dom/render, which is React 17's legacy
  entry point, and React said so on every run: `ReactDOM.render is no longer
  supported ... your app will behave as if it's running React 17`. That warning
  was not decoration. A legacy root schedules and batches differently from a
  concurrent one, so those tests were proving the shell mounts under semantics
  the app never runs, and the map's mount/render counts — the centerpiece proof
  that aircraft never touch React — were being counted in the wrong world.

  A React 18 root commits ASYNCHRONOUSLY, which is why the legacy call could
  simply be followed by `r/flush` and the old code could not. `act` is the
  supplied answer: it runs the render and drains React's queue (and, inside it,
  Reagent's) before returning, so the assertions that follow a mount still read
  a settled DOM. RTL's `act` is used rather than React's raw one because it also
  sets IS_REACT_ACT_ENVIRONMENT around the call, which React otherwise warns
  about — trading one console warning for another is not a fix."
  (:require
    ["@testing-library/react" :as rtl]
    [reagent.core :as r]
    [reagent.dom.client :as rdomc]))

(defn mount!
  "Mount `component` (hiccup) into DOM node `el` and return the root. Settled on
  return: the tree is committed and mount effects have run."
  [component el]
  (let [root (rdomc/create-root el)]
    (rtl/act (fn []
               (rdomc/render root component)
               (r/flush) ;; reagent 1.2.0 exposes `flush`, not `flush!`
               js/undefined))
    root))

(defn unmount!
  "Tear the root down. Settled on return: unmount effects — the map's `destroy!`,
  the layer's `detach!` — have already run."
  [root]
  (rtl/act (fn []
             (rdomc/unmount root)
             js/undefined)))
