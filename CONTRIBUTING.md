# Contributing

Use JDK 17, Android SDK 35 and the checked-in Gradle wrapper. Run:

```sh
./gradlew :svga-core:test :svga-player:testDebugUnitTest :svga-runtime:testDebugUnitTest :sample:assembleDebug
```

Keep ownership, threading and callback contracts explicit. A regression test should describe observable behavior rather than private field layout. Use latches or controlled dispatchers for concurrency tests; avoid timing sleeps.

The renderer is an Apache-2.0 upstream derivative. Keep its original notices and update `docs/UPSTREAM.md` when changing it. Runtime/core changes must be independently implemented. Do not import unlicensed decompiled application code, Smali, assets or binary dependencies.

Keep the core module free of Android types. Attach/detach and visibility belong in runtime. Animation format, drawing and per-playback audio belong in player. Do not add business-specific gift queues, authentication, app telemetry or server URLs.

When reporting a problem, include library revision, Android version, the smallest reproducible code, expected/actual behavior, and an animation you are permitted to share.
