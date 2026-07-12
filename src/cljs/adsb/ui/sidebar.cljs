(ns adsb.ui.sidebar
  "The aircraft list — Reagent chrome listing the current picture as a
  clickable, sortable, filterable roster beside the map. Like the detail
  panel, this genuinely IS React territory: it is low-churn compared to the
  map (a roster of a few-to-few-hundred rows, not sixty setData calls a
  second), so a component tree is right. The PLANES never come through here;
  they live in the MapLibre GeoJSON source. This is their table of contents.

  ONE SOURCE OF TRUTH. The list derives from the very same :aircraft/picture
  subscription the map reads — filtered and sorted, never re-fetched. Select
  the map's plane and its row lights up; select a row and the map's plane is
  selected. Same app-db, same identity (an icao string), no second copy of
  the sky.

  BOUNDARY 4 (docs/validation-boundaries.md). Every callsign and icao here
  arrived off unauthenticated radio. It is well-TYPED, not trustWORTHY. We
  render each as plain hiccup text and let Reagent escape it — a hostile
  callsign is a string on the screen, never markup.

  ABSENT IS NOT ZERO. Altitude is three-state: \"ground\", a real number, or
  the em-dash for a sky that never reported one. A missing altitude is never
  coerced to 0 — and it sorts LAST, never as a phantom sea-level plane at the
  top of the list.

  Styling is a NEUTRAL PLACEHOLDER: class names are the re-skin hooks and the
  visual pass is bead adsb-dgb.5. Structure, not a look.

  DISTANCE SORT, DELIBERATELY OMITTED. The acceptance asked for a
  distance sort; a distance needs a reference point, and the honest one — the
  receiver's own position — is PRIVATE by design and never reaches the
  browser. The only other reference is the map's current center, which would
  demand map-state plumbing this bead does not own and would still be a
  distance-from-where-you-happen-to-be-looking, not from the antenna. So we
  sort by altitude, callsign, and seen-age instead — three facts every
  aircraft actually carries. See the close reason for the full rationale."
  (:require
    [adsb.aircraft :as aircraft]
    [adsb.enrich :as enrich]
    [adsb.ui.alert :as alert]
    [clojure.string :as str]
    [re-frame.core :as rf]))

;; The em-dash stands in for every fact the sky never reported.
(def ^:const em-dash "—")

;; ---------------------------------------------------------------------
;; Presentation — pure. Absent becomes the em-dash; everything else is
;; stringified and handed to hiccup, which escapes it.

(defn- callsign-label
  "The row's headline: the callsign when the sky gave one, otherwise the
  bare icao. Never blank — an aircraft heard but unnamed is still a row."
  [{:aircraft/keys [callsign icao]}]
  (or callsign icao))

(defn- altitude-label
  "Altitude for a row, three-state: \"ground\" on the tarmac, the number
  when the sky reported one, the em-dash when it never did. A missing
  altitude is never coerced to 0."
  [{:aircraft/keys [on-ground? altitude-ft]}]
  (cond
    on-ground?          "ground"
    (some? altitude-ft) (str altitude-ft)
    :else               em-dash))

(defn- speed-label
  "Ground speed in knots as the row's secondary fact — a static number the
  aircraft already carries, so it needs no UI clock. Absent dashes."
  [{:aircraft/keys [ground-speed-kt]}]
  (if (some? ground-speed-kt) (str ground-speed-kt) em-dash))

;; ---------------------------------------------------------------------
;; Sorting — pure. The one law across every mode: a fact the sky never
;; reported cannot be ranked against ones it did, so it sinks to the
;; BOTTOM rather than masquerading as a zero at the top. Each sort key is
;; a [absent?-flag value] vector so sort-by's ascending order lands absent
;; last for free.

(defn- altitude-sort-key
  "Sort key for altitude, high→low with absent last. Ground is a floor
  below every airborne altitude but still present, so it sits just above
  the never-reported tail."
  [{:aircraft/keys [on-ground? altitude-ft]}]
  (let [v (cond
            (some? altitude-ft) altitude-ft
            on-ground?          ##-Inf   ; present, but below all airborne
            :else               nil)]    ; never reported → absent
    [(if (nil? v) 1 0) (if v (- v) 0)]))

(defn- seen-sort-key
  "Sort key for seen-age, freshest→stalest with never-timestamped last.
  A larger :aircraft/seen-at-ms is a more recent hearing, so we sort by
  its negation to put the freshest first."
  [{:aircraft/keys [seen-at-ms]}]
  [(if seen-at-ms 0 1) (if seen-at-ms (- seen-at-ms) 0)])

