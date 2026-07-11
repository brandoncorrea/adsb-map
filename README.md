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
