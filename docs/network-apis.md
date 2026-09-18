# Signal Android — Network Calls & URLs

Catalog of external hosts and API paths used by Signal Android.

**Architecture / self-host context:** see [`COMPANION_SERVICES.md`](COMPANION_SERVICES.md) (what `chat` / `storage` / `cdn*` are, local vs prod, tickets vs GroupsV2). Local URL switch: [`LOCAL_SERVER.md`](LOCAL_SERVER.md).

**Sources:** `app/build.gradle.kts`, `SignalServiceNetworkAccess.kt`, `PushServiceSocket.java`, `lib/network/.../api/*`, `lib/libsignal-service/.../api/*`.

**Transport note:** Most chat endpoints are **HTTP-over-WebSocket** (`WebSocketRequestMessage` on `wss://chat.signal.org/v1/websocket/`), not plain REST. Registration, GroupsV2, and storage use OkHttp **REST**. Some attachment/username/multi-recipient paths are implemented in **libsignal** (JNI) without path string literals in this repo.

---

## Base hosts (production)

| Role | URL |
|------|-----|
| Chat / messages | `https://chat.signal.org` |
| Storage + GroupsV2 | `https://storage.signal.org` |
| CDN 0 | `https://cdn.signal.org` |
| CDN 2 | `https://cdn2.signal.org` |
| CDN 3 | `https://cdn3.signal.org` |
| Contact discovery (CDSI) | `https://cdsi.signal.org` |
| Secure Value Recovery (SVR2) | `https://svr2.signal.org` |
| Voice/video SFU | `https://sfu.voip.signal.org` |
| Content proxy | `contentproxy.signal.org:443` |
| Service status | `uptime.signal.org` |
| Static assets | `https://updates2.signal.org` |
| APK updates (non-Play builds) | `https://updates.signal.org/android/latest.json` |

### Staging

| Role | URL |
|------|-----|
| Chat | `https://chat.staging.signal.org` |
| Storage | `https://storage-staging.signal.org` |
| CDN 0 / 2 / 3 | `https://cdn-staging.signal.org`, `cdn2-staging`, `cdn3-staging` |
| CDSI | `https://cdsi.staging.signal.org` |
| SVR2 | `https://svr2.staging.signal.org` |
| SFU | `https://sfu.staging.voip.signal.org` |
| Captcha | `https://signalcaptchas.org/staging/registration/generate.html` |

### Internal SFU variants

| Name | URL |
|------|-----|
| Test | `https://sfu.test.voip.signal.org` |
| Staging | `https://sfu.staging.voip.signal.org` |
| Development | `https://sfu.staging.test.voip.signal.org` |

---

## Architecture overview

```
App
 ├─ WS  chat.signal.org/v1/websocket/     → most chat APIs
 ├─ HTTPS chat.signal.org                 → registration
 ├─ HTTPS storage.signal.org              → storage + GroupsV2
 ├─ HTTPS cdn*.signal.org                 → media upload/download
 ├─ CDSI / SVR2 enclaves                  → discovery / PIN recovery
 ├─ SFU (RingRTC)                         → voice/video calls
 └─ Stripe / Giphy / FCM / captcha / updates*
```

---

## 1. Chat — `https://chat.signal.org`

### 1.1 WebSockets

| Method | Path / URL | Purpose | Source |
|--------|------------|---------|--------|
| WS | `wss://chat.signal.org/v1/websocket/` | Main authenticated / unauthenticated chat socket | `OkHttpWebSocketConnection.java`, `LibSignalChatConnection.kt` |
| WS | `wss://chat.signal.org/v1/websocket/provisioning/` | Device provisioning socket | `ProvisioningSocket.kt` |
| GET | `/v1/keepalive` | Keepalive | Same + `ProvisioningSocket.kt` |

#### Server → client (on chat WebSocket)

| Method | Path | Purpose | Source |
|--------|------|---------|--------|
| PUT | `/api/v1/message` | Incoming envelope delivery | `SignalWebSocket.kt` |
| PUT | `/api/v1/queue/empty` | Inbox drained | `SignalWebSocket.kt` |
| (recv) | `/v1/address` | Provisioning address | `ProvisioningSocket.kt` |
| (recv) | `/v1/message` | Provisioning ciphertext | `ProvisioningSocket.kt` |

