(ns adsb.http.security-test
  "The security headers ship from the APP, not the edge. These tests go
  through the real assembled handler (adsb.http.routes/handler), because
  the thing worth proving is not that a middleware function works — it is
  that the headers actually reach a 404 and a static asset, the two
  responses most likely to be forgotten when the policy lives in a proxy
  config nobody re-reads."
  (:require
    [adsb.http.routes :as routes]
    [adsb.http.security :as security]
    [adsb.http.server :as server]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest testing is]]
    [org.httpkit.client :as http]
    [org.httpkit.server :as http-kit]))

(defn- response-for
  [request]
  ((routes/handler {}) request))

(defn- header-names [response]
  (set (keys (:headers response))))

(deftest every-response-carries-the-security-headers
  (testing "an API response"
    (let [response (response-for {:request-method :get :uri "/healthz"})]
      (is (= 200 (:status response)))
      (is (every? (header-names response) (keys (security/headers))))))

  (testing "a 404 — what an anonymous scanner sees, and the response a
            proxy-side policy is likeliest to miss"
    (let [response (response-for {:request-method :get :uri "/nope"})]
      (is (= 404 (:status response)))
      (is (every? (header-names response) (keys (security/headers))))))

  (testing "the headers do not disturb the response they ride on"
    (let [response (response-for {:request-method :get :uri "/healthz"})]
      (is (some? (:body response)))
      (is (contains? (header-names response) "Content-Type")))))

(deftest the-policy-is-an-allowlist
  (testing "default-src 'none' — anything the policy does not name is
            refused, so a future third-party origin must be added here
            deliberately rather than arriving by accident"
    (is (str/includes? security/content-security-policy "default-src 'none'")))

  (testing "the basemap is the one cross-origin fact in the app
            (adsb.map.view/style-url) and it is named explicitly"
    (is (str/includes? security/content-security-policy
                       "connect-src 'self' https://tiles.openfreemap.org")))

  (testing "the app is never framed and never posts a form anywhere"
    (is (str/includes? security/content-security-policy "frame-ancestors 'none'"))
    (is (str/includes? security/content-security-policy "form-action 'none'"))))

