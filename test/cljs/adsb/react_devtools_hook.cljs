(ns adsb.react-devtools-hook)

;; Not private: nothing reads this, and a private var nobody reads is a lint
;; error. `defonce` is what makes it a once-per-page install rather than a
;; value — a hot reload of the preload must not re-stub a hook React has
;; already read.
(defonce devtools-hook-stubbed?
  (when (undefined? (.-__REACT_DEVTOOLS_GLOBAL_HOOK__ js/window))
    (set! (.-__REACT_DEVTOOLS_GLOBAL_HOOK__ js/window)
          (js-obj
            "isDisabled" true
            "supportsFiber" true
            "inject" (fn [_])
            "onCommitFiberRoot" (fn [& _])
            "onCommitFiberUnmount" (fn [& _])))
    true))