(defn sort-aircraft
  "Order a seq of aircraft by `sort-key` — :altitude (default), :callsign,
  or :seen. Callsign is A→Z, case-folded, and always present (icao
  fallback). Altitude and seen place their absent values last."
  [sort-key aircraft]
  (case sort-key
    :callsign (sort-by (comp str/lower-case callsign-label) aircraft)
    :seen     (sort-by seen-sort-key aircraft)
    (sort-by altitude-sort-key aircraft)))

;; ---------------------------------------------------------------------
;; State — sort mode and filter toggles under :ui/* keys. Defaults live in
;; the subs (altitude sort, filters off), so default-db needs no seed.

(rf/reg-event-db
  :ui/sidebar-sort
  (fn [db [_ sort-key]]
    (assoc db :ui/sidebar-sort sort-key)))

(rf/reg-event-db
  :ui/sidebar-toggle-positioned
  (fn [db _]
    (update db :ui/sidebar-positioned-only? not)))

(rf/reg-event-db
  :ui/sidebar-toggle-airborne
  (fn [db _]
    (update db :ui/sidebar-airborne-only? not)))

(rf/reg-sub
  :ui/sidebar-sort
  (fn [db _]
    (:ui/sidebar-sort db :altitude)))

(rf/reg-sub
  :ui/sidebar-positioned-only?
  (fn [db _]
    (boolean (:ui/sidebar-positioned-only? db))))

(rf/reg-sub
  :ui/sidebar-airborne-only?
  (fn [db _]
    (boolean (:ui/sidebar-airborne-only? db))))

;; The roster: the same :aircraft/picture the map reads, filtered by the
;; toggles and ordered by the sort mode. One source of truth — this never
;; re-fetches the sky, it only views it.
(rf/reg-sub
  :aircraft/sidebar-list
  :<- [:aircraft/picture]
  :<- [:ui/sidebar-sort]
  :<- [:ui/sidebar-positioned-only?]
  :<- [:ui/sidebar-airborne-only?]
  (fn [[picture sort-key positioned-only? airborne-only?] _]
    (->> (cond->> (vals picture)
           ;; Never reported a position — heard, never located.
           positioned-only? (filter aircraft/positioned?)
           ;; on-ground? is true-or-absent, so airborne is simply not-on-ground.
           airborne-only?   (remove :aircraft/on-ground?))
         (sort-aircraft sort-key))))

;; ---------------------------------------------------------------------
;; Interaction. Sort/filter handlers are module-level so the roster's
;; per-second re-render never allocates a fresh closure for them (a new
;; fn each render defeats React reconciliation — Auteur's cardinal
;; performance rule).

(defn- sort-by-altitude! [_] (rf/dispatch [:ui/sidebar-sort :altitude]))
(defn- sort-by-callsign! [_] (rf/dispatch [:ui/sidebar-sort :callsign]))
(defn- sort-by-seen!     [_] (rf/dispatch [:ui/sidebar-sort :seen]))
(defn- toggle-positioned! [_] (rf/dispatch [:ui/sidebar-toggle-positioned]))
(defn- toggle-airborne!   [_] (rf/dispatch [:ui/sidebar-toggle-airborne]))

(defn- on-list-click!
  "One delegated click handler for the whole list, so N rows cost ONE
  handler, not N closures rebuilt every second. It walks up from the
  clicked element to the nearest row and dispatches the map's existing
  [:aircraft/select icao] contract — the same event a plane click fires."
  [event]
  (when-let [icao (some-> (.-target event)
                          (.closest "[data-icao]")
                          (.getAttribute "data-icao"))]
    (rf/dispatch [:aircraft/select icao])))

;; ---------------------------------------------------------------------
;; Components — kebab-case functions returning hiccup.

(defn- sort-button [label sort-key current on-click]
  [:button.adsb-sort-btn
   {:type         "button"
    :class        (when (= sort-key current) "adsb-sort-btn-active")
    :aria-pressed (= sort-key current)
    :on-click     on-click}
   label])

(defn- sort-controls [current]
  [:div.adsb-sidebar-sort {:role "group" :aria-label "Sort aircraft"}
   [sort-button "Alt"  :altitude current sort-by-altitude!]
   [sort-button "Call" :callsign current sort-by-callsign!]
   [sort-button "Seen" :seen     current sort-by-seen!]])

(defn- filter-button [label active? on-click]
  [:button.adsb-filter-btn
   {:type         "button"
    :class        (when active? "adsb-filter-btn-active")
    :aria-pressed (boolean active?)
    :on-click     on-click}
   label])

(defn- filter-controls [positioned-only? airborne-only?]
  [:div.adsb-sidebar-filters {:role "group" :aria-label "Filter aircraft"}
   [filter-button "Positioned" positioned-only? toggle-positioned!]
   [filter-button "Airborne"   airborne-only?   toggle-airborne!]])

(defn- badges
  "Status flags for a row — emergency squawk, suspect position, MLAT — each
  shown only when true. Emergency uses the promoted domain predicate
  (adsb.aircraft/emergency-kind) and names the MEANING in words, not a bare
  'EMG': a hijacking and a radio failure are not the same row. `role=status`
  mirrors the detail panel's badges."
  [aircraft*]
  (let [{:aircraft/keys [position-suspect? mlat?]} aircraft*
        emergency-kind (aircraft/emergency-kind aircraft*)]
    (when (or emergency-kind position-suspect? mlat?)
      [:span.adsb-row-badges
       (when emergency-kind
         [:span.adsb-badge.adsb-badge-emergency {:role "status"}
          (alert/emergency-words emergency-kind)])
       (when position-suspect?
         [:span.adsb-badge.adsb-badge-suspect {:role "status"} "SUS"])
       (when mlat?
         [:span.adsb-badge.adsb-badge-mlat {:role "status"} "MLAT"])])))

(defn- aircraft-row
  "One roster row. `role=option` + `aria-selected` model the list as a
  single-select listbox; `data-icao` is what the delegated click reads.
  Every string is feeder-origin and rendered as escaped hiccup text.

  The type code (adsb.enrich) is shown only when it is ALREADY cached — the
  row reads the enrichment cache but never triggers a fetch, so a full roster
  costs no network. It appears for aircraft whose shard a panel selection has
  already warmed, and is simply omitted otherwise: cheap, or absent."
  [aircraft selected-icao shards]
  (let [icao      (:aircraft/icao aircraft)
        selected? (= icao selected-icao)
        type-code (enrich/type-code (enrich/record-for shards icao))]
    [:li.adsb-row
     {:role          "option"
      :data-icao     icao
      :data-testid   (str "row:" icao)
      :aria-selected selected?
      :class         (when selected? "adsb-row-selected")
      :tab-index     0}
     [:span.adsb-row-call (callsign-label aircraft)]
     (when type-code
       [:span.adsb-row-type {:data-testid (str "row-type:" icao)} type-code])
     [:span.adsb-row-alt {:data-testid (str "row-alt:" icao)}
      (altitude-label aircraft)]
     [:span.adsb-row-speed (speed-label aircraft)]
     (badges aircraft)]))

(defn- aircraft-list [aircraft selected-icao shards]
  [:ul.adsb-sidebar-list
   {:role "listbox" :aria-label "Aircraft" :on-click on-list-click!}
   (for [a aircraft]
     ^{:key (:aircraft/icao a)} [aircraft-row a selected-icao shards])])

(defn sidebar
  "The aircraft roster, mounted permanently in the app root. A form-2
  component: subscribe once to the derived list (which already folds in the
  filter and sort state), deref per render. Always renders — an empty sky is
  a legitimate state and says so."
  []
  (let [roster    (rf/subscribe [:aircraft/sidebar-list])
        selected  (rf/subscribe [:aircraft/selected-icao])
        sort-mode (rf/subscribe [:ui/sidebar-sort])
        pos-only  (rf/subscribe [:ui/sidebar-positioned-only?])
        air-only  (rf/subscribe [:ui/sidebar-airborne-only?])
        ;; One subscription for the whole cache; rows do a pure lookup. The
        ;; sidebar never fetches — it only shows type codes already warmed by
        ;; a panel selection (adsb.enrich).
        shards    (rf/subscribe [:enrich/shards])]
    (fn []
      (let [aircraft @roster]
        [:aside.adsb-sidebar {:aria-label "Aircraft list"}
         [:div.adsb-sidebar-header
          [:span.adsb-sidebar-count {:data-testid "sidebar-count"}
           (str (count aircraft) " aircraft")]]
         [sort-controls @sort-mode]
         [filter-controls @pos-only @air-only]
         (if (seq aircraft)
           [aircraft-list aircraft @selected @shards]
           [:p.adsb-sidebar-empty "No aircraft"])]))))
