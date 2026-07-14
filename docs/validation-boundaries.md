# Validation Boundaries

This document defines where input validation lives, and why there's more of it here
than you'd expect for a hobby project.

## Start Here: ADS-B Has No Authentication

This is the single most important fact about the system, and everything else in this
document follows from it.

**ADS-B is broadcast in the clear, with no authentication, no encryption, and no
integrity checking of any kind.** An aircraft transmits its identity and position;
anyone with a receiver hears it. The protocol was designed in an era when the ability
to transmit on 1090 MHz implied you were an aircraft. That assumption is no longer
true — a software-defined radio costs about thirty dollars, and injecting entirely
fictional aircraft into a receiver's view is a well-documented, frequently
demonstrated attack.

So when a payload arrives claiming a 747 is at 41,000 feet over Denver, the honest
description of what we know is: **somebody, somewhere, transmitted a radio signal
that says so.** That's all.

This does not mean the sky is full of attackers. It means the ingest path is a
genuine trust boundary rather than a formality, and it should be built like one. We
are not defending a bank. But we *are* parsing adversarially-shaped input from an
anonymous source, and the code should read like it knows that.

Ultrafeeder is not the adversary here. Ultrafeeder is an honest reporter of what the
antenna heard. The antenna is the boundary.

## The Trust Map

Untrusted data enters at five places, in descending order of how much it should
worry you:

1. **The ultrafeeder feed** — unauthenticated radio, relayed as JSON. The primary
   boundary. Everything below is secondary.
2. **The HTTP API** — query params, paths, and headers, from anyone on the
   internet (`map.bwawan.com`, adsb-kh4.4).
3. **Configuration and environment** — the feeder URL is operator-supplied, and it
   determines what host the server makes requests to.
4. **Dependencies** — Maven and npm packages run with full privileges at build and
   at runtime.
5. **The SSE stream, as seen by the browser** — our own server, so mostly trusted,
   but the *content* it carries originated at the antenna and is not laundered clean
   by having passed through us.

That last one is the subtle one, and it's covered in Boundary 4.

## Boundary 1 — Ingest (the important one)

Everything from the feeder is validated and coerced by **Malli**, at the edge, before
it becomes a domain aircraft. The schemas live in `src/cljc/adsb/schema.cljc`.

### The feeder payload is messier than you think

`aircraft.json` is not a clean data structure. It's the accreted output of a
real-world decoder handling real-world radio. The traps, all of which are real:

| Field | The trap |
|---|---|
| `alt_baro` | Is a **number**, *or* the **string `"ground"`**, *or* **absent entirely** — ~13% of real observations are bare mode_s targets carrying only `hex`/`messages`/`rssi`/`seen`. Not `0`. |
| `lat` / `lon` | Frequently **absent entirely**. An aircraft can be heard for minutes before it ever transmits a position — or never. |
| `flight` | The callsign, **space-padded to 8 characters** (`"UPS2717 "`). Trim it. Also often absent. |
| `hex` | Usually a 6-char ICAO address, but may be **prefixed with `~`** for non-ICAO (TIS-B / ADS-R) targets. |
| `squawk` | A **string of four octal digits** (`"7700"`), not an integer. `"0000"` is meaningful and is not nil. |
| `gs`, `track`, `baro_rate` | Any of them may be **absent** on any given cycle. Absent is not zero. |
| Any field | May be **`null`**. |

Every one of those is a test in `test/cljc/adsb/` and a real class of input in the
cast (see `testing-standards.md`).

The rule that follows: **absent is not zero, and zero is not absent.** An aircraft
with no reported altitude is not an aircraft at sea level. An aircraft with no
reported speed is not stationary. Coercing missing data into a plausible-looking
default is how you end up drawing a 747 parked in the Atlantic.

### Validate once, at the edge

The coercion path lives in `src/cljc/adsb/ingest/coerce.cljc`:

```clojure
(defn ->aircraft
  "Coerce one raw feeder entry into a domain aircraft, or nil when it
  cannot be one."
  [raw]
  (some-> raw
          coerce-raw               ; Malli schema pass; nil when invalid
          raw->aircraft            ; feeder vocabulary -> domain keys
          drop-implausible-fields))
```

Past this function, the rest of the system may **trust the data completely**. The
domain does not re-check. Handlers do not re-check. The UI does not re-check. That's
what a boundary *is* — the place where you pay the cost once so that nobody
downstream has to pay it again, or forget to.

### What is dropped, and what is kept

