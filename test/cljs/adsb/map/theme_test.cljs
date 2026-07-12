(ns adsb.map.theme-test
  "The edition switch, proven at its seam. `match-media` is redef'd to a
  scripted MediaQueryList, so the tests drive `prefers-color-scheme`
  without an OS in the loop: the query decides the theme, `sync!` aligns
  the ratom, and `watch-system!` registers/unregisters a real listener."
  (:require
    [adsb.map.theme :as theme]
    [cljs.test :refer-macros [deftest is testing use-fixtures]]))

(use-fixtures :each
  {:after (fn [] (theme/set-theme! :day))})

(defn- fake-mql
  "A MediaQueryList double: scripted `matches`, captured listeners."
  [matches? !added !removed]
  (js-obj "matches" matches?
          "addEventListener" (fn [_type handler] (reset! !added handler))
          "removeEventListener" (fn [_type handler] (reset! !removed handler))))

(deftest system-theme-follows-the-media-query
  (testing "dark scheme matching means the night edition"
    (with-redefs [theme/match-media (fn [q]
                                      (is (= theme/dark-scheme-query q)
                                          "asks about the dark scheme, nothing else")
                                      (fake-mql true (atom nil) (atom nil)))]
      (is (= :night (theme/system-theme)))))
  (testing "no dark preference means the day edition"
    (with-redefs [theme/match-media (fn [_] (fake-mql false (atom nil) (atom nil)))]
      (is (= :day (theme/system-theme))))))

(deftest sync!-aligns-the-ratom-with-the-system
  (with-redefs [theme/match-media (fn [_] (fake-mql true (atom nil) (atom nil)))]
    (is (= :night (theme/sync!)) "returns the theme it settled on")
    (is (= :night @theme/!theme) "and the chrome-visible ratom followed")))

(deftest watch-system!-delivers-flips-and-unlistens
  (let [!added   (atom nil)
        !removed (atom nil)
        mql      (fake-mql true !added !removed)
        !seen    (atom [])]
    (with-redefs [theme/match-media (fn [_] mql)]
      (let [unlisten (theme/watch-system! #(swap! !seen conj %))]
        (is (some? @!added) "a change listener was registered")

        (testing "a change event delivers the mql's CURRENT edition"
          (@!added (js-obj))
          (is (= [:night] @!seen))
          (set! (.-matches mql) false)
          (@!added (js-obj))
          (is (= [:night :day] @!seen)))

        (testing "unlisten removes the very handler that was added"
          (unlisten)
          (is (identical? @!added @!removed)))))))
