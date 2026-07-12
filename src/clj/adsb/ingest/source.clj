(ns adsb.ingest.source
  "The ingest seam. A Source produces the current batch of domain aircraft
  from some wire format, and everything downstream depends on this protocol
  rather than on any one transport.

  We poll ultrafeeder's aircraft.json today (adsb.ingest.ultrafeeder). SBS
  on :30003 and Beast on :30005 are the same sky at lower latency and will
  arrive as their own Source implementations — a socket held open across
  polls, which is exactly why the lifecycle is open!/fetch!/close! rather
  than a bare fetch. The swap must never reach the domain; see
  docs/clean-code-standards.md.")

(defprotocol Source
  "A live feed of aircraft, behind a narrow seam.

  fetch! returns already-coerced domain aircraft (see
  docs/validation-boundaries.md — each Source is the trust boundary for
  its own wire format), so the poll loop stays transport-agnostic. A Source
  that cannot reach its feed throws; the poll loop turns that into feeder
  status and backoff, never a crash."
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

(defn fn-source
  "A Source backed by a plain fetch fn, for tests and REPL work. open! and
  close! are no-ops. Proves the protocol seam holds without a socket or an
  http call — the full fixture-replay Source is adsb-nqf.4."
  [fetch-fn]
  (reify Source
    (open! [this] this)
    (fetch! [_] (fetch-fn))
    (close! [this] this)))
