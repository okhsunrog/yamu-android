# Rebuilding and relinking the LGPL multimedia libraries

Tagged releases contain a
`yamu-downloader-<version>-lgpl-sources.tar.gz` asset. This complements
GitHub's automatically generated Yamu Downloader source archives: the extra
asset contains the external multimedia sources that are downloaded or built
during the native build.

The two archives provide this relevant layout after extraction:

```text
yamu-android-<tag>/
├── native/
└── vendor/
    ├── ffmpeg-sys-next/
    └── mp3lame-sys/

yamu-downloader-<version>-lgpl-sources/
├── FFmpeg/
├── vendor/
│   ├── ffmpeg-sys-next/
│   └── mp3lame-sys/
├── LICENSES/
├── BUILD-INFO/
└── RELINKING.md
```

`BUILD-INFO` contains the pinned source identifiers, an FFmpeg source diff,
and the configure/build record captured from every ABI build. LAME 3.100 is
under `vendor/mp3lame-sys/lame-3.100`.

The application uses the published `yamu` crate rather than a sibling
checkout. Cargo obtains the exact registry package and verifies the checksum
recorded in `native/Cargo.lock`; it is not mirrored in the LGPL source asset.

## Toolchain

The release workflow uses:

- Rust 1.97.0;
- `cargo-ndk` 4.1.2;
- Android SDK platform 37;
- Android NDK 29.0.14033849;
- Java 21;
- Autoconf, Automake, Clang, CMake, Libtool, Make, NASM, and pkg-config.

Android build-tools 36.1.0-rc1 are installed by CI for signing and validation.
The application bytecode targets Java 17.

## Rebuild an APK

The FFmpeg directory in the LGPL source asset retains the shallow Git metadata
needed by the native build script. From the extracted application source, run:

```console
cd yamu-android-<tag>
lgpl_sources="../yamu-downloader-<version>-lgpl-sources"
YAMU_FFMPEG_REPOSITORY="$lgpl_sources/FFmpeg" \
YAMU_FFMPEG_REVISION="$(git -C "$lgpl_sources/FFmpeg" rev-parse HEAD)" \
./gradlew :app:assembleRelease -Pyamu.abi=arm64-v8a
```

The supported ABI values are `arm64-v8a`, `armeabi-v7a`, `x86_64`, and `x86`.
An unsigned release APK is sufficient for verification; Android installation
requires signing it with a key you control. A local `keystore.properties` can
also provide the normal Gradle signing values described in the README.

Without the two FFmpeg environment variables the build fetches the immutable
revision recorded in `vendor/ffmpeg-sys-next/FFMPEG_REVISION` directly from
upstream.

## Relink with a modified FFmpeg or LAME

To change FFmpeg, edit its checkout in the LGPL source directory and create a
local commit so the build script can fetch it:

```console
git -C "$lgpl_sources/FFmpeg" switch -c local-ffmpeg
# edit FFmpeg sources
git -C "$lgpl_sources/FFmpeg" add -A
git -C "$lgpl_sources/FFmpeg" commit -m 'local FFmpeg changes'
```

Then run the Gradle command above. `YAMU_FFMPEG_REVISION` must be the new commit
ID. The build script detects revision changes and discards its cached FFmpeg
libraries before rebuilding.

To change LAME, edit
`vendor/mp3lame-sys/lame-3.100` in the Yamu Downloader source tree, then
rebuild. This directory is a tracked Gradle input, so a normal rebuild
invalidates the native task. If you are comparing builds or changing native
compiler flags, start from a clean tree with `./gradlew clean` and remove the
Cargo build directory as well.

The resulting APK contains a newly linked `libyamu_native.so`. The configure
records in `BUILD-INFO` document the original release; a modified build
naturally produces its own paths and configuration record.
