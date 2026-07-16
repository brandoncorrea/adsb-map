(ns adsb.reagent-rtl-test
  (:require ["@testing-library/react" :as rtl]
            [clojure.test :refer-macros [deftest is use-fixtures async]]
            [reagent.core :as r]))

(use-fixtures :each {:after rtl/cleanup})

(defn counter []
  (let [n (r/atom 0)]
    (fn []
      [:div
       [:span {:data-testid "count"} @n]
       [:button {:on-click #(swap! n inc)} "increment"]])))

(deftest counter-renders
  (rtl/render (r/as-element [counter]))
  (is (= "0" (.-textContent (.getByTestId rtl/screen "count")))))

(deftest counter-responds-to-a-react-event
  (rtl/render (r/as-element [counter]))
  (.click rtl/fireEvent (.getByRole rtl/screen "button" (js-obj "name" "increment")))
  (async done
    (-> (.findByText rtl/screen "1")
        (.then (fn [el]
                 (is (= "1" (.-textContent el)))))
        (.catch (fn [err]
                  (is false (str "the counter never reached 1: " err))))
        (.finally done))))
