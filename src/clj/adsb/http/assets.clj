(ns adsb.http.assets
  "Content-addressed URLs for the static frontend, so it can be cached
  forever and still never be stale (adsb-8cb).

  WHY THIS SHAPE. The obvious fix — Cache-Control with a revalidation —
  was built, deployed, and MEASURED, and the edge threw it away: no
  conditional request survives the trip. `If-Modified-Since` never
  reaches this container (Cloudflare in front of DigitalOcean's own
  Cloudflare), so the 304 the app is perfectly capable of serving is
  never asked for, and a `no-cache` body is one Cloudflare declines to
  store at all (cf-cache-status: BYPASS). Revalidation-based caching is
  simply not available to us here. What the edge WILL honour is a
  positive max-age.

  But a max-age on /js/main.js is a promise the name cannot keep: the
  bundle is /js/main.js at every deploy, so a cached copy whose max-age
  has not expired is a cache serving the OLD app with no way to be told
  otherwise. The name is the problem, not the header.

  So the URL carries the bytes' identity. Every asset is served under
  /assets/<version>/… where <version> is a hash OF THE ASSET BYTES
  THEMSELVES, and gets `immutable, max-age=1y`. Change the bundle and
  the hash changes, so the URL changes, so every cache on earth misses
  and fetches the new one — not because a timer expired, but because it
  is a different resource, which it is. Staleness is impossible by
  construction rather than bounded by a TTL.

  index.html is the one thing that CANNOT be cached that way: it is what
  names the current version, so it must always be fresh. It stays
  `no-cache`, which costs ~600 compressed bytes a load — the price of
  the other ~350 KB never being fetched twice.

  Hashing the BYTES, not the build clock, is what makes the version
  stable across restarts and across replicas of the same build: two
  containers running the same image serve the same URLs, and a rebuild
  that changes nothing changes no URL."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.security MessageDigest)))

(def ^:const asset-root
  "The path prefix under which the fingerprinted assets are served. The
  version segment that follows is a cache key and nothing else — see
  handler."
  "/assets")

(def ^:const fingerprinted-assets
  "The assets whose URLs index.html carries, and therefore the assets
  whose bytes decide the version. These are exactly the files referenced
  by resources/public/index.html — the compiled bundle and the two
  stylesheets. Add a <link> or a <script> there and it belongs here too,
  or it will be served unfingerprinted and uncached."
  ["js/main.js" "app.css" "maplibre-gl.css"])

(def ^:const immutable-cache-control
  "A year, and `immutable` on top: do not merely refrain from asking
  whether this changed — understand that it CANNOT have. The URL names
  the bytes, so a different body would be a different URL. `immutable`
  is what stops a browser from revalidating on an explicit reload, which
  is the one case where max-age alone still generates a request."
  "public, max-age=31536000, immutable")

(def ^:const document-cache-control
  "index.html: cacheable anywhere, but revalidate every single time. It
  is the map to every other asset, so a stale one points at a bundle
  that may no longer exist. Deliberately NOT max-age'd — see the ns
  docstring for why the bundle can be and this cannot."
  "public, no-cache")

(def ^:const data-cache-control
  "A day, for the assets that are NOT coupled to a deploy: the aircraft
  database (resources/public/db — 1,001 shards, 42 MB, fetched by
  adsb.enrich whenever a click lands on an aircraft), the basemap glyphs
  (adsb.map.basemap), and the fonts.

  These are the bulk of the bytes and the reason this matters at all;
  the ~1 MB bundle the bead was written about is the small part. Left
  uncacheable they come off the container on every page load and every
  click, forever.

  They get a TTL rather than a content-addressed URL because the
  question that decides between the two is ONLY 'what does a stale copy
  cost?'. For the bundle, a stale copy is old app code talking to a new
  backend — a bug, so its URL names its bytes and staleness is made
  impossible. For a registry shard it is an aircraft's type code being a
  day out of date, and for a glyph it is nothing at all: these change
  when someone re-runs `bb db:fetch`, not when someone deploys. A day of
  that is a fair price for 42 MB that stops moving.

  Content-addressing these too would mean the compiled bundle knowing
  the version at runtime (adsb.enrich builds its own shard URLs), which
  is real frontend plumbing for a staleness window nobody can feel."
  "public, max-age=86400")

(def ^:const long-lived-prefixes
  "URI prefixes served with data-cache-control. Everything else that is
  not fingerprinted and not index.html stays no-cache — the default is
  the cautious one, and an asset earns a TTL by being named here."
  ["/db/" "/glyphs/" "/fonts/"])

(defn- long-lived? [uri]
  (boolean (some #(str/starts-with? uri %) long-lived-prefixes)))

(defn- resource
  "The classpath resource for an asset path, or nil. Assets live under
  resources/public, which is `public/` on the classpath and inside the
  uberjar alike — the same lookup works in dev and in the container."
  [path]
  (io/resource (str "public/" path)))

(defn- hex [bytes]
  (str/join (map #(format "%02x" %) bytes)))

(defn content-version
  "A short hex digest over `entries` — a seq of [name source] pairs, in
  order, where source is anything clojure.java.io can open (a URL, a
  File, a byte array) or nil for an asset that is not there.

  Same bytes in, same version out — no clock, no counter, no build id —
  so it is stable across restarts and identical across replicas of one
  image, and it changes if and only if the bytes do. That is the whole
  contract `immutable` rests on, which is why this takes its inputs as
  an argument: the property can then be tested against bytes in memory
  rather than by editing the build output of a running watch.

  The CONTENTS and not the timestamps, deliberately. Hashing (path,
  mtime, size) would be cheaper and is the obvious shortcut, but a build
  that normalises entry timestamps for reproducibility — which jar
  tooling may well do — would then hand two different bundles the SAME
  version. Under `immutable` that is not a slow page; it is a browser
  pinned to a stale app for a year with no way to be told otherwise. The
  expensive hash is the one that cannot lie.

  A MISSING source is folded in as its absence rather than throwing: a
  checkout that has not run `bb build` has no js/main.js, and the server
  should still start and serve what it does have — the 404 is honest, a
  crash at boot would not be. MessageDigest is the interop we keep;
  clojure.core has no SHA-256."
  [entries]
  (let [digest (MessageDigest/getInstance "SHA-256")
        buffer (byte-array 8192)]
    (doseq [[name source] entries]
      (.update digest (.getBytes ^String name "UTF-8"))
      (when source
        (with-open [in (io/input-stream source)]
          (loop []
            (let [n (.read in buffer)]
              (when (pos? n)
                (.update digest buffer 0 n)
                (recur)))))))
    (subs (hex (.digest digest)) 0 12)))

(defn- digest-version
  "content-version over the fingerprinted assets as they are on the
  classpath right now."
  []
  (content-version (map (juxt identity resource) fingerprinted-assets)))

(defn- staleness-key
  "A cheap (mtime, size) probe of the fingerprinted assets — NOT the
  version, just a way to notice that recomputing the version is worth
  it. See `current`."
  []
  (mapv (fn [path]
          (when-let [res (resource path)]
            (let [conn (.openConnection res)]
              [path (.getLastModified conn) (.getContentLengthLong conn)])))
        fingerprinted-assets))

(defn asset-url
  "The versioned URL for an asset — /assets/<version>/js/main.js."
  [version path]
  (str asset-root "/" version "/" path))

(defn- versioned-path
  "The asset path inside a /assets/<version>/… request URI, or nil if
  this is not one. The version segment is NOT checked against the
  current version, and that is deliberate: it is a cache key, not a
  credential. Serving the current bytes for a stale version URL is
  harmless — nothing links to it, and the alternative (a 404) would turn
  a client holding an old index.html into a broken page instead of a
  merely slow one.

  Returns the tail with a leading slash, ready to hand to the resource
  handler — which is where the traversal guard lives: `..` in the tail
  resolves outside resources/public and is refused there (verified), so
  this deliberately does not re-implement that check."
  [uri]
  (when-let [[_ tail] (re-matches #"/assets/[^/]+(/.+)" uri)]
    tail))

(defn index-html
  "resources/public/index.html with its asset URLs rewritten to the
  versioned ones. Rendered when the version changes, not per request
  (see `current`) — re-reading and rewriting the document on every page
  load would be I/O in the hot path for a string that almost never
  moves.

  A string replace rather than an HTML parse, because the thing being
  replaced is an exact literal we control and can see: `src=\"/js/main.js\"`
  in the file, `\"/js/main.js\"` here. If a future index.html references an
  asset that is not in fingerprinted-assets, this leaves it alone and it
  is served unfingerprinted — noisy in a cache-status probe, never
  wrong."
  [version]
  (when-let [res (resource "index.html")]
    (reduce (fn [html path]
              (str/replace html
                           (str "\"/" path "\"")
                           (str "\"" (asset-url version path) "\"")))
            (slurp res)
            fingerprinted-assets)))

(def ^:private cache
  "The version and the rendered document, memoised against the cheap
  probe above."
  (atom nil))

(defn- current
  "The current {:version, :html}, recomputed only when an asset's
  timestamp or size has moved.

  WHY THIS IS NOT SIMPLY COMPUTED ONCE AT BOOT. In production it may as
  well be — the assets live in a read-only jar and never move. But under
  `bb dev` the shadow-cljs watch REWRITES js/main.js on every recompile,
  and a version fixed at boot would go on naming the same URL while the
  bytes underneath it changed. We have told the browser that URL is
  `immutable`, and it would believe us: a refresh after a recompile would
  serve the stale cached bundle and hot reload would look broken. That is
  exactly the dev-only papercut that gets 'fixed' by weakening the
  production header, so it is designed out instead.

  The version follows the bytes, and `immutable` stays a promise the app
  can keep — in dev and in production, with no dev flag to set and none
  to forget.

  Only the DOCUMENT needs this: the asset branch ignores the version
  segment entirely, so the probe runs once per page load, not once per
  asset."
  []
  (let [probe (staleness-key)]
    (or (when-let [cached @cache]
          (when (= (:probe cached) probe) cached))
        (let [version (digest-version)]
          (reset! cache {:probe   probe
                         :version version
                         :html    (index-html version)})))))

(defn version
  "The current asset version — a hash of the asset bytes. See `current`."
  []
  (:version (current)))

;; ---------------------------------------------------------------------
;; The handler.

(def ^:private index-uris
  "Both spellings of the document. The bare / used to 302 to /index.html
  (reitit's resource handler does that); it is now answered directly,
  which saves every first-time visitor a round trip."
  #{"/" "/index.html"})

(defn- document-response
  [html]
  {:status  200
   :headers {"Content-Type"  "text/html; charset=utf-8"
             "Cache-Control" document-cache-control}
   :body    html})

(defn- cache-control-for
  "The caching header for a NON-fingerprinted, non-document asset."
  [uri]
  (if (long-lived? uri) data-cache-control document-cache-control))

(defn handler
  "The static-asset handler: the document, the fingerprinted assets, and
  everything else, in that order.

  `resources` is the underlying resource handler (injected rather than
  built here so a test can hand in a stub, and so the traversal guard
  stays the resource handler's job — `..` in a path resolves outside
  resources/public and is refused there).

  Returns nil for a path it does not serve, so the 404 default handler
  downstream still runs.

  This handler — and NOTHING outside it — is where caching headers are
  set. The SSE stream must never meet a middleware that inspects or
  buffers a response body (adsb.stream); it is not reachable from here."
  [resources]
  (fn [{:keys [uri] :as request}]
    (let [tail (versioned-path uri)]
      (cond
        (contains? index-uris uri)
        (when-let [html (:html (current))]
          (document-response html))

        tail
        (some-> (resources (assoc request :uri tail))
                (assoc-in [:headers "Cache-Control"] immutable-cache-control))

        :else
        (some-> (resources request)
                (assoc-in [:headers "Cache-Control"] (cache-control-for uri)))))))
