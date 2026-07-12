# ya-mus-downloader

Android client prototype for `yandex-music-api`. On first launch the app signs
the user in with Yandex OAuth Device Flow: authorization happens on Yandex's
official browser page and the access token is saved locally. The downloader UI
then accepts a track link and saves the best available quality into the
public `Music/Ya Music` directory through Android MediaStore. The completed
track can also be sent immediately with the system Share sheet. Numeric IDs are
deliberately rejected.
Track links can be pasted, shared to the application with Android `ACTION_SEND`,
or opened directly with `ACTION_VIEW`. A shared link is retained through sign-in
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

Audio downloading, FLAC-in-MP4 normalization, M4A metadata, cover embedding,
and complete validation run in Rust. The Android build enables the library's
in-process `media-ffmpeg` backend and statically builds FFmpeg 8.1 through
`ffmpeg-sys-next`; no `ffmpeg` executable is packaged or launched.

`vendor/ffmpeg-sys-next` is the unmodified 8.1.0 release apart from a one-line
Android tool lookup fix (`Path::parent()` for `llvm-nm`/`llvm-strip`). The
upstream build script otherwise treats the compiler executable as a directory.

## Build

Requirements:

- Rust 1.97 with `aarch64-linux-android` and `x86_64-linux-android`;
- Android SDK 37 and NDK 29.0.14033849;
- `cargo-ndk`;
- sibling checkout `../ya-music`.

```console
./gradlew assembleDebug
```

The first native build clones and compiles FFmpeg and therefore takes longer.
The APK is written under `app/build/outputs/apk/debug/`.

The access token is encrypted with an app-specific AES-GCM key held by Android
Keystore; preferences contain only the IV and ciphertext, and Android backup is
disabled. The app never asks the user to copy or paste a token. This is an MVP;
subsequent iterations can add refresh-token handling, albums, playlists,
progress, cancellation, and MediaStore export.
