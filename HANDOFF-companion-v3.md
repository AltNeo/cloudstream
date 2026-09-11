# Companion v3 — Implementation Handoff

> **Audience**: an implementing agent/engineer starting fresh in this repository with no prior
> conversation context. This document is the single source of truth. Where this document and
> old branch code disagree, this document wins.

---

## 0. Repository facts

- Repo: fork of `recloudstream/cloudstream` at `E:\cloudstream`.
  - `origin` = https://github.com/AltNeo/cloudstream.git
  - `upstream` = https://github.com/recloudstream/cloudstream.git
- Local `master` is **byte-identical** to `upstream/master` (verified `rev-list --left-right --count master...upstream/master` → `0 0`). Use it as the clean baseline.
- Branch `feat/lan-remote` contains the **previous (v1/v2) implementation**. It is REFERENCE
  ONLY. Do **not** merge it, do **not** branch from it. Salvage rules are in §8.
- `minSdk = 23` (`gradle/libs.versions.toml:61`). Kotlin + kotlinx-serialization already set up
  (`app/build.gradle.kts:11,235`). OkHttp already used. There is **no** bundled HTTP server lib.
- Build flavors exist: unit test task names are `testPrereleaseDebugUnitTest` /
  `testStableDebugUnitTest` (bare `testDebugUnitTest` is ambiguous and fails).
- Do NOT commit `.pi-audit.md`, `plan.md`, `plan-companion-v2.md`, or this file's scratch
  derivatives to the new branch. This handoff doc itself may be committed if desired.

### Branch setup (first action)

```
git checkout master
git checkout -b feat/companion-v3
```

All work happens on `feat/companion-v3`.

---

## 1. Product goals (what we are building)

CloudStream runs on both phones and Android TVs (same APK, layout switched by
`isLayout(PHONE/TV/EMULATOR)`). We are building a **first-class, native phone⇄TV companion**:

- **G1 — TV-owned connect experience.** The TV presents a native "Connect a phone" feature:
  a full-screen pairing screen showing a PIN, a list of paired phones, and
  revocation controls. The phone never runs a server; it only discovers and dials out.
  Technically the TV listens on the LAN, but it must never be presented as a "server" —
  no "LAN server" toggles, no port numbers in primary UX (advanced section only).
- **G2 — Phone resolves, TV plays.** The user's TV runs an old Android version whose WebView
  cannot pass modern Cloudflare/captcha challenges. Therefore: the phone performs all link
  extraction (including WebView-based challenge solving) and sends **only resolved,
  probe-validated streaming URLs + required headers** to the TV. The TV plays them with its
  existing ExoPlayer pipeline.
- **G3 — Native look & feel.** Every UI surface follows existing app conventions (settings
  navigation graph, strings in `strings.xml`, dimensions in dp/resources, existing themes and
  focus handling for d-pad). No hard-coded pixel values or literal user-facing strings in
  Kotlin.
- **G4 — Secure by design.** Encrypted, mutually-authenticated channel from the first release.
  No cleartext bearer tokens, no unauthenticated command frames. See §5.
- **G5 — Remote control & second screen.** Phone controls TV playback (play/pause/seek/next),
  shows live now-playing state, and provides a keyboard for TV search.
- **G6 — Zero impact when unused.** If the feature is disabled or no phone is paired, the TV
  app behaves exactly like upstream: no listener, no threads, no UI additions beyond the
  settings entry.

### Non-goals (explicitly out of scope)

- NO cloud relay, no Google Play Services dependency, no new build flavors. LAN only.
- NO DRM link handoff. `DrmExtractorLink` is never sent to the TV (license keys must not
  cross the wire). If the chosen source is DRM-only, play locally on the phone and show a
  string-resource message explaining why.
- NO background clipboard watching (privacy). A share-target flow covers the use case (§7, F4).
- NO raw SharedPreferences sync between devices.
- NO extension/plugin file transfer in this phase (may return later on top of the secure
  channel).

---

## 2. High-level architecture

New package: `com.lagradost.cloudstream3.companion` in `app/src/main/java/...`, organized so
transport and security are isolated and unit-testable without Android:

```
companion/
  protocol/    # wire DTOs, framing, versioning — pure Kotlin, no Android imports
  crypto/      # key agreement, AEAD framing, key storage interface — JVM-testable
  transport/   # TCP client/server sockets, NSD discovery/registration (Android)
  tv/          # TV endpoint: session acceptor, command handlers, NowPlayingHub, pairing UI
  phone/       # phone: session manager, device picker, link resolution pipeline, remote UI
```

