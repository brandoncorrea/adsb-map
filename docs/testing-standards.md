# Testing Standards

Reference guide for test quality. Read this before writing any tests.

This project uses **`clojure.test`** on the JVM and **`cljs.test`** in the browser —
the same API, selected by reader conditional, so a `.cljc` domain test compiles and
runs on both platforms unchanged. The principles below are framework-agnostic; the
mechanics live in `testing-setup.md`.

## Test Naming

Test names are specifications. They describe behavior, not implementation. They
should read like documentation.

`clojure.test` gives you two naming surfaces, and you use **both**:

```clojure
(deftest normalize-aircraft
  (testing "drops aircraft that have never reported a position"
    ...)

  (testing "coerces alt_baro of \"ground\" to an on-ground flag"
    ...))
```

- The `deftest` name is the **unit under test** — a symbol, kebab-case, named after
  the function or namespace it covers. `normalize-aircraft`, not `test-1` or
  `aircraft-tests`.
- The `testing` string is the **behavior** — a full sentence a product person could
  read. This is where the specification actually lives.

**Good:** `"rejects a squawk that isn't four octal digits"`,
`"returns an empty list when the feeder reports no aircraft"`
**Bad:** `"test normalize"`, `"works"`, `"case 2"`

Aim for `<expected behavior> when <condition>` — but prioritize readability over
rigid format. If a test fails in CI, the `testing` string alone should tell you what
broke. If it doesn't, rename it.

**Never write a bare `is` outside a `testing` block.** An assertion with no
`testing` string is an assertion with no specification, and its failure message will
be a diff with no explanation of what was supposed to happen.

## Test Structure

Every test follows Arrange → Act → Assert:

```clojure
(testing "marks an aircraft stale after 60 seconds of silence"
  (let [aircraft (assoc fixtures/ups-2717 :aircraft/seen-at 1000)   ; Arrange
        stale?   (aircraft/stale? aircraft 62000)]                  ; Act
    (is (true? stale?))))                                           ; Assert
```

Use `use-fixtures` to extract shared setup and teardown. `:each` runs around every
test in the namespace; `:once` runs around the whole namespace — use `:once` only
for genuinely expensive, genuinely immutable setup.

Keep tests readable. If you can't tell what a test does without reading three
fixtures, the setup has been over-extracted.

## One Behavior Per Test

Each `testing` block verifies ONE logical behavior. Multiple assertions are fine if
they all verify the same behavior from different angles. But if a test fails, you
should immediately know WHAT broke without reading the body.

**Good:** two `is` forms checking that a normalized aircraft has the right ICAO AND
the right altitude (one behavior: normalization)
**Bad:** one `testing` block that checks normalization, then checks staleness, then
checks the SSE payload (three behaviors)

## Test Behavior, Not Implementation

Tests describe WHAT the system does, not HOW. Test from the outside in: call public
functions, dispatch re-frame events, render components, hit handlers. Check outputs
and observable effects — not the internal steps that produced them.

**Signs you're testing implementation:**
- Calling a `defn-` private function via `#'ns/private-fn`
- Asserting on the shape of an intermediate value that no caller ever sees
- Tests that break when you refactor without changing behavior
- Asserting that an internal function was called, rather than that the right thing
  happened
- Reaching into `re-frame.db/app-db` directly instead of going through a subscription

**Signs you're testing behavior:**
- Tests use the namespace's public API or the rendered DOM
- Refactoring internals doesn't break tests
- Test names read as user-facing specifications
- The test would still make sense if the implementation were rewritten from scratch

### Layers of "Outside-In"

Outside-in is not all-or-nothing. There are layers:

- The **HTTP layer** (reitit routing, middleware, coercion) is separate from the
  **domain logic** each handler calls. Test them independently.
- The **ingest layer** has its own contract — "given this feeder payload, produce
  these domain aircraft" — that is worth testing directly, without a server running.
- **Shared `.cljc` domain code** is the innermost layer and deserves direct,
  thorough tests. It's pure; there's no excuse not to.

