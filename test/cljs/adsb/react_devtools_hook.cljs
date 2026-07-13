(ns adsb.react-devtools-hook
  "Stops react-dom's development build from advertising the React DevTools
  extension on every browser-test run.

  The nag is react-dom's own, not ours: at MODULE INIT the development bundle
  looks for `window.__REACT_DEVTOOLS_GLOBAL_HOOK__` — the global the browser
  extension installs — and, finding none behind a Chrome-shaped user agent,
  console.info's `Download the React DevTools ...`. Headless Chromium runs no
  extensions, so the hook is never there and the line printed every run.

  It has to be a PRELOAD. `react-dom` initializes the moment anything requires
  reagent, which the test runner does transitively through re-frame, so by the
  time any test namespace's first form evaluates the message has already been
  printed. A preload is the only code that runs early enough. It is registered
  on the :test build alone: in `bb dev` the suggestion is a fair one and the
  DevTools are genuinely worth installing, and the production bundle strips the
  whole branch, so neither of those builds wants this.

  `:isDisabled true` is what makes the stub safe rather than a lie: react-dom
  checks that flag first and returns immediately, so it never calls `inject`
  and never mistakes this for a real DevTools backend.")

;; Not private: nothing reads this, and a private var nobody reads is a lint
;; error. `defonce` is what makes it a once-per-page install rather than a
;; value — a hot reload of the preload must not re-stub a hook React has
;; already read.
(defonce devtools-hook-stubbed?
  (when (undefined? (.-__REACT_DEVTOOLS_GLOBAL_HOOK__ js/window))
    (set! (.-__REACT_DEVTOOLS_GLOBAL_HOOK__ js/window)
          #js {:isDisabled           true
               :supportsFiber        true
               :inject               (fn [_] nil)
               :onCommitFiberRoot    (fn [& _] nil)
               :onCommitFiberUnmount (fn [& _] nil)})
    true))