(def ^:private font-face-url
  "Every url(...) inside an @font-face block in the stylesheet."
  #"@font-face\s*\{[^}]*?url\(\s*[\"']?([^\"')]+)")

(deftest the-policy-matches-the-fonts-the-app-actually-loads
  ;; The regression this exists for: the §5 faces were self-hosted (Space
  ;; Mono, Space Grotesk) AFTER this policy was first written, and under
  ;; `default-src 'none'` with no font-src every one of them is refused —
  ;; a failure a health check never sees and a test that only asserts a
  ;; string would never have caught. So assert against the CSS itself.
  (let [css   (slurp (io/resource "public/app.css"))
        faces (map second (re-seq font-face-url css))]
    (testing "the stylesheet does host faces of its own, and they are
              same-origin — a font CDN here would need its origin in the
              policy, and is a decision to make on purpose"
      (is (seq faces))
      (is (every? #(str/starts-with? % "/") faces)
          (str "a non-same-origin @font-face appeared: " (pr-str faces)
               " — add its origin to font-src or self-host it")))

    (testing "so the policy permits exactly that: self-hosted, nothing else"
      (is (str/includes? security/content-security-policy "font-src 'self';")))

    (testing "and the files those faces name are really on the classpath —
              a policy that permits a font the app cannot serve is a 404
              wearing a green test"
      (is (every? #(some? (io/resource (str "public" %))) faces)))))

(deftest the-dev-policy-cannot-leak-into-production
  (testing "the shipped policy permits nothing unsafe at all"
    (is (not (str/includes? security/content-security-policy "unsafe-eval")))
    (is (not (str/includes? security/content-security-policy "unsafe-inline")))
    (is (not (str/includes? security/content-security-policy "unsafe-hashes")))
    (is (not (str/includes? security/content-security-policy "ws:"))))

  (testing "the strict policy is what a handler serves unless it ASKS for
            the dev one — the default is never the weak one"
    (is (= security/content-security-policy
           (get (security/headers) "Content-Security-Policy")))
    (is (= security/content-security-policy
           (get-in ((routes/handler {}) {:request-method :get :uri "/healthz"})
                   [:headers "Content-Security-Policy"]))))

  (testing "the dev policy relaxes exactly THREE things, each for a piece
            of shadow-cljs dev tooling the release build does not contain:
            eval (the watch build's module loader, and the CLJS REPL), the
            devtools WebSocket, and style attributes (the dev HUD)"
    (is (str/includes? security/dev-content-security-policy
                       "script-src 'self' 'unsafe-eval'"))
    (is (str/includes? security/dev-content-security-policy "ws://localhost:*"))
    (is (str/includes? security/dev-content-security-policy
                       "style-src-attr 'unsafe-inline'")))

  (testing "the style relaxation is the ATTRIBUTE case only — style-src
            itself stays 'self', so inline <style> elements and stylesheet
            injection are refused in dev exactly as in production"
    (is (str/includes? security/dev-content-security-policy
                       "style-src 'self';"))
    (is (not (str/includes? security/dev-content-security-policy
                            "style-src 'self' 'unsafe-inline'"))))

  (testing "the WebSocket relaxation is LOOPBACK only — a dev machine's
            own shadow-cljs server, not the internet"
    (is (not (str/includes? security/dev-content-security-policy "ws://*")))
    (is (not (str/includes? security/dev-content-security-policy "wss://"))))

  (testing "and nothing else moves: every deny-by-default directive is
            still exactly as strict as production's"
    (is (str/includes? security/dev-content-security-policy
                       "default-src 'none'"))
    (is (str/includes? security/dev-content-security-policy
                       "frame-ancestors 'none'"))
    (is (str/includes? security/dev-content-security-policy
                       "base-uri 'none'"))
    (is (str/includes? security/dev-content-security-policy
                       "https://tiles.openfreemap.org")))

  (testing "and a handler that asks for it gets it"
    (is (= security/dev-content-security-policy
           (get-in ((routes/handler {:dev-csp? true})
                    {:request-method :get :uri "/healthz"})
                   [:headers "Content-Security-Policy"])))))

(def ^:private token "s3cret-origin-token")

(defn- locked-response-for
  [request]
  ((routes/handler {:origin-token token}) request))

(defn- with-token [request]
  (assoc-in request [:headers security/origin-token-header] token))

(deftest the-origin-lock-refuses-what-did-not-come-through-our-edge
  ;; adsb-wrx: App Platform also publishes the app on its own hostname,
  ;; which bypasses Cloudflare — measured live, that hostname answered
  ;; strangers 200 and took a forged X-Forwarded-For.
  (testing "no token -> 403, and the 403 explains nothing to a scanner"
    (let [response (locked-response-for {:request-method :get :uri "/api/stream"})]
      (is (= 403 (:status response)))
      (is (= "" (:body response)))))

  (testing "a WRONG token is refused exactly like no token"
    (let [response (locked-response-for
                     {:request-method :get
                      :uri            "/api/stream"
                      :headers        {security/origin-token-header "nope"}})]
      (is (= 403 (:status response)))))

  (testing "a token that is a PREFIX of the real one is refused — the
            comparison is constant-time, not a startsWith"
    (let [response (locked-response-for
                     {:request-method :get
                      :uri            "/api/stream"
                      :headers        {security/origin-token-header "s3cret"}})]
      (is (= 403 (:status response)))))

  (testing "the right token passes through to the real handler"
    (let [response (locked-response-for
                     (with-token {:request-method :get :uri "/healthz"}))]
      (is (= 200 (:status response)))))

  (testing "/healthz answers WITHOUT the token. App Platform's health check
            reaches the container directly, not through Cloudflare — a
            locked /healthz is a container the platform believes is dead,
            and it would be killed and redeployed forever"
    (let [response (locked-response-for {:request-method :get :uri "/healthz"})]
      (is (= 200 (:status response)))))

  (testing "and the lock is OFF when no token is configured — a laptop has
            no Cloudflare in front of it"
    (is (= 200 (:status (response-for {:request-method :get :uri "/healthz"}))))
    (is (= 404 (:status (response-for {:request-method :get :uri "/nope"}))))))

(deftest headers-survive-a-real-http-response
  (testing "over a real socket, not just as a map the middleware returned
            — including the absence of a Server header. Caddy used to
            strip http-kit's; the app must not depend on an edge for that,
            so it declines to send one, and this proves http-kit honors
            the :server-header option rather than us merely asking"
    (let [srv (server/start-server! {:port 0})]
      (try
        (let [port     (http-kit/server-port srv)
              response @(http/request {:url    (str "http://localhost:" port
                                                    "/healthz")
                                       :method :get})
              headers  (:headers response)]
          (is (= 200 (:status response)))
          (is (= security/content-security-policy
                 (:content-security-policy headers)))
          (is (= "nosniff" (:x-content-type-options headers)))
          (is (= "no-referrer" (:referrer-policy headers)))
          (is (some? (:strict-transport-security headers)))
          (is (nil? (:server headers))
              "the app does not name its HTTP library to strangers"))
        (finally
          (server/stop-server! srv))))))
