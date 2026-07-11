# Testing Setup — Clojure & ClojureScript

Stack-specific testing mechanics. For philosophy — naming, what to test, the cast of
fixtures — see `/docs/testing-standards.md`.

> **Status:** This describes the intended setup. The CLJS browser runner is tracked
> in a bead and has not been built yet. Where reality disagrees with this document,
> reality wins — fix the document.

## Framework

**`clojure.test` on the JVM, `cljs.test` in the browser.** Same API, selected by
reader conditional, so a `.cljc` domain test compiles and runs on both platforms
from one source file:

```clojure
(ns adsb.aircraft-test
  (:require
    [adsb.aircraft :as aircraft]
    [adsb.fixtures :as fixtures]
    #?(:clj  [clojure.test :refer [deftest testing is]]
       :cljs [cljs.test :refer-macros [deftest testing is]])))
```

That reader conditional is the only platform-specific line in a domain test. Write
it once, forget it.

## The Environment: A Real Browser, Not jsdom

CLJS tests run in **real headless Chromium, driven by Playwright.** Not jsdom.

This is a deliberate and slightly unusual choice, so it's worth stating why, because
someone will eventually try to "simplify" it:

**jsdom has no layout engine and no WebGL.** `getBoundingClientRect()` returns all
zeros, `canvas.getContext("webgl")` returns `nil`, and **MapLibre will not
initialize at all.** In a CRUD app, jsdom is a fine trade — fast, simple, close
enough. In a map app it quietly amputates the thing most worth testing, and it does
so without failing loudly. You'd get a green suite that proves nothing.

A real browser costs a few seconds of startup. Pay it.

The corollary, which matters just as much: **a real browser does not mean you should
render a real map.** See "The Map Seam" below.

## How the CLJS Suite Runs

Three moving parts.

**1. shadow-cljs compiles the tests into a page.** The `:browser-test` target
collects every namespace matching `-test$` and generates an HTML page that runs them.

```clojure
;; shadow-cljs.edn
{:source-paths ["src/cljc" "src/cljs" "test/cljc" "test/cljs"]

 :builds
 {:app  {:target     :browser
         :output-dir "resources/public/js"
         :modules    {:main {:init-fn adsb.core/init!}}}

  :test {:target    :browser-test
         :test-dir  "target/browser-test"
         :ns-regexp "-test$"
         :runner-ns adsb.test-runner}}}
```

**2. A custom runner ns signals the result to the page.** The default runner renders
results for a human to look at. We need a machine to read them, so the runner parks
the outcome somewhere Playwright can see it:

```clojure
(ns adsb.test-runner
  (:require
    [cljs.test :as t]
    [shadow.test :as st]))

(defmethod t/report [::t/default :end-run-tests] [m]
  (set! (.-adsbTestResult js/window)
        #js {:fail (:fail m) :error (:error m) :pass (:pass m)})
  (set! (.-adsbTestsDone js/window) true))

(defn ^:export init []
  (st/run-all-tests))
```

**3. Playwright drives the page and maps it to an exit code.** Load, wait for the
flag, read the counts, exit non-zero if anything failed:

```js
// script/run-browser-tests.mjs
import { chromium } from "playwright"

const browser = await chromium.launch()
const page = await browser.newPage()

page.on("console", msg => console.log(msg.text()))
await page.goto("http://localhost:8290")
await page.waitForFunction(() => window.adsbTestsDone, { timeout: 60_000 })

const { pass, fail, error } = await page.evaluate(() => window.adsbTestResult)
await browser.close()

console.log(`${pass} passed, ${fail} failed, ${error} errored`)
process.exit(fail + error > 0 ? 1 : 0)
```

Piping the browser console to stdout is the difference between a debuggable failure
and a mystery. Don't skip it.

## Running Tests

`bb` is the command surface. Nobody should have to remember the underlying
incantations.

```bash
bb test              # everything — clj, cljc, cljs. What CI runs.
bb test:clj          # JVM only. Fast. What you run most.
bb test:cljs         # browser suite via Playwright.
bb test:watch        # shadow-cljs watch + auto-rerun on save.
```

Day to day you live in `bb test:clj` — the domain is pure and the JVM suite is
quick. Run `bb test` before you commit.

## File Organization

`test/` mirrors `src/`, platform for platform, namespace for namespace.

```
src/
  cljc/adsb/aircraft.cljc
  cljs/adsb/ui/aircraft_panel.cljs
  clj/adsb/ingest/ultrafeeder.clj

test/
  cljc/adsb/
    aircraft_test.cljc          ← runs on BOTH platforms
    fixtures.cljc               ← the cast (see testing-standards.md)
  cljs/adsb/ui/
    aircraft_panel_test.cljs
  clj/adsb/ingest/
    ultrafeeder_test.clj
  resources/
    aircraft-sample.json        ← a real, recorded feeder payload
```

