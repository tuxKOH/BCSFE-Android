# Native save trailer fixtures

The old bundled templates end immediately after marker `150300`. Upstream's
Python parser exposes this region as opaque `remaining_data`; an upstream
round trip alone therefore does not validate the native game's later records.

These continuations were independently captured on 2026-11-12 by letting the
official JP 16.0.1 and TW 15.7.0 games migrate the bundled 15.5 templates and
write their own saves. Only the newly required record shapes were retained.
All variable scalar state, flags, counters and timestamps were reset to zero;
empty collections and native initial compact-table dimensions were retained.
No accounts, credentials, identifiers or complete player saves are included.

| Game version | Bytes after marker 150300 | Terminal marker |
| --- | ---: | --- |
| 15.5 | 18 | 150400 |
| 15.6 | 171 | 150600 |
| 15.7 | 191 | 150700 |
| 16.0 | 695 | 160000 |

Markers terminate the preceding records. In 16.0 the compact table grows from
one to five chapters, the flags before `150700` grow from three to eight, and
23 bytes of empty records precede the final `160000` marker. The compact
serializer was also checked in the JP ARM64 library at `0x70c570`; the main
trailer writer at `0x92d7e0` through `0x92da60` verifies field order and widths.

The default continuations were loaded by the native games as an independent
check. Unit tests compare the Java generator with these fixtures and verify
that explicit repair preserves all pre-existing bytes before the checksum.
Imported saves remain unchanged until repair is explicitly requested.
