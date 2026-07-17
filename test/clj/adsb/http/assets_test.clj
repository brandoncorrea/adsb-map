(ns adsb.http.assets-test
  (:require [adsb.http.assets :as assets]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]))

(defn- stub-resources [known]
  (fn [{:keys [uri]}]
    (when (contains? known uri)
      {:status 200 :headers {"Content-Type" "text/javascript"} :body uri})))

(def ^:private handler
  (-> #{"/js/main.js" "/app.css" "/db/004.json"
        "/glyphs/Space Mono Bold/0-255.pbf"
        "/fonts/space-mono-400.woff2"}
      stub-resources
      assets/handler))

(defn- GET [uri]
  (handler {:request-method :get
            :uri            uri
            :headers        {}}))

(deftest the-version-names-the-bytes
  (testing "the version is a hash of the asset CONTENTS, so it is stable
            across restarts and identical across replicas of one image —
            a build clock or a counter would give two containers serving
            the same image two different URLs for the same bytes"
    (is (= (assets/version!) (assets/version!))))
  (testing "it is short, hex, and URL-safe"
    (is (re-matches #"[0-9a-f]{12}" (assets/version!)))))

(defn- bytes* [s] (.getBytes ^String s "UTF-8"))

(deftest the-version-moves-when-the-bytes-move
  (testing "THE load-bearing claim. Every fingerprinted asset is served
            `immutable` for a year, which is only truthful because a change
            to the bundle changes its URL. If the version could stay put
            across a content change, `immutable` would pin browsers to a
            stale app for a year with no way to correct them"
    (is (not= (assets/content-version [["js/main.js" (bytes* "the old app")]])
              (assets/content-version [["js/main.js" (bytes* "the new app")]]))))
  (testing "a single flipped byte is enough — nothing is sampled or truncated
            before hashing"
    (is (not= (assets/content-version [["js/main.js" (bytes* "aaaa")]])
              (assets/content-version [["js/main.js" (bytes* "aaab")]]))))
  (testing "the same bytes give the same version, so two containers running
            one image serve one URL — the version is a function of content,
            not of a boot, a clock, or a timestamp that a reproducible build
            may normalise away"
    (is (= (assets/content-version [["js/main.js" (bytes* "same")]])
           (assets/content-version [["js/main.js" (bytes* "same")]]))))
  (testing "it is the bytes AND the name: moving content between assets is a
            different deployment and must not collide"
    (is (not= (assets/content-version [["js/main.js" (bytes* "x")]])
              (assets/content-version [["app.css" (bytes* "x")]]))))
  (testing "a missing asset is an absence, not a crash — a checkout that has
            not run `bb build` still serves what it has"
    (is (string? (assets/content-version [["js/main.js" nil]])))
    (is (not= (assets/content-version [["js/main.js" nil]])
              (assets/content-version [["js/main.js" (bytes* "built")]])))))

(deftest the-document-is-never-stale
  (testing "index.html revalidates every time — it is the map to every
            other asset, so a stale one points at a bundle that may no
            longer exist"
    (let [response (GET "/index.html")]
      (is (= 200 (:status response)))
      (is (= assets/document-cache-control
             (get-in response [:headers "Cache-Control"])))))
  (testing "and / is answered directly rather than 302'd to /index.html,
            which saves every first-time visitor a round trip"
    (is (= 200 (:status (GET "/")))))
  (testing "it names the versioned bundle, not the bare one — this is the
            whole mechanism: the document is what tells a browser which
            URL to fetch, and it is the only thing that changes"
    (let [html (:body (GET "/index.html"))]
      (is (str/includes? html (assets/asset-url (assets/version!) "js/main.js")))
      (is (not (str/includes? html "\"/js/main.js\"")))
      (is (str/includes? html (assets/asset-url (assets/version!) "app.css"))))))

(deftest fingerprinted-assets-are-immutable
  (testing "an asset under /assets/<version>/ is cacheable for a year and
            declared immutable — the URL names its bytes, so a different
            body would be a different URL, and there is nothing to revalidate"
    (let [response (GET (assets/asset-url (assets/version!) "js/main.js"))]
      (is (= 200 (:status response)))
      (is (= assets/immutable-cache-control
             (get-in response [:headers "Cache-Control"])))))
  (testing "the version segment is a cache key, NOT a credential: a stale
            version still serves the current bytes. A 404 here would turn a
            client holding an old index.html into a broken page rather than
            a merely slow one"
    (let [response (GET (assets/asset-url "000000000000" "js/main.js"))]
      (is (= 200 (:status response)))))
  (testing "a versioned URL for a file that does not exist is still a miss,
            so the 404 handler downstream runs"
    (is (nil? (GET (assets/asset-url (assets/version!) "js/nope.js"))))))

(deftest data-assets-get-a-ttl-not-a-fingerprint
  (testing "the database shards and the glyphs — the bulk of the bytes, and
            NOT coupled to a deploy — get a day's TTL. A stale registry entry
            or an unchanged glyph costs nothing; re-fetching them on every
            page load costs everything"
    (doseq [uri ["/db/004.json"
                 "/glyphs/Space Mono Bold/0-255.pbf"
                 "/fonts/space-mono-400.woff2"]]
      (is (= assets/data-cache-control
             (get-in (GET uri) [:headers "Cache-Control"]))
          uri)))
  (testing "anything else unfingerprinted stays no-cache — the default is the
            cautious one, and an asset earns a TTL by being named"
    (let [handler  (assets/handler (stub-resources #{"/robots.txt"}))
          response (handler {:request-method :get :uri "/robots.txt" :headers {}})]
      (is (= assets/document-cache-control
             (get-in response [:headers "Cache-Control"]))))))

(deftest a-miss-stays-a-miss
  (testing "nil passes through so the 404 default handler still runs —
            answering here would shadow it"
    (is (nil? (GET "/no/such/path")))))

(deftest traversal-cannot-escape-through-the-version-segment
  (testing "the version segment must not become a way to walk out of
            resources/public. The tail is handed to the resource handler,
            which refuses to resolve outside the root — this asserts the
            rewrite does not hand it something already escaped"
    (let [seen      (atom [])
          resources (fn [{:keys [uri]}] (swap! seen conj uri) nil)
          handler   (assets/handler resources)]
      (handler {:request-method :get
                :uri            "/assets/abc123/../../../deps.edn"
                :headers        {}})
      (testing "whatever reaches the resource handler is still a path it
                will refuse — it is never rewritten into a bare escape"
        (is (every? #(str/starts-with? % "/") @seen))))))