Rules:
- `protocol/` and the pure logic in `crypto/` must have **zero** Android dependencies so unit
  tests run on the JVM.
- Singletons are allowed only for: `CompanionTv` (TV-side facade) and `CompanionPhone`
  (phone-side facade). Everything else is constructor-injected for testability.
- All coroutine jobs are retained and cancelled with lifecycle generations (see §9 pitfalls
  M1/H4 — the old branch had duplicate-loop and start/stop race bugs; do not reintroduce them).

---

## 3. Wire protocol specification

### 3.1 Framing

- Transport: TCP. Every frame: `int32 big-endian length` + `payload bytes`.
- Max frame size: 1 MiB. Oversized frames ⇒ close connection.
- After the handshake (§5), every payload is an AES-256-GCM ciphertext (§5.4). During the
  handshake, payloads are plaintext JSON handshake messages only — no commands are accepted
  before the channel is established.
- JSON via kotlinx-serialization, `ignoreUnknownKeys = true` on both peers (forward compat).

### 3.2 Envelope (inside the encrypted channel)

```kotlin
@Serializable
data class Envelope(
    val v: Int,                // protocol version, start at 3
    val id: String,            // random UUID per request; echoed in replies
    val type: MessageType,
    val payload: JsonElement?, // type-specific payload, null when none
)
```

No HMAC field: authenticity comes from the AEAD channel (§5.4). Replay protection comes from
the per-direction GCM sequence counter (§5.4). Do **not** add a parallel token/HMAC scheme.

### 3.3 Message types

Phone → TV requests (TV replies with `RESULT`):
- `PING` — liveness/latency probe. Payload: none.
- `PLAY` — payload `PlayRequest` (§3.4). TV starts playback, subject to the lineage/attempt
  acceptance rules in §4.4.
- `PLAYER_CMD` — payload `PlayerCommand`: `action` enum `PLAY, PAUSE, TOGGLE, SEEK_TO,
  SEEK_REL, NEXT, PREV, STOP, SET_VOLUME` + optional `positionMs: Long`, `volume: Float`.
  `NEXT`/`PREV` during a remote-initiated session are NOT resolved on the TV — the TV
  emits `NAV_REQUESTED` back to the controlling phone, which owns the episode queue (§4.5).
- `KEY` — payload `{ keyCode: Int }`, Android `KeyEvent` keycode injected on TV (d-pad
  navigation remote).
- `INPUT_TEXT` — payload `{ text: String }`; sets the focused TV text field's content
  directly (NOT per-character key injection).
- `OPEN_PAGE` — payload `{ apiName: String, url: String }`; TV opens the result page.
- `SUBSCRIBE` — upgrades this connection to an event channel; after `RESULT(ok)` the TV
  pushes `EVENT` frames on it until close.
- `UNPAIR` — removes this phone's pairing on the TV. TV must immediately close all of this
  device's connections including event channels (§9 pitfall H6).

TV → phone (on event channels):
- `EVENT` — payload `Event` with `kind` enum:
  - `PLAYBACK_STATE`: `{ lineageId?, title, episodeLabel?, posterUrl?, positionMs,
    durationMs, state (PLAYING/PAUSED/BUFFERING/ENDED/IDLE), mediaId? }` — `lineageId` is
    set for remote-initiated sessions so the phone can match or cancel recovery (§4.4).
  - `INPUT_CONTEXT`: `{ context: SEARCH_FIELD | IDLE, currentText? }` — fired when the TV
    search field gains/loses focus (§7 F2).
  - `LINK_FAILED`: `{ lineageId: String, attempt: Int, linkIndex: Int, httpStatus: Int?,
    stage: MANIFEST | SEGMENT | AUTH | UNKNOWN }` — triggers phone re-resolve (§4.4).
  - `NAV_REQUESTED`: `{ lineageId: String, direction: NEXT | PREV }` — episode navigation
    request for a remote-initiated session; the phone resolves it (§4.5).

Replies:
- `RESULT` — payload `{ ok: Boolean, error: ErrorCode?, message: String? }`. `ErrorCode`
  enum: `NOT_AUTHORIZED, INVALID_PAYLOAD, NO_ACTIVE_PLAYER, UNSUPPORTED, INTERNAL,
  CONTROL_DISABLED, RATE_LIMITED, STALE_REQUEST`.

### 3.4 PlayRequest payload (the critical DTO)

