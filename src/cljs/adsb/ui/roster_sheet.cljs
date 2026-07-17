(ns adsb.ui.roster-sheet
  "The phone bottom-sheet state machine for the roster drawer.

  Pure snap geometry (sheet-heights, height-fraction->sheet, ease-out-cubic)
  and the pointer-gesture DOM edge that drives the live drag and settle.
  adsb.ui.roster owns the query/filter/sort/rows and the component that
  consumes this."
  (:require [adsb.corejs :as cjs]
            [clojure.math :as math]
            [re-frame.core :as rf]))

(def ^:const sheet-states [:closed :half :full])

;; Must match the CSS snap heights (--roster-sheet-h/--roster-full-h/
;; --roster-rail-h in adsb.css.phone's roster-sheet block) or the drag settle
;; animation lands on the wrong height.
(def ^:const sheet-heights
  {:closed 0.0
   :half   0.52
   :full   0.92})

(def ^:const closed-rail-px 48)

(defn- closed-sheet-min-px []
  (+ closed-rail-px (cjs/css-px "--safe-bottom")))

(def ^:const drag-velocity-threshold 0.45)
(def ^:const tap-slop-px 6)
(def ^:const settle-ms 420)

(defn ease-out-cubic [t]
  (let [u (- 1.0 (max 0.0 (min 1.0 t)))]
    (- 1.0 (math/pow u 3.0))))

(defn sheet-open? [sheet] (not= :closed sheet))

(defn height-fraction->sheet [frac velocity]
  (let [frac*   (max 0.0 (min 1.0 frac))
        v       (or velocity 0.0)
        nearest (apply min-key
                       (fn [s] (abs (- frac* (get sheet-heights s))))
                       sheet-states)]
    (cond
      (> v drag-velocity-threshold)
      (let [idx (.indexOf sheet-states nearest)]
        (nth sheet-states (min (dec (count sheet-states)) (inc idx))))

      (< v (- drag-velocity-threshold))
      (let [idx (.indexOf sheet-states nearest)]
        (nth sheet-states (max 0 (dec idx))))

      :else nearest)))

(defn next-sheet [sheet]
  (case sheet
    :closed :half
    :half :full
    :full :closed
    :half))

(def ^:const default-sheet :closed)
(def ^:const default-open-sheet :half)

(rf/reg-event-db
  :roster/set-sheet
  (fn [db [_ sheet]]
    (assoc db :roster/sheet (or sheet default-open-sheet))))

(rf/reg-event-db
  :roster/toggle
  (fn [db _]
    (let [sheet (get db :roster/sheet default-sheet)]
      (assoc db :roster/sheet
                (if (sheet-open? sheet) :closed default-open-sheet)))))

(rf/reg-event-db
  :roster/cycle
  (fn [db _]
    (assoc db :roster/sheet
              (next-sheet (get db :roster/sheet default-sheet)))))

(rf/reg-sub
  :roster/sheet
  (fn [db _]
    (get db :roster/sheet default-sheet)))

(defn- viewport-height [] (or (some-> js/window .-innerHeight) 1))

(defn sheet-height-px [sheet]
  (let [vh   (viewport-height)
        safe (cjs/css-px "--safe-bottom")
        frac (get sheet-heights sheet 0.52)]
    (case sheet
      :closed (+ closed-rail-px safe)
      :full (min (+ (* frac vh) safe)
                 (- vh (cjs/css-px "--safe-top")))
      (+ (* frac vh) safe))))

(defn- pointer-y [e]
  (or (.-clientY e)
      (some-> (.-touches e) (aget 0) .-clientY)
      0))

(defn roster-el [] (cjs/select "[data-testid=\"roster\"]"))

(defn set-sheet-height-px! [el h]
  (when el
    (let [px (str h "px")]
      (set! (-> el .-style .-height) px)
      (set! (-> el .-style .-maxHeight) px))))

(defn- clear-sheet-height! [el]
  (when el
    (set! (-> el .-style .-height) "")
    (set! (-> el .-style .-maxHeight) "")))

(defn cancel-settle! [!drag !raf !live-h]
  (when-let [id @!raf]
    (cjs/cancel-animation id)
    (reset! !raf nil))
  (when (or @!live-h (:settling? @!drag))
    (clear-sheet-height! (roster-el))
    (reset! !live-h nil)
    (when (:settling? @!drag)
      (reset! !drag nil))))

(defn abandon-gesture! [!drag !gesture !live-h]
  (reset! !gesture nil)
  (reset! !live-h nil)
  (clear-sheet-height! (roster-el))
  (reset! !drag nil))

(defn- drag-surface? [e]
  (let [target (.-target e)]
    (not (and target
              (.-closest target)
              (cjs/closest target ".adsb-roster-body")))))

(defn on-sheet-pointer-down! [!drag !gesture !live-h !raf e]
  (when (and (cjs/phone-stance?)
             (drag-surface? e)
             (or (nil? (.-button e)) (zero? (.-button e))))
    (cancel-settle! !drag !raf !live-h)
    (let [y0 (pointer-y e)
          el (.-currentTarget e)
          h0 (or (.-offsetHeight el) closed-rail-px)]
      (reset! !gesture {:active?  true
                        :start-y  y0
                        :start-h  h0
                        :last-y   y0
                        :last-t   (cjs/performance-now)
                        :velocity 0
                        :height   h0})
      (when (.-setPointerCapture el)
        (js-invoke el "setPointerCapture" (.-pointerId e)))
      (cjs/prevent-default e))))

(defn on-sheet-pointer-move! [!drag !gesture !live-h e]
  (when-let [{:keys [active? start-y start-h last-y last-t]} @!gesture]
    (when active?
      (let [y   (pointer-y e)
            t   (cjs/performance-now)
            dy  (- start-y y)
            vh  (viewport-height)
            top (- vh (cjs/css-px "--safe-top"))
            h   (max (closed-sheet-min-px) (min top (+ start-h dy)))
            dt  (max 1 (- t last-t))
            vy  (/ (- last-y y) dt)]
        (swap! !gesture assoc
               :last-y y
               :last-t t
               :velocity vy
               :height h)
        (when (and (not (:moved? @!drag))
                   (> (abs (- start-y y)) tap-slop-px))
          (swap! !drag assoc :moved? true))
        (when (:moved? @!drag)
          (reset! !live-h h)
          (set-sheet-height-px! (roster-el) h)
          (cjs/prevent-default e))))))

(defn- settle-sheet-after-drag! [!drag !raf !live-h release-h target-sheet]
  (cancel-settle! !drag !raf !live-h)
  (let [from (double (or release-h (sheet-height-px target-sheet)))
        to   (double (sheet-height-px target-sheet))
        dist (abs (- to from))
        el   (roster-el)]
    (cond
      (or (cjs/prefers-reduced-motion?) (< dist 0.5) (nil? el))
      (do (clear-sheet-height! el)
          (reset! !live-h nil)
          (reset! !drag nil))

      :else
      (let [t0 (cjs/performance-now)]
        (reset! !live-h from)
        (reset! !drag {:settling? true})
        (set-sheet-height-px! el from)
        (letfn [(frame [now]
                  (if-not (:settling? @!drag)
                    (reset! !raf nil)
                    (let [elapsed (- now t0)
                          p       (min 1.0 (/ elapsed settle-ms))
                          e       (ease-out-cubic p)
                          h       (+ from (* e (- to from)))]
                      (reset! !live-h h)
                      (set-sheet-height-px! el h)
                      (if (< p 1.0)
                        (reset! !raf (cjs/request-animation frame))
                        (do
                          (clear-sheet-height! el)
                          (reset! !live-h nil)
                          (reset! !raf nil)
                          (reset! !drag nil))))))]
          (reset! !raf (cjs/request-animation frame)))))))

(defn on-sheet-pointer-up! [!drag !gesture !live-h !raf !suppress-click sheet e]
  (when (:active? @!gesture)
    (let [{:keys [height velocity]} @!gesture
          moved? (boolean (:moved? @!drag))
          vh     (viewport-height)
          frac   (if (and height (pos? vh))
                   (/ height vh)
                   (get sheet-heights sheet 0.52))
          target (height-fraction->sheet frac velocity)]
      (reset! !gesture nil)
      (if moved?
        (do (reset! !suppress-click true)
            (rf/dispatch [:roster/set-sheet target])
            (settle-sheet-after-drag! !drag !raf !live-h height target))
        (abandon-gesture! !drag !gesture !live-h))
      (when (.-releasePointerCapture (.-currentTarget e))
        (try
          (js-invoke (.-currentTarget e) "releasePointerCapture" (.-pointerId e))
          (catch :default _))))))

(defn on-sheet-click! [!suppress-click e]
  (when (drag-surface? e)
    (if @!suppress-click
      (reset! !suppress-click false)
      (rf/dispatch (if (cjs/phone-stance?) [:roster/cycle] [:roster/toggle])))))
