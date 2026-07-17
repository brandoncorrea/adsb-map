(ns adsb.reagent-compiler
  "Single source of truth for reagent's render compiler, so the production
   entry (adsb.core/init!) and the test harness (adsb.test-flush preload)
   render through the SAME compiler — a suite green on a different compiler
   than production would prove nothing.

   reagent 2 can compile plain fn components to React function components
   instead of the legacy class components its default compiler emits. That is
   the direction reagent is heading; class components are legacy React. Our
   form-2 components (aircraft-panel, alert-ribbon, follow-control, health,
   and roster's non-class views) keep their once-per-instance outer let: the
   function wrapper stores the inner render fn in a useRef on first render, so
   the outer let still runs once per mount (adsb-0yz)."
  (:require [reagent.core :as r]))

(defn install! []
  (r/set-default-compiler! (r/create-compiler {:function-components true})))