```kotlin
@Serializable
data class PlayRequest(
    val lineageId: String,           // stable UUID per user-initiated play session (§4.4)
    val attempt: Int,                // 0 on initial send; +1 per automatic re-resolve (§4.4)
    val links: List<ResolvedLink>,   // ordered by preference, non-empty
    val subtitles: List<ResolvedSubtitle>,
    val title: String,
    val episodeLabel: String?,       // e.g. "S1E5"
    val posterUrl: String?,
    val mediaId: Int?,               // DataStoreHelper id for resume-position writes
    val startPositionMs: Long?,      // resume point
    val durationMs: Long?,
)

@Serializable
data class ResolvedLink(
    val url: String,                 // http/https only — validate before send AND on receive
    val type: LinkType,              // VIDEO (mp4 etc), M3U8, DASH — mirrors ExtractorLinkType
    val quality: Int,                // upstream quality int convention
    val sourceName: String,          // for the TV source dialog
    val referer: String?,            // EXPLICIT field. Do not fold into headers (v1 bug)
    val headers: Map<String, String>,// allowlist only: User-Agent, Cookie, Authorization,
                                     // Origin, Accept, Accept-Language, x-* custom
    val audioTracks: List<ResolvedAudioTrack>, // mirrors upstream AudioFile; v1 dropped these
    val playlist: List<PlaylistPart>?,// non-null ⇒ this link is an ORDERED CONCATENATION of
                                     // parts (upstream ExtractorLinkPlayList / 
                                     // ConcatenatingMediaSource); `url` is ignored for
                                     // playback in that case. Parts are NOT alternatives.
    val issuedAtMs: Long,
    val expiresAtMs: Long?,          // null = unknown; TV treats stale links per §4.4
)

@Serializable
data class ResolvedAudioTrack(       // mirrors upstream AudioFile (MainAPI.kt:1247-1250)
    val url: String,
    val headers: Map<String, String>,
)

@Serializable
data class PlaylistPart(             // mirrors upstream PlayListItem (ExtractorApi.kt:342-345)
    val url: String,
    val durationUs: Long,            // preserve exactly; player needs it for concatenation
)

@Serializable
data class ResolvedSubtitle(
    val url: String,
    val lang: String,                // language name/code as in SubtitleFile
    val mimeType: String?,
    val headers: Map<String, String>,
)
```

Mapping to upstream types: `ExtractorLink` is defined at
`library/src/commonMain/kotlin/com/lagradost/cloudstream3/utils/ExtractorApi.kt:690-723`
(fields: source, name, url, referer, quality, headers, extractorData, type,
audioTracks: List<AudioFile>); `ExtractorLinkType`→mime at `:404-425`; `AudioFile(url,
headers?)` at `library/.../MainAPI.kt:1247-1250`; `ExtractorLinkPlayList(playlist:
List<PlayListItem>)` at `ExtractorApi.kt:359-400` with `PlayListItem(url, durationUs)` at
`:342-345`; `SubtitleFile` at `MainAPI.kt:1205-1223`. `ResolvedLink` ⇄
`ExtractorLink`/`ExtractorLinkPlayList` converters live in `protocol/` with round-trip unit
tests — referer, audioTracks (url + headers), and playlist part order + durations must all
survive the round trip.

---

## 4. Phone-side link resolution pipeline (G2)

This is the heart of the feature. Location: `companion/phone/LinkResolutionPipeline.kt`.

### 4.1 Inputs

The normal playback flow already produces `ExtractorLink`s + `SubtitleFile`s via
`loadLinks` (provider contract `library/.../MainAPI.kt:692-699`, wrapped by
`APIRepository.kt:205-223`). WebView-based challenge solving is upstream's
`WebViewResolver` (`library/.../network/WebViewResolver.kt`, Android impl
`WebViewResolver.android.kt:36-244`) and `CloudflareKiller.kt:24-133` — these run ON THE
PHONE only. Never attempt WebView resolution on the TV device.

### 4.2 Pipeline steps (per selected source)

1. **Filter**: drop `DrmExtractorLink` and non-http(s) URLs. Map `ExtractorLinkPlayList` to
   ONE `ResolvedLink` with `playlist` set — parts are an ordered concatenation with
   durations, NOT alternative sources; preserve order and `durationUs` exactly so the TV
   can rebuild `ExtractorLinkPlayList` (v1 dropped playlists entirely — don't, and don't
   flatten them into candidates either, or playback stops after one part).
2. **Enrich headers**: merge `link.headers` + explicit referer + WebView User-Agent (from
   `WebViewResolver.webViewUserAgent`) + `CookieManager` cookies for the link URL *and* for
   `extractorData` host when present. Apply the header allowlist from §3.4.