### 1.2 Accounts

| Method | Path | Purpose | Source |
|--------|------|---------|--------|
| GET | `/v1/accounts/whoami` | Account identity | `AccountApi.kt` |
| PUT | `/v1/accounts/gcm` | Register FCM token | `AccountApi.kt` |
| DELETE | `/v1/accounts/gcm` | Clear FCM token | `AccountApi.kt` |
| PUT | `/v1/accounts/attributes` | Update account attributes | `AccountApi.kt` |
| PUT | `/v1/accounts/registration_lock` | Enable registration lock | `AccountApi.kt` |
| DELETE | `/v1/accounts/registration_lock` | Disable registration lock | `AccountApi.kt` |
| DELETE | `/v1/accounts/me` | Delete account | `AccountApi.kt` |
| GET | `/v2/accounts/data_report` | Account data export | `AccountApi.kt` |
| PUT | `/v2/accounts/number` | Change phone number | `AccountApi.kt` |
| PUT | `/v1/accounts/username_hash/reserve` | Reserve username hashes | `AccountApi.kt` |
| PUT | `/v1/accounts/username_hash/confirm` | Confirm username | `AccountApi.kt` |
| DELETE | `/v1/accounts/username_hash` | Clear username | `AccountApi.kt` |
| PUT | `/v1/accounts/username_link` | Set / update username link | `AccountApi.kt` |
| PUT | `/v1/accounts/name?deviceId={id}` | Set encrypted device name | `LinkDeviceApi.kt` |

Username **lookup** (by hash / link) uses libsignal `UnauthUsernamesService` via `UsernameApi.kt` — path not a string literal in this repo.

### 1.3 Devices / provisioning / linking

| Method | Path | Purpose | Source |
|--------|------|---------|--------|
| GET | `/v1/devices` | List devices | `LinkDeviceApi.kt` |
| DELETE | `/v1/devices/{deviceId}` | Unlink device | `LinkDeviceApi.kt` |
| GET | `/v1/devices/provisioning/code` | New device link code | `LinkDeviceApi.kt` |
| PUT | `/v1/devices/link` | Register as secondary device | `PushServiceSocket.java`, `RegistrationApi.kt` |
| PUT | `/v1/devices/capabilities` | Device capabilities | `AccountApi.kt` |
| PUT | `/v1/devices/restore_account/{token}` | Set restore method | `PushServiceSocket.java` |
| GET | `/v1/devices/restore_account/{token}?timeout=` | Poll restore method | `ProvisioningApi.kt` |
| GET | `/v1/devices/wait_for_linked_device/{token}?timeout=` | Wait for link | `LinkDeviceApi.kt` |
| PUT | `/v1/devices/transfer_archive` | Set transfer archive availability | `LinkDeviceApi.kt` |
| GET | `/v1/devices/transfer_archive?timeout=` | Poll transfer archive | `LinkDeviceApi.kt` |
| PUT | `/v1/provisioning/{deviceIdentifier}` | Send provisioning message | `ProvisioningApi.kt`, `LinkDeviceApi.kt` |

### 1.4 Registration / verification (REST on chat)

| Method | Path | Purpose | Source |
|--------|------|---------|--------|
| POST | `/v1/verification/session` | Create verification session | `PushServiceSocket.java`, `RegistrationApi.kt` |
| GET | `/v1/verification/session/{sessionId}` | Session status | same |
| PATCH | `/v1/verification/session/{sessionId}` | Captcha / push challenge / MCC-MNC | same |
| POST | `/v1/verification/session/{sessionId}/code` | Request SMS / voice code | same |
| PUT | `/v1/verification/session/{sessionId}/code` | Submit verification code | same |
| POST | `/v1/registration` | Register account | same |

### 1.5 Messages & keys