Domain tests go in `test/cljc/` and run on both platforms. That isn't redundant —
it's the point. It proves the domain really is portable, and it catches the
platform divergences that will otherwise find you in production (number precision,
regex behavior, `str` on a keyword).

Not every source file needs a test file. See "Implicit Coverage Is Real Coverage"
in `/docs/testing-standards.md`.

## Rendering Reagent Components

Use **React Testing Library**. It dispatches genuine DOM events rather than poking
handlers directly, and it queries the way a user finds things — by role, by label,
by text.

Render a Reagent component by turning it into a React element first:

```clojure
(ns adsb.ui.aircraft-panel-test
  (:require
    ["@testing-library/react" :as rtl]
    [adsb.fixtures :as fixtures]
    [adsb.ui.aircraft-panel :as panel]
    [cljs.test :refer-macros [deftest testing is use-fixtures]]
    [day8.re-frame.test :as rf-test]
    [re-frame.core :as rf]
    [reagent.core :as r]))

(use-fixtures :each {:after rtl/cleanup})

(deftest aircraft-panel
  (testing "shows the callsign of the selected aircraft"
    (rf-test/run-test-sync
      (rf/dispatch [:aircraft/received [fixtures/ups-2717]])
      (rf/dispatch [:aircraft/select "a1b2c3"])

      (rtl/render (r/as-element [panel/aircraft-panel]))

      (is (.getByText rtl/screen "UPS2717"))))

  (testing "flags an aircraft squawking 7700 as an emergency"
    (rf-test/run-test-sync
      (rf/dispatch [:aircraft/received [fixtures/squawking-7700]])
      (rf/dispatch [:aircraft/select (:aircraft/icao fixtures/squawking-7700)])

      (rtl/render (r/as-element [panel/aircraft-panel]))

      (is (.getByRole rtl/screen "alert" #js {:name "Emergency"})))))
```

**Always `rtl/cleanup` after each test.** Without it, components from the previous
test are still mounted in the DOM and `getByText` will find the wrong one — or throw
because it found two.

### Two Gotchas That Will Cost You an Afternoon

**Reagent batches re-renders on its own scheduler.** It is *not* React's. After you
change a ratom or dispatch a re-frame event, the DOM has not updated yet — you're
asserting against the previous frame. Force it:

```clojure
(rf/dispatch [:aircraft/select "a1b2c3"])
(r/flush!)                                  ; <- without this, you see the old DOM
(is (.getByText rtl/screen "UPS2717"))
```

`run-test-sync` handles the re-frame event queue, but **not** Reagent's render
queue. When an assertion fails and the value you see is one step stale, this is why.

For genuinely async behavior (a debounce, an SSE arrival), use RTL's `waitFor`
rather than sprinkling `flush!` calls.

**Never use an anonymous `fn` as a component's event handler in a test-sensitive
path.** A fresh closure every render defeats React's reconciliation and makes
interaction tests flaky in ways that look like your test is wrong.

### Interacting

`fireEvent` dispatches a single real DOM event. `user-event` simulates a full
interaction sequence — pointerdown, focus, pointerup, click — the way a browser
actually does it. Prefer `user-event` when the distinction could matter (anything
involving focus, hover, or keyboard).

```clojure
(.click rtl/fireEvent (.getByRole rtl/screen "button" #js {:name "Clear"}))
```

Query by **role, label, or visible text**. Reach for `data-testid` only when no
semantic query works — and treat needing it as a hint that the markup is
inaccessible.

## Testing re-frame

`day8.re-frame.test/run-test-sync` gives each test a fresh `app-db` and makes the
event queue synchronous, so you can dispatch and immediately assert.

**Events** — given a dispatch, is `app-db` right?

```clojure
(deftest aircraft-received
  (testing "indexes incoming aircraft by ICAO address"
    (rf-test/run-test-sync
      (rf/dispatch [:aircraft/received [fixtures/ups-2717 fixtures/long-silent]])
      (is (= #{"a1b2c3" "c0ffee"}
             (set (keys @(rf/subscribe [:aircraft/by-icao])))))))

  (testing "replaces an aircraft's prior state rather than merging into it"
    ...))
```

Note the assertion goes through a **subscription**, not through `re-frame.db/app-db`
directly. Reaching into `app-db` tests the shape of your state; going through the
subscription tests the contract. Prefer the contract.

**Subscriptions** — given an `app-db`, is the derived view right? Test the
subscription's logic, not its plumbing. A subscription that just does `(get db :x)`
does not need a test.