3. **Probe-validate** (MANDATORY — v1's biggest weakness was shipping the first intercepted
   URL blind):
   - `GET` the URL with the assembled headers, follow redirects, using OkHttp on the phone.
   - For M3U8: fetch the master playlist, parse enough to confirm it is a playlist, then
     fetch the first media-playlist/segment URL with the same headers. Both must return 2xx.
   - For DASH: fetch the MPD, confirm XML root `<MPD`, fetch first segment if trivially
     derivable, else accept manifest 2xx.
   - For direct video: issue a ranged `GET` (`Range: bytes=0-1023`) expecting 200/206 with a
     video-ish `Content-Type` or nonzero body.
   - For playlist links: probe the first part fully; ranged `GET` on up to 2 further parts.
     All probed parts must succeed or the candidate is discarded.
   - Record the **final post-redirect URL** as the URL to send.
   - Probe timeout: 10 s per request. A failed probe ⇒ candidate discarded.
   - **Budget**: probe at most 4 candidates, up to 3 in parallel; total assembly budget
     (extraction + probes) is 30 s wall-clock per `PLAY`. On expiry, send the candidates
     already validated; if none validated, fail fast — never block past the budget.
4. **Expiry estimation**: if the URL contains recognizable expiry query params (`expires=`,
   `exp=`, `token` with unix-time-looking values), set `expiresAtMs`; else null.
5. **Assemble** `PlayRequest` with all surviving candidates ordered by (probe success,
   quality), subtitles with their headers, resume position from
   `DataStoreHelper.getViewPos(id)`, and send `PLAY`.

If zero candidates survive probing: do NOT send anything; surface a phone-side error dialog
offering "Play on phone instead" (string resources).

### 4.3 TV-side playback entry

Add `companion/tv/CompanionPlaybackLauncher.kt`:
- Converts `ResolvedLink` list → `ExtractorLink` list (converters from §3.4) and starts
  `GeneratorPlayer` with a `MinimalLinkGenerator`-style generator, following the pattern of
  `OfflinePlaybackHelper.playLink` (`app/.../ui/player/OfflinePlaybackHelper.kt:25`) but as a
  **new first-class API that preserves headers and referer** — do NOT route through
  `DownloadedPlayerActivity`/ACTION_VIEW intents (offline-oriented, lossy).
- Headers/referer reach ExoPlayer via the existing data-source path
  (`CS3IPlayer.kt:779-827` applies `refererMap + link.headers` through
  `setDefaultRequestProperties`) — verify both survive end-to-end with a test stream.
- Applies `startPositionMs`, registers position reporting so `PLAYBACK_STATE` events and
  `DataStoreHelper` resume writes work (`mediaId`).

### 4.4 Failure / re-resolve loop (strict cancellation rules)

Session identity: every user-initiated "play on TV" creates a fresh `lineageId` (UUID) with
`attempt = 0`. Automatic re-resolves reuse the `lineageId` and increment `attempt`.

TV-side rules:
- The TV tracks at most ONE active remote session `(lineageId, attempt)`.
- Accept a `PLAY` iff (a) its `lineageId` differs from the active one (new user intent —
  replaces the session), or (b) same `lineageId` AND `attempt` strictly greater than the
  active one. Otherwise reply `RESULT(ok=false, error=STALE_REQUEST)` and do nothing —
  a delayed retry must never restart something the user stopped or replaced.
- Any local user action that changes playback (back/stop, TV user plays something else)
  ENDS the remote session: clear the lineage and broadcast
  `PLAYBACK_STATE { state: IDLE, lineageId }` so the phone cancels pending recovery.
- Candidate walk: try `links[i]` in order; skip candidates whose `expiresAtMs` is already
  past; per-candidate startup deadline 20 s; classify errors (HTTP status from the data
  source if available; stage MANIFEST vs SEGMENT). When all fail, emit `LINK_FAILED`
  (last failure detail) and show "Asking phone for a fresh link…" — session stays active
  and user-cancellable.
- If the controlling phone disconnects while the TV is waiting for recovery: stop waiting,
  end the session, show a "phone disconnected" message. No TV-side auto-recovery.

Phone-side rules:
- One active lineage at a time; starting a new play cancels (coroutine Job cancellation)
  all in-flight resolve/probe work of prior lineages.
- On `LINK_FAILED(lineageId, attempt)`: ignore unless it matches the active lineage AND the
  attempt the phone last sent. Then re-run §4.2 and send `PLAY(lineageId, attempt + 1)`
  with the TV's last reported position as `startPositionMs`.
