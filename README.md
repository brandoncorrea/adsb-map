# ADSB

A live aircraft map, fed by my own radio.

An [ultrafeeder](https://github.com/sdr-enthusiasts/docker-adsb-ultrafeeder)
container on the home server listens for
[ADS-B](https://en.wikipedia.org/wiki/Automatic_Dependent_Surveillance%E2%80%93Broadcast)
broadcasts from nearby aircraft. This project ingests that feed, normalizes it
into a domain model, and streams it to a browser map that shows the aircraft
moving in near-realtime.

## Stack

Full-stack Clojure, with a shared domain that both sides compile.

| Layer | Choice |
|---|---|
| Backend | Clojure (JVM) — Ring + [reitit](https://github.com/metosin/reitit), [http-kit](https://github.com/http-kit/http-kit) |
| Shared domain | `.cljc` — cross-compiled to both platforms |
| Schema & validation | [Malli](https://github.com/metosin/malli) |
| Frontend | ClojureScript — [Reagent](https://reagent-project.github.io/) + [re-frame](https://day8.github.io/re-frame/) |
| Map | [MapLibre GL JS](https://maplibre.org/) |
| Build | [deps.edn](https://clojure.org/guides/deps_and_cli) + [shadow-cljs](https://shadow-cljs.github.io/docs/UsersGuide.html) |
| Tasks | [babashka](https://babashka.org/) (`bb.edn`) |
| Tests | `clojure.test` / `cljs.test` |

## How it flows

```
ultrafeeder                backend (JVM)                    browser
┌──────────┐    poll     ┌──────────────┐     SSE      ┌──────────────┐
│  SDR /   │  ~1 Hz      │ ingest       │   ~1 Hz      │ re-frame     │
│  dump1090│ ──────────► │  ↓ validate  │ ───────────► │  ↓           │
│          │ aircraft    │  ↓ normalize │  aircraft    │ MapLibre     │
│          │  .json      │ SSE fan-out  │  state       │  GeoJSON     │
└──────────┘             └──────────────┘              └──────────────┘
```

The backend polls ultrafeeder's `/data/aircraft.json`, validates and normalizes
each aircraft against a Malli schema in the shared domain, and fans the result
out to connected browsers over Server-Sent Events. The browser hands aircraft
to MapLibre as a GeoJSON source and lets the GPU draw them.

Two decisions worth knowing up front, because they look like mistakes if you
don't know why:

- **Ingest sits behind a protocol.** We poll `aircraft.json` today, but SBS
  (TCP 30003) and Beast (TCP 30005) are the same data at lower latency.
  `adsb.ingest.source/Source` exists so that swap never reaches the domain.
- **The aircraft layer does not go through React.** Hundreds of aircraft
  updating every second will crawl if each one is a Reagent component. Aircraft
  positions are pushed straight into a MapLibre GeoJSON source via `setData`.
  re-frame owns the chrome; the map owns the planes. See
  [`archetypes/AUTEUR.md`](archetypes/AUTEUR.md).

## Quickstart

You need [Clojure](https://clojure.org/guides/install_clojure),
[babashka](https://github.com/babashka/babashka#installation), and Node (for
shadow-cljs). And an ultrafeeder you can reach.

```bash
bb dev          # backend + shadow-cljs watch, together
bb test         # everything: clj, cljc, cljs
bb build        # production artifacts
```

Point the app at your feeder:

```bash
export ADSB_ULTRAFEEDER_URL="http://homeserver.local:8080"
```

Then open http://localhost:8280.

## Basemap

The map draws on [OpenFreeMap](https://openfreemap.org)'s **liberty** style
(`https://tiles.openfreemap.org/styles/liberty`) — a richly-rendered basemap
with terrain, water, and labels.

- **No token, no key.** OpenFreeMap's public instance needs no registration and
  no API key, so **nothing secret ever reaches the browser bundle** — the map
  stays a source of zero client-visible secrets (see
  [`security-checklist.md`](docs/security-checklist.md) §3).
- **Fair use.** The public instance permits **unlimited** map views and requests,
  commercial use included, with no per-view caps — sustainable for a public
  hobby site. Sustainability is donation-funded; if that ever changes, the
  fallback is self-hosting [Protomaps](https://protomaps.com) PMTiles (no
  external dependency at all) — no code change reaches the domain, only the
  style URL in `adsb.map.view`.
- **Attribution.** MapLibre renders the style's own required credit —
  "OpenFreeMap © OpenMapTiles Data from OpenStreetMap" — automatically via the
  attribution control, which the app leaves enabled.
- **Variant vs provider.** This settles the *provider*; a later visual pass may
  swap the *variant* (liberty → bright / positron / dark) by editing the one
  style URL in [`src/cljs/adsb/map/view.cljs`](src/cljs/adsb/map/view.cljs).

## Aircraft enrichment data

The detail panel can show an airframe's **type, registration, and operator**,
looked up by ICAO hex against a static, sharded database the browser fetches
from the backend as plain files (`GET /db/<abc>.json`). This is enrichment, not
observation: it never touches the SSE wire, never blocks the live map, and
**degrades to absent** — an em-dash — whenever the database is missing or does
not know a hex.

The database is **optional** and **not committed**. Populate it with:

```bash
bb db:fetch     # downloads aircraft.csv.gz, shards it into resources/public/db/
```

- **Source:** [`tar1090-db`](https://github.com/wiedehopf/tar1090-db) —
  `aircraft.csv.gz`, whose data is maintained by
  [Mictronics](https://www.mictronics.de/aircraft-database/), a third-party
  compilation of public aircraft-registration data.
- **License:** the tar1090-db repository ships **no license file**, and the
  Mictronics page states **no explicit terms**. Treat it as third-party data of
  **unstated license** — which is exactly why it is fetched at build/dev time,
  gitignored (`resources/public/db/`), and **never committed** to this repo.
- **Degradation:** with no `resources/public/db/`, `/db/*.json` requests 404,
  the client records the shard as absent (logging once), and every enrichment
  row dashes. The map and the rest of the panel are unaffected.

## Layout

Source is split by platform because the build tools require it. Inside each
platform, group by **domain** — `aircraft`, `ingest`, `map` — never by
technical layer.

```
src/
  clj/adsb/        backend, JVM only
    ingest/          ultrafeeder polling; Source protocol
    stream/          SSE fan-out
    http/            reitit routes and handlers
  cljc/adsb/       shared domain, both platforms
    aircraft.cljc    the domain model
    schema.cljc      Malli schemas — the trust boundary
    geo.cljc         bearing, distance, bounds
  cljs/adsb/       frontend, browser only
    map/             MapLibre interop, aircraft layer
    ui/              Reagent components
    events.cljs      re-frame
    subs.cljs
test/              mirrors src/
```

## Docs

The `docs/` directory is the standards set. Read it before contributing — and
if you are an agent, read it before touching anything.

- [`clean-code-standards.md`](docs/clean-code-standards.md) — naming, functions,
  namespaces, Clojure idiom
- [`testing-standards.md`](docs/testing-standards.md) — test philosophy
- [`testing-setup.md`](docs/testing-setup.md) — how tests actually work here
- [`validation-boundaries.md`](docs/validation-boundaries.md) — where untrusted
  data enters, and what stops it
- [`security-checklist.md`](docs/security-checklist.md) — audit guide

`archetypes/` holds the agent personas — [AUTEUR](archetypes/AUTEUR.md) (UI/UX),
[BOB](archetypes/BOB.md) (clean code), [PENNY](archetypes/PENNY.md) (QA and
security).

## Issue tracking

This project uses [beads](https://github.com/gastownhall/beads). Not markdown
TODOs, not a project board.

```bash
bd ready              # what can I work on
bd show <id>
bd update <id> --claim
bd close <id>
```

## Deployment

The app ships as a container: a multi-stage [`Dockerfile`](Dockerfile) builds the
uberjar (`bb build`) in a full toolchain image and copies just the jar into a slim
JRE runtime that runs as a non-root user. Config is **environment-only** — nothing
is baked into the image.

The target is Docker in the cloud, internet-facing, reaching the home ultrafeeder
over a Cloudflare Tunnel. cloudflared runs on the **home** network (never
alongside the app), exposing the feeder as a private hostname that only the app
can read — see [Cloud-to-home feeder tunnel](#cloud-to-home-feeder-tunnel) below.

### Deploy to App Platform

[`.do/app.yaml`](.do/app.yaml) is the deployment — the only one. App Platform
builds the `Dockerfile` on its own infrastructure, runs the single container,
terminates TLS, and routes to it; there is no proxy of ours in front, which is
exactly why the app stamps its own security headers rather than delegating them to
an edge (see [TLS and the internet edge](#tls-and-the-internet-edge)).

The spec documents every environment variable inline — **read it before the first
deploy**, in particular the note on `ADSB_TRUSTED_PROXY_HOPS`, which must be
verified against the running app rather than assumed (see
[The client address](#the-client-address)).

```bash
doctl apps create --spec .do/app.yaml        # first deploy
doctl apps update <app-id> --spec .do/app.yaml
```

Secrets (`ADSB_FEEDER_AUTH_*`, `ADSB_RECEIVER_*`) are `REPLACE_ME` placeholders in
the committed spec — set the real values as encrypted app-level secrets. Setting
`ADSB_SOURCE=replay` proves the deployment, and that SSE survives the platform's
router, with no feeder in the loop at all.

### Run the container locally

Smoke-test the image (plain HTTP, no edge, **no feeder required**) — the
recorded fixture Source stands in for the sky:

```bash
docker build -t adsb:latest .             # multi-stage build → slim JRE image
docker run --rm -p 8280:8280 -e ADSB_SOURCE=replay adsb:latest
curl -s localhost:8280/healthz            # {"status":"ok","feeder-status":"ok"}
curl -s localhost:8280/api/stream         # SSE frames stream until you ^C
open http://localhost:8280                # the live map
```

The image declares a `HEALTHCHECK` that polls `/healthz`; `docker ps` shows the
container as `healthy` once it is serving.

### Configuration

All configuration is via environment variables (read once at boot,
`adsb.main/env->config`). A misconfigured live boot fails loudly on line one.

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `PORT` | no | `8280` | Port the JVM binds inside the container. |
| `ADSB_ULTRAFEEDER_URL` | yes¹ | — | Base URL of the home ultrafeeder (over the tunnel). Validated at boot; must be `http`/`https` with a host. |
| `ADSB_SOURCE` | no | live feeder | Set to `replay` to serve the recorded fixture — **no feeder needed**. Any other value (or unset) uses the live ultrafeeder. |
| `ADSB_RECEIVER_LAT` | no | feeder's `receiver.json`, else off | Receiver latitude for the range gate and max-range stat. |
| `ADSB_RECEIVER_LON` | no | feeder's `receiver.json`, else off | Receiver longitude, paired with the above. |
| `ADSB_CROP_LAT` | no³ | off (warns) | **Privacy crop** centre latitude — the disc this app publishes. A *public, arbitrary* point; **never** the receiver's. See [Hiding the antenna](#hiding-the-antenna). |
| `ADSB_CROP_LON` | no³ | off (warns) | Crop centre longitude, paired with the above. |
| `ADSB_CROP_RADIUS_KM` | no³ | off (warns) | Crop radius in km, `0 < r ≤ 400`. Must sit strictly inside real coverage in **every** bearing. |
| `ADSB_FEEDER_AUTH_ID` | no² | — | Cloudflare Access service-token **Client ID**, sent as the `CF-Access-Client-Id` header on every feeder request. |
| `ADSB_FEEDER_AUTH_SECRET` | no² | — | The service-token **Client Secret** (`CF-Access-Client-Secret`). Never logged. |
| `ADSB_SSE_MAX_CLIENTS` | no | `100` | Cap on concurrent SSE stream clients. At the cap a new connect gets `503` + `Retry-After`. |
| `ADSB_SSE_MAX_PER_IP` | no | `4` | Concurrent SSE connections allowed per client IP. |
| `ADSB_ORIGIN_TOKEN` | no⁴ | — | The **origin lock**: a shared secret Cloudflare stamps on every request to the origin. Without it the app answers 403. Unset = no lock — see [The client address](#the-client-address). |
| `ADSB_TRUST_FORWARDED_FOR` | no | `false` | Believe the client address the edge reports rather than the TCP peer. Sound **only** behind the origin lock. |
| `ADSB_TRUSTED_PROXY_HOPS` | no | `1` | Fallback only. How many proxies stand in front, deciding which `X-Forwarded-For` entry is the client — consulted only when `CF-Connecting-IP` is absent. |
| `ADSB_DEV_CSP` | no | `false` | `bb dev` only. Serves the relaxed dev Content-Security-Policy the shadow-cljs watch build needs. **Never set this in a deployment** — the boot warns loudly if you do. |
| `JAVA_OPTS` | no | tuned for a 512 MB box | JVM flags. The `Dockerfile` ships a set sized for App Platform's `basic-xxs`; override wholesale to retune. |

¹ Required for the live feeder. Not required when `ADSB_SOURCE=replay`.
² Optional, but **both or neither** — supplying only one fails the boot loudly.
Required when the feeder tunnel is gated by a Cloudflare Access policy (the cloud
deployment); omit both for a trusted-LAN feeder.
³ **All three or none.** A partial crop *fails the boot* — a privacy control that
silently degrades to "publish everything" while looking configured is worse than
one nobody set up. All three unset disables the crop and warns loudly at startup.
⁴ Not required to *boot* — the app warns loudly and runs unlocked, because refusing
to start is an outage. It **is** required for the per-IP cap to mean anything in a
deployment.

### Hiding the antenna

The receiver's position is a home address, so the wire never carries it: no
receiver coordinates, no `r_dst`/`r_dir`, no per-aircraft RSSI, no range ring, and
a map default-centre that is deliberately not the antenna (`adsb.wire`,
`adsb.ingest.receiver`).

**That is not enough on its own,** and the reason is worth understanding before you
deploy. The leak that survives a field allowlist is not in any field — it is in the
*shape of the observation set*. Publish every aircraft the antenna hears, and after
a few hours the union of those positions is a disc centred on the antenna: take the
hull, take the centroid, and you have the roof. Low altitude sharpens it (radio
horizon runs about `1.23·√(feet)` nautical miles, so the low traffic you can see
forms a small disc tight around you), and terrain and building shadows carve
persistent notches on fixed bearings that act as a fingerprint.

The fix is `ADSB_CROP_*`: publish only the aircraft inside a disc **you declared**,
so the boundary of the feed reveals your published choice rather than your horizon.
Two rules make it real rather than decorative:

1. **The centre is not the receiver** and is never derived from it. Use an
   arbitrary public point — a city, an airport.
2. **The disc sits strictly inside true coverage in every bearing.** If the crop
   pokes outside the real envelope anywhere, the shortfall in your weakest bearing
   re-reveals the geometry. Radius and altitude trade off through that radio
   horizon: a wide crop needs a high altitude floor before coverage is uniform
   across it, while a tight crop keeps the low traffic. Measure your real coverage
   polygon per altitude band before picking a radius.

The crop runs at *ingest* (`adsb.ingest.crop`, via `adsb.main/admit`), not at the
serializer — an aircraft outside it never enters `adsb.state`, so it cannot leak
through the stats, the connect-time snapshot, or a future endpoint. It is a
separate concern from the receiver-centred range gate in
`adsb.ingest.plausibility`, which stays: that one is anti-spoofing (reject what
this antenna cannot physically have heard), this one is privacy. Both run.

One thing the crop cannot help with: if you feed a public aggregator —
FlightAware, ADSBexchange, anything doing MLAT, which *requires* an accurate
position — your station's location is already semi-public on their site, and all of
the above is moot.

There is no TLS or domain configuration here: App Platform terminates TLS and
owns the certificate, so the app never sees one.

### TLS and the internet edge

The app speaks plain HTTP and publishes **no public port**. TLS terminates at
DigitalOcean's router, which is the only way in — the container is not otherwise
reachable.

**The edge is not ours, so the app trusts it with nothing.** Every security header
— a strict allowlist CSP, HSTS, `nosniff`, `Referrer-Policy`, `Permissions-Policy`
— is set in `adsb.http.security` and tested in
`test/clj/adsb/http/security_test.clj`; http-kit's `Server` header is suppressed at
the source rather than stripped downstream. Headers that live in a proxy config
stop shipping the moment the proxy changes, and a dropped CSP fails *silently*,
forever, while every health check stays green. Read that namespace, directive by
directive, before changing the policy.

Operational notes:

- **SSE must not be buffered or compressed by the edge.** A proxy that buffers
  `text/event-stream` holds frames until its buffer fills: the map freezes while
  the logs look perfect. On a proxy we controlled this was a config line; on a
  managed router it is an *assumption to verify*, and it is the open go-live
  blocker (`adsb-ju1`). Hold `curl -N` on `/api/stream` against the deployed app
  and watch for a steady ~1 frame/second.
- **SSE limits live in the app**, not the edge: `ADSB_SSE_MAX_CLIENTS` /
  `ADSB_SSE_MAX_PER_IP` above. A proxy cannot count event-stream clients well.
- **The feeder is never proxied.** No route — edge or app — forwards
  ultrafeeder's `/data/*`: those endpoints carry the receiver's coordinates
  (see `docs/validation-boundaries.md`, "The receiver position is itself a
  secret").
- **Cloudflare DNS** fronts `bwawan.com`. If the orange cloud is on for the app's
  hostname, the zone's SSL mode must be **Full (strict)** — never "Flexible",
  which would make the Cloudflare→origin hop plaintext.

#### The client address

The per-IP SSE cap counts connections per client address, and behind a proxy that
address arrives in a header — which any client can write. So there are two
questions, and the first one is the one that matters.

**1. Can the header be believed at all?** Only if the container is unreachable
except through our edge. **By default it is not.** App Platform also publishes the
app on its own `*.ondigitalocean.app` hostname, which bypasses Cloudflare entirely;
measured against the live deployment, that hostname answered anonymous requests
with `200` and cheerfully accepted a forged `X-Forwarded-For`.

`ADSB_ORIGIN_TOKEN` closes that door. Add a Cloudflare **Transform Rule** (Rules →
Transform Rules → Modify Request Header) that sets `X-Origin-Token` to a shared
secret on every request to the origin, and give the app the same value; it answers
`403` to anything without it. Generate one with `openssl rand -hex 32`, and set it
as an encrypted app-level secret. `/healthz` is deliberately exempt — App Platform's
health check reaches the container *directly*, not through Cloudflare, so a locked
`/healthz` is a container the platform believes is dead and restarts forever.

Without the lock, `ADSB_TRUST_FORWARDED_FOR=true` is trusting a stranger's typing,
and the boot warns you about exactly that.

**2. Which address is the client?** With the lock on, the app reads
**`CF-Connecting-IP`** — one address, set by Cloudflare, and overwritten if a client
tries to send its own.

It reads that rather than counting `X-Forwarded-For` hops because counting was tried
and **measured to fail**: five concurrent streams from one address, against a cap of
four, were all admitted. The chain is browser → Cloudflare → DigitalOcean's edge —
which is *itself* Cloudflare — and the address the last hop appends comes from a pool
that varies per connection, so every connection keyed under a different address and
the cap never bound. No hop *count* can repair that; there is no fixed index at which
the client reliably sits. `ADSB_TRUSTED_PROXY_HOPS` survives only as a fallback for
an edge that someday isn't Cloudflare.

### Cloud-to-home feeder tunnel

The cloud container is internet-facing; the home ultrafeeder must not be. The
connection between them is a **Cloudflare Tunnel** whose direction is the whole
point: the cloud backend reaches *in* to the home feeder, and the feeder is
reachable from nowhere else. No home port is ever forwarded.

**The thing being protected is the receiver's position**, and it can leak by two
independent routes. Both must be closed; closing one is not closing the other.

1. **The network path.** Port-forwarding the feeder and pointing DNS at it
   publishes your home IP, which geolocates to about a neighborhood. The tunnel
   closes this: cloudflared dials *outward*, so `adsb.bwawan.com` resolves to
   Cloudflare's edge and the home network needs no inbound port and no public IP.
2. **The feeder's own endpoints.** `/data/receiver.json` **is** the antenna's
   lat/lon, and `/data/aircraft.json` carries `r_dst`/`r_dir` — range and bearing
   from the antenna — on every aircraft, which one aircraft position inverts to
   the exact antenna location. **A tunnel hides your IP; it does not hide your
   data.** An ungated tunnel hostname is a CDN-accelerated port-forward that hands
   your coordinates to anyone who asks. The **Access service-token policy** is
   what closes this one, and it is not optional.

What the *app* re-serves is scrubbed by construction — `adsb.wire/aircraft->wire`
is an allowlist projection (so `r_dst`, `r_dir`, and `rssi` cannot reach a
browser), the stats map carries only the scalar `max-range-km` (a radius, no
bearing), the receiver position never leaves ingest config, and no route proxies
`/data/*`. See `docs/validation-boundaries.md`, "The receiver position is itself
a secret".

```
cloud app ──HTTPS + service-token headers──►  adsb.bwawan.com  (Cloudflare edge)
                                                     │  Access "Service Auth" policy
                                                     ▼
                                              cloudflared (HOME) ──► dietpi:8100
```

- **cloudflared runs on the home network**, beside the ultrafeeder — not in this
  repo's compose. It dials out to Cloudflare, so the home network needs no inbound
  ports and no public IP.
- **The tunnel is exactly one service wide.** The ingress rule forwards to the
  ultrafeeder's host:port and nothing else, with a `404` catch-all last. A tunnel
  is a hole in the home network; it should be the width of the one thing that
  needs to go through it, not the width of the LAN.
- **A Cloudflare Access policy** gates the tunnel hostname (`adsb.bwawan.com`)
  with a **service token**: the hostname is not publicly readable, and the cloud
  app is the only client that holds the token. Make the token the policy's *only*
  principal — no email login alongside it, so a phished account cannot reach the
  feeder either.
- **The app is a plain HTTPS client** of that hostname. It presents the token as
  two headers — `CF-Access-Client-Id` / `CF-Access-Client-Secret` — on every
  request (`aircraft.json` *and* `receiver.json`), threaded from
  `ADSB_FEEDER_AUTH_ID` / `ADSB_FEEDER_AUTH_SECRET` through
  `adsb.ingest.config/feeder-auth-headers`.

**Why this over the alternatives.** `cloudflared access tcp` on the cloud side
would work but adds a second daemon to the cloud container and a localhost hop for
no gain over an authenticated HTTPS GET. WARP/WireGuard puts the whole cloud host
on the home network — far more access than "read one JSON file" needs. The
service-token pattern grants exactly the reach required and nothing more.

**Failure mode is safe.** A downed tunnel, a revoked token, or a missing header
all yield a non-2xx from the edge, which the poll loop already treats as a feeder
outage: it backs off, and the browser shows the feeder as unhealthy. No special
handling, and no way for a live SSE stream to masquerade as healthy over a dead
feeder.

**The residual leak, stated honestly.** The map shows a disc of coverage centered
on the antenna, and anyone can estimate its center from where reception fades.
That is inherent to publishing *any* live map from a single receiver, and no
amount of scrubbing removes it. What everything above buys is that the disclosure
stays at neighborhood resolution instead of being a lat/lon in a JSON endpoint. If
that is still too much, the answer is not another header — it is not publishing
the map publicly.

#### Runbook

The steps below are **operational** — they run against the Overseer's Cloudflare
account and the home server, so they belong to go-live (`adsb-kh4.6`), not to the
image. They are recorded here so go-live is a checklist, not a research project.

*On the home server* (where the ultrafeeder lives):

1. **Install cloudflared** and authenticate it to the Cloudflare account:
   `cloudflared tunnel login`.
2. **Create the tunnel:** `cloudflared tunnel create adsb-feeder`. This writes a
   credentials file and prints a tunnel UUID.
3. **Add the ingress rule** so the tunnel forwards to the local feeder. In the
   tunnel config (`~/.cloudflared/config.yml`):
   ```yaml
   tunnel: <tunnel-UUID>
   credentials-file: /home/dietpi/.cloudflared/<tunnel-UUID>.json
   ingress:
     - hostname: adsb.bwawan.com
       service: http://dietpi:8100      # or http://localhost:8100 if
                                         # cloudflared runs on the feeder box
     - service: http_status:404          # catch-all, required last
   ```
   Note: cloudflared resolves `service:` from the home network, so use the LAN
   name/IP the feeder answers on (`dietpi:8100`, `localhost:8100`, or the LAN IP).
4. **Route DNS** to the tunnel:
   `cloudflared tunnel route dns adsb-feeder adsb.bwawan.com`. This creates the
   proxied CNAME on the `bwawan.com` zone.
5. **Run it** as a service: `cloudflared tunnel run adsb-feeder` (or
   `cloudflared service install` for a persistent systemd unit).

*In the Cloudflare Zero Trust dashboard* (Access):

6. **Create a self-hosted Access application** for `adsb.bwawan.com`.
7. **Add a policy with action _Service Auth_** (not Allow) whose only rule is
   *Include → Service Token → (the token from the next step)*. This is what makes
   the hostname unreadable without the token.
8. **Create the service token** (Access → Service Auth → Service Tokens). Copy the
   **Client ID** and **Client Secret** — the secret is shown once.

*On the cloud side* (App Platform — see [Deploy to App Platform](#deploy-to-app-platform)):

9. **Set the credentials as encrypted app-level secrets** — in the control panel,
   or by replacing the `REPLACE_ME` placeholders in a *local, uncommitted* copy of
   the spec. `.do/app.yaml` already declares them `type: SECRET`:
   ```
   ADSB_ULTRAFEEDER_URL=https://adsb.bwawan.com
   ADSB_FEEDER_AUTH_ID=<client-id>
   ADSB_FEEDER_AUTH_SECRET=<client-secret>
   ```
   A real service-token secret committed to this repo is a leaked credential —
   rotate it in Cloudflare, don't just amend the commit.
10. **Deploy:** `doctl apps update <app-id> --spec .do/app.yaml`. A boot with only
    one of the two auth vars fails loudly (`… must be set together`) rather than
    silently fetching the feeder unauthenticated.

    Note the receiver position (`ADSB_RECEIVER_LAT`/`LON`) is *also* an encrypted
    secret. You can omit both — the app reads `receiver.json` from the feeder over
    the authenticated tunnel at boot — but setting them explicitly keeps the range
    gate working even when `receiver.json` isn't served.

**Verify** the Access policy actually gates the hostname. Check
`receiver.json` — not just `aircraft.json` — because that endpoint *is* the
coordinates, and it is the one whose exposure is unrecoverable:

```bash
# Without the token → the Access policy blocks it (302 to login / 403), NOT 200.
for path in /data/receiver.json /data/aircraft.json; do
  printf '%s -> ' "$path"
  curl -sS -o /dev/null -w '%{http_code}\n' "https://adsb.bwawan.com$path"
done

# With the token → 200 and the JSON.
curl -sS https://adsb.bwawan.com/data/aircraft.json \
  -H "CF-Access-Client-Id: $ADSB_FEEDER_AUTH_ID" \
  -H "CF-Access-Client-Secret: $ADSB_FEEDER_AUTH_SECRET" | head
```

If either unauthenticated request returns `200`, the policy is misconfigured and
the hostname is public. Fix the Access application **before** go-live — and if
`receiver.json` was reachable while the tunnel was up, treat the position as
disclosed, because you cannot know who read it.

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.
