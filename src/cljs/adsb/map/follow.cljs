(ns adsb.map.follow
  "Free / Follow camera for a selected flight (adsb-jg4).

  Two modes only (course-up deferred):

    :free    — default. Selection does not move the chart after the
               one-shot fly-to from focus.
    :follow  — keep the selected aircraft centered (north-up) as its
               position steps in.

  User pan (MapLibre dragstart) always releases to :free — the Apple
  Maps rule that makes the mode trustworthy. Our own ease-to settles
  must NOT free the camera, which is why we listen to dragstart and not
  moveend.

  ## Camera ownership and the fast track loop

  Entering follow is ONE `fly-to!` (center + focus-zoom). While that
  animation is in flight, new picture positions are BUFFERED — they must
  not `ease-to!`, or MapLibre cancels the fly mid-zoom and the reader is
  punished for a 1 Hz feeder. After moveend, any buffered position eases
  once; later steps ease as usual. ease-to! also refuses to run while
  the map is moving (seam guard).

  Lifecycle matches adsb.map.selection: attach! / detach! owned by the
  map view, driven by a reagent track! outside React so picture churn
  never re-renders the shell."
  (:require
    [adsb.map.maplibre :as maplibre]
    [re-frame.core :as rf]
    [reagent.core :as r]))

(defn following?
  "True when camera mode is follow. Nil / anything else is free."
  [mode]
  (= :follow mode))

(defn should-track?
  "Pure gate: follow only when the mode is follow AND the selected
  aircraft has a position to center on."
  [mode aircraft]
  (boolean (and (following? mode)
                aircraft
                (:aircraft/position aircraft))))

(defn attach!
  "Start following the selected aircraft when app-db says so. Returns a
  handle for detach!. Idempotent against double-attach only in the sense
  that the map view owns a single handle.

  Camera ownership: the FIRST frame of a follow bout is `fly-to!` (center
  + focus-zoom floor). Later position steps are quiet `ease-to!` only,
  and never while the entry fly is still moving."
  [m]
  (let [!disposed (atom false)
        !last-pos (atom nil)
        !entering (atom false)   ; true while entry fly-to is in flight
        !pending  (atom nil)     ; latest pos while entering (no ease yet)
        selected  (rf/subscribe [:aircraft/selected])
        mode      (rf/subscribe [:map/camera-mode])
        track     (r/track!
                    (fn []
                      (when-not @!disposed
                        (let [mode* @mode
                              ac    @selected]
                          (if (should-track? mode* ac)
                            (let [pos (:aircraft/position ac)]
                              (cond
                                ;; Enter follow: pull in to inspect zoom.
                                (nil? @!last-pos)
                                (do (reset! !last-pos pos)
                                    (reset! !entering true)
                                    (reset! !pending nil)
                                    (maplibre/fly-to! m pos))

                                ;; Entry fly still running: buffer only.
                                @!entering
                                (reset! !pending pos)

                                ;; Settled follow: re-center on steps.
                                (not= pos @!last-pos)
                                (do (reset! !last-pos pos)
                                    (maplibre/ease-to! m pos))))
                            (do (reset! !last-pos nil)
                                (reset! !entering false)
                                (reset! !pending nil)))))))]
    ;; moveend ends the entry fly. Flush a buffered track step so a 1 Hz
    ;; picture during fly-in is not lost, and never eases mid-zoom.
    (maplibre/on-move! m
                       (fn []
                         (when-not @!disposed
                           (when @!entering
                             (reset! !entering false)
                             (when-let [pos @!pending]
                               (reset! !pending nil)
                               (when (not= pos @!last-pos)
                                 (reset! !last-pos pos)
                                 (maplibre/ease-to! m pos)))))))
    (maplibre/on-drag-start! m
                             (fn []
                               (when-not @!disposed
                                 (reset! !entering false)
                                 (reset! !pending nil)
                                 (rf/dispatch [:map/user-pan]))))
    {:track track :!disposed !disposed}))

(defn detach!
  "Stop the track and ignore further drag callbacks."
  [{:keys [track !disposed]}]
  (reset! !disposed true)
  (some-> track r/dispose!))
