(ns adsb.map.theme-test
  (:require [adsb.corejs :as cjs]
            [adsb.map.theme :as theme]
            [clojure.test :refer-macros [deftest is testing use-fixtures]]))

(use-fixtures :each
  {:after (fn [] (theme/set-theme! :day))})

(defn- fake-mql [matches? !added !removed]
  (js-obj "matches" matches?
          "addEventListener" (fn [_type handler] (reset! !added handler))
          "removeEventListener" (fn [_type handler] (reset! !removed handler))))

(deftest system-theme-follows-the-media-query
  (testing "dark scheme matching means the night edition"
    (with-redefs [cjs/match-media (fn [q]
                                    (is (= theme/dark-scheme-query q))
                                    (fake-mql true (atom nil) (atom nil)))]
      (is (= :night (theme/system-theme)))))
  (testing "no dark preference means the day edition"
    (with-redefs [cjs/match-media (fn [_] (fake-mql false (atom nil) (atom nil)))]
      (is (= :day (theme/system-theme))))))

(deftest sync!-aligns-the-ratom-with-the-system
  (with-redefs [cjs/match-media (fn [_] (fake-mql true (atom nil) (atom nil)))]
    (is (= :night (theme/sync!)))
    (is (= :night @theme/!theme))))

(deftest watch-system!-delivers-flips-and-unlistens
  (let [!added   (atom nil)
        !removed (atom nil)
        mql      (fake-mql true !added !removed)
        !seen    (atom [])]
    (with-redefs [cjs/match-media (fn [_] mql)]
      (let [unlisten (theme/watch-system! #(swap! !seen conj %))]
        (is (some? @!added))

        (testing "a change event delivers the mql's CURRENT edition"
          (@!added (js-obj))
          (is (= [:night] @!seen))
          (set! (.-matches mql) false)
          (@!added (js-obj))
          (is (= [:night :day] @!seen)))

        (testing "unlisten removes the very handler that was added"
          (unlisten)
          (is (identical? @!added @!removed)))))))