- On `PLAYBACK_STATE IDLE` for the active lineage, or user stop from the phone UI: cancel
  all pending resolve/probe work for that lineage immediately.
- Limits: max 2 automatic re-resolves per lineage AND a total recovery budget of 60 s
  wall-clock from the first `LINK_FAILED` (this bounds §4.2's 30 s assembly budget plus
  candidate startup time — do not promise faster). On exhaustion show "Try different
  source" / "Play on phone".

Required deterministic tests (fake clock + fake transport, JVM):
- delayed `PLAY(attempt=1)` arriving after user stop ⇒ rejected `STALE_REQUEST`, no playback;
- duplicate/reordered `PLAY` frames ⇒ only the highest attempt wins, others rejected;
- `LINK_FAILED` for a non-active lineage or stale attempt ⇒ no phone re-resolve;
- recovery budget expiry ⇒ exactly one terminal error surfaced, all jobs cancelled;
- phone disconnect during recovery ⇒ TV session ends, no orphaned waiting state.

### 4.5 Episode navigation ownership

The PHONE owns the episode queue for remote-initiated sessions. The TV never guesses the
next episode for content it did not load itself — `PlayRequest` deliberately carries no
episode queue.
- `PLAYER_CMD NEXT/PREV` from the phone, or next/prev pressed on the TV remote during a
  remote session, or playback reaching the end of a remote session ⇒ TV emits
  `NAV_REQUESTED { lineageId, direction }` to the controlling phone and takes no other
  action.
- The phone resolves the target episode with its own generator/episode context, runs §4.2,
  and sends a NEW-lineage `PLAY` (navigation is new user intent, which also supersedes any
  in-flight recovery of the old lineage).
- If no phone is connected, the TV shows a brief "connect your phone to change episodes"
  message and stays put.

---

## 5. Security specification (G4)

Threat model: hostile devices on the same LAN. Requirements: confidentiality + integrity of
all traffic, mutual authentication after pairing, no replay, TV-side authorization.

### 5.1 Identity keys

- Each device generates a long-term **P-256 EC keypair** on first use (JCA `KeyPairGenerator
  EC/secp256r1` — available on API 23; do NOT use X25519, unavailable pre-API 33).
- TV stores its private key in **AndroidKeyStore** where possible; phone likewise. Paired-peer
  public keys + metadata (device name, pairing date) are stored in a dedicated
  SharedPreferences file that is **excluded from Auto Backup** (add explicit excludes to both
  `backup_rules`/data-extraction XML files). No reusable bearer tokens exist anywhere.

### 5.2 Pairing (one-time, TV-native)

1. User opens TV → Settings → "Phone connection" → "Pair a phone". TV starts the listener
   (if not running), generates a random 6-digit PIN and a 2-minute pairing window, and shows
   a full-screen pairing UI: PIN, device name (QR removed — pairing is PIN-only).
2. Phone discovers the TV via NSD, the user picks the TV from the device picker and types the
   PIN shown on the TV.
3. Handshake: ephemeral ECDH (P-256) both directions →
   `sessionSecret = ECDH(ephA, ephB)`;
   `K = HKDF-SHA256(sessionSecret, salt = transcriptHash, info = "cs-companion-pair-v3" || PIN)`.
   Both sides exchange `confirm = HMAC-SHA256(K, transcriptHash || role)` and verify.
   Wrong PIN ⇒ confirmation fails; the PIN never crosses the wire.
   `transcriptHash` = SHA-256 over both ephemeral public keys and both long-term public keys
   (which are exchanged inside the handshake).
4. On success each side stores the peer's **long-term** public key as "paired". TV shows
   "Paired with <phone name>" and a d-pad-confirmable approval if PIN entry was manual.
5. Limits: max 5 PIN attempts per window (atomic counter under one lock — v1 had a race,
   §9 H2), rate-limit pairing attempts per source IP, close the window on success/timeout.
   Debug builds must NOT log the PIN (§9 L1).

### 5.3 Session establishment (every connection)

Ephemeral ECDH + signatures from long-term keys (SIGMA-style):
each peer signs the transcript with its long-term key; peer verifies the signature against a
**stored paired key**, else rejects (unknown phone ⇒ TV replies with a "not paired" handshake
error; phone offers pairing UX). Derive per-direction AES-256-GCM keys via HKDF with distinct
`info` strings (`"c2s"`, `"s2c"`). Forward secrecy comes from ephemerals.

### 5.4 Record layer

- AES-256-GCM. Per-direction 64-bit sequence counter, starting at 0, incremented per frame;
  nonce = 4-byte fixed salt (from HKDF) || 8-byte counter. Receiver enforces strictly
  increasing counters ⇒ replay/reorder protection. AAD = protocol version byte.
