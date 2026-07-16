# Project Instructions for AI Agents

This file provides instructions and context for AI coding agents working on this project.

<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:6cd5cc61 -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/SYNC_CONCEPTS.md for details and anti-patterns.

## Agent Context Profiles

The managed Beads block is task-tracking guidance, not permission to override repository, user, or orchestrator instructions.

- **Conservative (default)**: Use `bd` for task tracking. Do not run git commits, git pushes, or Dolt remote sync unless explicitly asked. At handoff, report changed files, validation, and suggested next commands.
- **Minimal**: Keep tool instruction files as pointers to `bd prime`; use the same conservative git policy unless active instructions say otherwise.
- **Team-maintainer**: Only when the repository explicitly opts in, agents may close beads, run quality gates, commit, and push as part of session close. A current "do not commit" or "do not push" instruction still wins.

## Session Completion

This protocol applies when ending a Beads implementation workflow. It is subordinate to explicit user, repository, and orchestrator instructions.

1. **File issues for remaining work** - Create beads for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **Handle git/sync by active profile**:
   ```bash
   # Conservative/minimal/default: report status and proposed commands; wait for approval.
   git status

   # Team-maintainer opt-in only, unless current instructions forbid it:
   git pull --rebase
   git push
   git status
   ```
5. **Hand off** - Summarize changes, validation, issue status, and any blocked sync/commit/push step

**Critical rules:**
- Explicit user or orchestrator instructions override this Beads block.
- Do not commit or push without clear authority from the active profile or the current user request.
- If a required sync or push is blocked, stop and report the exact command and error.
<!-- END BEADS INTEGRATION -->


## Build & Test

`bb` is the command surface. Prefer it over raw `clojure`/`shadow-cljs` invocations.

```bash
bb dev          # backend + shadow-cljs watch + Garden watch
bb css          # compile the stylesheet (src/css -> resources/public/app.css)
bb test         # everything — clj, cljc, cljs. What CI runs.
bb test:clj     # JVM only. Fast. Run this most.
bb test:cljs    # browser suite, real headless Chromium via Playwright
bb lint         # clj-kondo — treat its warnings as failures
bb build        # production artifacts
```

**`resources/public/app.css` is BUILD OUTPUT — do not edit it.** It is generated
from Garden namespaces under `src/css/` and gitignored, exactly as
`resources/public/js` is generated from `src/cljs/`. Start at `adsb.css.app`,
which is the cascade. Declarations go through `adsb.css.decl/decl`, never a map
literal — read that namespace's docstring before you doubt it.

CLJS tests run in a **real browser**, not jsdom — jsdom has no layout engine and no
WebGL, so MapLibre cannot initialize and `getBoundingClientRect` returns zeros. Do
not "simplify" this to jsdom; it produces a green suite that proves nothing.

## The REPL

**An nREPL is already running on port 7888 when you start.** `bb dev` brings it up
alongside the backend and the shadow watch. **Do not start your own** — a second `bb dev`
collides on 7888 and 8280. If `clojure_eval` fails to connect, the REPL is down: say so
and stop. Do not work around it by guessing.

Clojure MCP exposes it as `clojure_eval`. The tools are deferred, so load them first:
`ToolSearch` with `select:mcp__clojure-mcp__clojure_eval`.

Use it. Verify behavior by evaluating against the running system instead of reasoning
from source. `src/cljc/` is pure and takes time as an argument, so anything there can be
exercised directly with a literal map and a literal `now-ms` — no fixtures, no mocking,
no clock:

```clojure
(require '[adsb.aircraft :as ac] :reload)  ; :reload — always, or you get a stale namespace
ac/age-out-threshold-ms                          ;=> 120000
(ac/aged-out? #:aircraft{:seen-at-ms 0} 120001)  ;=> true — silent 2 min, out of the picture
```

That port is the JVM REPL. ClojureScript evaluation goes through shadow-cljs on its own
nREPL — pass that port to `clojure_eval` explicitly.

**The live-feeder rule extends here.** Slurping `dietpi.local:8100` from the REPL is
testing against a live feeder, and it is still forbidden. Replay a recorded payload.

## Architecture Overview

A live aircraft map. An ultrafeeder container on the home server receives ADS-B
broadcasts; this app ingests, validates, and streams them to a browser map.

```
ultrafeeder  ──poll aircraft.json──►  backend (JVM)  ──SSE──►  browser
                                       validate + normalize      MapLibre
```

```
src/clj/adsb/     backend — ingest/, stream/ (SSE), http/ (reitit)
src/cljc/adsb/    shared domain — pure. aircraft, schema (Malli), geo.
src/cljs/adsb/    frontend — Reagent + re-frame chrome, MapLibre map
src/css/adsb/     the stylesheet — Garden. Compiles to resources/public/app.css.
```

Stack: deps.edn + babashka + shadow-cljs · Ring/reitit + http-kit · Malli ·
Reagent + re-frame · MapLibre GL JS · clojure.test / cljs.test.

**Three decisions that look like mistakes if you don't know why:**

1. **The aircraft layer does not go through React.** Hundreds of aircraft updating
   every second will crawl if each is a Reagent component. Positions are pushed
   straight into a MapLibre GeoJSON source via `setData`. re-frame owns the chrome;
   the map owns the planes.
2. **Ingest sits behind a protocol** (`adsb.ingest.source/Source`). We poll
   `aircraft.json` today; SBS and Beast are the same data at lower latency. The swap
   must never reach the domain.
3. **The shared domain is pure.** No I/O, no atoms, no clock in `src/cljc/`. Time is
   an argument, not an ambient fact. That's what makes it testable and portable.

## Conventions & Patterns

Full detail in `docs/`. **Read `docs/clean-code-standards.md` before writing code.**
The load-bearing ones:

- **`?` for predicates, `!` for side effects.** Non-negotiable. The `!` is the only
  warning a reader gets that a function touches the world.
- **Namespaced keys in domain maps** — `:aircraft/icao`, not `:icao`.
- **Reagent components are kebab-case functions** (`aircraft-panel`), not PascalCase.
  They're not React components; they're functions returning hiccup.
- **Validate once, at the boundary, with Malli. Then trust.** The domain does not
  re-check. If you're writing defensive checks deep in the UI, the boundary leaked.
- **`(if (seq coll))`, never `(if coll)`.** Empty collections are truthy. This is the
  most common real bug in Clojure.
- **Never `clojure.core/read-string` on network data** — it evaluates code. Use
  `clojure.edn/read-string`.
- **The feeder is untrusted.** ADS-B is unauthenticated radio; anyone with a $30 SDR
  can inject fake aircraft. See `docs/validation-boundaries.md` — read it before
  touching ingest.
- **Never test against a live feeder.** The sky is not a fixture. Replay a recorded
  payload. This includes the REPL.
- **There is a REPL. Use it.** It's already running — see "The REPL" above. Evaluate
  against the running system rather than reasoning from source.
