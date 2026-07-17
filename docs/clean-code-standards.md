# Clean Code Standards — Clojure & ClojureScript

Clean code is a UX problem. Our users are other developers, coding agents, and our
future selves — we're creating a user experience for people who read our code.

This stack: a Clojure backend (`src/clj/`), a ClojureScript frontend (`src/cljs/`),
and a shared domain that both compile (`src/cljc/`). Data flows in from an
ultrafeeder, through Malli validation, out to a MapLibre map over SSE. State lives
in re-frame on the client and in a small number of explicit atoms on the server.

Examples are Clojure unless the rule is specifically about ClojureScript, Reagent,
or reader conditionals.

Like art, code quality is almost always subjective, but you can tell good code from
bad code because the good code follows rules. This document defines those rules. Any
of them can be broken — but only with reason and intention. Breaking a rule because
you thought about it and decided the code is clearer without it is clean code.
Breaking a rule because you didn't know it existed is not.

## The First Principle: Data Over Everything

Clojure's core insight is that most problems are better modeled as **data being
transformed** than as objects exchanging messages. Before you reach for a protocol,
a record, a multimethod, or a macro, ask whether a plain map and a plain function
would do.

They usually would.

```clojure
;; Overbuilt — a record and a protocol to hold four fields
(defprotocol Trackable
  (current-position [this])
  (stale? [this now]))

(defrecord Aircraft [icao callsign position seen]
  Trackable
  (current-position [this] (:position this))
  (stale? [this now] (> (- now (:seen this)) 60)))

;; Idiomatic — a map, and functions that read it
{:aircraft/icao      "a1b2c3"
 :aircraft/callsign  "UPS2717"
 :aircraft/position  {:geo/lat 39.87 :geo/lon -104.67}
 :aircraft/seen-at   1720713600}

(defn stale? [aircraft now]
  (> (- now (:aircraft/seen-at aircraft)) stale-threshold-seconds))
```

Reach for a protocol when you genuinely need **polymorphism across
implementations** — `adsb.ingest.source/Source` is a real protocol because we
really do intend to swap `aircraft.json` polling for an SBS socket. That's the bar.
One implementation is not polymorphism; it's ceremony.

## Naming

Names are the most important tool for readability. Invest time in them.

- **kebab-case for everything** — `ground-speed`, `parse-aircraft`, `stale-threshold-seconds`.
  Not `groundSpeed`, not `ground_speed`. The one exception is JS interop in CLJS,
  where you must match the foreign name.
- **Functions describe what they do, with a verb** — `normalize-aircraft`,
  `poll-feeder`. Not `aircraft` or `handler`.
- **Predicates end in `?`** — `stale?`, `emergency?`, `on-ground?`. A predicate
  returns a truthy/falsey value and does nothing else.
- **Side-effecting functions end in `!`** — `start-poller!`, `dispatch!`,
  `reset-app-db!`. The bang marks consequences: mutating state (an atom, the
  DOM, a socket, a db transaction), emitting to the world (a log line, a
  network send), or being the throwing variant of a safe counterpart (a `read`
  returns nil where a `read!` would throw). Plain reads stay bare even when
  they touch the world — a clock (`now-ms`), a file (`env/read`), a computed
  style (`css-px`) — reading is not a consequence. The `!` is the only warning
  the reader gets; do not dilute it.
- **Coercions read as `->`** — `json->aircraft`, `aircraft->geojson`. The arrow
  says "this is a pure transformation from one shape to another."
- **Avoid abbreviations** unless they're the domain's own vocabulary. `icao`,
  `squawk`, `rssi`, `nm` (nautical miles) are correct here — they're what the
  people who fly airplanes call these things. `acft`, `pos`, `cfg`, `req` are not.
- **Avoid generic names** — `data`, `result`, `tmp`, `value`, `m`, `x` almost always
  have a better name. `coll`, `f`, and `xs` are acceptable in genuinely generic
  higher-order functions, where the whole point is that the thing is anonymous.

If you need a comment to explain what a binding or function does, the name is wrong.
Rename it.

### Namespaced Keywords for Domain Data

Every map that represents a **domain entity** uses namespaced keys.

