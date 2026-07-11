(ns adsb.core
  "Frontend entry point. The app shell — MapLibre init, re-frame
  skeleton — is TODO(adsb-2yu.1). Until then this only proves the
  build boots.")

(defn init!
  "Called by the :app build on page load."
  []
  (js/console.log "adsb frontend loaded — app shell lands in adsb-2yu.1"))
