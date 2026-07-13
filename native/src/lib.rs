use std::{
    path::Path,
    sync::{LazyLock, Mutex, OnceLock},
};

use anyhow::{Context as _, Result, bail};
use jni::{
    EnvUnowned,
    errors::ThrowRuntimeExAndDefault,
    objects::{JClass, JObject, JString},
};
use yandex_music_api::{
    Client,
    auth::DeviceAuth,
    downloader::{CancellationToken, DownloadEvent, DownloadPhase, DownloadRequest, Downloader},
    media::{TrackMetadata, ffmpeg::Ffmpeg, verify_audio_file, write_metadata},
    models::{AudioCodec, DownloadOptions},
    resource::TrackRef,
};

#[derive(Clone, Debug, Default)]
struct NativeProgress {
    phase: &'static str,
    downloaded: u64,
    total: Option<u64>,
}

static DOWNLOAD_PROGRESS: LazyLock<Mutex<NativeProgress>> =
    LazyLock::new(|| Mutex::new(NativeProgress::default()));
static ACTIVE_DOWNLOAD: LazyLock<Mutex<Option<CancellationToken>>> =
    LazyLock::new(|| Mutex::new(None));
static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
static ARTWORK_CLIENT: LazyLock<reqwest::Client> = LazyLock::new(reqwest::Client::new);

struct ActiveDownloadGuard;

impl Drop for ActiveDownloadGuard {
    fn drop(&mut self) {
        *ACTIVE_DOWNLOAD
            .lock()
            .unwrap_or_else(|error| error.into_inner()) = None;
    }
}

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

impl From<yandex_music_api::Error> for NativeError {
    fn from(error: yandex_music_api::Error) -> Self {
        Self(format!("{:#}", anyhow::Error::new(error)))
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_okhsunrog_yamusdownloader_NativeBridge_initialize<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    context: JObject<'caller>,
) {
    unowned_env
        .with_env(|env| rustls_platform_verifier::android::init_with_env(env, context))
        .resolve::<ThrowRuntimeExAndDefault>()
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
pub extern "system" fn Java_dev_okhsunrog_yamusdownloader_NativeBridge_downloadProgress<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
) -> JString<'caller> {
    unowned_env
        .with_env(
            |env| -> std::result::Result<JString<'caller>, NativeError> {
                let progress = DOWNLOAD_PROGRESS
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    .clone();
                let response = serde_json::json!({
                    "phase": progress.phase,
                    "downloaded": progress.downloaded,
                    "total": progress.total,
                });
                Ok(JString::from_str(env, response.to_string())?)
            },
        )
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_okhsunrog_yamusdownloader_NativeBridge_cancelDownload<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
) {
    unowned_env
        .with_env(|_| -> std::result::Result<(), NativeError> {
            if let Some(cancellation) = ACTIVE_DOWNLOAD
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .as_ref()
            {
                cancellation.cancel();
            }
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_okhsunrog_yamusdownloader_NativeBridge_requestDeviceCode<
    'caller,
>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
) -> JString<'caller> {
    unowned_env
        .with_env(
            |env| -> std::result::Result<JString<'caller>, NativeError> {
                let runtime = runtime()?;
                let code = runtime.block_on(DeviceAuth::new()?.request_device_code())?;
                let response = serde_json::json!({
                    "deviceCode": code.device_code,
                    "userCode": code.user_code,
                    "verificationUrl": code.verification_url,
                    "expiresIn": code.expires_in,
                    "interval": code.interval,
                });
                Ok(JString::from_str(env, response.to_string())?)
            },
        )
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_okhsunrog_yamusdownloader_NativeBridge_pollDeviceToken<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    device_code: JString<'caller>,
) -> JString<'caller> {
    unowned_env
        .with_env(
            |env| -> std::result::Result<JString<'caller>, NativeError> {
                let device_code = device_code.try_to_string(env)?;
                let token =
                    runtime()?.block_on(DeviceAuth::new()?.poll_device_token(&device_code))?;
                let access_token =
                    token.map_or_else(String::new, |token| token.into_access_token());
                Ok(JString::from_str(env, access_token)?)
            },
        )
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
                let path = runtime()?.block_on(download_track(
                    &token,
                    &track_reference,
                    Path::new(&output_directory),
                ))?;
                Ok(JString::from_str(env, path)?)
            },
        )
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn runtime() -> Result<&'static tokio::runtime::Runtime> {
    if let Some(runtime) = RUNTIME.get() {
        return Ok(runtime);
    }

    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .context("failed to create async runtime")?;
    let _ = RUNTIME.set(runtime);
    Ok(RUNTIME.get().expect("runtime was initialized"))
}

async fn download_track(token: &str, reference: &str, output_directory: &Path) -> Result<String> {
    let cancellation = CancellationToken::new();
    {
        let mut active = ACTIVE_DOWNLOAD
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if active.is_some() {
            bail!("another download is already running");
        }
        *active = Some(cancellation.clone());
    }
    let _active_guard = ActiveDownloadGuard;
    set_native_phase("preparing");
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
    if cancellation.is_cancelled() {
        bail!("download was cancelled");
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
    let result = Downloader::new(client, backend.clone())
        .download(
            DownloadRequest {
                info,
                destination,
                replace: true,
            },
            cancellation.clone(),
            update_download_event,
        )
        .await?;
    let destination = result.path;

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
    set_native_phase("artwork");
    let artwork = fetch_artwork(&track).await;
    if cancellation.is_cancelled() {
        let _ = tokio::fs::remove_file(&destination).await;
        bail!("download was cancelled");
    }
    set_native_phase("metadata");
    write_metadata(&backend, &destination, &metadata, artwork).await?;
    if cancellation.is_cancelled() {
        let _ = tokio::fs::remove_file(&destination).await;
        bail!("download was cancelled");
    }
    set_native_phase("verifying");
    verify_audio_file(&backend, &destination, extension).await?;
    if cancellation.is_cancelled() {
        let _ = tokio::fs::remove_file(&destination).await;
        bail!("download was cancelled");
    }
    set_native_phase("completed");
    Ok(destination.display().to_string())
}

fn parse_track_link(reference: &str) -> Result<TrackRef> {
    if !reference.starts_with("https://music.yandex.ru/") {
        bail!("only Yandex Music track links are accepted");
    }
    reference.parse().context("invalid Yandex Music track link")
}

async fn fetch_artwork(track: &yandex_music_api::models::Track) -> Option<Vec<u8>> {
    let url = track.cover_url("600x600").or_else(|| {
        track
            .albums
            .first()
            .and_then(|album| album.cover_url("600x600"))
    })?;
    ARTWORK_CLIENT
        .get(url)
        .send()
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

fn set_native_phase(phase: &'static str) {
    let mut progress = DOWNLOAD_PROGRESS
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    progress.phase = phase;
    if phase == "preparing" {
        progress.downloaded = 0;
        progress.total = None;
    }
}

fn update_download_event(event: DownloadEvent) {
    let mut progress = DOWNLOAD_PROGRESS
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    match event {
        DownloadEvent::PhaseChanged(phase) => {
            progress.phase = match phase {
                DownloadPhase::Connecting => "connecting",
                DownloadPhase::Downloading => "downloading",
                DownloadPhase::Normalizing => "normalizing",
                DownloadPhase::Finalizing => "finalizing",
            };
        }
        DownloadEvent::Progress { downloaded, total } => {
            progress.downloaded = downloaded;
            progress.total = total;
        }
        DownloadEvent::Retrying { .. } => progress.phase = "retrying",
    }
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