- Any decrypt failure ⇒ close the connection immediately.

### 5.5 TV-side authorization & hardening

- Every decrypted request is attributed to the paired device identity from the handshake.
- Global setting "Allow phone control" — when toggled OFF, the TV must stop the listener AND
  close all live connections immediately (§9 H1), not just set a preference.
- Unpair (from TV UI or phone `UNPAIR`) ⇒ delete stored key, close all that device's
  connections including event subscriptions (§9 H6).
- Listener quotas: max 8 concurrent connections, max 3 per source IP, handshake deadline 10 s,
  bounded read budgets, idle timeout 5 min on non-subscribed connections (§9 H3).
- Lifecycle: single owner job for the accept loop; `start()`/`stop()` serialized via a
  mutex + generation check after bind (§9 H4). NSD registration only after successful bind,
  unregistered in `stop()`.

### 5.6 Crypto testing requirements

JVM unit tests (no Android): handshake succeeds with correct PIN, fails with wrong PIN, fails
with tampered transcript, unknown long-term key rejected, record-layer replay (repeated
counter) rejected, out-of-order counter rejected, oversized frame rejected, fuzz garbage
frames don't throw uncaught. Use fake key-storage interface; Android Keystore is behind an
interface (`crypto/KeyStore.kt` with `AndroidKeyStoreImpl` + `InMemoryKeyStoreImpl`).

---

## 6. Discovery & connectivity

- NSD service type `_cloudstream-companion._tcp.` registered by the TV while the feature is
  enabled. Reuse the NSD lifecycle patterns from `actions/temp/fcast/FcastManager.kt`
  (registerService/discoverServices/resolveService/unregister) but do not couple to Fcast
  packet types.
- TXT records: `v=3`, `name=<device name>`, `fp=<sha256 fingerprint of TV long-term pubkey>`.
  The phone device-picker matches `fp` against stored pairings to label devices as
  "Paired"/"New".
- Discovery must use a generation token checked in every callback and clear the device list on
  stop (§9 M5).
- Manual fallback: "Add TV by address" accepting `host:port`, `[ipv6]:port`, and bare IPv6 —
  parse with `InetSocketAddress`/URI rules, never `substringBeforeLast(":")` (§9 L5).
- Phone reconnect: exponential backoff 1s→30s, single retained loop job with generation,
  cancelled on app background after 5 min (foreground resumes it).

---

## 7. UI integration (exact seams)

### TV side

- Settings entry: new destination "Phone connection" in the settings navigation graph.
  Follow existing conventions: fragment + action in `res/navigation/mobile_navigation.xml`,
  wiring in `ui/settings/SettingsFragment.kt` (see how
  `action_navigation_global_to_navigation_settings_general` etc. are wired ~lines 225-231),
  visibility on all layouts but content adapts. Screens: status (enabled toggle, device
  name), "Pair a phone" (full-screen PIN, §5.2), paired-devices list with revoke.
  All strings in `strings.xml`; layouts must be d-pad focusable following existing TV
  layout patterns (`isLayout(TV or EMULATOR)` from `ui/settings/Globals.kt:16-59`).
- Pairing approval overlay: native dialog consistent with app theme.
- During remote playback: standard `GeneratorPlayer` UI — remote-initiated playback must be
  indistinguishable from local playback except a small "via <phone name>" chip in the player
  title area.

### Phone side

- Toolbar affordance: cast-style icon in the same region `MainActivity` hosts Chromecast/
  Fcast integration (~lines 1950-1986). States: hidden (feature off / no pairings and no
  discovered TVs), disconnected, connected (accent tint). Tap ⇒ device picker bottom sheet
  (paired + discovered devices, "Add TV by address", "Pair new TV" → PIN entry).
- Result screens: when a TV is connected, the primary play action offers "Play on TV"
  (badge/label per existing button styles); long-press or overflow keeps "Play on phone".
  Integration point: the play-action dispatch in `ui/result/ResultViewModel2.kt` (generator
  built at ~:1272 and ~:2077) — add a routing decision, keep the diff surgical.
- Now-playing sheet: bottom-sheet fragment showing poster/title/position with live scrubber
  (see F1 below), play/pause/seek ±10s/next/prev, and a d-pad tab for KEY navigation.
- Remote keyboard (F2): when `INPUT_CONTEXT SEARCH_FIELD` event arrives, show an input bar
  above the bottom nav; text changes send `INPUT_TEXT` (debounce 100 ms).

