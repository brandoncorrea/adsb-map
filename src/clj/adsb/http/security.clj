(ns adsb.http.security
  (:require [clojure.string :as str]
            [ring.util.response :as response])
  (:import (java.security MessageDigest)))

(def ^:const content-security-policy
  (str
    "default-src 'none'; "
    "script-src 'self'; "
    "style-src 'self'; "
    "font-src 'self'; "
    "img-src 'self' data: blob:; "
    "connect-src 'self' https://tiles.openfreemap.org; "
    "worker-src blob:; "
    "child-src blob:; "
    "base-uri 'none'; "
    "form-action 'none'; "
    "frame-ancestors 'none'"))

(def ^:const dev-content-security-policy
  (-> content-security-policy
      (str/replace-first "script-src 'self'" "script-src 'self' 'unsafe-eval'")
      (str/replace-first
        "connect-src 'self' https://tiles.openfreemap.org"
        (str "connect-src 'self' https://tiles.openfreemap.org "
             "ws://localhost:* ws://127.0.0.1:*"))
      (str "; style-src-attr 'unsafe-inline'")))

(def ^:private base-headers
  {"Strict-Transport-Security" "max-age=15552000"
   "X-Content-Type-Options"    "nosniff"
   "Referrer-Policy"           "no-referrer"
   "Permissions-Policy"        (str "accelerometer=(), camera=(), "
                                    "geolocation=(), gyroscope=(), "
                                    "magnetometer=(), microphone=(), "
                                    "payment=(), usb=()")})

(defn headers
  ([] (headers false))
  ([dev-csp?]
   (assoc base-headers
     "Content-Security-Policy"
     (if dev-csp?
       dev-content-security-policy
       content-security-policy))))

(defn- secure [resp response-headers]
  (when resp
    (reduce-kv response/header resp response-headers)))

(def ^:const origin-token-header "x-origin-token")

(def ^:const origin-lock-exempt-paths #{"/healthz"})

(defn- token-match? [expected supplied]
  (and (string? supplied)
       (MessageDigest/isEqual (.getBytes ^String expected "UTF-8")
                              (.getBytes ^String supplied "UTF-8"))))

(defn wrap-origin-lock [handler token]
  (if-not token
    handler
    (let [forbidden {:status 403 :headers {} :body ""}
          allowed?  (fn [request]
                      (or (contains? origin-lock-exempt-paths (:uri request))
                          (token-match?
                            token
                            (get-in request [:headers origin-token-header]))))]
      (fn
        ([request]
         (if (allowed? request) (handler request) forbidden))
        ([request respond raise]
         (if (allowed? request)
           (handler request respond raise)
           (respond forbidden)))))))

(defn wrap-security-headers
  ([handler] (wrap-security-headers handler false))
  ([handler dev-csp?]
   (let [response-headers (headers dev-csp?)]
     (fn
       ([request] (secure (handler request) response-headers))
       ([request respond raise]
        (handler request
                 (fn [resp] (respond (secure resp response-headers)))
                 raise))))))
