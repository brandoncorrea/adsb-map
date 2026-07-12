# Security Checklist — Clojure & ClojureScript

Audit guide for this project's actual stack. Work through it for every feature that
touches ingest, the HTTP surface, secrets, or dependencies.

## The Core Principle

**Never trust input crossing a boundary.** Here the boundaries are:

- The ultrafeeder feed — **unauthenticated radio**, and therefore genuinely
  untrusted. See `validation-boundaries.md`; read it before this file.
- The HTTP API — anonymous, unauthenticated, and **internet-facing**
  (`map.bwawan.com`, behind the TLS proxy — see §4)
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

The app **is** public now — `map.bwawan.com`, deliberately, with the above
understood (adsb-kh4.4). That decision does not soften this section; it hardens
it. **The receiver position is a server-side secret** (the Overseer's mandate on
adsb-kh4.4): it lives only inside ingest configuration, is never attached to a
domain aircraft, never stored in the state picture, never serialized to the wire,
and never logged. The full contract, including the `r_dst`/`r_dir` trap, is in
`validation-boundaries.md` (Boundary 1, plausibility layer).

- [ ] The receiver's lat/lon is **not** in the client bundle — and the map's
      default center is a fixed, whole-degree-rounded regional point
      (`adsb.map.view/default-center`), never the receiver
- [ ] The receiver's lat/lon is **not** committed to git — it comes from env;
      committed fixtures use synthetic receiver coordinates
- [ ] Nothing receiver-relative reaches the wire — `r_dst`/`r_dir` are one
      aircraft position away from being the antenna's coordinates, and the wire
      privacy tests (`adsb.stream.broadcast-test/wire-privacy`) prove they don't
- [ ] **The feeder is never proxied.** Neither Caddy nor the app forwards any
      feeder endpoint: ultrafeeder's `/data/receiver.json` IS the receiver
      position, and `/data/aircraft.json` carries `r_dst`/`r_dir` on every
      aircraft. The app polls the feeder privately and re-serves a scrubbed
      picture; that indirection is load-bearing. A route that passes feeder
      responses through — "just for debugging" included — is a **High** finding.

### Red Flag
The receiver coordinates hard-coded in a `.cljs` file. They will ship to every
browser that loads the page, they will be in your git history forever, and they are
your house. Same severity for a reverse-proxy route pointed at the feeder.

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

The server has **no authentication**, and it is now **on the open internet**
(`map.bwawan.com`, adsb-kh4.4). Anyone on earth can reach it, so the posture is
no longer "make sure exposure is a choice" — the choice is made, and this section
is the hardening that makes it survivable.

**The exposure model:** browsers reach a Caddy sidecar (`Caddyfile` +
`compose.yaml`) that terminates TLS with automatic Let's Encrypt certificates and
stamps the security headers on every response. The app container publishes **no
port**; the only way in is through the proxy. DNS is on Cloudflare — with the
proxy toggle on, the zone must run **Full (strict)** so the Cloudflare→origin hop
is verified TLS, never "Flexible". The audit items:

- [ ] **The app port is not published.** `compose.yaml` publishes 80/443 on the
      `proxy` service and nothing on `app`. A published 8280 is the plaintext,
      header-less side door — and it also makes the app's trust in
      `X-Forwarded-For` forgeable.
- [ ] **CORS is not `*`.** The SSE endpoint sets no `Access-Control-Allow-Origin`
      at all — same-origin only. A wildcard lets any page on the internet
      subscribe to the feed from its visitors' browsers.
- [ ] **SSE connections are bounded in the app**, not the proxy (a proxy can't
      count event-stream clients well): a total cap (`ADSB_SSE_MAX_CLIENTS`,
      default 100) and a per-IP cap (`ADSB_SSE_MAX_PER_IP`, default 4), enforced
      atomically in the stream registry (`adsb.stream.broadcast`). Over the
      limit is an honest `503` + `Retry-After`; a disconnect frees the slot.
- [ ] **The per-IP count keys on an address the client cannot choose.**
      `X-Forwarded-For` is honored only under `ADSB_TRUST_FORWARDED_FOR=true` —
      set exactly when every connection provably arrives through the proxy — and
      then only its **rightmost** (proxy-appended) entry. Direct connections use
      the TCP peer, read **from the socket**: http-kit's ring `:remote-addr` is
      silently substituted with the *leftmost* `X-Forwarded-For` entry whenever
      the header is present, so `:remote-addr` is attacker-controlled and must
      never be the key for any limit, audit log, or allowlist.
- [ ] Security headers set **at the proxy** (see `Caddyfile`, directive by
      directive): a strict allowlist CSP (§6 — no `unsafe-inline`, no
      `unsafe-eval`), `Strict-Transport-Security` (180 days, no preload yet),
      `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, and a
      deny-everything `Permissions-Policy`. Note **geolocation is currently
      denied**; if the map ever centers on the user, that one entry becomes
      `geolocation=(self)` — deliberately.
- [ ] Request size is bounded: http-kit runs with an 8 KB request-line/header
      ceiling and a 16 KB body ceiling (`adsb.http.server`) — this API accepts
      no bodies, so the only job of that number is to stop anonymous buffering.
- [ ] Stack traces **and exception messages** are not returned to clients. An
      unhandled handler exception is a log line and a generic `{"error":
      "internal error"}` 500 (`adsb.http.routes/exception-middleware`). Without
      it, http-kit writes `(.getMessage e)` — hostnames, config — into the body.
- [ ] The proxy never buffers the event stream: `flush_interval -1` on the
      `reverse_proxy`, and `encode` matches an explicit content-type list that
      excludes `text/event-stream` (a compressor is a buffer wearing a hat).

### Red Flag
`{"Access-Control-Allow-Origin" "*"}` on the SSE route. It's the default in every
tutorial and it means any website you visit can silently read your feed. Second
red flag, local to this stack: any limit or log keyed on http-kit's
`:remote-addr` — that value is the client's own `X-Forwarded-For` when it sends
one.

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
- [ ] A **Content-Security-Policy** is set at the proxy, and it includes neither
      `unsafe-inline` nor `unsafe-eval` — verified in a real browser, not assumed.
      The shipped policy (rationale per directive in `Caddyfile`):
      `default-src 'none'`, `script-src 'self'`, `style-src 'self'` (Reagent and
      MapLibre both style via the CSSOM, which CSP does not gate), `img-src 'self'
      data: blob:`, `connect-src 'self' https://tiles.openfreemap.org`,
      `worker-src blob:` + `child-src blob:` (MapLibre's tile workers),
      `base-uri 'none'`, `form-action 'none'`, `frame-ancestors 'none'`
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
