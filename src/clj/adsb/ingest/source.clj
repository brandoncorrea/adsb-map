(ns adsb.ingest.source)

(defprotocol Source
  "A live feed of aircraft, behind a narrow seam."
  (open! [source]
    "Acquire whatever the feed needs (a socket, say) and return the ready
    Source. A no-op for stateless HTTP polling. Called once before the
    first fetch!.")
  (fetch! [source]
    "Return the current batch of domain aircraft as a vector, or throw
    when the feed is unreachable or malformed.")
  (close! [source]
    "Release the feed's resources. A no-op for stateless HTTP polling.
    Called once after the last fetch!."))

(defprotocol Metadata
  "An OPTIONAL side-channel for payload-level metadata a
   Source observed on its last fetch!"
  (last-metadata [source]
    "The Source's latest payload-level metadata, e.g. {:messages n}, or
    nil/empty when none has been seen."))

(defn metadata [source]
  (when (satisfies? Metadata source)
    (last-metadata source)))

(defn fn-source [fetch-fn]
  (reify Source
    (open! [this] this)
    (fetch! [_] (fetch-fn))
    (close! [this] this)))
