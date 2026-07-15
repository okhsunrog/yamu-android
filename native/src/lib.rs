use std::{
    path::{Path, PathBuf},
    sync::{LazyLock, Mutex, OnceLock},
    time::Duration,
};

use anyhow::{Context as _, Result, bail};
use jni::{
    EnvUnowned,
    errors::ThrowRuntimeExAndDefault,
    objects::{JClass, JObject, JString},
};
use tokio::io::AsyncWriteExt;
use yamu::{
    Client, Error as YamuError,
    auth::{DeviceAuth, DeviceTokenPoll},
    downloader::{CancellationToken, DownloadEvent, DownloadPhase, DownloadRequest, Downloader},
    media::{
        EmbeddedLyrics, MediaBackend as _, TrackMetadata, ffmpeg::Ffmpeg, verify_audio_file,
        write_metadata,
    },
    models::{Album, AudioCodec, DownloadOptions, Id, LyricsFormat, Track},
    resource::{AlbumRef, PlaylistSourceRef, TrackRef},
};

#[derive(Clone, Debug, Default)]
struct NativeProgress {
    phase: &'static str,
    downloaded: u64,
    total: Option<u64>,
    item: usize,
    item_total: usize,
    item_label: String,
}

static DOWNLOAD_PROGRESS: LazyLock<Mutex<NativeProgress>> =
    LazyLock::new(|| Mutex::new(NativeProgress::default()));
static ACTIVE_DOWNLOAD: LazyLock<Mutex<Option<CancellationToken>>> =
    LazyLock::new(|| Mutex::new(None));
static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
static ARTWORK_CLIENT: OnceLock<reqwest::Client> = OnceLock::new();

const MAX_ARTWORK_BYTES: usize = 10 * 1024 * 1024;

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