The question is: does this code have its own contract? If yes, test it directly. If
it's a private helper that exists to serve one caller, implicit coverage through that
caller is fine.

## Test Independence

- No test may depend on another test's execution or state
- No test may depend on execution order (`clojure.test` does not guarantee it, and
  `cljs.test` runs async)
- Every test sets up its own state and tears it down (fixtures count)
- Shared fixtures are fine; shared **mutable** state is not

The re-frame corollary: **never let `app-db` leak between tests.** Use
`day8.re-frame.test/run-test-sync`, which snapshots and restores it. A test that
passes alone and fails in the suite is almost always an `app-db` leak.

## The Cast

Most of the suite is about aircraft. Rather than rebuilding a plausible aircraft map
in every test file, we keep a small, fixed cast of well-known aircraft in
`test/cljc/adsb/fixtures.cljc`. When someone reading a test sees `ups-2717`, they
should immediately know that's the boring, well-formed, cruising aircraft — not some
throwaway map.

Each member of the cast exists to represent **a real class of input the feeder
actually produces:**

| Fixture | What it is | What it's for |
|---|---|---|
| `ups-2717` | Cargo 747, cruising, complete data | The happy path. Use this unless you need otherwise. |
| `on-the-ground` | `alt_baro` is the **string** `"ground"` | The coercion trap. Altitude isn't always a number. |
| `never-positioned` | Heard on the radio, no `lat`/`lon` ever | Kept, with no `:aircraft/position` — in the sidebar, never on the map (adsb-bvi.3). Most common real-world case. |
| `squawking-7700` | Squawk `7700`, general emergency | The alerting path. |
| `long-silent` | `seen` 300 seconds ago | Must age out of the map. |
| `mlat-derived` | Position from multilateration, not ADS-B | Lower confidence; flagged differently. |

### Guidelines

- Keep the cast **small and stable**. Six memorable aircraft beat forty forgettable
  ones. Adding a seventh should require justifying what class of input it represents
  that none of the others do.
- Build fixtures against the **real Malli schemas**, so a schema change forces the
  fixtures to update rather than letting them rot into a fiction.
- Tests needing a genuinely unusual entity (a specific malformed field, an
  off-by-one boundary) should still build their own. The cast is for the common cases.
- Never mutate the cast. They're immutable maps; `assoc` a copy.

Uniform test data makes the suite read like one story rather than a pile of
fragments. A new contributor scanning the tests picks up the vocabulary fast.

## Implicit Coverage Is Real Coverage

Code does not need its own test file to be considered tested. A formatting helper
called by a component is tested through that component's tests.

Before declaring code "untested":
1. Trace the call sites
2. Check whether existing tests exercise the path
3. Only write a new test if the behavior is genuinely uncovered

**Exception:** shared code that has grown into its own module — with its own
responsibilities and multiple consumers — should be promoted to a directly testable
unit. Then its dependents can fake it out and stay focused.

Do not create a test file per source file as a goal unto itself.

## What to Test Here

The domain has a specific shape, so the risk does too. In rough order of how likely
each is to actually bite you:

1. **The ingest boundary.** This is where the bugs live. The feeder sends real-world
   junk: missing positions, `"ground"` where a number belongs, callsigns padded with
   spaces, aircraft that vanish mid-flight. Every one of those is a test.
   See `validation-boundaries.md`.
2. **The shared domain (`src/cljc/`).** Pure functions, no excuses. Staleness,
   emergency detection, geo math, GeoJSON conversion. Cheap to test, so test them
   thoroughly — including with generated input (see below).
3. **re-frame events and subscriptions.** Given an event, does `app-db` end up right?
   Given that `app-db`, does the subscription derive the right view?
4. **UI behavior.** Does clicking an aircraft in the list select it? Does the
   emergency badge appear for `squawking-7700`?
