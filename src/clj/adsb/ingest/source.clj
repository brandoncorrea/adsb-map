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

(defprotocol Metadata
  "An OPTIONAL side-channel for payload-level metadata a Source observed on
  its last fetch! — facts that belong to the whole payload, not to any one
  aircraft, and so never enter the coerced batch (which would churn every
  consumer). The ultrafeeder payload's cumulative `messages` counter is the
  first such fact; adsb.stats differences it into a rate.

  A Source updates this behind fetch! (an atom it owns) and exposes the
  latest here; the poll loop that drives fetch! stays oblivious. Sources
  with no payload metadata (the fixture-replay Source) simply do not
  implement this protocol."
  (last-metadata [source]
    "The payload-level metadata from the most recent fetch!, e.g.
    {:messages n}, or nil/empty when none has been seen."))

(defn metadata
  "The Source's latest payload metadata, or nil when the Source exposes
  none — a uniform accessor so callers need not know which Sources carry a
  side-channel."
  [source]
  (when (satisfies? Metadata source)
    (last-metadata source)))

(defn fn-source
  "A Source backed by a plain fetch fn, for tests and REPL work. open! and
  close! are no-ops. Proves the protocol seam holds without a socket or an
  http call — the full fixture-replay Source is adsb-nqf.4."
  [fetch-fn]
  (reify Source
    (open! [this] this)
    (fetch! [_] (fetch-fn))
    (close! [this] this)))
