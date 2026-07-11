(ns adsb.reagent-rtl-test
  "Proves the browser harness can render a Reagent component and drive a
  genuine React event through it under React Testing Library. The full
  aircraft-panel component lands in adsb-2yu.1; until then this exercises a
  self-contained counter so the harness itself is what's under test."
  (:require
    ["@testing-library/react" :as rtl]
    [cljs.test :refer-macros [deftest is use-fixtures async]]
    [reagent.core :as r]))

;; Without cleanup, components from the previous test stay mounted and the
;; queries below find two matches (or the wrong one). Run it after each test.
(use-fixtures :each {:after rtl/cleanup})

(defn counter
  "A Reagent component with local state and a real click handler."
  []
  (let [n (r/atom 0)]
    (fn []
      [:div
       [:span {:data-testid "count"} @n]
       [:button {:on-click #(swap! n inc)} "increment"]])))

(deftest counter-renders
  (rtl/render (r/as-element [counter]))
  (is (= "0" (.-textContent (.getByTestId rtl/screen "count")))
      "the component renders its initial state into the DOM"))

(deftest counter-responds-to-a-react-event
  (rtl/render (r/as-element [counter]))
  (.click rtl/fireEvent (.getByRole rtl/screen "button" #js {:name "increment"}))
  ;; RTL 16 renders through a React 18 root, which commits the reagent-triggered
  ;; re-render asynchronously — so we poll the DOM with an async `findBy` query
  ;; rather than asserting against the frame that has not been committed yet.
  (async done
    (-> (.findByText rtl/screen "1")
        (.then (fn [el]
                 (is (= "1" (.-textContent el))
                     "a real DOM click runs the handler and the view re-renders")))
        (.catch (fn [err]
                  (is false (str "the counter never reached 1: " err))))
        (.finally done))))