impl From<yamu::Error> for NativeError {
    fn from(error: yamu::Error) -> Self {
        Self(format!("{:#}", anyhow::Error::new(error)))
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_okhsunrog_yamu_NativeBridge_initialize<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    context: JObject<'caller>,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            #[cfg(target_os = "android")]
            {
                rustls_platform_verifier::android::init_with_env(env, context)
            }
            #[cfg(not(target_os = "android"))]
            {
                let _ = (env, context);
                Ok(())
            }
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_okhsunrog_yamu_NativeBridge_mediaBackend<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
) -> JString<'caller> {
    unowned_env
        .with_env(|env| JString::from_str(env, "ffmpeg-libav"))
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_okhsunrog_yamu_NativeBridge_downloadProgress<'caller>(
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
                    "item": progress.item,
                    "itemTotal": progress.item_total,
                    "itemLabel": progress.item_label,
                });
                Ok(JString::from_str(env, response.to_string())?)
            },
        )
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_okhsunrog_yamu_NativeBridge_cancelDownload<'caller>(
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
pub extern "system" fn Java_dev_okhsunrog_yamu_NativeBridge_requestDeviceCode<'caller>(
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
pub extern "system" fn Java_dev_okhsunrog_yamu_NativeBridge_pollDeviceToken<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    device_code: JString<'caller>,
) -> JString<'caller> {
    unowned_env
        .with_env(
            |env| -> std::result::Result<JString<'caller>, NativeError> {
                let device_code = device_code.try_to_string(env)?;
                let event = runtime()?
                    .block_on(DeviceAuth::new()?.poll_device_token_event(&device_code))?;
                let response = match event {
                    DeviceTokenPoll::Pending => serde_json::json!({ "status": "pending" }),
                    DeviceTokenPoll::SlowDown => serde_json::json!({ "status": "slow_down" }),
                    DeviceTokenPoll::Authorized(token) => serde_json::json!({
                        "status": "authorized",
                        "accessToken": token.into_access_token(),
                    }),
                };
                Ok(JString::from_str(env, response.to_string())?)
            },
        )
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_okhsunrog_yamu_NativeBridge_downloadResource<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    token: JString<'caller>,
    resource_reference: JString<'caller>,
    output_directory: JString<'caller>,
    prefer_mp3: jni::sys::jboolean,
    embed_lyrics: jni::sys::jboolean,
    save_lyrics_files: jni::sys::jboolean,
) -> JString<'caller> {
    unowned_env
        .with_env(
            |env| -> std::result::Result<JString<'caller>, NativeError> {
                let token = token.try_to_string(env)?;
                let resource_reference = resource_reference.try_to_string(env)?;
                let output_directory = output_directory.try_to_string(env)?;
                let result = runtime()?.block_on(download_resource(
                    &token,
                    &resource_reference,
                    Path::new(&output_directory),
                    prefer_mp3,
                    embed_lyrics,
                    save_lyrics_files,
                ))?;
                Ok(JString::from_str(env, result.to_string())?)
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

enum ResourceReference {
    Track(TrackRef),
    Album(AlbumRef),
    Playlist(PlaylistSourceRef),
}

struct DownloadJob {
    track: Track,
    metadata: TrackMetadata,
    stem: String,
    directory: PathBuf,
    target_directory: Option<String>,
}

#[derive(Clone, Copy)]
struct DownloadPreferences {
    prefer_mp3: bool,
    embed_lyrics: bool,
    save_lyrics_files: bool,
}

async fn download_resource(
    token: &str,
    reference: &str,
    output_directory: &Path,
    prefer_mp3: bool,
    embed_lyrics: bool,
    save_lyrics_files: bool,
) -> Result<serde_json::Value> {
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
    let reference = parse_resource_link(reference)?;
    let client = Client::new(token).context("invalid OAuth token")?;
    let uid = client
        .account_status()
        .await?
        .account
        .and_then(|account| account.uid)
        .context("account response contains no uid")?;
    tokio::fs::create_dir_all(output_directory).await?;
    let (kind, title, collection_directory, jobs) = match reference {
        ResourceReference::Track(track_ref) => {
            let track = client
                .tracks([track_ref.track_id()])
                .await?
                .into_iter()
                .next()
                .context("track metadata was not returned")?;
            let metadata = metadata_for_track(&track, None, None, None);
            let stem = format!(
                "{} - {}",
                safe_file_component(&metadata.artist),
                safe_file_component(&metadata.title)
            );
            let title = format!("{} — {}", metadata.artist, metadata.title);
            (
                "track",
                title,
                None,
                vec![DownloadJob {
                    track,
                    metadata,
                    stem,
                    directory: output_directory.to_owned(),
                    target_directory: None,
                }],
            )
        }
        ResourceReference::Album(album_ref) => {
            let album = client.album_with_tracks(album_ref.album_id()).await?;
            let directory_name = album_directory_name(&album);
            let directory = output_directory.join(&directory_name);
            tokio::fs::create_dir_all(&directory).await?;
            let title = album.title.as_deref().unwrap_or("Альбом").to_owned();
            let jobs = album_jobs(&album, &directory, &directory_name)?;
            ("album", title, Some(directory_name), jobs)
        }
        ResourceReference::Playlist(playlist_ref) => {
            let playlist = match playlist_ref {
                PlaylistSourceRef::User(reference) => {
                    client.playlist(reference.owner(), reference.kind()).await?
                }
                PlaylistSourceRef::Uuid(reference) => {
                    client.playlist_by_uuid(reference.playlist_uuid()).await?
                }
            };
            let title = playlist.title.as_deref().unwrap_or("Плейлист").to_owned();
            let directory_name = safe_file_component(&title);
            let directory = output_directory.join(&directory_name);
            tokio::fs::create_dir_all(&directory).await?;
            let total = playlist.tracks.len();
            let width = total.to_string().len().max(2);
            let mut jobs = Vec::with_capacity(total);
            for (index, short) in playlist.tracks.into_iter().enumerate() {
                let short_id = short.track_id();
                let track = match short.track {
                    Some(track) => track,
                    None => client
                        .tracks([short_id])
                        .await?
                        .into_iter()
                        .next()
                        .with_context(|| format!("playlist track {} was not returned", short.id))?,
                };
                let metadata = metadata_for_track(&track, None, None, None);
                jobs.push(DownloadJob {
                    stem: format!(
                        "{:0width$} - {} - {}",
                        index + 1,
                        safe_file_component(&metadata.artist),
                        safe_file_component(&metadata.title)
                    ),
                    track,
                    metadata,
                    directory: directory.clone(),
                    target_directory: Some(directory_name.clone()),
                });
            }
            ("playlist", title, Some(directory_name), jobs)
        }
    };

    let item_total = jobs.len();
    if item_total == 0 {
        bail!("collection contains no tracks");
    }
    let options = DownloadOptions::default();
    let backend = Ffmpeg;
    let preferences = DownloadPreferences {
        prefer_mp3,
        embed_lyrics,
        save_lyrics_files,
    };
    let mut files = Vec::with_capacity(item_total);
    let mut skipped = Vec::new();
    for (index, job) in jobs.into_iter().enumerate() {
        set_native_item(index + 1, item_total, &job.metadata.title);
        let track_id = job.track.id.to_string();
        let track_title = job.metadata.title.clone();
        let result = download_job(
            &client,
            &uid,
            &backend,
            job,
            &options,
            preferences,
            &cancellation,
        )
        .await;
        match result {
            Ok(path) => files.push(path),
            Err(error)
                if kind != "track"
                    && !cancellation.is_cancelled()
                    && is_track_unavailable(&error) =>
            {
                skipped.push(serde_json::json!({
                    "position": index + 1,
                    "trackId": track_id,
                    "title": track_title,
                    "reason": error.to_string(),
                }));
            }
            Err(error) => return Err(error),
        }
    }
    if files.is_empty() {
        bail!("all {item_total} tracks are unavailable for download");
    }
    set_native_phase("completed");
    Ok(serde_json::json!({
        "kind": kind,
        "title": title,
        "directory": collection_directory,
        "files": files,
        "skipped": skipped,
    }))
}

fn is_track_unavailable(error: &anyhow::Error) -> bool {
    match error.downcast_ref::<YamuError>() {
        Some(YamuError::DownloadUnavailable { .. }) => true,
        Some(YamuError::Api {
            status, message, ..
        }) => {
            *status == reqwest::StatusCode::FORBIDDEN && message.eq_ignore_ascii_case("no-rights")
        }
        _ => false,
    }
}

async fn download_job(
    client: &Client,
    uid: &Id,
    backend: &Ffmpeg,
    mut job: DownloadJob,
    options: &DownloadOptions,
    preferences: DownloadPreferences,
    cancellation: &CancellationToken,
) -> Result<serde_json::Value> {
    let track_id = job.track.id.to_string();
    let info = client
        .download_info(uid.clone(), track_id.as_str(), options)
        .await?;
    if cancellation.is_cancelled() {
        bail!("download was cancelled");
    }
    let extension = normalized_extension(&info.codec)?;
    let destination = job.directory.join(format!("{}.{extension}", job.stem));
    let result = Downloader::new(client.clone(), backend.clone())
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
    let mut destination = result.path;
    let mut extension = extension;
    if preferences.prefer_mp3 && extension == "m4a" {
        set_native_phase("transcoding_mp3");
        let mp3_destination = job.directory.join(format!("{}.mp3", job.stem));
        if let Err(error) = backend
            .transcode_mp3(destination.clone(), mp3_destination.clone(), 320, true)
            .await
        {
            let _ = tokio::fs::remove_file(&mp3_destination).await;
            return Err(error.into());
        }
        tokio::fs::remove_file(&destination).await?;
        destination = mp3_destination;
        extension = "mp3";
    }
    let lyrics = if preferences.embed_lyrics || preferences.save_lyrics_files {
        set_native_phase("lyrics");
        let lyrics = fetch_lyrics(client, &track_id, cancellation).await;
        if cancellation.is_cancelled() {
            let _ = tokio::fs::remove_file(&destination).await;
            bail!("download was cancelled");
        }
        lyrics
    } else {
        None
    };
    if preferences.embed_lyrics {
        job.metadata.lyrics = lyrics.as_ref().map(|lyrics| EmbeddedLyrics {
            text: lyrics.text.clone(),
            synchronized: lyrics.format == LyricsFormat::Lrc,
        });
    }
    set_native_phase("artwork");
    let artwork = fetch_artwork(&job.track, cancellation).await;
    if cancellation.is_cancelled() {
        let _ = tokio::fs::remove_file(&destination).await;
        bail!("download was cancelled");
    }
    set_native_phase("metadata");
    write_metadata(backend, &destination, &job.metadata, artwork).await?;
    if cancellation.is_cancelled() {
        let _ = tokio::fs::remove_file(&destination).await;
        bail!("download was cancelled");
    }
    set_native_phase("verifying");
    verify_audio_file(backend, &destination, extension).await?;
    if cancellation.is_cancelled() {
        let _ = tokio::fs::remove_file(&destination).await;
        bail!("download was cancelled");
    }
    let lyrics_path = if preferences.save_lyrics_files {
        match lyrics.as_ref() {
            Some(lyrics) => Some(
                write_lyrics_sidecar(&destination, lyrics.format, &lyrics.text)
                    .await
                    .context("failed to save lyrics sidecar")?,
            ),
            None => None,
        }
    } else {
        None
    };
    if cancellation.is_cancelled() {
        let _ = tokio::fs::remove_file(&destination).await;
        if let Some(path) = &lyrics_path {
            let _ = tokio::fs::remove_file(path).await;
        }
        bail!("download was cancelled");
    }
    Ok(serde_json::json!({
        "path": destination.display().to_string(),
        "directory": job.target_directory,
        "lyricsPath": lyrics_path.map(|path| path.display().to_string()),
    }))
}

struct FetchedLyrics {
    format: LyricsFormat,
    text: String,
}

async fn fetch_lyrics(
    client: &Client,
    track_id: &str,
    cancellation: &CancellationToken,
) -> Option<FetchedLyrics> {
    for format in [LyricsFormat::Lrc, LyricsFormat::Text] {
        if cancellation.is_cancelled() {
            return None;
        }
        let text = match client.track_lyrics(track_id, format).await {
            Ok(lyrics) => client.fetch_lyrics(&lyrics).await.ok(),
            Err(_) => None,
        };
        if let Some(text) = text.filter(|text| !text.trim().is_empty()) {
            return Some(FetchedLyrics { format, text });
        }
    }
    None
}

fn lyrics_sidecar_path(audio_path: &Path, format: LyricsFormat) -> PathBuf {
    audio_path.with_extension(format.file_extension())
}

async fn write_lyrics_sidecar(
    audio_path: &Path,
    format: LyricsFormat,
    text: &str,
) -> Result<PathBuf> {
    let destination = lyrics_sidecar_path(audio_path, format);
    let temporary = destination.with_extension(format!("{}.part", format.file_extension()));
    let result = async {
        let mut file = tokio::fs::File::create(&temporary).await?;
        file.write_all(text.as_bytes()).await?;
        file.sync_all().await?;
        drop(file);
        tokio::fs::rename(&temporary, &destination).await?;
        Ok::<_, std::io::Error>(())
    }
    .await;
    if result.is_err() {
        let _ = tokio::fs::remove_file(&temporary).await;
    }
    result?;
    Ok(destination)
}

fn parse_resource_link(reference: &str) -> Result<ResourceReference> {
    if !reference.starts_with("https://music.yandex.ru/") {
        bail!("only Yandex Music links are accepted");
    }
    if let Ok(track) = reference.parse::<TrackRef>() {
        return Ok(ResourceReference::Track(track));
    }
    if let Ok(album) = reference.parse::<AlbumRef>() {
        return Ok(ResourceReference::Album(album));
    }
    if let Ok(playlist) = reference.parse::<PlaylistSourceRef>() {
        return Ok(ResourceReference::Playlist(playlist));
    }
    bail!("link must point to a track, album, or playlist")
}

fn metadata_for_track(
    track: &Track,
    album_override: Option<&Album>,
    track_number: Option<u32>,
    disc_number: Option<u32>,
) -> TrackMetadata {
    let artist = track
        .artists
        .iter()
        .filter_map(|artist| artist.name.as_deref())
        .collect::<Vec<_>>()
        .join(", ");
    let artist = if artist.is_empty() {
        "Unknown artist".to_owned()
    } else {
        artist
    };
    let album = album_override.or_else(|| track.albums.first());
    let album_artist = album.map(|album| {
        album
            .artists
            .iter()
            .filter_map(|artist| artist.name.as_deref())
            .collect::<Vec<_>>()
            .join(", ")
    });
    TrackMetadata {
        title: track.title.as_deref().unwrap_or("Untitled").to_owned(),
        artist,
        album: album.and_then(|album| album.title.clone()),
        album_artist: album_artist.filter(|value| !value.is_empty()),
        genre: album.and_then(|album| album.genre.clone()),
        year: album.and_then(|album| album.year),
        track_number: track_number.or_else(|| {
            album
                .and_then(|album| album.track_position.as_ref())
                .and_then(|position| position.index)
        }),
        disc_number: disc_number.or_else(|| {
            album
                .and_then(|album| album.track_position.as_ref())
                .and_then(|position| position.volume)
        }),
        lyrics: None,
    }
}

fn album_directory_name(album: &Album) -> String {
    let artists = album
        .artists
        .iter()
        .filter_map(|artist| artist.name.as_deref())
        .collect::<Vec<_>>()
        .join(", ");
    let artist = if artists.is_empty() {
        "Unknown artist"
    } else {
        &artists
    };
    let year = album
        .year
        .map(|year| format!(" ({year})"))
        .unwrap_or_default();
    format!(
        "{} - {}{}",
        safe_file_component(artist),
        safe_file_component(album.title.as_deref().unwrap_or("Untitled album")),
        year
    )
}

fn album_jobs(album: &Album, directory: &Path, target_root: &str) -> Result<Vec<DownloadJob>> {
    let volumes = album
        .volumes
        .as_ref()
        .context("album response contains no tracks")?;
    let total = volumes.iter().map(Vec::len).sum::<usize>();
    let width = volumes
        .iter()
        .map(Vec::len)
        .max()
        .unwrap_or_default()
        .to_string()
        .len()
        .max(2);
    let mut jobs = Vec::with_capacity(total);
    for (disc_index, tracks) in volumes.iter().enumerate() {
        let disc = (disc_index + 1) as u32;
        let subdirectory = (volumes.len() > 1).then(|| format!("CD{disc}"));
        let track_directory = subdirectory
            .as_deref()
            .map_or_else(|| directory.to_owned(), |name| directory.join(name));
        for (track_index, track) in tracks.iter().enumerate() {
            let number = (track_index + 1) as u32;
            let metadata = metadata_for_track(track, Some(album), Some(number), Some(disc));
            jobs.push(DownloadJob {
                stem: format!(
                    "{number:0width$} - {} - {}",
                    safe_file_component(&metadata.artist),
                    safe_file_component(&metadata.title)
                ),
                track: track.clone(),
                metadata,
                directory: track_directory.clone(),
                target_directory: Some(match &subdirectory {
                    Some(name) => format!("{target_root}/{name}"),
                    None => target_root.to_owned(),
                }),
            });
        }
    }
    Ok(jobs)
}

async fn fetch_artwork(
    track: &yamu::models::Track,
    cancellation: &CancellationToken,
) -> Option<Vec<u8>> {
    let url = track.cover_url("600x600").or_else(|| {
        track
            .albums
            .first()
            .and_then(|album| album.cover_url("600x600"))
    })?;
    let client = artwork_client().ok()?;
    let mut response = tokio::select! {
        () = cancellation.cancelled() => return None,
        response = client.get(url).send() => response.ok()?,
    }
    .error_for_status()
    .ok()?;
    if response
        .content_length()
        .is_some_and(|length| length > MAX_ARTWORK_BYTES as u64)
    {
        return None;
    }
    let mut bytes = Vec::new();
    loop {
        let chunk = tokio::select! {
            () = cancellation.cancelled() => return None,
            chunk = response.chunk() => chunk.ok()?,
        };
        let Some(chunk) = chunk else {
            return Some(bytes);
        };
        if bytes.len().saturating_add(chunk.len()) > MAX_ARTWORK_BYTES {
            return None;
        }
        bytes.extend_from_slice(&chunk);
    }
}

fn artwork_client() -> Result<&'static reqwest::Client> {
    if let Some(client) = ARTWORK_CLIENT.get() {
        return Ok(client);
    }
    let client = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(10))
        .read_timeout(Duration::from_secs(15))
        .build()?;
    let _ = ARTWORK_CLIENT.set(client);
    ARTWORK_CLIENT
        .get()
        .context("artwork HTTP client was not initialized")
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
        progress.item = 0;
        progress.item_total = 0;
        progress.item_label.clear();
    }
}