```clojure
;; Anonymous — which lat? whose altitude? What is this map?
{:lat 39.87 :lon -104.67 :alt 35000}

;; Self-describing — you can hand this to any function and it stays meaningful
{:geo/lat 39.87 :geo/lon -104.67 :aircraft/altitude-ft 35000}
```

This is not decoration. Namespaced keys let you merge maps without collision, let
Malli schemas be registered and reused globally, and — critically for us — let a
reader know what they're looking at without tracing the call site. Bare keywords
are fine for local, throwaway option maps (`{:timeout 5000}`).

### Language Conventions Win

When a general clean-code heuristic conflicts with an established Clojure
convention, **the Clojure convention wins.** For this project:

- **Threading macros** (`->`, `->>`, `some->`, `cond->`) are the idiomatic way to
  express a pipeline. A threaded pipeline of six steps is clean, even though six
  nested calls would not be.
- **Trailing parens close on the same line.** `(defn f [x] (inc x))` — never a
  "dangling paren" on its own line. This is universal in Clojure and every
  formatter enforces it. It looks wrong if you come from a C-family language. Do it
  anyway.
- **2-space indentation**, aligned per the standard rules. Let `cljfmt` settle
  arguments about this; don't hand-format.
- **`(comment ...)` blocks** at the bottom of a namespace for REPL scratch work are
  idiomatic and welcome. They are not dead code — they're executable documentation
  of how to drive the namespace. Keep them current or delete them.
- **Reagent components are kebab-case functions**, not PascalCase:
  `aircraft-panel`, not `AircraftPanel`. They are invoked as data —
  `[aircraft-panel selected]` — not as `<AircraftPanel />`. The React convention
  does not apply, because in Reagent they are not React components; they are
  functions that return hiccup.

These conventions aren't just style — they affect tooling (`clj-kondo`, `cljfmt`,
LSP), macro expansion, and reader expectations. A name that's "technically more
readable" by general rules but breaks Clojure expectations is not clean — it's
wrong.

## Functions

### Do One Thing

A function does one thing if you cannot meaningfully extract another function from
it. If you can describe it only with "and" or "then," split it.

### Keep Them Short

Aim for ~10 lines. This isn't a hard ceiling, but if a function exceeds 10 lines,
look for extraction opportunities. If it exceeds 20, it almost certainly does more
than one thing.

Clojure makes this easy in a way most languages don't: a threading pipeline of
well-named steps *is* the extraction.

```clojure
;; One thing, expressed as five named things
(defn normalize-aircraft [raw]
  (-> raw
      parse-fields
      coerce-altitude
      derive-position
      drop-unpositioned
      stamp-received-at))
```

### Limit Parameters

- 0-2 parameters: ideal
- 3 parameters: acceptable, consider a map
- 4+ parameters: take a map and destructure it

```clojure
;; Bad — the caller has to remember the order, forever
(defn track-aircraft [icao lat lon alt heading speed])

;; Good — self-describing at every call site
(defn track-aircraft [{:aircraft/keys [icao altitude-ft heading ground-speed]
                       :geo/keys      [lat lon]}])
```

Destructuring in the parameter vector is not just shorter — it documents the
function's real contract with the map it receives.

### Phantom Parameters

If a parameter always receives the same value at every call site, it's not a real
parameter — it's a phantom. Hard-code it and remove it from the signature. It can
always be reintroduced when requirements actually demand it. Until then, it's noise
every caller has to know about for no reason.

### No Flag Arguments

A boolean parameter that switches a function between two behaviors means the
function does two things. Split it.

```clojure
;; Bad — what does true mean?
(render-aircraft aircraft true)

;; Good — two functions that say what they do
(render-selected-aircraft aircraft)
(render-aircraft aircraft)
```

If you see `(f x true)` and can't tell what the boolean controls without reading the
implementation, that's a flag argument.

### Minimize Nesting

Deep nesting obscures logic. Clojure gives you better tools than `if` inside `if`
inside `if` — use them.

```clojure
;; Bad — the happy path is buried three levels down
(defn position-of [raw]
  (if (:lat raw)
    (if (:lon raw)
      (if (valid-coords? raw)
        {:geo/lat (:lat raw) :geo/lon (:lon raw)}
        nil)
      nil)
    nil))

;; Good — the pipeline short-circuits on nil for you
(defn position-of [raw]
  (some-> raw
          coords
          validate-coords
          ->position))
```

