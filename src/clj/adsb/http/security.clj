(ns adsb.http.security
  "The security response headers, owned by the APP rather than the edge.

  They used to live in a Caddy reverse-proxy config, which was fine while
  that proxy was the only way in (adsb-kh4.4). It stopped being fine the
  moment the app was deployed somewhere that proxy isn't: DigitalOcean
  App Platform terminates TLS and routes to the container itself, so a
  proxy config of ours never runs and every header below would silently
  stop shipping. A missing CSP does not fail a health check; it fails
  quietly, forever. So the app sets them itself and the edge is free to
  be anything — which it now is, because the edge belongs to DigitalOcean
  and we neither write its config nor hear about it when it changes
  (adsb-kh4.7 retired the proxy we controlled).

  Setting them here is also strictly safer than setting them at an edge:
  a header the app emits cannot be lost by a proxy that forgets it, and
  no proxy needs to know anything about our threat model.

  The Content-Security-Policy is an ALLOWLIST and deliberately narrow —
  `default-src 'none'` means anything not named below is refused. The one
  cross-origin fact in the whole app is the basemap
  (adsb.map.view/style-url, https://tiles.openfreemap.org); MapLibre
  fetches its tiles from a blob-backed web worker, which is what
  `worker-src`/`child-src blob:` and `img-src blob:` are for. If a future
  change adds a third-party origin, it goes here or it does not load."
  (:require [clojure.string :as str]
            [ring.util.response :as response]))

(def ^:const content-security-policy
  "Strict, allowlist-only. The app is CSP-friendly by construction — no
  inline scripts, no eval, no third-party JS — so none of this needs an
  escape hatch, and any future directive that seems to need one is a
  design smell first and a header change second."
  (str
    ;; deny anything not explicitly allowed below
    "default-src 'none'; "
    ;; the compiled bundle (/js/main.js) only
    "script-src 'self'; "
    ;; app.css + the vendored maplibre-gl.css. Both Reagent/React and
    ;; MapLibre set element styles through the CSSOM, which CSP does not
    ;; gate, so NO 'unsafe-inline' is needed.
    "style-src 'self'; "
    ;; the @font-face faces in app.css, served from resources/public/fonts.
    ;; Self-hosted, so 'self' — the app fetches from no font CDN, and a
    ;; typeface is not worth a third-party origin. NOT for the basemap
    ;; glyphs: those are PBF, fetched by MapLibre with fetch(), and so are
    ;; governed by connect-src, not by this directive.
    "font-src 'self'; "
    ;; 'self' for static assets; data: for the inline SVG icons in
    ;; maplibre-gl.css; blob: for MapLibre-decoded sprite images
    "img-src 'self' data: blob:; "
    ;; 'self' for the SSE stream + API; the tile origin for
    ;; style/tiles/glyphs/sprites, which MapLibre loads with fetch
    ;; (adsb.map.view/style-url)
    "connect-src 'self' https://tiles.openfreemap.org; "
    ;; MapLibre spawns its tile workers from a blob: URL. child-src is
    ;; the legacy alias some engines still consult.
    "worker-src blob:; "
    "child-src blob:; "
    ;; the app has no <base> and no forms
    "base-uri 'none'; "
    "form-action 'none'; "
    ;; never embeddable — anti-clickjacking
    "frame-ancestors 'none'"))

(def ^:const dev-content-security-policy
  "The policy for `bb dev` ONLY: the real policy plus exactly TWO
  relaxations, each for a specific piece of DEV TOOLING that the shipped
  build does not contain. A dev policy that diverges further than it must
  is a dev policy that has stopped catching the CSP mistakes it exists to
  catch, so both were measured in a real browser, not assumed.

  1. style-src-attr 'unsafe-inline' — for the shadow-cljs dev HUD, the
     overlay that shows compile errors. It builds its DOM with shadow.dom,
     which sets `style` ATTRIBUTES; those are governed by style-src-attr,
     which — absent from the real policy — falls back to `style-src 'self'`
     and is refused. Note this is the ATTRIBUTE case only: style-src itself
     stays 'self', so inline <style> elements and stylesheet injection are
     as forbidden in dev as in production. Adding 'unsafe-inline' to
     style-src instead would have permitted all of that too.

  2. script-src 'unsafe-eval' — because the DEV bundle runs on eval(). The
     watch build is not one compiled file: it is a module loader plus a
     file per namespace under js/cljs-runtime, and it brings them in with
     eval. Under `script-src 'self'` every one of those throws EvalError
     and the app never mounts — a blank page, not a degraded one. The CLJS
     REPL (the shadow-cljs nREPL — CLAUDE.md, 'The REPL') evaluates through
     the same door.

  3. connect-src ws: — for shadow's devtools WebSocket
     (shadow.cljs.devtools.client.websocket), the channel hot reload
     arrives on. It dials the shadow-cljs server on a DIFFERENT port than
     the app, so 'self' does not cover it.

  None of the three can reach production, because none of the tooling
  exists there. `shadow-cljs release` — the only build the Dockerfile
  makes, via `bb build` — compiles ONE optimized bundle with no eval, no
  HUD, and no devtools socket; nothing in src/cljs pulls any of them in.
  This policy is served only when ADSB_DEV_CSP is set, which the
  deployment specs never set, `bb dev` always sets, and the boot warns
  loudly about."
  (-> content-security-policy
      (str/replace-first "script-src 'self'" "script-src 'self' 'unsafe-eval'")
      (str/replace-first
        "connect-src 'self' https://tiles.openfreemap.org"
        (str "connect-src 'self' https://tiles.openfreemap.org "
             "ws://localhost:* ws://127.0.0.1:*"))
      (str "; style-src-attr 'unsafe-inline'")))

(def ^:private base-headers
  "The security headers set on every response.

  Strict-Transport-Security rides every response, including the plain-HTTP
  ones served in dev: the spec requires a browser to IGNORE an HSTS header
  that did not arrive over TLS, so this is inert locally and load-bearing
  in production, with no conditional to get wrong. 180 days — deliberately
  no `preload` (hard to undo) and no includeSubDomains (this host has
  none); raise max-age once the deployment has soaked.

  Referrer-Policy: the app navigates nowhere that needs one, and the URL
  is nobody's business. Permissions-Policy denies every powerful feature,
  because the app uses none of them — if the map ever centers on the user,
  geolocation becomes =(self), and that is a decision someone makes on
  purpose, not a default that drifted.

  No `Server` header appears here because it is not ours to add — http-kit
  writes its own, and adsb.http.server suppresses it at the source.

  The CSP is not in here: it is the one header that differs between dev
  and production, so `headers` assembles it."
  {"Strict-Transport-Security" "max-age=15552000"
   "X-Content-Type-Options"    "nosniff"
   "Referrer-Policy"           "no-referrer"
   "Permissions-Policy"        (str "accelerometer=(), camera=(), "
                                    "geolocation=(), gyroscope=(), "
                                    "magnetometer=(), microphone=(), "
                                    "payment=(), usb=()")})

(defn headers
  "The security headers to set on every response. `dev-csp?` swaps in the
  dev Content-Security-Policy — see dev-content-security-policy for why
  the dev build needs one and why production can never get it. Default:
  the strict policy, so the only way to weaken it is to ask."
  ([] (headers false))
  ([dev-csp?]
   (assoc base-headers
          "Content-Security-Policy"
          (if dev-csp?
            dev-content-security-policy
            content-security-policy))))

(defn- secure
  "Add the security headers to a response, overriding rather than
  deferring to whatever the handler set: these are the app's policy, and
  a handler does not get to weaken them. nil passes through untouched —
  a nil response means no handler matched, and Ring, not us, decides what
  that becomes."
  [resp response-headers]
  (when resp
    (reduce-kv response/header resp response-headers)))

(defn wrap-security-headers
  "Ring middleware: every response leaves with the headers above.

  Wrap the OUTERMOST handler — the static assets and the 404 need these
  as much as the API does, and the 404 in particular is the response an
  anonymous scanner is most likely to see.

  `dev-csp?` (default false) serves the dev Content-Security-Policy
  instead of the real one. It is threaded from the composition root, from
  ADSB_DEV_CSP, and no deployment sets it.

  The SSE response is the one thing this cannot reach, and that is
  correct: adsb.stream.broadcast switches the request to an http-kit
  async channel and writes its own response head (adsb.stream.sse/headers),
  so nothing here applies to it. An event-stream is not a document — it
  has no origin to police, nothing to sniff, and no referrer to leak."
  ([handler] (wrap-security-headers handler false))
  ([handler dev-csp?]
   (let [response-headers (headers dev-csp?)]
     (fn
       ([request] (secure (handler request) response-headers))
       ([request respond raise]
        (handler request
                 (fn [resp] (respond (secure resp response-headers)))
                 raise))))))
