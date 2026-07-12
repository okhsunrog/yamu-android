use std::path::{Path, PathBuf};

use anyhow::{Context as _, Result, bail};
use futures_util::StreamExt as _;
use jni::{
    EnvUnowned,
    errors::ThrowRuntimeExAndDefault,
    objects::{JClass, JString},
};
use tokio::io::AsyncWriteExt as _;
use yandex_music_api::{
    Client,
    media::{MediaBackend as _, TrackMetadata, ffmpeg::Ffmpeg, verify_audio_file, write_metadata},
    models::{AudioCodec, DownloadInfo, DownloadOptions},
    resource::TrackRef,
};

#[derive(Debug)]
struct NativeError(String);

impl std::fmt::Display for NativeError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(&self.0)
    }
}

impl std::error::Error for NativeError {}

impl From<anyhow::Error> for NativeError {
    fn from(error: anyhow::Error) -> Self {
        Self(format!("{error:#}"))
    }
}

impl From<jni::errors::Error> for NativeError {
    fn from(error: jni::errors::Error) -> Self {
        Self(error.to_string())
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_okhsunrog_yamusdownloader_NativeBridge_mediaBackend<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
) -> JString<'caller> {
    unowned_env
        .with_env(|env| JString::from_str(env, "ffmpeg-libav"))
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_okhsunrog_yamusdownloader_NativeBridge_downloadTrack<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    token: JString<'caller>,
    track_reference: JString<'caller>,
    output_directory: JString<'caller>,
) -> JString<'caller> {
    unowned_env
        .with_env(
            |env| -> std::result::Result<JString<'caller>, NativeError> {
                let token = token.try_to_string(env)?;
                let track_reference = track_reference.try_to_string(env)?;
                let output_directory = output_directory.try_to_string(env)?;
                let runtime = tokio::runtime::Builder::new_multi_thread()
                    .enable_all()
                    .build()
                    .context("failed to create async runtime")?;
                let path = runtime.block_on(download_track(
                    &token,
                    &track_reference,
                    Path::new(&output_directory),
                ))?;
                Ok(JString::from_str(env, path)?)
            },
        )
        .resolve::<ThrowRuntimeExAndDefault>()
}

async fn download_track(token: &str, reference: &str, output_directory: &Path) -> Result<String> {
    let track_reference = parse_track_link(reference)?;
    let track_id = track_reference.track_id();
    let client = Client::new(token).context("invalid OAuth token")?;
    let uid = client
        .account_status()
        .await?
        .account
        .and_then(|account| account.uid)
        .context("account response contains no uid")?;
    let track = client
        .tracks([track_id])
        .await?
        .into_iter()
        .next()
        .context("track metadata was not returned")?;
    let info = client
        .download_info(uid, track_id, &DownloadOptions::default())
        .await?;
    if info.decryption_key.is_some() {
        bail!("the server returned encrypted audio");
    }

    tokio::fs::create_dir_all(output_directory).await?;
    let artist = track
        .artists
        .iter()
        .filter_map(|artist| artist.name.as_deref())
        .collect::<Vec<_>>()
        .join(", ");
    let artist = if artist.is_empty() {
        "Unknown artist"
    } else {
        &artist
    };
    let title = track.title.as_deref().unwrap_or("Untitled");
    let extension = normalized_extension(&info.codec)?;
    let destination = output_directory.join(format!(
        "{} - {}.{extension}",
        safe_file_component(artist),
        safe_file_component(title)
    ));
    let backend = Ffmpeg;

    if matches!(info.codec, AudioCodec::FlacMp4) {
        let source = temporary_path(&destination, "source.m4a");
        download_audio(&client, &info, &source).await?;
        let result = backend
            .remux_flac(source.clone(), destination.clone(), true)
            .await;
        let _ = tokio::fs::remove_file(source).await;
        result?;
    } else {
        let temporary = temporary_path(&destination, "download.part");
        download_audio(&client, &info, &temporary).await?;
        if tokio::fs::try_exists(&destination).await? {
            tokio::fs::remove_file(&destination).await?;
        }
        tokio::fs::rename(temporary, &destination).await?;
    }

    let album = track.albums.first();
    let album_artist = album.map(|album| {
        album
            .artists
            .iter()
            .filter_map(|artist| artist.name.as_deref())
            .collect::<Vec<_>>()
            .join(", ")
    });
    let metadata = TrackMetadata {
        title: title.to_owned(),
        artist: artist.to_owned(),
        album: album.and_then(|album| album.title.clone()),
        album_artist: album_artist.filter(|value| !value.is_empty()),
        genre: album.and_then(|album| album.genre.clone()),
        year: album.and_then(|album| album.year),
        track_number: album
            .and_then(|album| album.track_position.as_ref())
            .and_then(|position| position.index),
        disc_number: album
            .and_then(|album| album.track_position.as_ref())
            .and_then(|position| position.volume),
        lyrics: None,
    };
    let artwork = fetch_artwork(&track).await;
    write_metadata(&backend, &destination, &metadata, artwork).await?;
    verify_audio_file(&backend, &destination, extension).await?;
    Ok(destination.display().to_string())
}

