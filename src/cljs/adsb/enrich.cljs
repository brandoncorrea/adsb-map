(ns adsb.enrich
  (:require [clojure.string :as str]
            [re-frame.core :as rf]))

(def ^:const shard-prefix-length 3)
(def ^:const db-base-path "/db")
(def ^:private icao-re #"^[0-9a-f]{6}$")

(defn enrichable-prefix [icao]
  (when (string? icao)
    (let [lower (str/lower-case icao)]
      (when (re-matches icao-re lower)
        (subs lower 0 shard-prefix-length)))))

(defn type-code [record] (get record "t"))
(defn type-desc [record] (get record "d"))
(defn registration [record] (get record "r"))
(defn operator [record] (get record "o"))

(defn record-for [shards icao]
  (when-let [prefix (enrichable-prefix icao)]
    (let [shard (get shards prefix)]
      (when (map? shard)
        (get shard (str/lower-case icao))))))

(defn- resp->json [prefix resp]
  (if (.-ok resp)
    (js-invoke resp "json")
    (js-invoke js/Promise "reject" (js/Error. (str "shard " prefix ": HTTP " (.-status resp))))))

(defn get-shard! [prefix]
  (-> (js/fetch (str db-base-path "/" prefix ".json"))
      (js-invoke "then" (partial resp->json prefix))
      (js-invoke "then" js->clj)))

(defonce ^:private !logged-failure? (atom false))

(defn- log-failure-once! [prefix reason]
  (when (compare-and-set! !logged-failure? false true)
    (js/console.info
      (str "adsb.enrich: aircraft enrichment unavailable (shard " prefix ": "
           reason "). Airframe details will be absent; the map is unaffected. "
           "Run `bb db:fetch` to populate resources/public/db/."))))

(defn- on-shard-fetched [prefix parsed]
  (if (map? parsed)
    (rf/dispatch [:enrich/shard-loaded prefix parsed])
    (do (log-failure-once! prefix "malformed shard")
        (rf/dispatch [:enrich/shard-failed prefix]))))

(defn- on-shard-error [prefix err]
  (log-failure-once! prefix (.-message err))
  (rf/dispatch [:enrich/shard-failed prefix]))

(defn fetch-shard! [prefix]
  (-> (get-shard! prefix)
      (js-invoke "then" (partial on-shard-fetched prefix))
      (js-invoke "catch" (partial on-shard-error prefix))))

(rf/reg-fx :enrich/fetch! fetch-shard!)

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

(rf/reg-sub
  :enrich/shards
  (fn [db _]
    (get db :enrich/shards {})))

(rf/reg-sub
  :enrich/record
  :<- [:enrich/shards]
  (fn [shards [_ icao]]
    (record-for shards icao)))