Never let `app-db` leak between tests. A test that passes alone and fails in the
suite is almost always this.

## The Map Seam

**Never render a real MapLibre map in a unit test.** Even in real Chromium, headless
WebGL is slow and flaky, and if you did get it working you'd be testing MapLibre's
rendering — which is MapLibre's job, and not ours.

Instead, test the **seam**. `adsb.map.maplibre` is a thin interop wrapper whose only
job is to be fakeable, and the aircraft layer talks to the map only through it:

```clojure
(deftest aircraft-layer
  (testing "hands the map one GeoJSON feature per positioned aircraft"
    (let [calls (atom [])
          fake  (reify maplibre/Map
                  (set-source-data! [_ _source data] (swap! calls conj data)))]

      (layer/render! fake [fixtures/ups-2717 fixtures/never-positioned])

      (let [features (-> @calls first :features)]
        (is (= 1 (count features))
            "the never-positioned aircraft has no coordinates to draw")
        (is (= [-104.67 39.87]
               (get-in (first features) [:geometry :coordinates])))))))
```

That test is fast, deterministic, and tests the only thing we actually own: **do we
give MapLibre correct GeoJSON?** Whether MapLibre then draws a triangle at that
coordinate is not our contract.

Note the coordinate order. GeoJSON is `[lon lat]`. ADS-B, aviation, and human
intuition are all `lat, lon`. Getting this backwards puts your aircraft in the ocean
off Somalia, and it is by far the most common bug in this class of application.
There should be a test named exactly that.

The real map is covered by running the app and looking at it. That's legitimate —
some things are.

## Testing Ingest

`adsb.ingest.source/Source` is a protocol, which makes this easy: the test supplies
a fake that replays a recorded payload.

```clojure
(defn replaying-source [payload]
  (reify source/Source
    (poll! [_] payload)))

(deftest ingest-poll
  (testing "drops aircraft that have never reported a position"
    (let [raw    (json/parse-string (slurp "test/resources/aircraft-sample.json") true)
          result (ingest/poll-once! (replaying-source raw))]
      (is (every? :aircraft/position result)))))
```

**Record a real payload and commit it.** `test/resources/aircraft-sample.json` is a
genuine capture from the feeder, warts included — the `"ground"` altitudes, the
missing positions, the space-padded callsigns. Hand-written fixtures are a fiction
of what you *think* the feeder sends. A recording is what it *does* send.

**Never test against a live ultrafeeder.** The sky is not a fixture. It's different
every second, empty in fog, and busy at 8am. A test that depends on it is a test
that fails for reasons that have nothing to do with your code.

## Property-Based Testing

Malli schemas generate valid data for free. The domain is parsing- and math-heavy,
which is exactly where hand-picked examples miss.

```clojure
(ns adsb.geo-test
  (:require
    [adsb.geo :as geo]
    [adsb.schema :as schema]
    [clojure.test.check.clojure-test :refer [defspec]]
    [clojure.test.check.properties :as prop]
    [malli.generator :as mg]))

(defspec bearing-is-always-a-compass-heading 200
  (prop/for-all [from (mg/generator schema/position)
                 to   (mg/generator schema/position)]
    (<= 0 (geo/bearing from to) 360)))
```

Good candidates: geo math (invariants — a bearing is a compass heading, a distance
is never negative), coercion (any payload the schema accepts normalizes without
throwing), round trips (`aircraft → geojson → aircraft`).

Don't force it. A table of six examples is often clearer than a property, and
clarity wins.

## Faking, Not Mocking

Prefer **fakes** — real implementations with simple behavior — over **mocks** that
assert on interactions.

```clojure
;; Good — a fake. Tests behavior: what came out?
(reify source/Source
  (poll! [_] recorded-payload))

;; Bad — a mock. Tests interaction: was a function called?
(is (= 1 @poll-call-count))
```

`poll!` being called once is not the behavior anyone cares about. The aircraft
coming out the other side correctly is.

Stub at the **boundary** — the HTTP client, the clock, the map object. Never stub
the namespace under test.

**Time is an argument, not an ambient fact.** The domain takes `now-ms` as a
parameter (see "Pure Core, Effects at the Edges" in `clean-code-standards.md`),
which means testing staleness is `(aircraft/stale? a 62000)` — a literal, not a
clock you have to fight.

## Continuous Integration

CI runs, on every push:

```bash
bb lint          # clj-kondo — it finds real bugs, not just style
bb test          # clj + cljc + cljs
bb build         # a broken build is a failed test
```

All three must be green. `clj-kondo` catches unused bindings, arity errors, and
shadowed vars before a human has to. Treat its warnings as failures.
