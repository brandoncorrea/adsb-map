(ns adsb.ui.alert
  "The emergency ribbon — the loudest piece of chrome the app owns, and it
  earns it. A plane squawking 7500/7600/7700 is a human being having the
  worst day of their life; the UI must not whisper it. While ANY aircraft in
  the picture is squawking a distress code, a banner sits at the very top of
  the shell, directly under the header, naming every emergency: who
  (callsign, or the bare icao when the sky gave no callsign), the raw squawk,
  and — the part a number alone can't carry — the MEANING in plain words.

  ACCESSIBILITY. The banner carries `role=alert` so assistive tech announces
  it the moment it appears. Colour is never the only channel: the meaning is
  spelled out in text.

  CLICK TO SELECT. Each alert is a button on the map's existing
  [:aircraft/select icao] contract — the same event a plane click or a
  sidebar row fires — so clicking an alert opens that aircraft's detail
  panel. One delegated handler serves the whole banner: emergencies are rare,
  but a fresh closure per render is still a habit we don't keep.

  MULTIPLE EMERGENCIES: WE STACK, WE DON'T CYCLE. A cycling banner hides
  emergencies behind a timer — the second-worst day in the sky waits its turn
  to be seen. Unacceptable. Every active emergency gets its own row, ordered
  stably by icao (the :aircraft/emergencies sub sorts them) so the list never
  reshuffles under the reader between frames.

  BOUNDARY 4 (docs/validation-boundaries.md). Callsign, icao, and squawk all
  arrived off unauthenticated radio. They are well-TYPED, not trustWORTHY. We
  render each as escaped hiccup text — a hostile callsign is a string on the
  screen, never markup.

  The words are the ONLY thing this namespace adds to the domain's notion of
  an emergency: adsb.aircraft knows the KIND (:hijack/:radio-failure/
  :general); the human copy is presentation and lives here, shared with the
  sidebar and detail-panel badges.

  Dressed by the visual pass (adsb-dgb.5) as the direction's NOTAM strip
  (§7): a red field under the header with a stamped NOTAM tab beside the
  rows — grave, drawn once, and it NEVER blinks (app.css owns the look;
  no animation touches anything under .adsb-alerts)."
  (:require
    [adsb.aircraft :as aircraft]
    [re-frame.core :as rf]))

(def ^:const kind-words
  "Emergency KIND -> the plain-language meaning the chrome renders. The
  domain speaks in keywords; a human reads words. Rendered soberly — a
  hijacking is stated, not sensationalized."
  {:hijack        "hijacking"
   :radio-failure "radio failure"
   :general       "general emergency"})

(defn emergency-words
  "The plain-language meaning for an emergency `kind`, or nil for a kind we
  don't recognize. The shared source of the emergency copy — the ribbon and
  both badge surfaces read it here so they can never disagree."
  [kind]
  (get kind-words kind))

;; ---------------------------------------------------------------------
;; Interaction. One delegated handler for the whole banner: it walks up from
;; the clicked element to the nearest alert and dispatches the map's existing
;; [:aircraft/select icao] contract — the same event a plane or a sidebar row
;; fires. Module-level, so no fresh closure is allocated per render.

(defn- on-alert-click!
  [event]
  (when-let [icao (some-> (.-target event)
                          (.closest "[data-icao]")
                          (.getAttribute "data-icao"))]
    (rf/dispatch [:aircraft/select icao])))

;; ---------------------------------------------------------------------
;; Components — kebab-case functions returning hiccup.

(defn- alert-item
  "One emergency, as a clickable button. Names the aircraft, its squawk, and
  the meaning in words; `data-icao` is what the delegated click reads. Every
  string is feeder-origin and rendered as escaped text (Boundary 4)."
  [aircraft*]
  (let [{:aircraft/keys [icao callsign squawk]} aircraft*
        name  (or callsign icao)
        words (emergency-words (aircraft/emergency-kind aircraft*))]
    [:button.adsb-alert
     {:type        "button"
      :data-icao   icao
      :data-testid (str "alert:" icao)
      ;; A single spoken sentence for assistive tech — the visual row splits
      ;; the same facts across spans for the sighted reader.
      :aria-label  (str "Emergency: " name " squawking " squawk
                        ", " words)}
     [:span.adsb-alert-name name]
     [:span.adsb-alert-squawk squawk]
     [:span.adsb-alert-meaning words]]))

(defn alert-ribbon
  "The emergency banner, mounted permanently in the app root directly under
  the header. Renders NOTHING while the sky is calm — an empty banner is
  clutter — and the instant an emergency clears from the picture it vanishes.
  A form-2 component: subscribe once, deref per render. `role=alert` makes
  its appearance an announcement, not a quiet DOM change."
  []
  (let [emergencies (rf/subscribe [:aircraft/emergencies])]
    (fn []
      (let [alerts @emergencies]
        (when (seq alerts)
          [:div.adsb-alerts
           {:role        "alert"
            :aria-label  "Emergency aircraft"
            :data-testid "alert-ribbon"
            :on-click    on-alert-click!}
           ;; The stamped NOTAM tab — decoration for the sighted reader
           ;; (the strip's form), hidden from assistive tech, which gets
           ;; each row's full aria-label sentence instead.
           [:span.adsb-alert-stamp {:aria-hidden true} "NOTAM"]
           (into [:div.adsb-alert-rows]
                 (for [a alerts]
                   ^{:key (:aircraft/icao a)} [alert-item a]))])))))
