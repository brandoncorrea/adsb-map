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
over a Cloudflare Tunnel. cloudflared runs on the **home** network (never in this
compose), exposing the feeder as a private hostname that only the app can read —
see [Cloud-to-home feeder tunnel](#cloud-to-home-feeder-tunnel) below.

### Build and run

```bash
docker build -t adsb:latest .        # multi-stage build → slim JRE image
docker compose up -d --build         # build + run app AND the TLS proxy
docker compose logs -f app
```

Compose brings up two services: the `app`, reachable only on the compose
network, and the Caddy `proxy` — the sole published ports (80/443). See
[TLS and the internet edge](#tls-and-the-internet-edge).

Smoke-test the image alone (plain HTTP, no proxy, **no feeder required**) — the
recorded fixture Source stands in for the sky:

```bash
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
| `ADSB_FEEDER_AUTH_ID` | no² | — | Cloudflare Access service-token **Client ID**, sent as the `CF-Access-Client-Id` header on every feeder request. |
| `ADSB_FEEDER_AUTH_SECRET` | no² | — | The service-token **Client Secret** (`CF-Access-Client-Secret`). Never logged. |
| `ADSB_SSE_MAX_CLIENTS` | no | `100` | Cap on concurrent SSE stream clients. At the cap a new connect gets `503` + `Retry-After`. |
| `ADSB_SSE_MAX_PER_IP` | no | `4` | Concurrent SSE connections allowed per client IP. |
| `ADSB_TRUST_FORWARDED_FOR` | no | `false` | Honor the proxy-appended `X-Forwarded-For` for the per-IP limit. Set `true` **only** when the app port is reachable exclusively through the trusted proxy (the compose deployment sets it). |
| `ADSB_DOMAIN` | no³ | `map.bwawan.com` | Public hostname the Caddy proxy answers and gets certificates for. |
| `ACME_EMAIL` | no³ | operator email | ACME account email for certificate-expiry warnings. |
| `JAVA_OPTS` | no | — | Extra JVM flags (heap, GC, …) passed to `java`. |

¹ Required for the live feeder. Not required when `ADSB_SOURCE=replay`.
² Optional, but **both or neither** — supplying only one fails the boot loudly.
Required when the feeder tunnel is gated by a Cloudflare Access policy (the cloud
deployment); omit both for a trusted-LAN feeder.
³ Read by the `proxy` (Caddy) service, not the app.

### TLS and the internet edge

The app itself speaks plain HTTP and publishes **no port**. Internet exposure
goes through the **Caddy sidecar** in `compose.yaml`: it terminates TLS with
automatic Let's Encrypt certificates on 80/443 and stamps the security headers
(strict CSP, HSTS, `nosniff`, `Referrer-Policy`, `Permissions-Policy`) on every
response. The `Caddyfile` documents every header and directive inline — read it
before changing either file.

Operational notes:

- **Cloudflare DNS.** Works DNS-only or proxied. If the orange cloud is on, set
  the zone's SSL mode to **Full (strict)** — never "Flexible" — and do the
  *first* certificate issuance grey-cloud (details in the `Caddyfile` header).
- **SSE is never buffered or compressed at the proxy** (`flush_interval -1`,
  and the `encode` matcher excludes `text/event-stream`). If the map freezes
  behind a different proxy someday, look there first.
- **SSE limits live in the app**, not the proxy: `ADSB_SSE_MAX_CLIENTS` /
  `ADSB_SSE_MAX_PER_IP` above. A proxy cannot count event-stream clients well.
- **The feeder is never proxied.** No route — Caddy or app — forwards
  ultrafeeder's `/data/*`: those endpoints carry the receiver's coordinates
  (see `docs/validation-boundaries.md`, "The receiver position is itself a
  secret").
- Validate proxy config changes with `caddy validate --config Caddyfile`.

### Cloud-to-home feeder tunnel

The cloud container is internet-facing; the home ultrafeeder must not be. The
connection between them is a **Cloudflare Tunnel** whose direction is the whole
point: the cloud backend reaches *in* to the home feeder, and the feeder is
reachable from nowhere else. No home port is ever forwarded.

```
cloud app ──HTTPS + service-token headers──►  feeder.bwawan.com  (Cloudflare edge)
                                                     │  Access "Service Auth" policy
                                                     ▼
                                              cloudflared (HOME) ──► dietpi:8100
```

- **cloudflared runs on the home network**, beside the ultrafeeder — not in this
  repo's compose. It dials out to Cloudflare, so the home network needs no inbound
  ports and no public IP.
- **A Cloudflare Access policy** gates the tunnel hostname (`feeder.bwawan.com`)
  with a **service token**: the hostname is not publicly readable, and the cloud
  app is the only client that holds the token.
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
     - hostname: feeder.bwawan.com
       service: http://dietpi:8100      # or http://localhost:8100 if
                                         # cloudflared runs on the feeder box
     - service: http_status:404          # catch-all, required last
   ```
   Note: cloudflared resolves `service:` from the home network, so use the LAN
   name/IP the feeder answers on (`dietpi:8100`, `localhost:8100`, or the LAN IP).
4. **Route DNS** to the tunnel:
   `cloudflared tunnel route dns adsb-feeder feeder.bwawan.com`. This creates the
   proxied CNAME on the `bwawan.com` zone.
5. **Run it** as a service: `cloudflared tunnel run adsb-feeder` (or
   `cloudflared service install` for a persistent systemd unit).

*In the Cloudflare Zero Trust dashboard* (Access):

6. **Create a self-hosted Access application** for `feeder.bwawan.com`.
7. **Add a policy with action _Service Auth_** (not Allow) whose only rule is
   *Include → Service Token → (the token from the next step)*. This is what makes
   the hostname unreadable without the token.
8. **Create the service token** (Access → Service Auth → Service Tokens). Copy the
   **Client ID** and **Client Secret** — the secret is shown once.

*On the cloud host:*

9. **Wire the env** (via the git-ignored `.env` next to `compose.yaml`, or the
   host's secret store — never committed):
   ```bash
   ADSB_ULTRAFEEDER_URL=https://feeder.bwawan.com
   ADSB_FEEDER_AUTH_ID=<client-id>
   ADSB_FEEDER_AUTH_SECRET=<client-secret>
   ```
10. **Deploy:** `docker compose up -d --build`. A boot with only one of the two
    auth vars fails loudly (`… must be set together`).

**Verify** the Access policy actually gates the hostname:

```bash
# Without the token → the Access policy blocks it (302 to login / 403), NOT 200.
curl -sS -o /dev/null -w '%{http_code}\n' https://feeder.bwawan.com/data/aircraft.json

# With the token → 200 and the aircraft JSON.
curl -sS https://feeder.bwawan.com/data/aircraft.json \
  -H "CF-Access-Client-Id: $ADSB_FEEDER_AUTH_ID" \
  -H "CF-Access-Client-Secret: $ADSB_FEEDER_AUTH_SECRET" | head
```

If the first request returns `200`, the policy is misconfigured — the hostname is
public. Fix the Access application before go-live.

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.
