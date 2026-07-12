# Test Resources

## aircraft-sample.json

A genuine `aircraft.json` payload captured from the live ultrafeeder
(`http://dietpi.local:8100/data/aircraft.json`) on **2026-07-11**, selected as the
richest of 10 captures polled at ~25-second intervals. It is a real recording,
warts included — space-padded callsigns, aircraft with no `lat`/`lon`, absent
`gs`/`track`/`baro_rate`, string `squawk` values (including leading zeros) — and
must stay that way. Do not pretty-print, trim, normalize, or "fix" it; the whole
point of the fixture is that it is what the feeder actually sends
(see `docs/validation-boundaries.md`).

### Privacy redaction

The receiver's location is private. The following fields were **removed from every
aircraft** before committing, because they reveal the receiver's position (each is
the aircraft's distance/bearing measured from the receiver, so two or three of them
triangulate the antenna):

- `r_dst` — distance from the receiver, in nautical miles
- `r_dir` — bearing from the receiver, in degrees

No other bytes were altered: the redaction was performed by textual removal of the
`"r_dst":<value>` / `"r_dir":<value>` pairs only, and the result was verified to be
JSON-equal to the original minus exactly those keys. The capture contained no other
receiver-position fields (no receiver lat/lon appears in `aircraft.json`; the
`lastPosition` object is the *aircraft's* stale position, not the receiver's).

### Quirks present in this fixture (verified against the live capture set)

| Quirk | Status in captures |
|---|---|
| `alt_baro` is a number | confirmed |
| `alt_baro` is the string `"ground"` | not observed in this capture window (claim stands; see doc) |
| `lat`/`lon` absent entirely | confirmed |
| `flight` space-padded to 8 chars | confirmed |
| `flight` absent | confirmed |
| `hex` with `~` prefix (TIS-B/ADS-R) | not observed in this capture window |
| `squawk` as 4-digit string, leading zeros preserved | confirmed |
| `gs`/`track`/`baro_rate` absent | confirmed |
| explicit `null` values | not observed in this capture window |
| `alt_baro` itself absent (bare `mode_s` targets) | confirmed (not in the doc table, but real) |

"Not observed" means the quirk did not occur during this ten-poll window — the doc's
claim that it *can* occur stands. Ingest must still handle those cases; this fixture
alone does not exercise them.

## receiver-sample.json

A `receiver.json` response in the live feeder's exact shape (readsb: top-level
`lat`/`lon` beside `refresh`/`history`/`version`), verified against the real
feeder on **2026-07-11** — but with **synthetic coordinates**. The receiver's
real position is private (see `docs/validation-boundaries.md`); the committed
`lat`/`lon` are rounded stand-ins in the same general region as the
aircraft-sample positions, so the range gate behaves realistically in tests
without locating the antenna. The git hash in `version` is likewise zeroed.
Do not replace these with real coordinates.
