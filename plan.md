# CloudStream Companion — Phone ⇄ TV (plan.md)

> **Feature**: Use the phone app as the native companion for the TV app. The TV is
> the only movie playback target: the TV can browse and play independently, or the
> phone can browse and send the primary Play command to the TV.
> Extensions, repos, accounts, cookies and logins live on the **phone**; the **TV**
> receives fully-resolved streams and renders playback. Libraries (bookmarks,
> subscriptions, favorites, watch progress) are shared. Extensions installed on
> the phone are mirrored onto the TV. Interaction is semantic ("play this
> episode on the TV"), not a dumb button remote.

This document is written for an **implementer agent**. Every section references
real files/symbols in this repository. Read the referenced code before editing.

---

## 1. Current state of the repository (what already exists)

A first cut of a LAN remote is **already committed** (`feat(remote): add phone tv control`,
`test(remote): cover lan protocol`). All of it lives in
`app/src/main/java/com/lagradost/cloudstream3/remote/`:

| File | Role |
|---|---|
| `LanRemoteProtocol.kt` | Protocol **v1**: 4-byte length-prefixed JSON frames over raw TCP, port `46900`, NSD type `_cloudstream-remote._tcp.`. Commands: `PING, LAUNCH, KEY, TEXT, PLAY`. Max frame 1 MB. |
| `LanRemoteServer.kt` | TV-side server. `ServerSocket`, **sequential** client handling (one at a time), NSD registration, dispatches keys via `CommonActivity.activity.dispatchKeyEvent`, plays via `OfflinePlaybackHelper.playIntent`. Retries `PLAY` up to 10× while cold-starting the app. |
| `LanRemoteClient.kt` | Phone-side client. One socket per request. Persists selected endpoint in `SharedPreferences("lan_remote")`. |
| `LanRemoteDiscovery.kt` | NSD discovery + multicast lock + queued resolution. |
| `LanRemoteService.kt` | Foreground service (`connectedDevice`) hosting the server. |
| `LanRemoteBootReceiver.kt` | Starts the service on boot **only if** `UI_MODE_TYPE_TELEVISION`. |
| `RemoteControlActivity.kt` | Phone UI: discovery spinner, manual host entry, dpad buttons, text input. This is the "broken remote" experience we must supersede (keep it as a secondary "classic remote" tab). |
| `ui/player/PlaybackCoordinator.kt` | Core playback-target decision. TV devices keep the native local player; paired phones resolve links and send the primary Play request to the TV. The LAN protocol is an implementation detail, not a player action. |

Supporting pieces that already exist elsewhere:

- `actions/temp/CloudStreamPackage.kt` — `MinimalVideoLink` / `MinimalSubtitleLink`
  (the exact DTOs used to hand fully-resolved streams to another CloudStream
  instance) and `LINKS_EXTRA`/`SUBTITLE_EXTRA`/`ID_EXTRA`/`POSITION_EXTRA` keys.
- `ui/player/OfflinePlaybackHelper.playIntent()` — builds a `MinimalLinkGenerator`
  and navigates to `GeneratorPlayer`. The TV already plays remote content through
  this path; **no extractors run on the TV**.
- `ui/player/CS3IPlayer.kt` — `createVideoSource()` (~line 783) applies
  `link.headers` (+ `referer`) to **all** media requests incl. HLS/DASH segments,
  via `HttpDataSource.Factory.setDefaultRequestProperties`. So any auth/cookie
  headers the phone embeds into the links are honored by the TV player.
- `MainActivity.kt` (~line 1990) — auto-starts `LanRemoteService` when
  `Configuration.UI_MODE_TYPE_TELEVISION`.
- `SettingsFragment.kt:252` — settings row that opens `RemoteControlActivity`.
- `EpisodeAdapter.kt` + `ResultViewModel2.kt` — the primary Play action is now a
  stable app action. `PlaybackCoordinator` selects local TV playback, remote TV
  playback, or pairing UI. Explicit "Play in app" and external-player actions
  remain separate local escape hatches.
- App-level `Event<T>` bus in `MainActivity.Companion`
  (`afterPluginsLoadedEvent`, `bookmarksUpdatedEvent`, `reloadLibraryEvent`) —
  reuse this pattern for sync triggers.
- Tests: `app/src/test/java/com/lagradost/cloudstream3/remote/LanRemoteProtocolTest.kt`.

### Gaps to close (this plan)

1. **No security**: anyone on the LAN can send `PLAY`/`KEY`. → Pairing + HMAC auth.
2. **No extension sync** (phone → TV mirroring).
3. **No library sync** (bookmarks/favorites/subscriptions/resume/progress).
4. **No feedback channel**: phone gets no now-playing state; watch progress made
   on the TV never comes back to the phone.
5. **Remote UX is key injection**; we need semantic commands (`OPEN_PAGE`,
   player control) and a proper companion UI.
6. Server is single-threaded-per-accept; no persistent connection for events.

---

## 2. Architecture

### 2.1 Roles

- **One APK, two roles.** No new build flavor. Role is decided at runtime:
  - `isTelevision = (uiMode and UI_MODE_TYPE_MASK) == UI_MODE_TYPE_TELEVISION`
    (same check as `MainActivity.kt:1990`).
  - New setting **"Allow this device to be controlled"** (receiver role):
    default ON on TV, OFF on phone. Replaces the hardcoded TV check in
    `MainActivity` and `LanRemoteBootReceiver`.
- **Phone = control plane.** Extensions, repositories, accounts
  (`DataStoreHelper.accounts`), WebView logins (`ui/WebviewFragment.kt`,
  `android.webkit.CookieManager`), Cloudflare clearance cookies
  (`network/CloudflareKiller.kt`), library DB — all live here. Search, `load()`,
  and `loadLinks()` (see `MainAPI.loadLinks`, library module) run on the phone.
- **TV = playback plane.** It receives *fully resolved* `ExtractorLink` data
  (URL + mime type + headers, incl. cookies) and subtitle URLs, then plays them
  with the existing player. The TV still keeps its own synced copy of the
  extensions so that (a) it remains fully usable standalone with its own UI,
  (b) provider `MainAPI.getVideoInterceptor()` and extension-registered
  `VideoClickAction`s / subtitle providers exist on the TV.

### 2.2 Why the TV still needs the extensions (design constraint)

The user asked for "install on phone ⇒ installed on TV". Beyond standalone TV
browsing, two playback-relevant reasons:

1. `CS3IPlayer` can route media requests through a provider-supplied OkHttp
   `Interceptor` (`MainAPI.getVideoInterceptor()`). The lookup needs the
   provider instance, which needs the plugin loaded on the TV.
2. Extension-registered subtitle providers / click actions exist on the TV.

So extension sync is **not** just cosmetic — see §7 for how playback links keep
their `source` (provider apiName) so the TV player can find the provider.

### 2.3 Process/connection model

Keep the existing transport (raw TCP + length-prefixed JSON, NSD discovery) —
it is simple, testable, and already partially shipped. Upgrade it:

- **Control channel**: connect-per-request (as today) for one-shot commands.
- **Event channel**: one persistent TCP connection per paired phone, opened with
  `SUBSCRIBE`; the TV streams events (playback state, sync results, library
  deltas). Phone reconnects with exponential backoff while the TV is paired and
  reachable. Server handles each client on its own coroutine (fix the current
  sequential accept loop).

---

## 3. Protocol v2 (`remote/LanRemoteProtocol.kt` — rewrite)

### 3.1 Framing & envelope

Unchanged framing: `[4-byte big-endian length][UTF-8 JSON]`, max frame 1 MB
(file transfer uses chunked frames, §8.4).

```kotlin
@Serializable
data class RemoteEnvelope(
    val version: Int = 2,
    val requestId: String = UUID.randomUUID().toString(),
    val deviceId: String,              // random UUID assigned at pairing; "" for PAIR_*/PING
    val timestampMs: Long,
    val auth: String? = null,          // Base64 HMAC-SHA256(token, "$requestId:$timestampMs")
    val type: RemoteMessageType,
    val payload: JsonObject? = null,   // decoded per type
)

@Serializable
data class RemoteReply(
    val version: Int = 2,
    val requestId: String,
    val accepted: Boolean,
    val error: String? = null,
    val payload: JsonObject? = null,
)
```

Keep `LanRemoteProtocol.json` (`encodeDefaults`, `ignoreUnknownKeys`) — unknown
fields must not break older/newer peers.

**Version policy**: v2 server rejects v1 envelopes with
`error = "upgrade-required"`. The v1 `PING` shape is still answered (without
auth) so an old phone shows a meaningful message. Both devices are installed by
the same user; hard cutover is acceptable.

### 3.2 Message types

```kotlin
enum class RemoteMessageType {
    // unauthenticated
    PING, PAIR_HELLO, PAIR_VERIFY,
    // authenticated, one-shot
    UNPAIR, HELLO, LAUNCH, KEY, TEXT,
    PLAY, PLAYER_CMD, GET_STATE, OPEN_PAGE,
    SYNC_EXTENSIONS, EXT_FILE_START, EXT_FILE_CHUNK, EXT_FILE_END,
    SYNC_LIBRARY,
    // event channel
    SUBSCRIBE, EVENT,
}
```

Payload sketches (all `@Serializable`, put them in `remote/RemoteMessages.kt`):

```kotlin
@Serializable data class DeviceInfo(
    val deviceId: String, val name: String, val appVersion: String,
    val protocol: Int, val isTv: Boolean, val paired: Boolean,
    val capabilities: Set<String>,      // e.g. "events","ext-sync","lib-sync","player-cmd"
)

@Serializable data class PairHelloRequest(val deviceId: String, val deviceName: String)
@Serializable data class PairHelloReply(val pairingSessionId: String, val expiresInMs: Long)
@Serializable data class PairVerifyRequest(val pairingSessionId: String, val pin: String)
@Serializable data class PairVerifyReply(val token: String, val tv: DeviceInfo)

@Serializable data class PlayPayload(
    val links: List<MinimalVideoLinkV2>,      // §7
    val subtitles: List<CloudStreamPackage.MinimalSubtitleLink> = emptyList(),
    val title: String? = null,
    val poster: String? = null,
    val mediaId: Int? = null,                 // ResultEpisode.id — resume key
    val positionMs: Long? = null,
    val durationMs: Long? = null,
)

@Serializable data class PlayerCmdPayload(
    val action: Action, val positionMs: Long? = null, val deltaMs: Long? = null,
) { enum class Action { PAUSE, RESUME, PLAY_PAUSE, SEEK_TO, SEEK_BY, STOP,
                        SET_SPEED, VOLUME_UP, VOLUME_DOWN, MUTE } }

@Serializable data class OpenPagePayload(val apiName: String, val url: String)

@Serializable data class ExtensionSyncPayload(          // §8
    val repos: List<RepositoryData>,
    val plugins: List<SyncedPlugin>,
)
@Serializable data class SyncedPlugin(
    val internalName: String, val url: String, val repositoryUrl: String,
    val version: Int, val fileHash: String?, val displayName: String,
)
@Serializable data class ExtensionSyncReply(val results: List<PluginSyncResult>)
@Serializable data class PluginSyncResult(
    val internalName: String,
    val status: Status,          // OK_INSTALLED, OK_ALREADY, UPDATED, REMOVED,
                                 // NEWER_KEPT, DOWNLOAD_ONLY_SAFE_MODE, FAILED
    val message: String? = null,
)

@Serializable data class LibrarySyncPayload(            // §9
    val full: Boolean,
    val entries: List<LibraryEntry>,
)
@Serializable data class LibraryEntry(
    val key: String,             // unprefixed DataStore key, §9.2
    val valueJson: String?,      // null = tombstone (deleted)
    val updatedAtMs: Long,
)

// TV -> phone, on the event channel
@Serializable data class RemoteEvent(
    val kind: Kind,
    val nowPlaying: NowPlayingPayload? = null,
    val libraryDelta: LibrarySyncPayload? = null,
    val pluginSync: PluginSyncResult? = null,
) { enum class Kind { PLAYBACK_STATE, PLAYER_GONE, LIBRARY_DELTA,
                      PLUGIN_SYNC_STATUS, PAIRING_STARTED } }

@Serializable data class NowPlayingPayload(
    val mediaId: Int?, val title: String?, val episodeName: String?,
    val poster: String?, val positionMs: Long, val durationMs: Long,
    val state: State,            // PLAYING, PAUSED, BUFFERING, ENDED, IDLE
    val speed: Float = 1f,
)
```

### 3.3 Authentication & replay protection

- Every envelope except `PING`/`PAIR_*` must carry `deviceId` + `auth`.
- `auth = Base64(HMAC_SHA256(token, "$requestId:$timestampMs"))`.
- Receiver checks: deviceId known → token → HMAC match → `|now − timestampMs| ≤ 5 min`
  → `requestId` not in a per-device LRU (last ~500) → process.
- New file `remote/RemoteAuth.kt` with pure functions `sign(token, requestId, ts)`
  / `verify(...)` — unit-testable.

---

## 4. Pairing (`remote/PairingManager.kt` — new)

**Flow** (phone initiates; TV must be on-screen):

1. Phone discovers TV (NSD, existing `LanRemoteDiscovery`) or manual IP.
2. Phone sends `PAIR_HELLO {deviceId=uuid4(), deviceName}`.
3. TV generates a 6-digit PIN, stores `(sessionId, pin, phoneDeviceId, expiry=120s)`,
   and shows a **full-screen PIN overlay** (`remote/ui/PairingOverlayDialog` shown on
   `CommonActivity.activity`, works over the player too). Replies `PAIR_HELLO` →
   `{pairingSessionId, expiresInMs}`.
4. User types PIN on the phone → `PAIR_VERIFY`.
5. TV verifies (max 5 attempts/session, then invalidate): returns
   `{token = 32 random bytes b64, tv = DeviceInfo}`. TV persists
   `paired_phones: Map<deviceId, PairedPhone{name, token}>`.
6. Phone persists `paired_tvs: Map<deviceId, PairedTv{name, host, port, token, lastSeen}>`
   and marks it the **active** TV.

**Storage**: private `SharedPreferences` (`MODE_PRIVATE`) via
`CloudStreamApp.setKey/getKey` (`CloudStreamApp.kt`) — keys `companion/paired_tvs`,
`companion/paired_phones`. Note in code that Keystore/EncryptedSharedPreferences
is a future hardening option, not v1.

**Unpair**: `UNPAIR` (authenticated) removes the peer on both sides; also expose
"forget" buttons locally on both UIs. Forgetting locally makes the peer's auth
fail → it will show "pairing required".

**Pairing while TV app is in background**: the server runs in
`LanRemoteService`; if no activity exists, `PAIR_HELLO` launches the app first
(existing `launchApp()` helper in `LanRemoteServer`) and posts the PIN dialog on
the main handler.

---

## 5. Server & client rework

### 5.1 `LanRemoteServer.kt` (rewrite internals, keep public API `start/stop`)

- Accept loop → launch one coroutine per socket (`Dispatchers.IO`).
- Frame read → `RemoteEnvelope` → gate:
  - `PING` → reply `DeviceInfo` (paired = whether requester is known).
  - `PAIR_HELLO`/`PAIR_VERIFY` → `PairingManager`.
  - else → `RemoteAuth.verify` or reject `"unauthenticated"`.
- Route to handlers — new `remote/server/CommandHandlers.kt`, one function per
  message type, each taking `(Context, envelope, payload) -> RemoteReply`.
- `SUBSCRIBE` connections are **not** closed after reply: register the socket in
  `NowPlayingHub` (§6.3) and stream `RemoteEvent` frames until disconnect.
- Keep the existing cold-start `PLAY` retry logic, but generalize: a
  `PendingCommandQueue` (last command, 30 s expiry) that also covers `OPEN_PAGE`.

### 5.2 `LanRemoteClient.kt` (upgrade)

- `send(tv, type, payload)` — builds the signed envelope, one-shot socket.
- `selectedEndpoint` → replaced by `PairingManager.getActiveTv()`. Keep the old
  prefs key migration: if legacy `lan_remote/selected_host` exists with no
  paired TVs, surface "re-pair required" in UI (old pairing had no token).

### 5.3 `CompanionSessionManager.kt` (phone, new, singleton)

- Holds the **event channel** to the active TV: connect → `SUBSCRIBE` → read
  `RemoteEvent`s → expose
  `val nowPlaying: StateFlow<NowPlayingPayload?>`, `val tvOnline: StateFlow<Boolean>`.
- Reconnect with backoff (1 s → 30 s cap) while app is in foreground; stop when
  backgrounded (rely on `GET_STATE` when returning).
- Started from `MainActivity.onCreate` (phone role) and stopped `onDestroy`.
- All one-shot sends go through this manager so UI never touches sockets.

### 5.4 Discovery

Keep NSD. Add two robustness items:
- Persist `lastSeen` host/port per paired TV; on `HELLO` failure, re-run
  discovery and update the stored endpoint if the same `deviceId` answers at a
  new address (deviceId is the stable identity, **not** the IP).
- Keep manual `host:port` entry (already in `RemoteControlActivity`) — reuse in
  the new pairing UI.

---

## 6. Play on TV — the native path

### 6.1 Primary Play is a core app action

- `EpisodeAdapter.getPlayerAction()` always emits the primary Play action; it no
  longer stores a remote-player action in the generic player preference.
- `PlaybackCoordinator.primaryTarget()` chooses `LOCAL_TV` for a TV device,
  `REMOTE_TV` for a paired phone, and `PAIR_TV` when a phone has no active TV.
- `PAIR_TV` opens companion settings. It must never silently start movie playback
  on the phone.
- The explicit `ACTION_PLAY_EPISODE_LOCALLY` path and external-player actions are
  intentionally separate from primary Play.

### 6.2 `PlaybackCoordinator` remote-play implementation

1. Build links as today, **but** keep provider identity: extend
   `CloudStreamPackage.MinimalVideoLink` with `val source: String? = null`
   (default keeps JSON back-compat) and set it from `ExtractorLink.source`.
   This lets the TV player resolve `getVideoInterceptor()` (§7.2).
2. **Cookie/header enrichment (phone-side, the core of "cookies stay on the
   phone")**: for each link where `headers["Cookie"]` is absent, merge
   `CookieManager.getInstance().getCookie(link.url)` (WebView login sessions,
   Cloudflare `cf_clearance`) into the headers before sending. Referer merge
   already exists in `MinimalVideoLink.fromExtractor`. Result: the TV needs zero
   login state; every credential required for playback travels as explicit
   per-request headers on the resolved stream URLs, which `CS3IPlayer` already
   applies to playlist/segment requests (`CS3IPlayer.kt:783-830`).
3. Send `PlayPayload` (incl. `poster`, `title`, `mediaId`, resume position via
   existing `getViewPos(video.id)`).
4. Errors: surface TV rejections (`RemoteReply.error`) and keep the primary Play
   path TV-only. `"unauthenticated"` opens the repair flow; unreachable TVs do
   not fall back to the phone player.

### 6.3 TV-side playback & reporting (`remote/server/PlaybackReporter.kt` — new)

- `LanRemoteServer` PLAY handler: existing `OfflinePlaybackHelper.playIntent`
  path; additionally stash `PlayPayload` in `NowPlayingHub` so state events
  carry title/poster even before the player emits progress.
- **Player hook**: in `GeneratorPlayer` (see `GeneratorPlayer.kt:1747` where it
  already calls `DataStoreHelper.setViewPosAndResume(...)` on progress saves)
  register/unregister the active player into `NowPlayingHub`
  (`registerPlayer(IPlayer)` on start, `unregister` on destroy —
  `IPlayer`/`CS3IPlayer` expose seek/pause/resume used by `PLAYER_CMD`).
- `NowPlayingHub` (TV, singleton): throttles `PLAYBACK_STATE` events (every
  10 s while playing, immediately on pause/seek/buffering/end) to all
  `SUBSCRIBE`d phones. When the player is dismissed → `PLAYER_GONE`.
- `PLAYER_CMD` handler routes to the registered player (or `AudioManager` for
  volume — reuse the existing volume branch in `dispatchKey`).

### 6.4 Progress report-back (TV → phone)

Progress written on the TV must land in the phone's DataStore. Implement as a
**library delta event**, not a bespoke message: the TV's own
`DataStoreHelper.setViewPosAndResume` write is captured by the library-sync
hook (§9.3) and pushed as `LIBRARY_DELTA` with the exact entries
(`VIDEO_POS_DUR/<id>`, `RESULT_RESUME_WATCHING/...`). The phone applies them
through the same LWW merge path as any library delta (§9). This gives "resume
on phone where TV stopped" for free and avoids a second mechanism.

### 6.5 Phone Now-Playing UI

- **Mini controller bar** in `MainActivity` (above the bottom nav, phone layout
  only — mirror how `MiniControllerFragment` is embedded for cast): poster,
  title/episode, play/pause, stop; tap → opens
  `remote/ui/CompanionNowPlayingFragment` (bottom sheet) with seek bar
  (driven by `nowPlaying` StateFlow, sends `PLAYER_CMD SEEK_TO` on scrub),
  volume +/-, episode info, "Open on TV" (`OPEN_PAGE`) and "Disconnect".
- **Notification** (new channel `companion_now_playing`, importance LOW, ongoing
  while playing): actions Pause/Resume, ±10 s, Stop → intents into a small
  `CompanionControlReceiver` that calls `CompanionSessionManager`. This gives
  lock-screen control without a MediaSession in v1 (note MediaSession as v2
  polish so headphones/lock screen get native transport controls).

### 6.6 Semantic navigation (`OPEN_PAGE`)

TV handler resolves `{apiName, url}` → finds the provider in `APIHolder.apis`
→ navigates with the existing helpers `AppContextUtils.loadResult(...)`
(`utils/AppContextUtils.kt:710`) / `loadSearchResult(...)` (`:744`) on
`CommonActivity.activity`. This is how the phone says "show this title's page
on the TV" natively — zero key injection. Phone UI: a "Open on TV" action on
the result page overflow menu. If the provider is missing on the TV (extension
sync disabled), reply with a clear error → phone offers "Sync extensions now".

### 6.7 Keep the classic remote

`RemoteControlActivity` stays (dpad/text/launch) but moves behind
Companion settings → "Classic remote". It must send v2 signed envelopes
(update its `send()` to go through `CompanionSessionManager`).

---

## 7. Stream handoff details (phone → TV)

### 7.1 DTO changes (`actions/temp/CloudStreamPackage.kt`)

```kotlin
@Serializable data class MinimalVideoLink(
    ...existing fields...,
    @JsonProperty("source") @SerialName("source") val source: String? = null,
)
```

`fromExtractor` sets `source = link.source`. `toExtractorLink()` passes it into
`newExtractorLink(source = link.source ?: "NONE", ...)`.

### 7.2 Interceptor-aware playback on TV

In `CS3IPlayer` where the video source is built, the interceptor lookup uses the
link's provider (trace `getVideoInterceptor` callers before editing). Because
`MinimalLinkGenerator`/`toExtractorLink` now preserve `source`, a synced plugin
on the TV (§8) supplies the interceptor. If the provider is absent → play
without interceptor (today's behavior) — log + `NowPlaying` warning flag is
acceptable for v1.

### 7.3 Limitations to document in code/UI

- **DRM** (`DrmExtractorLink`) — filtered out already; TV path reports
  "No TV-compatible links" when a provider only yields DRM. Out of scope.
- **Subtitle URLs** (OpenSubtitles etc.) are time-limited; they are resolved
  fresh at play time on the phone, so this is fine — but note that
  *downloading* subtitles happens on the TV, so subtitle-provider **extensions
  must be synced** for any on-TV subtitle search.
- HLS AES keys/cookies: covered because headers apply to all requests of the
  data-source factory (§6.2).

---

## 8. Extension sync (phone ⇒ TV)

### 8.1 Source of truth & triggers (phone)

The phone's installed set = `PluginManager.getPluginsOnline()`
(`PLUGINS_KEY`; `PluginData{internalName,url,isOnline,filePath,version}`) +
repos = `RepositoryManager.getRepositories()` (`REPOSITORIES_KEY`) +
`PREBUILT_REPOSITORIES`.

Triggers (debounced 5 s, only when a TV is paired & reachable):
1. New hook `PluginManager.onPluginSetChanged` — invoke from `setPluginData`,
   `deletePluginData`, `deleteRepositoryData` (`plugins/PluginManager.kt`) and
   from `RepositoryManager.addRepository/removeRepository`
   (`plugins/RepositoryManager.kt`). Implement as a simple listener list on a
   new `remote/sync/SyncHooks` object to avoid polluting PluginManager with
   remote imports.
2. Successful pairing, and every `HELLO` where the TV advertises a
   `pluginSetHash` ≠ phone's hash (hash = sha256 over sorted
   `internalName@version@repoUrl` list — cheap change detector for `HELLO`).

Payload construction: for each `PluginData`, recover the repo URL from the
file path (`getPluginPath` embeds the sanitized repo folder → store repoUrl
explicitly in the new `SyncedPlugin` manifest instead of reverse-engineering;
simplest: keep a phone-side map `internalName → repositoryUrl` updated at
download time in `PluginManager.downloadPlugin`). Version/hash come from the
repo metadata the phone last saw (persist `fileHash` alongside `PluginData` —
extend `PluginData` with an optional `fileHash` field, defaulting null).

### 8.2 TV apply logic (`remote/sync/ExtensionSyncManager.kt` — new, role-aware)

On `SYNC_EXTENSIONS` (authenticated):

1. **Repos**: add missing via `RepositoryManager.addRepository`. Do **not**
   delete TV-extra repos (they may be user-added on TV); only plugins are
   mirrored.
2. **Plan** (pure function, unit-test it):
   `plan(desired: List<SyncedPlugin>, current: Array<PluginData>, managed: Set<String>) -> List<PlanAction>`
   - desired & (missing | version > current) → `InstallOrUpdate`
   - desired & version < current → `KeepNewer` (report `NEWER_KEPT`; do not
     downgrade — the phone will catch up via its own auto-update)
   - current & managed(filePath) & not desired → `Remove`
   - current & **not** managed → leave alone (TV-local installs)
3. **Execute**:
   - `InstallOrUpdate`: `RepositoryManager.downloadPluginToFile(context, url,
     PluginManager.getPluginPath(context, internalName, repositoryUrl), fileHash)`
     then load. `PluginManager.loadPlugin` is currently **private** → add a
     public wrapper `PluginManager.loadPluginFile(context, file, data)`
     (thin, keeps the private body). Persist via `setPluginData`.
   - `Remove`: `PluginManager.deletePlugin(File(filePath))`.
   - Update the TV-side managed set `SYNCED_PLUGINS_KEY` (list of filePaths)
     accordingly.
   - If `PluginManager.isSafeMode()` → download only, skip load, report
     `DOWNLOAD_ONLY_SAFE_MODE`.
4. **Reply** `ExtensionSyncReply` + stream per-plugin `PLUGIN_SYNC_STATUS`
   events so the phone can show live progress.
5. After the batch, fire `MainActivity.afterPluginsLoadedEvent` so TV UI
   refreshes providers (same pattern as `___DO_NOT_CALL_FROM_A_PLUGIN_loadAllOnlinePlugins`).

### 8.3 Interaction with TV auto-update

TV keeps its normal `updateAllOnlinePluginsAndLoadThem` cycle. Divergence is
healed by the `NEWER_KEPT` rule + next phone sync. Document this explicitly in
the sync manager KDoc.

### 8.4 Fallback file transfer (repo unreachable from TV)

If `downloadPluginToFile` fails (e.g. repo blocked), the TV replies
`FAILED("download")` for that plugin; the phone then **pushes the bytes**:

```
EXT_FILE_START {internalName, repositoryUrl, sizeBytes, sha256}
EXT_FILE_CHUNK {seq, dataB64}        // ≤ 384 KB raw per chunk
EXT_FILE_END   {internalName}
```

TV writes `cacheDir`-temp, verifies sha256, moves to `getPluginPath(...)`,
loads, persists `PluginData`. Phone reads its local file from
`PluginData.filePath`. (Chunking keeps the 1 MB frame cap.)

### 8.5 UI

- Phone, Extensions screen (`ui/settings/extensions/`): small "TV ✓ / TV ↻ /
  TV ✕" badge per plugin from the last `ExtensionSyncReply` (store in memory in
  `CompanionSessionManager`; no DB). A "Sync now" menu action.
- Phone, Companion settings: toggles **Sync extensions** (default ON),
  **Auto-sync on change** (default ON), per-TV last-sync timestamp + result.
- TV, Settings → Companion: "Managed by {phone name}", last sync summary,
  "Unpair".

---

## 9. Library sync (bidirectional, phone-authoritative lists)

### 9.1 Scope (DataStore keys, see `utils/DataStoreHelper.kt`)

Synced entry groups (per-account keys — see §9.2):

| DataStore key | Content |
|---|---|
| `RESULT_WATCH_STATE_DATA` | `BookmarkedData` (library "watching/completed/…" lists) |
| `RESULT_FAVORITES_STATE_DATA` | `FavoritesData` |
| `RESULT_SUBSCRIBED_STATE_DATA` | `SubscribedData` |
| `RESULT_WATCH_STATE` | per-result watch type int |
| `RESULT_RESUME_WATCHING` | "Continue watching" entries |
| `VIDEO_POS_DUR` | per-episode position/duration (`PosDur`) |
| `VIDEO_WATCH_STATE` | per-episode watched state |
| `RESULT_EPISODE` / `RESULT_SEASON` / `RESULT_DUB` | per-result last selections |

### 9.2 Account-prefix normalization

Keys are stored as `"$currentAccount/<KEY>/..."` where `currentAccount =
selectedKeyIndex.toString()` (`DataStoreHelper.kt:181`). Account indices differ
across devices, so the wire format uses **unprefixed** keys; each side
re-prefixes with its *own* current account on apply. v1 syncs **the current
account only** — log & document this; multi-account merge is future work.

### 9.3 Change capture & timestamps (both devices)

- Add to `DataStoreHelper`: a private `markSyncedWrite(unprefixedKey)` called
  from the setters in §9.1 (`setBookmarkedData`, `setFavoritesData`,
  `setSubscribedData`, `setViewPosAndResume`, `setVideoWatchState`,
  `setResultWatchState`, `setResultEpisode`, `setResultSeason`, `setDub`) that
  (a) records `updatedAtMs` in a `LIBRARY_SYNC_TS` map key, and (b) emits on a
  new `libraryChangedEvent: Event<String>` (reuse the `Event<T>` pattern).
- `remote/sync/LibrarySyncManager.kt` (both roles):
  - **On connect/pair**: full exchange — dump all synced keys (read via
    existing `getAllBookmarkedData()`, `getAllFavorites()`,
    `getAllSubscriptions()`, `getAllResumeStateIds()` + `getViewPos` etc.) as
    `LibrarySyncPayload(full=true)`, apply peer's with **last-write-wins per
    key** using `updatedAtMs`.
  - **On change**: debounce 2 s → push delta (`full=false`). TV progress
    report-back (§6.4) is exactly this path.
  - Applying remote entries must **not** re-trigger a push (guard with an
    `isApplyingRemote` flag).
- Tombstones: when a setter removes an entry, push `valueJson=null`.

### 9.4 Result

- Library page (`ui/library/`) on both devices shows the same lists (phone is
  effectively authoritative for list membership because most edits happen
  there; LWW keeps the TV's local toggles).
- "Continue watching" and per-episode progress are shared both ways —
  pick the episode on the phone, watch on the TV, resume on the phone.

---

## 10. Settings & UX surface

**Phone — Settings → "TV Companion"** (new fragment
`remote/ui/CompanionSettingsFragment.kt`, entry replaces the current row at
`SettingsFragment.kt:252`):
- Active TV card (name, IP, online?, buttons: Sync now, Classic remote, Unpair).
- "Add TV" → discovery list (reuse `LanRemoteDiscovery`) + manual IP → PIN flow.
- Toggles: Sync extensions · Sync library · Show now-playing notification. The
  primary Play target is automatic from the device role and active pairing.
- Per-plugin sync status link-through to Extensions screen.

**TV — Settings → Companion**: receiver on/off (also gates `LanRemoteService` —
change `MainActivity.kt:1990` and `LanRemoteBootReceiver` to check the setting,
not just the UI mode), paired phones list, last sync, "Allow pairing requests"
(default ON; when OFF, `PAIR_HELLO` is rejected).

**TV overlays**: PIN pairing dialog; transient "Playing from {phone}" toast
(reuse `CommonActivity.showToast`); optional setting "Ask before playing from
companion" (default OFF).

---

## 11. File-by-file work list

### New files

```
app/src/main/java/com/lagradost/cloudstream3/remote/
├── RemoteMessages.kt                 # all v2 DTOs (§3.2)
├── RemoteAuth.kt                     # HMAC sign/verify (pure, tested)
├── PairingManager.kt                 # both roles; token & device stores
├── CompanionSessionManager.kt        # phone: event channel + send API + StateFlows
├── server/CommandHandlers.kt         # per-type handlers (§5.1)
├── server/NowPlayingHub.kt           # TV: player registry + event broadcast
├── server/PlaybackReporter.kt        # TV: GeneratorPlayer hooks → events
├── sync/SyncHooks.kt                 # listener registries used by PluginManager/DataStoreHelper
├── sync/ExtensionSyncManager.kt      # push planner (phone) + apply executor (TV)
├── sync/LibrarySyncManager.kt        # capture, LWW merge, full/delta sync
└── ui/
    ├── CompanionSettingsFragment.kt  # phone companion settings
    ├── CompanionNowPlayingFragment.kt# phone bottom-sheet controller
    ├── CompanionNotificationManager.kt
    ├── CompanionControlReceiver.kt   # notification actions
    └── PairingOverlayDialog.kt       # TV PIN dialog
app/src/main/res/layout/…             # companion_settings.xml, now_playing_sheet.xml,
                                      # pairing_overlay.xml, item_paired_device.xml
```

### Modified files (with the exact touch points)

| File | Change |
|---|---|
| `remote/LanRemoteProtocol.kt` | v2 envelope/framing (§3.1); keep v1 PING compat reply. |
| `remote/LanRemoteServer.kt` | per-client coroutines, auth gate, SUBSCRIBE channels, pending-command queue (§5.1). |
| `remote/LanRemoteClient.kt` | signed envelopes; delegate endpoint selection to `PairingManager` (§5.2). |
| `ui/player/PlaybackCoordinator.kt` | Native primary Play target selection and phone-to-TV payload construction (§6.1–6.2). |
| `remote/RemoteControlActivity.kt` | route sends via `CompanionSessionManager`; keep as classic remote (§6.7). |
| `remote/LanRemoteService.kt` | unchanged logic; start/stop now gated by receiver setting. |
| `remote/LanRemoteBootReceiver.kt` | check receiver setting instead of only TV UI mode. |
| `actions/temp/CloudStreamPackage.kt` | `MinimalVideoLink.source` (+ plumb through `toExtractorLink`) (§7.1). |
| `ui/player/OfflinePlaybackHelper.kt` | accept poster/title extras into player metadata (small). |
| `ui/player/GeneratorPlayer.kt` | register/unregister player with `NowPlayingHub`; progress-save hook already at `:1747` (§6.3). |
| `plugins/PluginManager.kt` | `SyncHooks` notifications from `setPluginData`/`deletePluginData`/`deleteRepositoryData`; new public `loadPluginFile` wrapper; persist `fileHash` on `PluginData` (§8.1–8.2). |
| `plugins/RepositoryManager.kt` | `SyncHooks` notifications from `addRepository`/`removeRepository`. |
| `utils/DataStoreHelper.kt` | `markSyncedWrite` + `libraryChangedEvent` in the §9.1 setters (§9.3). |
| `MainActivity.kt` | start/stop `LanRemoteService` by setting (`:1990`); start `CompanionSessionManager` (phone); expose now-playing bar container. |
| `ui/settings/SettingsFragment.kt` | replace remote row (`:252`) with Companion section. |
| `ui/settings/extensions/ExtensionsViewModel.kt` + adapters | per-plugin TV sync badge (reads `CompanionSessionManager`) (§8.5). |
| `AndroidManifest.xml` | register new activity/receiver; service/receiver entries already exist. |
| `res/values/strings.xml` (+ locales fallback) | all new UI strings. |

---

## 12. Edge cases & failure modes

- **TV asleep/off**: `send()` connect timeout (2 s) → mark offline; Play press
  falls back to local player dialog. Wake-on-LAN is out of scope.
- **IP changed**: deviceId-based re-resolution via NSD (§5.4).
- **Two phones paired**: both may control; `NowPlayingHub` broadcasts to all
  subscribers; last command wins (document; no locking in v1).
- **Phone switches active TV**: `CompanionSessionManager` tears down event
  channel, re-`HELLO`s the new one.
- **Sync during playback**: plugin unload on the TV while its provider is
  playing → guard: skip `Remove`/`InstallOrUpdate` for a plugin whose
  `sourcePlugin` matches the currently playing link's source; defer and retry
  after `PLAYER_GONE`.
- **Replay**: timestamp window + requestId LRU (§3.3).
- **PIN brute force**: 5 attempts/session, 120 s expiry, new session per
  `PAIR_HELLO`, TV overlay shows attempts.
- **Safe mode** (`PluginManager.isSafeMode()`): download-only (§8.2).
- **Clock skew**: HMAC window ±5 min; if devices drift beyond that, reply
  `"clock-skew"` and surface in UI.
- **App updated on one device only**: `capabilities` in `DeviceInfo` gate
  features (phone hides sync toggles if TV lacks `ext-sync`).

---

## 12.1. QC checkpoint (compaction-safe)

Keep this checkpoint with the implementation so a context compaction cannot lose the
reproduction contract:

- Target TV: `192.168.0.8:5555`, package `com.lagradost.cloudstream3.debug`, role Android TV.
- Fixture: repository shortcode `864` → Phisher Repo; extension `UHDmovies` v37; `Batman:
  Caped Crusader`, Season 1, Episode 5 (`The Stress of Her Regard`).
- Product invariant: TV browsing and phone browsing are both supported; the primary Play
  action always targets the TV. Phone playback is never the fallback. Explicit local play is
  only a diagnostic/developer path.
- Baseline captured on 2026-08-02: TV fixture and episode are present; TV-local play reaches
  the player but reports `No Links Found`. Phone/TV pairing succeeded; the phone emulator
  became unavailable during the long remote repro, so an unsupported-URL rejection still
  requires a live phone→TV replay before declaring it fixed.
- Before every code change: record app versions, pairing state, extension/provider version,
  exact episode, TV screenshot, and filtered phone/TV logcat around the PLAY request. Preserve
  the first failing URL and its link type/source; do not replace it with a paraphrase.
- Exit criteria: cold-TV and warm-TV primary Play both start on the TV; no phone player opens;
  only absolute HTTP(S) TV-compatible links are transported; invalid/relative links produce a
  clear no-TV-links result; resume, subtitles, and player commands still work.
- Compaction handoff fields: current branch/commit, changed files, last test command/result,
  device state, exact next action, and any retained `adb` artifact/log path.

## 13. Testing plan

**Unit (JVM, `app/src/test/.../remote/`)** — extend alongside
`LanRemoteProtocolTest.kt`:
- Envelope round-trip for every message type; unknown-field tolerance;
  chunk encode/decode; 1 MB cap enforcement.
- `RemoteAuth`: sign/verify, wrong token, timestamp window, replay LRU.
- `ExtensionSyncManager.plan(...)`: install/update/remove/keep-newer/unmanaged
  matrix as a pure function.
- `LibrarySyncManager` merge: LWW per key, tombstones, account re-prefixing,
  no re-emit while applying.

**Robolectric**:
- `PairingManager` full flow (hello→pin→verify→token persist; attempt limit).
- `CompanionSessionManager` reconnect backoff with a fake socket factory.

**Manual matrix** (phone + TV on same LAN):
1. Pair (NSD + manual IP), unpair, re-pair.
2. Play movie/episode on TV from phone: cold TV app, warm TV app, TV already
   playing something else.
3. Pause/seek/stop from phone; verify progress appears on phone (kill TV app
   mid-play → last position synced).
4. Install/update/remove extension on phone → TV mirrors (incl. repo-blocked
   fallback via airplane-mode + local HTTP server).
5. Bookmark on phone → appears on TV; finish episode on TV → phone continue-
   watching updates.
6. Old (v1) client → clear "upgrade required" error.

---

## 14. Implementation milestones (each independently shippable)

| Milestone | Content | Exit criteria |
|---|---|---|
| **M1 — Secure transport** | §3 protocol v2, §4 pairing, §5.1–5.2 server/client rework, settings-gated service, tests | Paired phone can PING/KEY/PLAY; unpaired rejected; v1 client gets upgrade error |
| **M2 — Native play & feedback** | §6.1 default-player wiring, §6.2 link enrichment, §6.3 now-playing hub + `PLAYER_CMD`, §6.5 phone UI + notification, §6.4 progress report-back via minimal library delta (only `VIDEO_POS_DUR` + `RESULT_RESUME_WATCHING`) | One-press play on TV; live phone mini-controller; resume position shared |
| **M3 — Extension sync** | §8 planner/executor, hooks, fallback transfer, badges, TV status | Install/remove/update on phone mirrors to TV with visible status |
| **M4 — Library sync & navigation** | §9 full LWW sync, §6.6 `OPEN_PAGE`, companion settings polish | Libraries identical on both; "Open on TV" works |
| **M5 — Hardening & docs** | edge cases §12, capability gating, strings/i18n, README/user docs | Test matrix §13 green |

Suggested PR split = milestones. M2 depends only on M1; M3/M4 are parallel-able.

---

## 15. Open questions for the product owner

1. **TV standalone usage**: confirmed assumption — TV keeps its own UI and the
   synced extensions so it works without the phone. (If you want a *thin* TV
   that never browses, extension sync becomes optional and M3 shrinks.) - we want the tv to keep its own ui as well in case phone is not available
2. **Multiple accounts**: v1 syncs current account only — acceptable? yes
3. **Multiple TVs**: phone stores many, one *active* at a time — OK, or do you
   want per-room simultaneous control? - no, one tv at a time
4. **DRM streams**: accepted as unsupported on the TV path (phone falls back to
   local playback)? - no need, we can just not have them
5. **Encryption**: HMAC-auth-only on LAN (v1) vs. TLS with per-pairing
   self-signed certs (v2 hardening) — is auth-only acceptable on your home LAN? yes
6. **Watch-progress conflicts** (both devices watched the same episode
   offline): last-write-wins per key — acceptable? - yes
