# Third-party dependencies

This repository does not include Slay the Spire 2 game assets.  The Steam integration added to the Android launcher uses the following third-party libraries at build/runtime:

| Dependency | Purpose | License (upstream) |
| --- | --- | --- |
| JavaSteam (`in.dragonbra:javasteam`) | Steam login and Steam Cloud RemoteStorage client | MIT |
| OkHttp / Okio | Steam WebSocket / HTTP / CDN transport | Apache-2.0 |
| Protocol Buffers | Steam protobuf message encoding | BSD-3-Clause |
| Kotlin coroutines / serialization | Steam protocol implementation helpers | Apache-2.0 |
| AndroidX Security Crypto | EncryptedSharedPreferences for Steam refresh tokens | Apache-2.0 |
| Bouncy Castle | JavaSteam crypto support | Bouncy Castle License / MIT-style |
| Android Prefab zstd (`com.fpliu.ndk.pkg.prefab.android.21:zstd`) | Native Steam VZstd depot chunk decompression on Android | BSD-3-Clause upstream zstd notice / package metadata |
| XZ for Java | Steam VZip/LZMA chunk decompression | Public domain / 0BSD-style upstream notice |

The Steam protocol/downloader source under `android/steam-protocol/` and `android/steam-content/` was adapted for this launcher from the local reference project `../ref/SlayTheAmethystModded/`; keep future updates source-only and do not commit downloaded Steam depot contents, cloud saves, tokens, passwords, or other user-private data.
