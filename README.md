# Yamu Downloader

Android client for the `yamu` Rust library. On first launch the app signs
the user in with Yandex OAuth Device Flow: authorization happens on Yandex's
official browser page and the access token is saved locally. The downloader UI
accepts links to tracks, albums, and playlists and saves the best available
quality into the public `Music/Ya Music` directory through Android MediaStore.
Each album and playlist gets its own directory; multi-disc albums also get
`CD1`, `CD2`, and subsequent subdirectories. A completed single track can be
sent immediately with the system Share sheet. Numeric IDs are deliberately
rejected.

Links can be pasted, shared to the application with Android `ACTION_SEND`, or
opened directly with `ACTION_VIEW`. A shared link is retained through sign-in
and starts automatically once authorization finishes.
The app also publishes a rank-0 Sharing Shortcut named `Скачать` for the Direct
Share row. Android retains final control over Sharesheet ordering; frequent use
or manually pinning the target can move it ahead of other applications.

The interface uses Jetpack Compose, Material 3, edge-to-edge drawing, and
`WindowInsets.safeDrawing`, so content stays outside camera cutouts, status
bars, and gesture navigation areas.

## Screenshots

![Yandex sign-in screen](screenshots/auth-home.png)

![Downloader in dark theme](screenshots/dark-home.png)

![Download progress](screenshots/download-progress.png)

Audio downloading, FLAC-in-MP4 container normalization, metadata and cover
embedding, transcoding, and complete validation run in Rust. The Android build
enables the library's in-process `media-ffmpeg` backend and statically builds
FFmpeg 8.1 through `ffmpeg-sys-next`; no `ffmpeg` executable is packaged or
launched.

The Settings tab contains an optional `MP3 instead of AAC/M4A` mode. When it is
enabled, AAC/M4A downloads are decoded and encoded locally as 320 kbit/s MP3 by
FFmpeg's `libmp3lame` encoder. FLAC and existing MP3 files are left unchanged.
The option is disabled by default because lossy-to-lossy transcoding cannot
improve the source quality and consumes additional time and battery.

The app uses the library's shared `downloader` pipeline. It displays real
downloaded and total byte counts when the CDN provides `Content-Length`, shows
normalization/tagging/verification phases, and can cancel an active request
while cleaning up its temporary file.
The download is owned by a foreground data-sync service rather than the
Activity, so it continues while the app is in the background. Its notification
mirrors progress and includes a cancel action.

`vendor/ffmpeg-sys-next` is based on release 8.1.0 and fetches the immutable
FFmpeg commit recorded in `vendor/ffmpeg-sys-next/FFMPEG_REVISION`. Besides an
Android tool lookup fix (`Path::parent()` for `llvm-nm`/`llvm-strip`), it has a local
`build-lib-mp3lame` feature which passes the bundled LAME installation to
FFmpeg's build. `vendor/mp3lame-sys` is based on release 0.1.11 and includes
LAME 3.100. Its small local patch declares Cargo's `links = "mp3lame"` key and
exports the built prefix so `ffmpeg-sys-next` can find the headers and static
library while cross-compiling.

These two crates are patched build adapters, not a general dependency mirror.
All other Rust dependencies, including `yamu`, come from crates.io through the
locked registry dependency graph. The local copies can be removed once the
required Android, source-pinning, and LAME-discovery changes are available in
compatible upstream releases.

## Build

Requirements:

- Rust 1.97 with `aarch64-linux-android`, `armv7-linux-androideabi`,
  `x86_64-linux-android`, and `i686-linux-android`;
- Android SDK 37 and NDK 29.0.14033849;
- `cargo-ndk`.

The native bridge uses the published `yamu` 0.1.1 crate from crates.io. Its
exact version and registry checksum are locked in `native/Cargo.lock`; no
sibling source checkout is required.

```console
./gradlew assembleDebug
```

The default build emits separate APKs for `arm64-v8a`, `armeabi-v7a`,
`x86_64`, and `x86`. To build only one architecture, pass its Android ABI:

```console
./gradlew assembleDebug -Pyamu.abi=arm64-v8a
```

Local debug and release builds use `keystore.properties` when it is present.
The file is ignored by Git; its `keyAlias`, `password`, and `storeFile` entries
follow the standard Android Gradle signing-property layout.

The first native build compiles LAME, clones and compiles FFmpeg, and therefore
takes longer. Gradle invokes `cargo-ndk` directly and keeps generated JNI
libraries under `app/build/rustNative`; `gradle clean` removes them.
APKs are written under `app/build/outputs/apk/debug/`. GitHub Actions builds
the four architectures in parallel and uploads each signed APK separately.
Tags matching `v*` publish all four APKs, an LGPL source archive, and
`SHA256SUMS` to a GitHub release. The checksum file covers both the APKs and
the source archive.

The access token is encrypted with an app-specific AES-GCM key held by Android
Keystore; preferences contain only the IV and ciphertext, and Android backup is
disabled. The app never asks the user to copy or paste a token.

## Licensing and distribution

FFmpeg and LAME are built as statically linked components of the native
library. The APK includes their license texts and an in-app open-source notice.
Each tagged release additionally publishes the exact FFmpeg and LAME sources,
per-ABI FFmpeg build configuration, and instructions for rebuilding or
relinking the application with modified multimedia libraries. GitHub's normal
source archives contain the matching application source and build scripts.

See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for component notices
and [`docs/RELINKING.md`](docs/RELINKING.md) for the LGPL-source layout and
rebuild procedure.