Decided in adsb-bvi.3, reconciling this document with the state store
(adsb-nqf.2):

- **No usable identity — `hex` missing or malformed → drop the entry.** An
  aircraft we cannot name is an aircraft we cannot track, dedupe, or age out.
- **Schema-invalid → drop the entry**, with one bounded log line (below).
- **No position — `lat`/`lon` never reported → keep the aircraft**, as a domain
  aircraft without `:aircraft/position`. Roughly a quarter of real observations
  are heard-but-never-positioned; they belong in the sidebar and the counts.
  They simply produce no map feature.
- **A schema-valid but implausible field → drop the field, keep the aircraft**
  (see the plausibility layer below).

So `(when (:aircraft/position a) ...)` in the one place that derives map
features is correct and expected — position-less is a lawful domain state, not
a validation failure. What remains a smell is *re-validating* an aircraft deep
in the UI: if a component is checking types or re-running schemas, the boundary
leaked.

### One bad aircraft must not kill the batch

A payload of 400 aircraft with one malformed entry should yield **399 aircraft and
one log line** — not an exception, not an empty map, not a dropped poll cycle.

```clojure
(defn ->aircraft-batch [raw-entries]
  (->> raw-entries
       (keep ->aircraft-or-log!)
       vec))

(defn- ->aircraft-or-log! [raw]
  (try
    (or (->aircraft raw)
        (log-rejection! raw (rejection-reason raw)))
    (catch #?(:clj Exception :cljs :default) e
      (log-rejection! raw (ex-message e)))))
```

The ingest loop is a long-running process fed by a noisy radio. It **must not die.**
A malformed entry is an ordinary Tuesday, not an exceptional condition. Treating it
as exceptional means the map goes blank because one aircraft two hundred miles away
had a corrupted burst.

Log the rejection with enough context to debug it — but do not log the entire
payload on every failure, or a stuck bad actor will fill the disk.

### Plausibility is a second, separate layer

Schema validity and physical plausibility are different questions, and conflating
them is a mistake.

An aircraft reporting 400,000 feet, or 3,000 knots, or a position in the middle of
the Pacific when your antenna is in Colorado, is **schema-valid**. Every field has
the right type. It's also nonsense — the product of a corrupted burst, a decoder
bug, or somebody with an SDR having fun.

```clojure
;; Real ground speeds arrive as doubles (450.5), so these are number
;; bounds, not :int.
(def plausible-altitude-ft
  [:and number? [:>= -1500] [:<= 60000]])

(def plausible-ground-speed-kt
  [:and number? [:>= 0] [:<= 1000]])
```

Decide explicitly, per field, what to do when plausibility fails — and write the
decision down:

