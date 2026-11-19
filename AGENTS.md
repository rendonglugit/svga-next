# Repository guidance

This is an independently maintained Apache-2.0 SVGA library. Read `README.md` and `docs/ARCHITECTURE.md` before changing ownership or playback behavior.

- Keep `svga-core` free of Android types. Player handles animation formats/drawing/audio; runtime handles Android requests/views/cache ownership.
- Never copy unlicensed decompiled application code, Smali, binaries or assets into this repository. Design references do not supply a source-code license.
- Preserve upstream notices. Record renderer changes in `docs/UPSTREAM.md` and keep `docs/upstream-snapshot.json` as the imported baseline.
- Every successful loader callback transfers a client lease. Eviction must not invalidate active clients; cancellation must release undelivered leases.
- View mutation and loader callback delivery belong to the main thread. Resource cleanup must run outside ownership locks.
- Test observable contracts, especially races, cancellation, shared playback and malformed inputs. Use deterministic synchronization instead of sleeping in concurrency tests.
- Run the relevant Gradle tests and sample assembly before claiming a change works. Do not publish externally, commit or push unless the user asks.
