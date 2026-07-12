(ns adsb.enrich
  "Airframe enrichment — type, registration, operator for a known ICAO hex —
  from a STATIC sharded database served by the backend as plain files. This
  is tar1090's own pattern: the browser, not the server, does the lookup.

  ## Why it lives entirely here, off the live path

  The SSE wire (adsb.wire) carries only what the sky transmits. Enrichment is
  reference data ABOUT an airframe, not an observation OF it, so it never
  touches the wire — coupling the live path to a lookup would let a slow or
  missing database stall the map. Instead: an aircraft renders the instant it
  arrives; its type/registration/operator, if the database knows them, fill in
  a beat later. The map never waits.

  ## The database is DATA, not truth (docs/validation-boundaries.md)

  The shards come from the tar1090-db / Mictronics aircraft database — a
  third-party compilation of public registration data, fetched at build time
  (`bb db:fetch`), gitignored, never committed. So this namespace treats a
  missing shard, a 404, and a malformed shard IDENTICALLY: as ABSENCE. A hex
  the database cannot name renders an em-dash, exactly as an unreported
  altitude does. Enrichment degrades to absent and is never allowed to fail
  loudly or block.

  ## The cache

  Each shard is fetched at most once and cached in app-db under
  `:enrich/shards`, keyed by the hex prefix:

      prefix -> :loading            fetch in flight
             -> {icao -> record}    fetch succeeded; the shard's aircraft
             -> :absent             404, network error, or malformed JSON

  A record is `{\"t\" type-code, \"d\" description, \"r\" registration,
  \"o\" operator}`, absent fields omitted — string keys, straight off the
  JSON, never keywordized (the outer keys are hex strings and have no business
  becoming keywords).

  Lookups (`:enrich/record`) are pure and NEVER fetch — a subscription with a
  side effect is a bug. Fetching is driven by `:enrich/ensure`, which the
  detail panel dispatches for the selected aircraft. Everything else reads
  only what is already cached, so a busy sky costs no network."
  (:require
    [clojure.string :as str]
    [re-frame.core :as rf]))

;; ---------------------------------------------------------------------
;; Shard addressing. Shards are named by the first three hex characters of
;; the lowercased ICAO address (adsb.db.build writes them the same way), so a
;; click on one aircraft warms the shard for its near neighbours too.

(def ^:const shard-prefix-length 3)
(def ^:const db-base-path "/db")

;; A clean ICAO address: exactly six lowercase hex digits. TIS-B / ADS-R
;; targets arrive `~`-prefixed and non-ICAO; they are not in the database and
;; are never looked up.
(def ^:private icao-re #"^[0-9a-f]{6}$")

(defn enrichable-prefix
  "The shard prefix for `icao`, or nil when the address is not a plain
  six-hex-digit ICAO (and so cannot be in the database)."
  [icao]
  (when (string? icao)
    (let [lower (str/lower-case icao)]
      (when (re-matches icao-re lower)
        (subs lower 0 shard-prefix-length)))))

;; ---------------------------------------------------------------------
;; Record accessors. A record is the raw string-keyed map from the shard; the
;; UI asks for facts by name and never reaches for a raw key.

(defn type-code   "ICAO type designator, e.g. \"B738\"."      [record] (get record "t"))
(defn type-desc   "Long type name, e.g. \"Boeing 737-800\"."  [record] (get record "d"))
(defn registration "Tail number, e.g. \"N12345\"."           [record] (get record "r"))
(defn operator    "Owner / operator name."                    [record] (get record "o"))

(defn record-for
  "Pure lookup: the enrichment record for `icao` given the `shards` cache, or
  nil when the address is not enrichable, its shard is not loaded, its shard
  failed (:absent), or the shard simply has no entry for it. A non-map shard
  value (:loading / :absent, or a malformed payload) yields nil — absence."
  [shards icao]
  (when-let [prefix (enrichable-prefix icao)]
    (let [shard (get shards prefix)]
      (when (map? shard)
        (get shard (str/lower-case icao))))))

;; ---------------------------------------------------------------------
;; The fetch seam. The ONLY place enrich touches the network. Returns a
;; promise resolving to the parsed shard (a clj map) or rejecting on a
;; non-2xx / network error. Tests redef this so no real fetch is made.

(defn get-shard!
  "Fetch and parse the shard JSON for `prefix`. A promise of the parsed map,
  or a rejected promise on any HTTP or network failure."
  [prefix]
  (-> (js/fetch (str db-base-path "/" prefix ".json"))
      (.then (fn [resp]
               (if (.-ok resp)
                 (.json resp)
                 (js/Promise.reject
                   (js/Error. (str "shard " prefix ": HTTP " (.-status resp)))))))
      (.then (fn [json] (js->clj json)))))

;; Log the first enrichment failure and then fall silent — a missing database
;; is an ordinary, expected state, not an error worth one line per shard.
(defonce ^:private !logged-failure? (atom false))

(defn- log-failure-once!
  [prefix reason]
  (when (compare-and-set! !logged-failure? false true)
    (js/console.info
      (str "adsb.enrich: aircraft enrichment unavailable (shard " prefix ": "
           reason "). Airframe details will be absent; the map is unaffected. "
           "Run `bb db:fetch` to populate resources/public/db/."))))

(defn fetch-shard!
  "Fetch a shard and land the result in app-db: the parsed map on success, or
  :absent on any failure — HTTP, network, or a payload that is not a JSON
  object. Failures are swallowed (logged once); enrichment degrades, never
  throws."
  [prefix]
  (-> (get-shard! prefix)
      (.then (fn [parsed]
               (if (map? parsed)
                 (rf/dispatch [:enrich/shard-loaded prefix parsed])
                 (do (log-failure-once! prefix "malformed shard")
                     (rf/dispatch [:enrich/shard-failed prefix])))))
      (.catch (fn [err]
                (log-failure-once! prefix (.-message err))
                (rf/dispatch [:enrich/shard-failed prefix])))))

(rf/reg-fx :enrich/fetch! fetch-shard!)

;; ---------------------------------------------------------------------
;; Events.

;; Ensure the shard for `icao` is loaded or loading. Idempotent: a no-op once
;; the prefix is known (loading, loaded, or absent), so re-dispatching on
;; every panel render never re-fetches and never loops — the app-db it reads
;; back is unchanged. A non-enrichable icao is a no-op too.
(rf/reg-event-fx
  :enrich/ensure
  (fn [{:keys [db]} [_ icao]]
    (let [prefix (enrichable-prefix icao)]
      (if (and prefix (not (contains? (:enrich/shards db) prefix)))
        {:db            (assoc-in db [:enrich/shards prefix] :loading)
         :enrich/fetch! prefix}
        {}))))

(rf/reg-event-db
  :enrich/shard-loaded
  (fn [db [_ prefix records]]
    (assoc-in db [:enrich/shards prefix] records)))

(rf/reg-event-db
  :enrich/shard-failed
  (fn [db [_ prefix]]
    (assoc-in db [:enrich/shards prefix] :absent)))

;; ---------------------------------------------------------------------
;; Subscriptions.

;; The whole shard cache — a listing surface subscribes once and does a
;; pure record-for lookup per entry, not one subscription per aircraft.
(rf/reg-sub
  :enrich/shards
  (fn [db _]
    (get db :enrich/shards {})))

;; The enrichment record for one icao, or nil. Pure — it reads the cache and
;; never triggers a fetch (that is :enrich/ensure's job).
(rf/reg-sub
  :enrich/record
  :<- [:enrich/shards]
  (fn [shards [_ icao]]
    (record-for shards icao)))