| Method | Path | Purpose | Source |
|--------|------|---------|--------|
| PUT | `/v1/messages/{destination}?story=` | Send message (1:1 / identified) | `MessageApi.kt`, `PushServiceSocket.java` |
| POST | `/v1/messages/report/{serviceId}/{serverGuid}` | Report spam | `MessageApi.kt` |
| PUT | `/v1/messages/multi_recipient?ts=&online=&urgent=&story=` | Multi-recipient send (also via libsignal) | `PushServiceSocket.java` |
| POST | `/v2/keys/check` | Check repeated-use prekeys | `KeysApi.kt` |
| GET | `/v2/keys?identity=` | Prekey counts | `KeysApi.kt` |
| PUT | `/v2/keys?identity=` | Upload prekeys | `KeysApi.kt` |
| GET | `/v2/keys/{identifier}/{deviceSpecifier}` | Fetch prekeys (`*` = all) | `KeysApi.kt`, `KeysApiV2.kt` |

Attachment upload form: `AttachmentApi` → libsignal `AuthMessagesService.getUploadForm()` (historically `/v4/attachments/form/upload`-class).

### 1.6 Profiles

| Method | Path | Purpose | Source |
|--------|------|---------|--------|
| PUT | `/v1/profile` | Write profile (+ avatar form) | `ProfileApi.kt` |
| GET | `/v1/profile/{aci}/{version}/{zkRequest}?credentialType=expiringProfileKey` | Profile + credential | `ProfileApi.kt`, `ProfileService.java` |
| GET | `/v1/profile/{aci}/{version}` | Versioned profile | same |
| GET | `/v1/profile/{serviceId}` | Basic profile | same |
| POST | `/v1/profile/identity_check/batch` | Batch identity fingerprint check | `ProfileService.java` |

### 1.7 Certificates & auth helpers

| Method | Path | Purpose | Source |
|--------|------|---------|--------|
| GET | `/v1/certificate/delivery` | Delivery certificate | `CertificateApi.kt` |
| GET | `/v1/certificate/delivery?includeE164=false` | Delivery cert without E164 | `CertificateApi.kt` |
| GET | `/v1/certificate/auth/group?redemptionStartSeconds=&redemptionEndSeconds=` | Groups auth credentials | `GroupsV2ApiHelper.kt` |
| GET | `/v1/storage/auth` | Storage service auth | `StorageServiceApi.kt` |
| GET | `/v2/svr/auth` | SVR2 auth | `SecureValueRecoveryV2.kt` |
| POST | `/v2/svr/auth/check` | Validate SVR2 credentials | `PushServiceSocket.java`, `RegistrationApi.kt` |
| GET | `/v3/backup/auth` | SVR3 / backup auth | `SecureValueRecoveryV3.kt` |
| POST | `/v3/backup/auth/check` | Validate SVR3 credentials | `PushServiceSocket.java`, `RegistrationApi.kt` |
| GET | `/v2/directory/auth` | CDSI auth | `CdsApi.kt` |
| GET | `/v1/payments/auth` | MobileCoin payments auth | `PaymentsApi.kt` |
| GET | `/v1/payments/conversions` | Currency conversion rates | `PaymentsApi.kt` |

### 1.8 Remote config, challenges, calling (chat)

| Method | Path | Purpose | Source |
|--------|------|---------|--------|
| GET | `/v2/config` | Remote config | `RemoteConfigApi.kt` |
| POST | `/v1/challenge/push` | Request push challenge | `RateLimitChallengeApi.kt` |
| PUT | `/v1/challenge` | Submit push or captcha challenge | `RateLimitChallengeApi.kt` |
| PUT | `/v1/call_quality_survey` | Call quality survey (protobuf) | `CallingApi.kt` |
| GET | `/v2/calling/relays` | TURN / calling relays | `CallingApi.kt` |
| POST | `/v1/call-link/create-auth` | Call-link create credential | `CallingApi.kt` |

SFU HTTP is driven by RingRTC via `CallingApi.makeCallingRequest(url, …)` (opaque absolute SFU URLs).

### 1.9 Archives / cloud backup

