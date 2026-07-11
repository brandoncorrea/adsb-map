# Security Checklist — Clojure & ClojureScript

Audit guide for this project's actual stack. Work through it for every feature that
touches ingest, the HTTP surface, secrets, or dependencies.

## The Core Principle

**Never trust input crossing a boundary.** Here the boundaries are:

- The ultrafeeder feed — **unauthenticated radio**, and therefore genuinely
  untrusted. See `validation-boundaries.md`; read it before this file.
- The HTTP API — anonymous, unauthenticated, open to anyone who reaches the port
- Configuration — decides what hosts the server talks to
- Maven and npm dependencies — run with full privileges

There is no login, no user data, and no database. The classic OWASP checklist mostly
doesn't apply. What *does* apply is unusual, so read the whole thing rather than
pattern-matching from other projects.

## 0. Privacy: Your Antenna Is At Your House

Before anything technical. This is the finding most likely to actually matter to you,
and it isn't a code bug.

**The receiver's location is your home address.** It's in the config, it's implied by
the coverage boundary of every aircraft you can see, and it's trivially derivable from
a map of your own data even if you never state it. A public map of "aircraft near
me" is a public map of *where I live*.

- [ ] The receiver's lat/lon is **not** in the client bundle unless the map is
      genuinely meant to be public
- [ ] The receiver's lat/lon is **not** committed to git — it comes from env
- [ ] If the app is exposed beyond the LAN, this was a **deliberate** decision, made
      knowing the above
- [ ] If publishing publicly: consider showing coverage without pinning the exact
      receiver site, and think about whether the *absence* of aircraft below your
      horizon quietly triangulates you anyway

### Red Flag
The receiver coordinates hard-coded in a `.cljs` file. They will ship to every
browser that loads the page, they will be in your git history forever, and they are
your house.

## 1. Secrets & Environment

- [ ] No secrets in source. Config comes from the environment.
- [ ] `.env` is gitignored; `.env.example` documents required vars with placeholders
- [ ] **Nothing secret is passed to ClojureScript via `:closure-defines`.** This is
      the CLJS equivalent of a `PUBLIC_` env var: shadow-cljs bakes the value into
      the bundle at compile time, and it ships to every browser. It is not a secret.
      It is a string in a JS file.
- [ ] Secrets are not logged. Check that config maps aren't dumped at startup — a
      `(log/info "config" config)` is how tokens end up in a log aggregator.
- [ ] Source maps are not served in production (they hand out your source, and any
      comments in it)

### Red Flag
Any `:closure-defines` entry, or any value threaded into the CLJS build, that you
would not paste into a public GitHub issue.

## 2. The Ingest Path

The primary boundary. Fully specified in `validation-boundaries.md`; the audit items:

- [ ] Every field of the feeder payload is coerced by a **Malli schema** before
      becoming a domain aircraft
- [ ] `alt_baro` is handled as **number-or-the-string-`"ground"`**
- [ ] Absent fields stay absent — they are **not** defaulted to `0`
- [ ] One malformed aircraft **cannot** kill the batch or the poll loop
- [ ] Plausibility limits are enforced separately from schema validation (altitude,
      speed, distance-from-receiver)
- [ ] Rejections are logged with context, but **rate-limited** — a stuck malformed
      transmitter must not fill the disk
- [ ] The poller has a **timeout** and a bounded retry. A hung feeder must not hang
      the server.
- [ ] The poller does not grow unboundedly: aircraft age out, and the in-memory map
      has a ceiling

### Red Flag
`(Integer/parseInt (:alt_baro raw))` anywhere. It will throw on `"ground"`, and
`"ground"` arrives every time a plane lands.

## 3. Map & Tiles

MapLibre GL JS is open source and needs no vendor token — that's a security benefit,
not just a licensing one, and it's a reason to stay on it.

- [ ] If a tile provider requiring an API key is adopted, that key is **URL-restricted**
      at the provider (locked to your origin). A browser-visible key is not a secret;
      restriction is the only control that means anything.
- [ ] Tile URLs are `https`
- [ ] The tile provider's terms permit your usage volume

## 4. The HTTP Surface

The server has **no authentication.** Anyone who can reach the port sees everything.
That's an acceptable design for a LAN service and an unacceptable one on the open
internet — the checklist is about making sure that's a choice, not an accident.

- [ ] The server **binds to the LAN interface, not `0.0.0.0`**, unless public exposure
      is intended
- [ ] **CORS is not `*`.** The SSE endpoint should permit your own origin. A wildcard
      lets any page on the internet subscribe to your feed.
- [ ] SSE connections are **bounded** — a connection limit, and a timeout on idle
      clients. An unbounded SSE endpoint is a free denial-of-service: each connection
      holds a thread or a channel, and nothing stops one client opening ten thousand.
- [ ] Security headers set: `X-Content-Type-Options: nosniff`,
      `Referrer-Policy: strict-origin-when-cross-origin`, and a `Content-Security-Policy`
      that permits only your tile origin and your own scripts
- [ ] `Permissions-Policy` denies what the app doesn't use (camera, microphone,
      payment). Note that **geolocation may be legitimately needed** if you center the
      map on the user — decide deliberately.