fn parse_track_link(reference: &str) -> Result<TrackRef> {
    if !reference.starts_with("https://music.yandex.ru/") {
        bail!("only Yandex Music track links are accepted");
    }
    reference.parse().context("invalid Yandex Music track link")
}

async fn download_audio(client: &Client, info: &DownloadInfo, destination: &Path) -> Result<()> {
    let mut last_error = None;
    for url in &info.urls {
        let result = async {
            let response = client.open_audio_stream(url).await?;
            let mut stream = response.bytes_stream();
            let mut file = tokio::fs::File::create(destination).await?;
            while let Some(chunk) = stream.next().await {
                file.write_all(&chunk?).await?;
            }
            file.flush().await?;
            file.sync_all().await?;
            Ok::<_, anyhow::Error>(())
        }
        .await;
        match result {
            Ok(()) => return Ok(()),
            Err(error) => {
                last_error = Some(error);
                let _ = tokio::fs::remove_file(destination).await;
            }
        }
    }
    Err(last_error.unwrap_or_else(|| anyhow::anyhow!("the server returned no download URLs")))
}

async fn fetch_artwork(track: &yandex_music_api::models::Track) -> Option<Vec<u8>> {
    let url = track.cover_url("600x600").or_else(|| {
        track
            .albums
            .first()
            .and_then(|album| album.cover_url("600x600"))
    })?;
    reqwest::get(url)
        .await
        .ok()?
        .error_for_status()
        .ok()?
        .bytes()
        .await
        .ok()
        .map(|bytes| bytes.to_vec())
}

fn normalized_extension(codec: &AudioCodec) -> Result<&'static str> {
    match codec {
        AudioCodec::Flac | AudioCodec::FlacMp4 => Ok("flac"),
        AudioCodec::Aac | AudioCodec::HeAac | AudioCodec::AacMp4 | AudioCodec::HeAacMp4 => {
            Ok("m4a")
        }
        AudioCodec::Mp3 => Ok("mp3"),
        AudioCodec::Other(value) => bail!("unsupported codec {value}"),
        _ => bail!("unsupported codec returned by the server"),
    }
}

fn safe_file_component(value: &str) -> String {
    let value = value
        .chars()
        .map(|character| match character {
            '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|' | '\0' => '_',
            _ => character,
        })
        .collect::<String>();
    let value = value.trim().trim_matches('.');
    if value.is_empty() {
        "Untitled".to_owned()
    } else {
        value.to_owned()
    }
}

fn temporary_path(destination: &Path, suffix: &str) -> PathBuf {
    let name = destination
        .file_name()
        .unwrap_or_default()
        .to_string_lossy();
    destination
        .parent()
        .unwrap_or_else(|| Path::new("."))
        .join(format!(".{name}.{}.{suffix}", std::process::id()))
}

#[cfg(test)]
mod tests {
    use super::{parse_track_link, safe_file_component};

    #[test]
    fn sanitizes_android_file_names() {
        assert_eq!(safe_file_component("AC/DC: Song?"), "AC_DC_ Song_");
    }

    #[test]
    fn accepts_only_track_links() {
        let link = parse_track_link(
            "https://music.yandex.ru/album/19097174/track/94298678?utm_source=share",
        )
        .unwrap();
        assert_eq!(link.track_id(), "94298678");
        assert!(parse_track_link("94298678").is_err());
        assert!(parse_track_link("https://music.yandex.ru/album/19097174").is_err());
    }
}