| Method | Path | Purpose | Source |
|--------|------|---------|--------|
| GET | `/v1/archives/auth?redemptionStartSeconds=&redemptionEndSeconds=` | Backup auth credentials | `ArchiveApi.kt` |
| GET | `/v1/archives/auth/read?cdn=` | Read CDN auth | `ArchiveApi.kt` |
| GET | `/v1/archives/auth/svrb` | SVRB auth | `ArchiveApi.kt` |
| PUT | `/v1/archives/backupid` | Reserve backup IDs | `ArchiveApi.kt` |
| GET | `/v1/archives/backupid/limits` | Backup ID limits | `ArchiveApi.kt` |
| PUT | `/v1/archives/keys` | Set backup public key | `ArchiveApi.kt` |
| GET | `/v1/archives/upload/form?uploadLength=` | Message backup upload form | `ArchiveApi.kt` |
| GET | `/v1/archives` | Backup info / status | `ArchiveApi.kt` |
| POST | `/v1/archives` | Refresh / commit backup | `ArchiveApi.kt` |
| DELETE | `/v1/archives` | Delete backup | `ArchiveApi.kt` |
| GET | `/v1/archives/media/upload/form?uploadLength=` | Media upload form | `ArchiveApi.kt` |
| GET | `/v1/archives/media?limit=&cursor=` | List archived media | `ArchiveApi.kt` |
| PUT | `/v1/archives/media` | Copy media into archive | `ArchiveApi.kt` |
| PUT | `/v1/archives/media/batch` | Batch copy | `ArchiveApi.kt` |
| POST | `/v1/archives/media/delete` | Delete archived media | `ArchiveApi.kt` |
| POST | `/v1/archives/redeem-receipt` | Redeem backup receipt | `DonationsApi.kt` |

### 1.10 Donations / subscriptions

| Method | Path | Purpose | Source |
|--------|------|---------|--------|
| GET | `/v1/subscription/configuration` | Donation config | `DonationsApi.kt` |
| GET | `/v1/subscription/bank_mandate/{type}` | Bank mandate text | `DonationsApi.kt` |
| GET | `/v1/subscription/{subscriberId}` | Get subscription | `DonationsApi.kt` |
| PUT | `/v1/subscription/{subscriberId}` | Create / update subscriber | `DonationsApi.kt` |
| DELETE | `/v1/subscription/{subscriberId}` | Cancel | `DonationsApi.kt` |
| PUT | `/v1/subscription/{id}/level/{level}/{currency}/{idempotencyKey}` | Set level | `DonationsApi.kt` |
| POST | `/v1/subscription/boost/create` | Create boost | `DonationsApi.kt` |
| POST | `/v1/subscription/{id}/create_payment_method?type=` | Stripe payment method | `DonationsApi.kt` |
| POST | `/v1/subscription/boost/paypal/create` | PayPal boost create | `DonationsApi.kt` |
| POST | `/v1/subscription/boost/paypal/confirm` | PayPal boost confirm | `DonationsApi.kt` |
| POST | `/v1/subscription/{id}/create_payment_method/paypal` | PayPal recurring | `DonationsApi.kt` |
| POST | `/v1/subscription/{id}/receipt_credentials` | Sub receipt credentials | `DonationsApi.kt` |
| POST | `/v1/subscription/boost/receipt_credentials` | Boost receipt credentials | `DonationsApi.kt` |
| POST | `/v1/subscription/{id}/default_payment_method/stripe/{pmId}` | Set Stripe default PM | `DonationsApi.kt` |
| POST | `/v1/subscription/{id}/default_payment_method_for_ideal/{setupIntentId}` | iDEAL default | `DonationsApi.kt` |
| POST | `/v1/subscription/{id}/default_payment_method/braintree/{pmId}` | Braintree / PayPal default | `DonationsApi.kt` |
| POST | `/v1/subscription/{id}/playbilling/{purchaseToken}` | Play Billing | `DonationsApi.kt` |
| POST | `/v1/donation/redeem-receipt` | Redeem donation receipt | `DonationsApi.kt` |
| POST | `/v1/donation/permit` | Donation permit | `DonationsApi.kt` |

---

## 2. Storage — `https://storage.signal.org`

Called via `makeStorageRequest` in `PushServiceSocket.java`.

### 2.1 Storage sync