fn set_native_item(item: usize, item_total: usize, item_label: &str) {
    let mut progress = DOWNLOAD_PROGRESS
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    progress.item = item;
    progress.item_total = item_total;
    progress.item_label = item_label.to_owned();
    progress.downloaded = 0;
    progress.total = None;
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
    use super::{
        ResourceReference, is_track_unavailable, lyrics_sidecar_path, parse_resource_link,
        safe_file_component, write_lyrics_sidecar,
    };
    use yamu::Error as YamuError;
    use yamu::models::LyricsFormat;
    use yamu::resource::PlaylistSourceRef;

    #[test]
    fn sanitizes_android_file_names() {
        assert_eq!(safe_file_component("AC/DC: Song?"), "AC_DC_ Song_");
    }

    #[test]
    fn uses_the_fetched_lyrics_format_for_sidecars() {
        let audio = std::path::Path::new("Artist - Track.flac");
        assert_eq!(
            lyrics_sidecar_path(audio, LyricsFormat::Lrc),
            std::path::Path::new("Artist - Track.lrc")
        );
        assert_eq!(
            lyrics_sidecar_path(audio, LyricsFormat::Text),
            std::path::Path::new("Artist - Track.txt")
        );
    }

    #[tokio::test]
    async fn writes_lyrics_sidecars_atomically() {
        let nonce = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let directory = std::env::temp_dir().join(format!(
            "yamu-native-lyrics-test-{}-{nonce}",
            std::process::id()
        ));
        tokio::fs::create_dir_all(&directory).await.unwrap();
        let audio = directory.join("Artist - Track.flac");

        let sidecar = write_lyrics_sidecar(&audio, LyricsFormat::Lrc, "[00:00]Test")
            .await
            .unwrap();

        assert_eq!(sidecar, directory.join("Artist - Track.lrc"));
        assert_eq!(
            tokio::fs::read_to_string(&sidecar).await.unwrap(),
            "[00:00]Test"
        );
        assert!(
            !tokio::fs::try_exists(directory.join("Artist - Track.lrc.part"))
                .await
                .unwrap()
        );
        tokio::fs::remove_dir_all(directory).await.unwrap();
    }

    #[test]
    fn accepts_supported_resource_links() {
        assert!(matches!(
            parse_resource_link(
                "https://music.yandex.ru/album/19097174/track/94298678?utm_source=share"
            ),
            Ok(ResourceReference::Track(_))
        ));
        assert!(matches!(
            parse_resource_link("https://music.yandex.ru/album/19097174"),
            Ok(ResourceReference::Album(_))
        ));
        assert!(matches!(
            parse_resource_link("https://music.yandex.ru/users/example/playlists/42"),
            Ok(ResourceReference::Playlist(PlaylistSourceRef::User(_)))
        ));
        assert!(matches!(
            parse_resource_link(
                "https://music.yandex.ru/playlists/fa1b8d08-71c7-3ed8-9c58-8eebbdccdf7f?utm_source=web&utm_medium=copy_link"
            ),
            Ok(ResourceReference::Playlist(PlaylistSourceRef::Uuid(_)))
        ));
        assert!(parse_resource_link("94298678").is_err());
    }

    #[test]
    fn skips_only_errors_for_unavailable_collection_tracks() {
        let no_rights = anyhow::Error::new(YamuError::Api {
            status: reqwest::StatusCode::FORBIDDEN,
            message: "no-rights".to_owned(),
            body: None,
        });
        let unavailable = anyhow::Error::new(YamuError::DownloadUnavailable {
            name: "not-found".to_owned(),
            message: "track has no downloadable source".to_owned(),
        });
        let rate_limited = anyhow::Error::new(YamuError::Api {
            status: reqwest::StatusCode::TOO_MANY_REQUESTS,
            message: "rate-limit".to_owned(),
            body: None,
        });

        assert!(is_track_unavailable(&no_rights));
        assert!(is_track_unavailable(&unavailable));
        assert!(!is_track_unavailable(&rate_limited));
    }
}