**Tools to flatten:**
- **`when-let` / `if-let` / `when-some`** — bind and guard in one step
- **`some->` / `some->>`** — a pipeline that aborts on the first `nil`
- **`cond`** instead of nested `if`/`else` — a flat table of conditions
- **`cond->`** — conditionally apply steps to a value without rebinding it
- **Early guard clauses** — `(when-not (valid? x) (throw ...))` at the top
- **Extract a function** — the universal answer

### Extract Complex Conditions

When a condition is compound, give it a name that describes its *business meaning*,
not its mechanics.

```clojure
;; Bad — the reader has to run the logic in their head
(when (and (:aircraft/position a)
           (< (- now (:aircraft/seen-at a)) 60)
           (not= "ground" (:aircraft/altitude-ft a)))
  (draw! a))

;; Good — the intent is the code
(defn trackable? [aircraft now]
  (and (positioned? aircraft)
       (fresh? aircraft now)
       (airborne? aircraft)))

(when (trackable? a now)
  (draw! a))
```

This applies regardless of condition length. Even a two-part condition is worth
extracting if the business meaning isn't obvious.

### Pure Core, Effects at the Edges

This is the single most important structural rule in the codebase.

**Domain logic in `src/cljc/` must be pure.** No I/O, no atoms, no `System/currentTimeMillis`,
no `js/Date`, no logging. Given the same arguments, it returns the same value,
always. That's what makes it testable without a running feeder, and what lets the
same code run on the JVM and in the browser.

Effects — HTTP calls, atom swaps, SSE writes, `setData` on a map — live at the
edges, in `src/clj/` and `src/cljs/`, in functions whose names end in `!` when
they have consequences (effectful reads at the edge stay bare — see Naming).

```clojure
;; Bad — the domain reaches out and grabs the clock
(defn stale? [aircraft]
  (> (- (System/currentTimeMillis) (:aircraft/seen-at aircraft)) 60000))

;; Good — time is an argument; the caller at the edge supplies it
(defn stale? [aircraft now-ms]
  (> (- now-ms (:aircraft/seen-at aircraft)) stale-threshold-ms))
```

The second version can be tested with a literal. The first requires you to
manipulate the system clock. **Pass the clock in.** Same for randomness, same for
config, same for connections.

## No Unnecessary Ceremony

Write the simplest form that communicates the intent. Extra syntax that adds nothing
is noise.

**Thread, don't nest.** Reading inside-out is a tax on the reader.

```clojure
;; Noisy
(stamp-received-at (drop-unpositioned (coerce-altitude (parse-fields raw))))

;; Clean
(-> raw parse-fields coerce-altitude drop-unpositioned stamp-received-at)
```

Use `->` when the value flows in **first** position (maps, most domain data), `->>`
when it flows **last** (sequences). Don't fight the macro — if you're reaching for
`as->` on every step, the functions have inconsistent argument order and *that's*
the bug.

**Destructure.** Name things at the point of use.

```clojure
;; Noisy
(defn label [aircraft]
  (str (:aircraft/callsign aircraft) " @ " (:aircraft/altitude-ft aircraft)))

;; Clean
(defn label [{:aircraft/keys [callsign altitude-ft]}]
  (str callsign " @ " altitude-ft))
```

**Use `when` for a single branch, `if` for two.** An `if` with no `else` is a `when`
wearing a disguise. And `when` gives you an implicit `do`.

```clojure
;; Noisy
(if (emergency? a)
  (do (log-emergency! a)
      (alert! a))
  nil)

;; Clean
(when (emergency? a)
  (log-emergency! a)
  (alert! a))
```

**Prefer `cond` to nested `if`.** A flat table beats a staircase.

**Keywords are functions. Use them.** `(:aircraft/icao a)` — not `(get a :aircraft/icao)`
unless you need a default.

**Sets are predicates.** `(#{"7500" "7600" "7700"} squawk)` reads better than an
`or` chain of `=` checks.

**Prefer sequence functions to `loop`/`recur`.** `map`, `filter`, `reduce`,
`keep`, `group-by`, `frequencies`. Reach for `loop`/`recur` only when there's
genuinely no sequence abstraction that fits — which is rarer than you think.