| Method | Path | Purpose | Source |
|--------|------|---------|--------|
| GET | `/v1/storage/manifest` | Current manifest | `PushServiceSocket.java` |
| GET | `/v1/storage/manifest/version/{version}` | Manifest at version | same |
| PUT | `/v1/storage/read` | Read storage items | same |
| PUT | `/v1/storage` | Write storage items | same |
| GET | `/ping` | Storage connectivity ping | same |

### 2.2 GroupsV2 (same storage host)

| Method | Path | Purpose | Source |
|--------|------|---------|--------|
| PUT | `/v2/groups/` | Create group | `PushServiceSocket.java` |
| GET | `/v2/groups/` | Get group | same |
| PATCH | `/v2/groups/` | Mutate group | same |
| PATCH | `/v2/groups/?inviteLinkPassword=` | Mutate via invite link | same |
| GET | `/v2/groups/logs/{fromVersion}?maxSupportedChangeEpoch=&includeFirstState=` | Change log | same |
| GET | `/v2/groups/avatar/form` | Group avatar upload form | same |
| GET | `/v2/groups/join/{password}` | Join info | same |
| GET | `/v2/groups/token` | External group credential | same |
| GET | `/v2/groups/joined_at_version` | Joined-at revision | same |

---

## 3. CDN — `cdn` / `cdn2` / `cdn3.signal.org`

Path builders in `PushServiceSocket.java`. Uploads use signed URLs from forms.

| Method | Path template | Purpose | CDN |
|--------|---------------|---------|-----|
| GET | `attachments/{cdnKey}` | Attachment by key (v4) | 0 / 2 / 3 |
| GET | `attachments/{cdnId}` | Attachment by numeric id (v2) | 0 |
| GET | `stickers/{packId}/manifest.proto` | Sticker pack manifest | 0 |
| GET | `stickers/{packId}/full/{stickerId}` | Sticker asset | 0 |
| GET | `backups/{mediaCdnPath}/{mediaId}` | Archived media | archive CDN |
| POST | `` (form fields) | Profile / group avatar upload (CDN0 multipart) | 0 |
| POST / HEAD / PATCH | `{signedUploadLocation}` (+ `/{key}` for CDN3) | Resumable / TUS attachment & backup upload | 2 / 3 |

---

## 4. CDSI — `https://cdsi.signal.org`

| Method | Path / protocol | Purpose | Source |
|--------|-----------------|---------|--------|
| GET | `/v2/directory/auth` (on **chat**) | Auth credentials | `CdsApi.kt` |
| WS / protocol | CDSI enclave session | Contact discovery | `CdsApi` → libsignal `Network` |

No further CDSI path literals in this Android tree.

---

## 5. SVR2 — `https://svr2.signal.org`

| Method | Path | Purpose | Source |
|--------|------|---------|--------|
| GET | `/v2/svr/auth` (on **chat**) | Auth | `SecureValueRecoveryV2.kt` |
| POST | `/v2/svr/auth/check` (on **chat**) | Credential check | `PushServiceSocket.java` |
| WS | `/v1/{mrEnclave}` | SVR2 WebSocket enclave session | `Svr2Socket.kt` |

### SVR3 / SVRB

| Method | Path | Purpose | Source |
|--------|------|---------|--------|
| GET | `/v3/backup/auth` (on **chat**) | Auth | `SecureValueRecoveryV3.kt` |
| POST | `/v3/backup/auth/check` (on **chat**) | Credential check | `PushServiceSocket.java` |
| GET | `/v1/archives/auth/svrb` (on **chat**) | SVRB-specific auth | `ArchiveApi.kt` |
| — | Enclave ops via libsignal `Network.svrB()` | Store / restore | `SvrBApi.kt` |

---

## 6. SFU — `https://sfu.voip.signal.org`

| Method | Path | Purpose | Source |
|--------|------|---------|--------|
| — | RingRTC-supplied absolute URLs | Group / call-link media | `CallingApi.makeCallingRequest`, `Environment.kt` |

Auth for call links / TURN comes from chat (`/v1/call-link/create-auth`, `/v2/calling/relays`).