- [ ] Stack traces are not returned to clients. An exception is a log line, not a
      response body.

### Red Flag
`{"Access-Control-Allow-Origin" "*"}` on the SSE route. It's the default in every
tutorial and it means any website you visit can silently read your feed.

## 5. Clojure-Specific Code Hazards

These are the ones that don't show up in a generic checklist, and they're the ones
that bite Clojure teams.

- [ ] **Never `clojure.core/read-string` on untrusted input.** It honors `#=()`
      reader-eval and will execute arbitrary code. Use `clojure.edn/read-string`,
      which does not.

      ```clojure
      (read-string s)            ; ✗ arbitrary code execution
      (clojure.edn/read-string s) ; ✓
      ```

- [ ] **No unbounded keyword interning from external input.** `(keyword user-string)`
      is a memory leak: keywords are interned and never garbage collected. A remote
      caller can exhaust the heap one request at a time. Coerce to a **closed enum**
      via Malli instead.

- [ ] **No `eval`, `resolve`, `requiring-resolve`, or `load-string` on anything
      derived from input.** Not from the feeder, not from a query param, not from a
      config file that a request can influence.

- [ ] **No `clojure.java.shell/sh` with interpolated input.** There's no reason to
      shell out in this app; if one appears, scrutinize it.

- [ ] **Transit read handlers are not a free deserialization gadget** — but custom
      read handlers *can* be. Ours should be data-only. Same rule for any Java
      deserialization: don't.

- [ ] **Reflection warnings are off by default and worth enabling** — not a security
      control, but `*warn-on-reflection*` surfaces surprising interop that often
      hides a type confusion.

### Red Flag
`read-string` — the bare one — anywhere near a network payload. This is the single
most dangerous function in Clojure and its name gives you no hint of that.

## 6. Browser Hazards (ClojureScript)

Reagent escapes hiccup strings by default. Most XSS risk here comes from deliberately
leaving that default.

- [ ] **No `:dangerouslySetInnerHTML`.** There is no legitimate use in this app.
- [ ] **No `js/eval`, no `set!` on `.-innerHTML`, no dynamic `<script>` injection.**
- [ ] **No `href` or `src` built from feeder data.** A callsign is an anonymous
      stranger's string; it does not belong in a URL without validation.
- [ ] A **Content-Security-Policy** is set, and it does not include `unsafe-inline`
      or `unsafe-eval`
- [ ] `:advanced` compilation for production builds (a smaller, harder-to-read
      bundle isn't security, but the absence of dev tooling and source maps is)

### Red Flag
Any callsign, registration, or feeder-derived string interpolated into a URL, an
attribute, or raw HTML. It came off the radio. Anyone can transmit.

## 7. Dependencies

Two package ecosystems, so two audits.

- [ ] **Maven/Clojure:** run `nvd-clojure` or an equivalent CVE scan against the
      dependency tree. Findings that can't be fixed are documented and tracked in a
      bead — not ignored silently.
- [ ] **npm:** `npm audit` clean, or findings tracked. shadow-cljs pulls a real npm
      tree; it is not exempt.
- [ ] **Lockfiles committed** — `package-lock.json`, and pinned versions in
      `deps.edn`. Note that `deps.edn` pins your *direct* deps but resolves
      transitives; `clj -Stree` shows what you actually got.
- [ ] No unmaintained or deprecated direct dependencies
- [ ] Dependency updates land in **their own commit**, so a regression is bisectable
- [ ] `:mvn/repos` points only at repositories you trust. A hostile repo in the chain
      is game over.
- [ ] Git dependencies (`:git/url`) are pinned to a **SHA**, never a branch. A branch
      dep means someone else's force-push changes what you build.

## 8. Operational

- [ ] The server runs as a **non-root** user
- [ ] The JVM is patched. A container image from a year ago is a CVE list.
- [ ] Logs don't leak the receiver location or any secret
- [ ] There is a way to tell the poller is dead — a silent map looks identical to a
      quiet sky, and that ambiguity will waste an hour of your life

## Severity Levels

When reporting findings, classify them:

- **Critical** — exploitable now, no authentication needed. `read-string` on a
  network payload. `js/eval` on feeder data. A secret in the CLJS bundle. Remote code
  execution, in other words.
- **High** — exploitable by anyone who finds the port or the right URL. `CORS: *` on
  the SSE stream. An unbounded SSE endpoint. XSS via an unvalidated callsign in an
  `href`. Home coordinates in a public bundle.
- **Medium** — defense-in-depth gaps. Missing CSP. No rate limit on rejection
  logging. Binding to `0.0.0.0` without meaning to. Missing timeouts on the poller.
- **Low** — best-practice polish. Tightening `Permissions-Policy`. Enabling
  `*warn-on-reflection*`. Pinning a transitive dependency.

## What This Project Does NOT Have

Don't audit for these; they don't exist. If one appears, this checklist grows a
section the same day:

- No authentication, sessions, or user accounts
- No database, no ORM, no query builder → **no injection surface**
- No writes from the browser; no forms, no `POST` of user data
- No file uploads, no user-supplied templates
- No PII, unless you count your own home address — and you should. See §0.