**Don't `def` inside a function.** Ever. Use `let`. A `def` inside a function
creates a top-level var as a side effect, which is almost never what you meant and
will confuse every tool that reads your code.

**Beware nil vs. empty.** This is the most common Clojure bug there is:

```clojure
;; WRONG — an empty vector is truthy
(if aircraft-list
  (render-list aircraft-list)
  (render-empty-state))

;; RIGHT — seq returns nil for an empty collection
(if (seq aircraft-list)
  (render-list aircraft-list)
  (render-empty-state))
```

`[]`, `{}`, and `""` are all **truthy** in Clojure. Only `nil` and `false` are
falsey. If you want "is there anything in here," you want `seq`.

This isn't about being clever or terse. It's about removing visual noise so the
reader's attention goes to what the code *does*.

## Namespaces

### One Namespace, One Responsibility

A namespace has a single reason to change. `adsb.ingest.ultrafeeder` knows how to
talk to an ultrafeeder. It does not also know how to fan out SSE. If you can't
describe a namespace's job in one sentence without "and," split it.

Grab-bag namespaces (`adsb.utils`, `adsb.helpers`, `adsb.core` as a dumping ground)
are where code goes to become unfindable. If you can't name the namespace after what
it *does*, you haven't figured out what it does yet.

### Requires Are Sorted, Aliased, and Explicit

```clojure
(ns adsb.ingest.ultrafeeder
  (:require
    [adsb.aircraft :as aircraft]
    [adsb.ingest.source :as source]
    [adsb.schema :as schema]
    [clojure.string :as str]
    [malli.core :as m]
    [org.httpkit.client :as http]))
```

- **One `:require` per line**, alphabetically sorted by namespace.
- **Always alias.** Never `:refer :all` — it makes every symbol's origin invisible
  to both readers and agents. `:refer` a specific symbol only when it's overwhelmingly
  idiomatic to do so (`[clojure.test :refer [deftest testing is]]`).
- **Alias consistently across the whole codebase.** `clojure.string` is `str`
  everywhere. `re-frame.core` is `rf` everywhere. Pick once, grep for precedent
  before inventing a new alias.

### The Newspaper Metaphor

Read a namespace top to bottom like a newspaper article. Public API near the top,
private helpers below it, `(comment ...)` scratch at the very bottom. Callers above
callees.

Clojure's single-pass compiler pushes you toward the opposite (defs must exist
before use), which is why `declare` exists. Use it when readability demands the
public function come first.

## Reagent Components

Components are functions that return hiccup. Keep them that way.

**Keep logic out of the component.** A component's job is to turn data into hiccup.
Formatting, filtering, sorting, and deriving belong in the domain (`src/cljc/`) or
in a re-frame subscription — not in the render body.

```clojure
;; Noisy — sorting and formatting tangled into rendering
(defn aircraft-list []
  (let [aircraft @(rf/subscribe [:aircraft/all])
        sorted   (sort-by :aircraft/altitude-ft > aircraft)]
    [:ul
     (for [a sorted]
       [:li (str (:aircraft/callsign a) " — "
                 (.toLocaleString (:aircraft/altitude-ft a)) " ft")])]))

;; Clean — the subscription sorts, the domain formats, the component renders
(defn aircraft-list []
  [:ul
   (for [a @(rf/subscribe [:aircraft/sorted-by-altitude])]
     ^{:key (:aircraft/icao a)}
     [:li (aircraft/label a)])])
```

**Always give `:key` metadata to elements in a `for`.** React needs it, and without
it you get silent, baffling re-render bugs. `^{:key (:aircraft/icao a)}` on the
element — this is the single most-forgotten thing in Reagent.

**Never put an anonymous function in a component that renders often.** `(fn [] ...)`
creates a new identity every render, defeating React's reconciliation. Hoist it or
use a named handler that dispatches a re-frame event.

**No business logic in event handlers.** An `:on-click` dispatches a re-frame event
and nothing else. `[:button {:on-click #(rf/dispatch [:aircraft/select icao])}]`.

## Magic Values

Inline numbers and strings with no context are unreadable. Give them a name.