---

## 7. Libsignal-opaque chat endpoints

Implemented in libsignal JNI; path strings are not in this repo. Invoked from:

| Caller | Libsignal service | Expected purpose |
|--------|-------------------|------------------|
| `AttachmentApi` | `AuthMessagesService.getUploadForm` | Attachment upload form |
| `MessageApi` / `MessageApiV2` | Unauth / auth message services | Multi-recipient & sealed send |
| `UsernameApi` | `UnauthUsernamesService` | Username hash / link lookup |

---

## 8. Third-party & ancillary hosts

| Host / URL | Purpose | Source |
|------------|---------|--------|
| `https://api.stripe.com/v1/...` | Donations: payment methods, tokens, payment intents, setup intents | `StripeApi.kt`, `StripePaths.kt`, `BuildConfig.STRIPE_BASE_URL` |
| `https://signaldonations.org/stripe/return/ideal` | iDEAL return URL | `StripeApi.kt` |
| Google Pay / Play Billing | In-app purchase | Play services + chat Play Billing path |
| `fog://fog.prod.mobilecoinww.com` | MobileCoin fog (prod) | `MobileCoinMainNetConfig.java` |
| `fog://fog.test.mobilecoin.com` | MobileCoin fog (test) | config |
| `https://www.mobilecoin.com/terms-of-use.html` | Terms link | `strings.xml` |
| `https://signalcaptchas.org/registration/generate.html` | Registration captcha WebView | `BuildConfig.SIGNAL_CAPTCHA_URL` |
| `https://signalcaptchas.org/challenge/generate.html` | Rate-limit reCAPTCHA | `BuildConfig.RECAPTCHA_PROOF_URL` |
| `signalcaptcha://…` | Captcha result deep link | `RegistrationConstants.java`, `RecaptchaProofActivity.java` |
| `contentproxy.signal.org:443` | Link preview / Giphy proxy | `ContentProxySelector.java` |
| `https://api.giphy.com/v1/gifs/` | GIF search (via content proxy) | `GiphyMp4PagedDataSource.java` |
| `uptime.signal.org` | Service outage DNS check | `ServiceOutageDetectionJob.java` |
| `https://updates2.signal.org` | Static assets (emoji, fonts, badges, calling weights) | `S3.kt`, `CallingAssets.kt`, `BADGE_STATIC_ROOT` |
| `https://updates2.signal.org/static/badges/` | Badge images | `BuildConfig.BADGE_STATIC_ROOT` |
| `https://updates.signal.org/android/latest.json` | APK update manifest (website / GitHub builds) | `BuildConfig.APK_UPDATE_MANIFEST_URL` |
| Firebase Cloud Messaging | Push notifications | `FcmReceiveService.java`, `firebase_messaging.xml` |
| Google Maps / Play Services Auth | Maps, auth helpers | Play Services SDKs |

### Domain fronting (censorship circumvention)

Configured in `SignalServiceNetworkAccess.kt`:

| Front hosts | Backend Host header |
|-------------|---------------------|
| Fastly fronts (`github.githubassets.com`, `pinterest.com`, `redditstatic.com`, …) | `chat-signal.global.ssl.fastly.net`, `storage.signal.org.global.prod.fastly.net`, `cdn*.…`, `cdsi-signal…`, `svr2-signal…` |
| Google fronts (`www.google.com`, `android.clients.google.com`, …) | `reflector-nrgwuv7kwq-uc.a.run.app` |

Static IP fallbacks: `app/static-ips.properties`.

---

## Notes

1. **Primary path files**
   - `lib/libsignal-service/.../PushServiceSocket.java`
   - `lib/libsignal-service/.../api/**/*Api*`
   - `lib/network/src/main/java/org/signal/network/api/*.kt`
   - `app/src/main/java/.../push/SignalServiceNetworkAccess.kt`
   - `app/build.gradle.kts`

2. **Messages are E2E encrypted** on device before upload; servers primarily route ciphertext.

3. **Custom proxy** is supported for restricted networks (`EditProxyActivity`).

4. Hosts and paths can change with app versions; regenerate this doc from the sources above when updating.
