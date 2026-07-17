(ns adsb.stream.source-test
  (:require [adsb.stream.source :as source]
            [clojure.test :refer-macros [deftest is testing]]))

(defn- fake-event-source
  "A stand-in EventSource that records the listener wiring connect! performs,
  so a test can fire a named event and observe which callback it reaches.
  connect! `new`s up js/EventSource directly — there is no injection seam — so
  the object it constructs is the only place to intercept the mapping."
  []
  (let [listeners (atom {})]
    #js {:listeners        listeners
         :readyState       1
         :addEventListener (fn [name cb] (swap! listeners assoc name cb))
         :close            (fn [] (this-as this (set! (.-closed? this) true)))}))

(defn- with-fake-event-source
  "Run `f` with js/EventSource replaced by a constructor that always yields
  `fake`, restoring the real global afterward. A constructor returning an
  object makes `new` hand back that object, so `(js/EventSource. url)` inside
  connect! resolves to `fake`."
  [fake f]
  (let [real (.-EventSource js/window)]
    (set! (.-EventSource js/window) (fn [_url] fake))
    (try (f) (finally (set! (.-EventSource js/window) real)))))

(defn- record [calls key]
  (fn [& args] (swap! calls conj (into [key] args))))

(deftest connect!-maps-each-server-event-to-its-callback
  (let [fake  (fake-event-source)
        calls (atom [])
        conn  (with-fake-event-source fake
                (fn []
                  (source/connect!
                    "/api/stream"
                    {:on-open     (record calls :open)
                     :on-frame    (record calls :frame)
                     :on-aircraft (record calls :aircraft)
                     :on-stats    (record calls :stats)
                     :on-config   (record calls :config)
                     :on-error    (record calls :error)})))
        listeners @(.-listeners fake)
        fire  (fn [event data] ((get listeners event) #js {:data data}))]

    (testing "onopen routes to :on-open carrying no payload — the (re)connect
              signal is only that the stream is live, so the callback is
              invoked with no arguments"
      (reset! calls [])
      ((.-onopen fake) #js {})
      (is (= [[:open]] @calls)))

    (testing "snapshot and update both route to :on-frame with the event's data
              string — both are full pictures and connect! wires them to one
              handler, so the reader never distinguishes them"
      (reset! calls [])
      (fire "snapshot" "SNAP")
      (fire "update" "UPD")
      (is (= [[:frame "SNAP"] [:frame "UPD"]] @calls)))

    (testing "the aircraft event routes to :on-aircraft with its data — a single
              merged aircraft, kept off the :on-frame full-picture path so the
              stream layer can merge it by icao (adsb-jpf)"
      (reset! calls [])
      (fire "aircraft" "AC")
      (is (= [[:aircraft "AC"]] @calls)))

    (testing "the stats event routes to :on-stats with its data — session stats
              and feeder health travel their own event so they never land in
              the aircraft picture"
      (reset! calls [])
      (fire "stats" "STATS")
      (is (= [[:stats "STATS"]] @calls)))

    (testing "the config event routes to :on-config with its data — the one
              boot-config frame, kept distinct from the snapshot that follows it"
      (reset! calls [])
      (fire "config" "CFG")
      (is (= [[:config "CFG"]] @calls)))

    (testing "onerror routes to :on-error with the source's ready state as a
              keyword, not the raw event — :connecting means the browser is
              auto-retrying, ours to leave alone"
      (reset! calls [])
      (set! (.-readyState fake) 0)
      ((.-onerror fake) #js {})
      (is (= [[:error :connecting]] @calls)))

    (testing "a CLOSED ready state reaches :on-error as :closed — the source is
              dead and the reconnect is ours to drive, the distinction the whole
              seam exists to surface"
      (reset! calls [])
      (set! (.-readyState fake) 2)
      ((.-onerror fake) #js {})
      (is (= [[:error :closed]] @calls)))

    (testing "the returned Connection's close! tears down the EventSource — the
              one capability the client needs, to cancel the browser's built-in
              auto-reconnect and take it over"
      (source/close! conn)
      (is (true? (.-closed? fake))))))