```clojure
;; Bad — what is 7700? What is 60000?
(when (= squawk "7700") (alert! a))
(when (> age 60000) (drop! a))

;; Good
(def ^:const emergency-squawk "7700")
(def ^:const stale-threshold-ms 60000)

(when (= squawk emergency-squawk) (alert! a))
(when (> age stale-threshold-ms) (drop! a))
```

This matters more than usual in an ADS-B domain, which is *full* of magic numbers
with life-or-death meanings. `7500` is a hijacking. `7600` is radio failure. `7700`
is a general emergency. Nobody reading `(= squawk "7600")` cold should have to look
that up.

Exceptions: `0`, `1`, `-1`, and `nil` are usually self-evident. Use judgment.

## Comments

### When Comments Are Warranted

- **Why, not what.** Business reasons, tradeoffs, non-obvious constraints.
- **Domain facts a reader can't be expected to know.** This codebase is full of
  them, and they're the most valuable comments here:
  `;; alt_baro is the string "ground" for aircraft on the tarmac, not a number`
- **TODOs with a bead ID** — `;; TODO(adsb-4ga): handle MLAT-derived positions`
- **Warnings** — `;; NOTE: called from the poller thread, not the request thread`
- **Docstrings on public functions.** Not on private helpers whose names already
  say it.

### When Comments Are Code Smells

- Explaining *what* the code does → rename things instead
- Commented-out code → delete it. Git remembers.
- Change logs and journal comments → that's what `git log` is for
- A comment restating the docstring

## Dead Code

Dead code is a liability. It bloats the source, bloats the tests, bloats the
bundle, and misleads anyone reading it into thinking it matters. If a function,
namespace, branch, or binding has no call sites and no reason to exist:

1. Delete the dead code
2. Delete any tests that only covered the dead code
3. Verify the remaining test suite still passes

Commented-out code is dead code. Unreachable `cond` branches are dead code. A
`defmulti` with one `defmethod` is dead abstraction. Delete them all.

`clj-kondo` will find unused vars and unused requires for you. Listen to it.

Do not keep dead code "just in case." Version control exists for that.

## Error Handling

- **`nil` is a fine return value.** It's the idiomatic way to say "nothing found."
  Don't wrap it in an empty collection or an `Either` unless the caller genuinely
  benefits. Most of Clojure's core library is designed around nil-punning; work
  with it.
- **Throw `ex-info`, never bare exceptions.** `(throw (ex-info "message" {...}))`
  carries structured, machine-readable data. A bare `Exception` carries a string
  and nothing else.

  ```clojure
  (throw (ex-info "Aircraft failed schema validation"
                  {:type    ::invalid-aircraft
                   :icao    (:hex raw)
                   :errors  (me/humanize explanation)}))
  ```

- **Catch by inspecting `ex-data`**, not by catching `Exception` and string-matching
  the message.
- **Don't use exceptions for control flow.** An aircraft with no position isn't
  exceptional — it's Tuesday. Return `nil` and filter it out.
- **Error handling is one thing.** A function that handles errors should do little
  else. Separate the happy path from the recovery path.
- **The ingest loop must never die.** A single malformed aircraft in a payload of
  400 is expected, not fatal. Log it, drop that one, keep the other 399. See
  `validation-boundaries.md`.

## Structure and Organization

### Screaming Architecture

The directory structure should scream what the application *does*, not what
framework it uses.

We split `src/` by platform (`clj/`, `cljc/`, `cljs/`) because **the build tools
require it** — that's a tooling constraint, not an architectural statement. Inside
each platform, organize by domain:

```
;; Bad — screams "I am a web application"
src/clj/adsb/
  controllers/
  services/
  models/
  utils/

;; Good — screams "I track airplanes"
src/clj/adsb/
  ingest/          ; getting aircraft in
  stream/          ; getting aircraft out
  http/            ; the edge
```

Group by feature or domain concept. The handler, the logic, and the tests for
"ingest" live together — not scattered across four layer folders.

### Vertical Distance

Things that are related should be close together. If `poll!` calls `parse-payload`,
they should be adjacent. Don't make the reader jump around.

### Line Length

Keep lines under 80 characters. Long lines break side-by-side diffs and make code
harder to scan. In Clojure, a long line is usually a sign that a threading pipeline
should break across lines or a `let` binding should be extracted.

Acceptable to exceed:
- Long namespace paths in `:require`
- URLs in comments
- String literals where breaking mid-sentence hurts more than the long line

