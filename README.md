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

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.