### Feature order after core

- F1 Live scrubber: `PLAYBACK_STATE` cadence 3 s while playing + immediate on
  play/pause/seek/end/buffer. Scrubber commits `SEEK_TO` on release.
- F2 Remote keyboard: TV `SearchFragment` focus hook → `INPUT_CONTEXT` events; TV applies
  `INPUT_TEXT` by setting the EditText text directly on the main thread.
- F3 Resume-position sync: typed records over `DataStoreHelper` (`getViewPos`/`setViewPos`,
  account-namespaced keys `"$currentAccount/$VIDEO_POS_DUR"`, see `utils/DataStoreHelper.kt:693,753`).
  Only `VIDEO_POS_DUR` + watch-state for known media ids. Strict validation: bounded clock
  skew (reject `updatedAt` > now + 5 min), LWW per key. NEVER accept arbitrary preference keys
  (§9 H5).
- F4 Share-target "Send to TV": phone intent filter for ACTION_SEND text/URLs; runs the URL
  through the standard link-loading + pipeline (§4.2), then `PLAY`. No clipboard watching.
- F5 Source/audio/subtitle selection from phone (needs new EVENT kinds `TRACKS_AVAILABLE` +
  requests `SELECT_SOURCE/AUDIO/SUBTITLE` — design analogous to §3.3; player track APIs are on
  `CS3IPlayer`/`GeneratorPlayer` source & track dialogs).

Implement core first (M0–M4 in §10); F1–F2 belong to M4; F3–F5 are M5, in that order.

---

## 8. Salvage guide (reference branch `feat/lan-remote`)

Read with `git show feat/lan-remote:<path>`. Salvage = re-derive concepts/logic and port
tests; do not copy transport/auth code.

