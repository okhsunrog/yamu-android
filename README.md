# ya-mus-downloader

Android client prototype for `yandex-music-api`. The UI accepts a Yandex Music
OAuth token and a track URL, then downloads the best available quality into the
application's external Music directory. Numeric IDs are deliberately rejected.
Track links can be pasted, shared to the application with Android `ACTION_SEND`,
or opened directly with `ACTION_VIEW`. A shared link starts automatically when
a saved token is available; otherwise the app fills the link and requests one.
The app also publishes a rank-0 Sharing Shortcut named `Скачать` for the Direct
Share row. Android retains final control over Sharesheet ordering; frequent use
or manually pinning the target can move it ahead of other applications.

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

The token is stored only when the checkbox is enabled. It is encrypted with an
app-specific AES-GCM key held by Android Keystore; preferences contain only the
IV and ciphertext, and Android backup is disabled. This is an MVP; a subsequent
iteration should replace direct token entry with OAuth Device Flow and add
albums, playlists, progress, cancellation, and MediaStore export.