### File Length

Aim for namespaces under 100 lines. Over 200 lines almost certainly means multiple
responsibilities. Namespaces that are mostly hiccup (Reagent views) get more
leeway — markup pushes line counts up — but a 300-line view namespace is still
telling you to extract components.

### The Boy Scout Rule

Leave every file cleaner than you found it. If you touch a file, improve one small
thing: a name, a docstring, a simplification. Over time the codebase gets better
instead of worse.

## DRY — But Not Prematurely

Duplication is acceptable when:
- You've only seen the pattern once (it might not be a pattern)
- The two instances might diverge as requirements evolve
- The abstraction would be harder to understand than the duplication

Extract a shared abstraction after you see the same pattern **three** times. At that
point you understand what varies and what doesn't.

The Clojure-specific version of this warning: **do not write a macro to remove
duplication that a function could remove.** Macros are the most expensive
abstraction in the language — they're invisible to `grep`, opaque to agents, hostile
to debuggers, and they don't compose. If a function can do it, a function should do
it.

## SOLID, Translated

You don't need to cite these by name. But the ideas survive the jump to a functional
language, even though the mechanics change:

- **Single Responsibility** — a namespace has one reason to change. Split it if not.
- **Open/Closed** — extend by adding a `defmethod` or a new implementation of a
  protocol, not by editing a `cond` every time a case appears.
- **Liskov Substitution** — every implementation of `Source` must actually behave
  like a `Source`. A fake that lies in tests is worse than no fake.
- **Interface Segregation** — small protocols. A protocol with eight methods where
  implementers only ever need two is two protocols.
- **Dependency Inversion** — `adsb.ingest` depends on the `Source` **protocol**, not
  on `adsb.ingest.ultrafeeder`. That's why the swap to SBS won't hurt.

The functional restatement of all five: **depend on data and pure functions; push
the parts that vary behind a narrow seam.**

## Agent-Friendly Code

These rules are good practice for humans too, but coding agents benefit from them
disproportionately. Agents pattern-match aggressively, read code literally, and
struggle with indirection. Clojure is *especially* punishing here — its dynamism
gives you many ways to write code that no static reader, human or machine, can trace.

### Consistency Over Preference

If the codebase does something one way, do it that way everywhere — even if you
prefer another style. Three different ways to build a response map means an agent
will pick one arbitrarily or blend them. One pattern, used everywhere, means the
agent learns it once and applies it correctly.

Grep for precedent before introducing a new pattern.

### No Clever Code

Agents take code literally. Point-free chains of `comp` and `partial`, dense
`reduce`s with anonymous functions doing three jobs, `juxt` cleverness — these trip
up agents more than they'd trip a human, and they trip humans plenty.

```clojure
;; Clever — technically correct, genuinely hard to read
(def summarize (comp (partial reduce (partial merge-with +)) (partial map (juxt :type (constantly 1)))))

;; Obvious
(defn summarize [aircraft]
  (frequencies (map :aircraft/type aircraft)))
```

Write obvious code. Save your cleverness for the architecture.

### Avoid Dynamic Dispatch and Metaprogramming

`eval`, `resolve`, `requiring-resolve`, runtime-constructed symbols, `alter-var-root`,
`^:dynamic` vars rebound with `binding` — agents cannot statically trace any of
these. They will miss call sites, misunderstand what's invoked, and produce broken
refactors.

Macros are in this category too. Every macro you write is a small private language
that a reader has to learn before they can read the code that uses it. Write one only
when you genuinely need to control evaluation — and never to save typing.

Prefer static, traceable function calls. If you must do something dynamic, document
it loudly.

### One Clear Public API Per Namespace

When a namespace exposes a small, obvious public surface and marks everything else
`defn-`, an agent can reason about the dependency graph without reading every line.
`adsb.ingest.ultrafeeder` exposes `->source`. That's it. Everything else is private.

Use `defn-` for helpers. It's a signal, not just a scoping tool.

### Colocate Context

If understanding a function requires reading five other namespaces, an agent has to
load all of them — and might not. Self-contained functions with minimal distant
dependencies are easier to work with correctly. This reinforces screaming
architecture: keeping ingest's parser, its schema, and its tests together means
nobody has to hunt.
