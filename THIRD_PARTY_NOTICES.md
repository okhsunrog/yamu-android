# Third-party notices

Yamu Downloader itself is available under either the Apache License 2.0 or the
MIT License. Its Android APK also contains the following statically linked
multimedia components.

## FFmpeg

- Project: [FFmpeg](https://ffmpeg.org/)
- Source: release/8.1 at commit
  `94138f6973dd1ac6208ace92148ac0d172455d65`
- License for this build: GNU Lesser General Public License version 2.1 or
  later
- License text: [`LICENSES/FFmpeg-LGPL-2.1.txt`](LICENSES/FFmpeg-LGPL-2.1.txt)

The build explicitly disables FFmpeg's GPL, version-3, and nonfree feature
sets. It enables only the libraries and codecs selected in
`native/Cargo.toml`, including the LGPL-compatible `libmp3lame` integration.
The exact configure command used for every Android ABI is included in each
tagged release's LGPL source archive.

## LAME

- Project: [LAME](https://lame.sourceforge.io/)
- Version: 3.100
- License: GNU Library General Public License version 2
- License text: [`LICENSES/LAME-LGPL-2.0.txt`](LICENSES/LAME-LGPL-2.0.txt)

LAME is included in `vendor/mp3lame-sys/lame-3.100` and is used by FFmpeg's
`libmp3lame` encoder.

## Rust FFmpeg bindings

The `ffmpeg-next` and `ffmpeg-sys-next` Rust bindings are licensed under the
WTFPL version 2. The license text is available at
[`LICENSES/ffmpeg-rust-WTFPL.txt`](LICENSES/ffmpeg-rust-WTFPL.txt).

The vendored `mp3lame-sys` 0.1.11 wrapper declares the LGPL version 3. Its
license text is available at
[`LICENSES/mp3lame-sys-LGPL-3.0.txt`](LICENSES/mp3lame-sys-LGPL-3.0.txt).

## Source and relinking

Every tagged GitHub release includes an `lgpl-sources.tar.gz` asset next to the
APKs. It contains the exact FFmpeg and LAME sources, the local Rust build
wrappers, license texts, and the build configuration recorded for all four
APKs. The matching application source is available through GitHub's standard
source archives; ordinary Rust dependencies are reproduced from
`native/Cargo.lock`. See
[`docs/RELINKING.md`](docs/RELINKING.md) for rebuild and relinking instructions.

No warranty is provided for these third-party components. Their respective
licenses govern copying, modification, and redistribution.