5. **The map interop seam.** Does the aircraft layer hand MapLibre the right GeoJSON?
   (Not: does MapLibre draw it. That's MapLibre's job, and it isn't ours to test.)

### Property-Based Testing

The domain is math-and-parsing heavy, which is exactly where example-based tests
miss. Our Malli schemas generate valid data for free — use it.

```clojure
;; Every aircraft the schema can produce must survive a round trip to GeoJSON
(defspec aircraft->geojson-round-trips 100
  (prop/for-all [a (mg/generator schema/positioned-aircraft)]
    (= (:aircraft/icao a)
       (-> a aircraft/->geojson-feature geojson/->icao))))
```

Good candidates: geo math (a bearing is always 0–360), coercion (any valid feeder
payload normalizes without throwing), serialization round trips. Don't force it
where a table of examples is clearer.

## What NOT to Test

- Things already covered (see "No Duplicate Tests")
- Library internals — MapLibre's rendering, Malli's validator, http-kit's socket
- Private helpers with no independent contract
- Dead code (delete it instead)
- **That a real map renders.** Rendering a real WebGL map in a unit test is slow,
  flaky, and tests someone else's library. Test the seam.

## No Duplicate Tests

Before writing a new test, search for existing tests covering the same behavior.
Duplication creates noise, false confidence, and maintenance burden.

- If a test exists for the behavior, **update it** if the behavior changed
- If two tests cover the same behavior, **remove the weaker one** (less descriptive
  name, fewer edge cases, more coupled to implementation)
- If a behavior was removed, **remove its tests**

Duplicates often appear after refactoring, when old tests are left behind. Clean
them up proactively.

## Dead Code

Dead code is a liability. If a function, namespace, or branch has no call sites and
no reason to exist:

1. Delete the dead code
2. Delete any tests that only covered the dead code
3. Verify the remaining suite still passes

Do not keep dead code "just in case." Version control exists for that.

## Assertion Style

- **Assert on values, not on truthiness, when the value is the contract.** If
  normalization should produce a specific map, assert the map — `clojure.test` gives
  you a readable diff for free.
- **Prefer truthiness checks when truthy/falsey is genuinely all you mean.** But note
  the Clojure trap: `(is (empty? xs))` says what you mean; `(is (not xs))` does not,
  because `[]` is truthy.
- For generated or time-dependent values, assert on **shape or boundary**, not exact
  values. A bearing is `(<= 0 b 360)`, not `247.3819`.
- For collections, assert on contents when the values matter, on count or emptiness
  when they don't. Compare **sets** when order is not part of the contract — a test
  that fails because `map` returned things in a different order is a bad test.

The guiding question: **is the exact value part of the behavior contract, or am I
just checking that something reasonable came back?** Let that drive the assertion.

## Coverage Philosophy

We value meaningful coverage of critical paths over chasing a percentage. 60%
coverage of the right things beats 95% padded with trivial tests.

Focus coverage on:
1. The ingest boundary and validation
2. Domain rules (staleness, emergencies, geo)
3. Error handling and failure modes — especially "the feeder went away"
4. Complex conditional logic

Do not write tests solely to move a number. Every test must protect against a real
regression.

## Test Speed

- Unit tests must be fast. If a test hits the network, disk, or a real feeder, it's
  an integration test — isolate it and mark it.
- **Prefer fakes over mocks.** A fake `Source` that replays a canned
  `aircraft.json` is worth more than a mock asserting `poll!` was called once.
  Mocks verify interaction; fakes verify behavior. We care about behavior.
- If you must stub, stub at the **boundary** — the HTTP client, the clock, the
  MapLibre object — never the namespace under test.
- **Never test against a live ultrafeeder.** The sky is not a fixture. It changes
  every second, it's different at 3am, and it's empty when there's fog. Record a
  payload, commit it, replay it.

## Red → Green → Refactor Checklist

Before moving from GREEN to REFACTOR:
- [ ] Is there duplication between this test and an existing one?
- [ ] Can the `testing` string be more specific about the behavior?
- [ ] Is the test coupled to implementation details?

Before moving from REFACTOR to the next RED:
- [ ] Are all tests still green — `clj` **and** `cljs`?
- [ ] Is the production code as simple as it can be for the behaviors tested so far?
- [ ] Did I commit?