- `app/.../remote/RemoteMessages.kt` — payload catalog; source of naming and field ideas for
  §3. The new protocol is NOT wire-compatible (that's fine; no released peers exist).
- `app/.../ui/player/PlaybackCoordinator.kt` (:22-116) — target selection, link filtering,
  cookie capture. Port the filtering/cookie logic into the new pipeline (§4.2) minus its
  UI coupling.
- `app/.../ui/player/PlaybackLinkResolver.kt` — phone WebView resolution of non-portable
  links (URL pattern matching for `.m3u8/.mpd/.mp4`, UA/cookie injection at :119-145).
  Port the interception patterns; ADD probe validation (§4.2 step 3) which it lacked.
- `app/.../remote/server/CommandHandlers.kt`, `NowPlayingHub.kt`, `PlaybackReporter.kt` —
  command semantics, event throttling, player registration. Port semantics; fix M4 (§9).
- `app/.../remote/sync/LibrarySyncManager.kt` — allowlist/validation/LWW logic (~:270-390)
  reusable for F3.
- Unit tests under `app/src/test/**/remote/` (8 files) — port as behavioral specs where the
  behavior carries over (framing limits, replay rejection intent, IPv6 parsing, sync
  validation, pairing attempt limits).
- Everything else (LanRemoteServer/Client/Protocol raw TCP, RemoteAuth HMAC scheme,
  PairingManager token storage, phone-led PairingFlow, CompanionSettingsActivity): reference
  for behavior only — the new design replaces them.

---

## 9. Known pitfalls from the v1 audit — must not recur

Regression checklist (from `.pi-audit.md`, keyed for reference):
- C1/C2: no unauthenticated or cleartext frames anywhere; §5 design eliminates by
  construction. No bearer tokens.
- H1: disabling control stops listener AND closes live sockets.
- H2: pairing attempt counter atomic; sessions deduped per device; window enforced.
- H3: connection/read quotas (§5.5 numbers).
- H4: listener start/stop serialized, generation-checked after bind.
- H5: sync accepts only allowlisted typed records with bounded timestamps.
- H6: unpair closes event subscriptions immediately.
- M1: phone reconnect loop retained/cancellable, generation-guarded.
- M2: replay protection must not degrade under traffic (GCM counters solve this).
- M4: `GeneratorPlayer.releasePlayer()` must unregister from the reporting hub before every
  release; registration is generation/identity-aware.
- M5: NSD callbacks generation-checked; device list cleared on stop.
- M6: keys in Keystore; backup excludes for companion prefs.
- L1: never log PINs/keys, including debug builds.
- L3: all strings in `strings.xml`, all dimensions in dp/resources.
- L5: IPv6-correct address parsing.

---

## 10. Milestones, acceptance criteria, validation

Work strictly in this order; each milestone compiles, passes tests, and is demoable.

### M-1 — Compatibility spike (BEFORE any rebuild work)
Prove the core premise on real hardware: links resolved on the phone actually play on the
old TV, for the user's actual problematic sources. Cloudflare clearance cookies
(`cf_clearance`) can be bound to the issuing client's User-Agent and TLS fingerprint; the
TV's OkHttp/Cronet stack does not share the phone WebView's fingerprint, so copying
cookies/headers is NOT guaranteed to work — this must be measured, not assumed.
- Use the existing `feat/lan-remote` build (functional) or a minimal throwaway harness:
  resolve each problematic source on the phone, play on the real TV.
- Record a matrix per source: plays with URL only / plays with copied UA+cookie headers /
  fails (and at which stage: manifest vs segment).
- Decision gate: if key sources fail due to device/session-bound clearance, PROMOTE the
  phone LAN-proxy mode (phone fetches upstream, TV streams from the phone over LAN) from
  "later/optional" into M2 core scope before proceeding.
- Accept: written source matrix + explicit go/no-go call on proxy promotion.

### M0 — Skeleton + protocol
- Package structure §2, DTOs §3 with converters, framing codec, JVM tests (round-trip,
  unknown-field tolerance, size limits, referer/audioTracks/playlist preservation).
- Accept: `./gradlew :app:testPrereleaseDebugUnitTest --tests "*companion*"` green;
  `./gradlew :app:assemblePrereleaseDebug` builds.

### M1 — Secure transport + pairing
- Crypto layer §5 with full test suite §5.6; TCP client/server; NSD register/discover §6;
  TV settings screens + pairing UI §7; phone device picker + PIN pairing.
- Accept: crypto tests green; manual: pair phone↔TV emulator via PIN, wrong PIN rejected
  5-attempt lockout, revoke kills connection, toggle off stops listener (verify port closed).

### M2 — Play-on-TV core
- Pipeline §4.2, TV launcher §4.3, failure/re-resolve loop §4.4.
- Accept: manual on real devices — (a) a direct-mp4 source plays on TV with resume position;
  (b) an HLS source behind Cloudflare resolves on phone and plays on TV (choose sources per
  the M-1 matrix); (c) killing the link (short-lived token source) triggers LINK_FAILED →
  phone re-resolves → playback resumes or fails deterministically within the 60 s recovery
  budget of §4.4 (no fixed-latency promise; record the measured time); (d) DRM source
  refuses handoff with message; (e) the §4.4 deterministic stale/cancellation/budget tests
  are green; (f) a multi-part playlist source plays across part boundaries without stopping.

### M3 — Native UX pass
- Result-screen "Play on TV" routing, toolbar states, connected chip, "via phone" chip on TV
  player, all strings/dimensions resourced, d-pad traversal on all new TV screens.
- Accept: UX review against G3/G6; TV app with feature disabled has zero behavioral diff.

### M4 — Remote control + now-playing + keyboard
- `PLAYER_CMD`/`KEY` handlers on main thread, `PLAYBACK_STATE` events (F1 cadence), scrubber
  sheet, `INPUT_CONTEXT`/`INPUT_TEXT` (F2). Fix-forward M4 pitfall (player unregister).
- Accept: manual — pause/seek/next from phone <500 ms perceived latency on LAN; search text
  typed on phone appears on TV; player release during remote command doesn't crash.

### M5 — Second screen extras
- F3 resume sync, F4 share-target, F5 track selection — in that order, each with tests.

### Global validation
- Unit tests: `./gradlew :app:testPrereleaseDebugUnitTest`
- Lint: `./gradlew :app:lintPrereleaseDebug` (must not add new errors)
- Never commit unless explicitly asked by the repo owner. When asked, use conventional
  commits (`feat(companion): …`) and include `Co-Authored-By: Warp <agent@warp.dev>`.

---

## 11. Open decisions already made (do not re-litigate)

- Rebuild from `master`, not incremental cleanup of `feat/lan-remote`.
- Custom AEAD handshake (§5) instead of raw TLS: avoids certificate plumbing on API 23 and
  keeps pairing PIN-bound; the design above is fully specified — implement as written.
- TCP + NSD (not HTTP/WebSocket): preserves the working v1 model, minimal dependencies.
- Protocol v3 is not wire-compatible with the old branch. No migration path is required;
  old builds were never released.
- Feature naming in UI: "Phone connection" (TV) / "Connect to TV" (phone). Internal package
  name `companion`.
- The only intentionally deferred decision: whether phone LAN-proxy mode is core (M2) or
  later — gated on the M-1 spike results, not on preference.