- **Out of receiver range** (a position further away than physics allows for your
  antenna's horizon) → **drop it.** It didn't come from the sky you can see.
  (Built in adsb-nqf.3: `adsb.ingest.plausibility/gate-range`.) The horizon is
  configurable and defaults to **400 km (~216 nm)** — generous for 1090 MHz, which
  rarely carries much past 250 nm even under exceptional conditions. The gate is
  strictly-beyond: a position exactly at the horizon is kept. Position-less
  aircraft pass; there is nothing to gate.
- **Absurd altitude or speed** → **drop the field, keep the aircraft.** The rest of
  its data may be fine, and a plane with a garbled altitude is still a plane.
  (Built in adsb-bvi.3: `drop-implausible-fields`.)
- **Impossible position jump** (400 nm in one second) → **flag it, don't drop it.**
  This is the fingerprint of spoofing, and silently discarding it means you'd never
  know. Surface it. (Built in adsb-nqf.3:
  `adsb.ingest.plausibility/flag-position-jumps`, composed with the picture merge
  in `adsb.state/apply-batch!` — the one place the previous observation is
  available.) A new position implying a sustained speed strictly above **1200 kt**
  from the aircraft's previous observation sets `:aircraft/position-suspect? true`
  on the domain aircraft. Clearing rule: **nothing the aircraft transmits clears
  it.** The flag means "this track has made at least one impossible jump since we
  picked it up," so it sticks for the track's life in the picture — on both ingest
  paths, so the badge means one thing regardless of deployment (adsb-caf). A track
  that could clear its own mark by settling down would only need one plausible
  message to launder a spoof. The single decay boundary is the age-out sweep: an
  aircraft re-acquired after five minutes of silence is a new track and starts
  clean. The 1200 kt threshold deliberately sits above the 1000 kt per-field
  ceiling so the two layers never disagree about a fast-but-real aircraft.

The temptation is to silently clamp bad values into range. **Don't.** Clamping turns
"this data is wrong" into "this data is fine," which is the opposite of what a
boundary is for.

**Where the receiver position comes from** (adsb-nqf.3): resolved **once at poller
setup** by `adsb.ingest.receiver/resolve-position!`, never per poll. The
`ADSB_RECEIVER_LAT`/`ADSB_RECEIVER_LON` environment override wins; otherwise the
feeder's `/data/receiver.json` (readsb serves top-level `lat`/`lon`); otherwise
**the range gate is disabled** and that is logged exactly once — a feeder that
cannot say where it is must not cost aircraft blindly. Out-of-range or half-set
coordinates are rejected, never clamped.

**The receiver position is itself a secret.** It locates a home antenna, and it is
deliberately hidden from the public site. It lives only inside ingest
configuration: never attached to a domain aircraft, never stored in the state
picture, never serialized to the wire, and its coordinates are never logged. The
feeder's per-aircraft `r_dst`/`r_dir` fields (receiver-relative range and bearing)
are the same secret wearing an aircraft costume — one aircraft's position plus its
`r_dst`/`r_dir` locates the antenna exactly — and `adsb.ingest.coerce` is a
selective copy that never carries them. Tests assert both absences; committed
fixtures use synthetic receiver coordinates (see `test/resources/README.md`).

The same secret is why **the feeder is never proxied** (the adsb-kh4.4 mandate).
Ultrafeeder's `/data/receiver.json` *is* the receiver position, and its
`/data/aircraft.json` carries `r_dst`/`r_dir` on every aircraft — so no route,
in the app or at any edge in front of it, may ever forward a feeder endpoint to a
browser. The app polls the feeder privately (over the Access-gated tunnel) and
re-serves a scrubbed picture; a passthrough route would undo every scrubbing
decision above in one line. This holds even for "temporary" debugging routes —
the coverage boundary of the raw data triangulates the antenna all by itself.

## Boundary 2 — The HTTP API

This boundary faces the whole internet: TLS terminates at DigitalOcean App
Platform's router (`.do/app.yaml`) and the app container publishes no public port
of its own. The app stamps its **own** security headers (`adsb.http.security`)
rather than trusting the edge to do it, precisely because that edge is not ours —
we neither write its config nor get told when it changes. Everything below is
anonymous input from strangers.

reitit does coercion with the same Malli schemas, declaratively:

```clojure
["/api/aircraft/:icao"
 {:get {:parameters {:path [:map [:icao schema/icao-address]]}
        :responses  {200 {:body schema/aircraft}}
        :handler    handler/aircraft-detail}}]
```

- **Every parameter is coerced by a schema.** No hand-rolled `Integer/parseInt` in a
  handler. No `(keyword (:type params))` — that's unbounded keyword interning from
  user input, which is a memory leak with a friendly face.
- **Responses are schema'd too.** It catches the day a domain change silently alters
  the API contract.
- **Never build a query from a string.** No SQL today, but when history lands (bead
  pending), the parameterized-query rule arrives with it.
- **What strangers can hold open is bounded.** The SSE stream is the expensive
  resource here — each connection holds a channel forever — so admission is
  enforced in the stream registry itself (`adsb.stream.broadcast`): a total cap
  (`ADSB_SSE_MAX_CLIENTS`, default 100) and a per-IP cap (`ADSB_SSE_MAX_PER_IP`,
  default 4). Over a limit is a `503` with `Retry-After`; disconnects free the
  slot. Request lines and bodies are capped in http-kit (`adsb.http.server`).
- **Unhandled exceptions leave as a generic 500.** The message goes to the log;
  the client gets `{"error": "internal error"}` — an exception message is an
  internal detail, and http-kit would otherwise hand it over verbatim.

### The client-address trust model

The per-IP cap needs to know who's connecting, and "who" is itself untrusted
input:

- `X-Forwarded-For` is an ordinary header any client can write. It is honored
  **only** when `ADSB_TRUST_FORWARDED_FOR=true`, which is correct exactly when
  the app is reachable solely through a trusted proxy — App Platform qualifies
  (DigitalOcean's router is the only way in); a directly reachable app port does
  not.
- Trusting the header is only half of it. **Which** entry is the client depends
  on how many proxies stand in front, and each one *appends* the peer it saw —
  so the header reads left-to-right from attacker-written claim to trustworthy
  last hop, and the client sits at index `(count - hops)`. That count is
  `ADSB_TRUSTED_PROXY_HOPS` (`adsb.stream.broadcast/forwarded-ip`, default 1).
  It is a property of the **deployment**, not of the code, so it cannot
  be derived here and must be verified against the environment it runs in —
  count the entries to the right of your own address, add one. Wrong low, and
  every visitor is counted as a proxy's single address (the site locks at
  `ADSB_SSE_MAX_PER_IP` strangers); wrong high, and the per-IP key becomes
  client-chosen and spoofable, leaving only the total cap.
- On direct connections the flag stays off and the **TCP peer** is counted —
  read from the socket, not from ring's `:remote-addr`, because http-kit
  populates `:remote-addr` from the *leftmost* `X-Forwarded-For` entry whenever
  the header exists. That makes bare `:remote-addr` attacker-chosen; nothing in
  this codebase may use it for limits, logs, or allowlists.

## Boundary 3 — Configuration

`ADSB_ULTRAFEEDER_URL` decides what host the server issues HTTP requests to. That is
a **server-side request forgery** primitive, and it deserves to be named as one.

Today it's read from the environment by the operator, which makes the risk low: if
you can set the server's env vars, you have already won. So the rule is simply:

- The feeder URL comes from the **environment only**. It is never read from a request
  parameter, a header, or the SSE channel.
- Validate it at startup — it must parse, and it must be `http`/`https`. Fail loudly
  at boot, not on the first poll.

**If this ever becomes user-configurable — a settings page, a multi-feeder
selector — the risk changes completely** and you need an allowlist of permitted
hosts. Do not let it reach `http://169.254.169.254/` or `http://localhost:6379/`.
Write that bead the day the feature is proposed.

## Boundary 4 — The Browser

Reagent escapes strings in hiccup by default, so `[:span callsign]` is safe even
though `callsign` came off an unauthenticated radio. That's the default, and the
default is correct.

It stops being correct the moment anyone reaches for an escape hatch:

- **`:dangerouslySetInnerHTML`** — there is no legitimate use for this in this
  application. If it appears in a diff, that's a finding.
- **A URL built from feeder data** — `[:a {:href (str "/track/" callsign)}]` where
  `callsign` is attacker-controlled. Route params get encoded; hrefs get validated.
- **Anything reaching `js/eval`, `set!` on `.-innerHTML`, or dynamic script
  injection** — same answer.

The mental model to hold: **the callsign in your DOM is a string that an anonymous
stranger transmitted over the air.** It passed through our schema, which checked that
it's a string of a plausible length. It did not become *trustworthy* along the way.
It became *well-typed*. Those are different things, and the difference is exactly
where XSS lives.

## Boundary 5 — Dependencies

Maven and npm packages run with full privileges — at build time and at runtime.
Treat updates as their own audit step, in their own commit, so a regression is
bisectable. See `security-checklist.md` §6.

## Where Validation Does NOT Belong

Validation belongs at boundaries. Putting it anywhere else is not "extra safety" —
it's noise that obscures where the real boundary is, and it rots.

- **Not in the domain.** `src/cljc/` receives already-validated data. A domain
  function that defensively checks whether its argument is a map is a domain function
  that doesn't know what its contract is.
- **Not in Reagent components.** A component that renders "unknown" when the callsign
  is nil is fine. A component that *validates* the aircraft is a component doing the
  boundary's job, badly and late.
- **Not twice.** If ingest coerced altitude to an int, the UI does not re-parse it.

The test for whether something is a boundary: **is this the first place the data
enters our control?** If yes, validate. If no, trust — and if you don't feel you can
trust it, the real bug is upstream.

## What This Project Does NOT Have

Documenting absences explicitly, so nobody adds validation in the wrong place or
audits for a threat that doesn't exist:

- **No user accounts, no auth, no sessions** → no password validation, no JWTs, no
  session fixation. The app is read-only and anonymous.
- **No writes from the browser.** The client sends no state to the server. There is
  no `POST /aircraft`, and there should never be — the aircraft come from the sky,
  not from a form.
- **No database** (yet). When history lands, parameterized queries and a migration
  story arrive with it. Until then, there is no injection surface.
- **No file uploads, no user-supplied templates, no server-side rendering of user
  content.**
- **No secrets in the browser.** MapLibre needs no vendor token. If a tile provider
  requiring a key is ever adopted, that key becomes the first browser-visible secret
  and this section changes. See `security-checklist.md` §3.

If any of these change, reintroduce the relevant validation layer and update this
document in the same commit.
