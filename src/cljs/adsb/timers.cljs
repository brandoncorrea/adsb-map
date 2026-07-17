(ns adsb.timers
  "Thin, banged wrappers over the browser's timer primitives. These are the
  side-effecting seam the UI schedules against; tests redef them to drive
  callbacks synchronously without a real clock. Not Web Workers — just
  setTimeout/setInterval with the ! the standards require.")

(defn timeout! [f delay-ms] (js/setTimeout f delay-ms))

(defn clear-timeout! [id] (js/clearTimeout id))

(defn interval! [f delay-ms] (js/setInterval f delay-ms))

(defn clear-interval! [id] (js/clearInterval id))
