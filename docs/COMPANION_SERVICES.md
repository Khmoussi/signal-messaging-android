# Signal companion services — chat, storage, CDN, and this Android fork

Detailed map of hosts this app talks to, what is replaced by a **local Signal-Server**, and what you need for **1:1 chat + ticketing** without Signal’s production companions.

**Audience:** Android / full-stack engineers on this fork.

**Related docs**

| Doc | Topic |
|-----|--------|
| [`LOCAL_SERVER.md`](LOCAL_SERVER.md) | Pointing the app at a local chat server |
| [`network-apis.md`](network-apis.md) | Full production URL / path catalog |
| [`signal-android-architecture.md`](signal-android-architecture.md) | App architecture |
| Signal-Server: `docs/COMPANION_SERVICES.md` | Canonical server-side version of this guide |
| Signal-Server: `LOCAL_FEATURES.md`, `LOCAL_STORAGE.md`, `docs/TICKETING_DEVELOPER_GUIDE.md` | Local matrix, stores, tickets |

---

## 1. Executive summary

The Android client is configured with several base URLs (see `app/build.gradle.kts`). They are **different services**:

| BuildConfig field | Production URL | Role | Replaced when `useLocalSignalServer = true`? |
|-------------------|----------------|------|-----------------------------------------------|
| `SIGNAL_URL` | `https://chat.signal.org` | Chat API + WebSocket (+ `/v1/tickets` on this fork) | **Yes** → `http://<localSignalHost>` |
| `STORAGE_URL` | `https://storage.signal.org` | Account storage sync + GroupsV2 | **No** (still prod URL unless you change it) |
| `SIGNAL_CDN_URL` | `https://cdn.signal.org` | CDN 0 — avatars / stickers | **No** |
| `SIGNAL_CDN2_URL` | `https://cdn2.signal.org` | CDN 2 — attachments | **No** |
| `SIGNAL_CDN3_URL` | `https://cdn3.signal.org` | CDN 3 — attachments / backups | **No** |

**MVP for this fork:** local chat (`SIGNAL_URL`) is enough for **1:1 text** and **ticket text discussions**. You do **not** need working Storage Service or CDN for that.

**Important:** Local chat **cannot** usefully reuse Signal’s production `storage.signal.org` or `cdn*.signal.org` — credentials and upload signatures from your server will not validate there.

---

## 2. Big picture

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         Signal-Android (this repo)                        │
└───────┬──────────────────┬──────────────────┬────────────────────────────┘
        │                  │                  │
        ▼                  ▼                  ▼
   SIGNAL_URL         STORAGE_URL        SIGNAL_CDN*_URL
 chat / tickets      storage-service      media bytes
        │                  │                  │
        │  GET /v1/storage/auth               │
        │  GET /v1/certificate/auth/group     │
        │  attachment upload forms ───────────┤
        │                                     │
   Your Signal-Server              Separate hosts (Signal prod
   (or chat.signal.org)            or your own later)
```

---

## 3. `chat.signal.org` → `SIGNAL_URL`

### What it is

Signal’s **Signal-Server** (Java): accounts, devices, keys, message send/receive, registration, profile metadata, minting auth for companions, and on this fork **ticket APIs**.

It is **not** the Storage Service and **not** the media CDN.

### Self-hosted / local switch

Documented toggle in `app/build.gradle.kts`:

```kotlin
val useSelfHostedSignalServer = true
// Local test-server via Android emulator (host localhost:8080):
val selfHostedSignalUrl = "http://10.0.2.2:8080"
// Physical device on LAN: "http://192.168.x.x:8080"
// Deployed: "https://chat.xrp.ing"
```

| Mode | `SIGNAL_URL` |
|------|----------------|
| Local test-server (emulator) | `http://10.0.2.2:8080` |
| Local test-server (device) | `http://<LAN-IP>:8080` |
| Deployed self-host | `https://chat.xrp.ing` |
| Official Signal | set `useSelfHostedSignalServer = false` → `https://chat.signal.org` |

**Local front-to-back checklist**

1. Start server in WSL: `./mvnw integration-test -Ptest-server -DskipTests=true` (or run `LocalWhisperServerService`) → `http://localhost:8080`
2. Keep `selfHostedSignalUrl = "http://10.0.2.2:8080"` for emulator (`10.0.2.2` = host loopback)
3. Rebuild/reinstall the app (cleartext + `CLEARTEXT` OkHttp specs are enabled for `http://` SIGNAL_URL)
4. Wipe app data if you previously used `chat.xrp.ing`
5. Register two accounts → New message → enter the other E164 → local directory lookup should run

**WebSocket note:** libsignal’s `Network` hardcodes `chat.signal.org` / `grpc.chat.signal.org` and cannot be pointed at a custom host without rebuilding libsignal. When `USE_SELF_HOSTED_SIGNAL_SERVER` is true, `ApplicationDependencyProvider` uses `OkHttpWebSocketConnection` so auth/unauth sockets follow `SIGNAL_URL` (e.g. `ws://10.0.2.2:8080/v1/websocket/` or `wss://chat.xrp.ing/v1/websocket/`). Keep `IndividualSendJob` on the legacy (non-V2) path for the same reason — MessageApiV2 needs a libsignal `ChatConnection`.

### What works against local chat (typical)

- Registration (with local stubs: captcha / FCM shortcuts)  
- 1:1 **text** messaging over WebSocket  
- Contact discovery via local chat API (`POST /v1/directory/local/lookup`) when `USE_SELF_HOSTED_SIGNAL_SERVER`  
- Ticket list / create / membership / **text** discussion via `/v1/tickets`  

See server `LOCAL_FEATURES.md` for the full matrix.

### Local directory (CDSI stand-in)

When `USE_SELF_HOSTED_SIGNAL_SERVER` is true:

- Batch refresh (`ContactDiscoveryRefreshV2`) → `POST /v1/directory/local/lookup` with `{ "e164s": ["+1...", ...] }`
- **Live contact search** → `POST /v1/directory/local/search` with `{ "query": "optional" }`  
  Digit queries filter by E164; empty/name queries return up to 100 registered accounts, then the client filters locally by name.

→ `{ "results": [ { "e164", "aci", "pni" }, ... ] }`

**Trade-off:** chat becomes an E164→ACI oracle (enumeration risk). Fine for private MVP; not for a public internet deployment without extra controls.

---

## 4. `storage.signal.org` → `STORAGE_URL`

### What it is exactly

**Not** inside Signal-Server. **Not** “AWS” as a drop-in product.

| | |
|--|--|
| Software | Separate open-source service: [signalapp/storage-service](https://github.com/signalapp/storage-service) |
| Host | `https://storage.signal.org` |
| Job | Client-encrypted **account sync** + **GroupsV2** HTTP APIs |
| Prod DB (reported) | Google Bigtable (chat server uses DynamoDB — different stack) |

#### APIs the app calls (via storage host)

**Storage sync** — `PushServiceSocket.makeStorageRequest` / storage sync helpers:

- `GET /v1/storage/manifest`, `.../version/{v}`  
- `PUT /v1/storage/read`, `PUT /v1/storage`  

**GroupsV2** — same host:

- `PUT/GET/PATCH /v2/groups/`, change logs, invite links, etc.  

Auth is minted by **chat**, not by storage itself:

- `GET /v1/storage/auth` on **chat**  
- `GET /v1/certificate/auth/group` on **chat** (groups)  

### What Signal-Server only does

The chat server:

1. Mints storage credentials (`SecureStorageController`).  
2. On account delete, calls `DELETE /v1/storage` on the configured `storageService.uri`.  

It does **not** implement group create/patch or storage manifests. Those live only in storage-service.

### Why the app talks to storage “directly”

GroupsV2 and storage sync are **client-encrypted**. The chat server is deliberately kept out of membership plaintext. Android (`GroupManagerV2`, storage sync) is the crypto endpoint; storage-service is the encrypted store.

### Can local `SIGNAL_URL` keep using prod `STORAGE_URL`?

**No.** Tokens from local chat are signed with **your** shared secret; production storage expects **Signal’s**. Requests fail auth.

Same for Groups ZK / group credentials from a local server.

### Do tickets need storage?

**No.** Tickets are a **first-class chat-server feature** (`/v1/tickets`), not GroupsV2.

| | GroupsV2 | Tickets (this fork) |
|--|----------|---------------------|
| Membership / status | On storage-service (opaque) | On Signal-Server (queryable, enforceable) |
| Messages | Chat delivery + group tokens | Chat ticket send + E2E per participant |
| `STORAGE_URL` required? | Yes | **No** |

Flow for ticket messages (this repo):

1. `TicketsRepository` / `TicketApi` → chat WebSocket `/v1/tickets`.  
2. `TicketMessageSender` encrypts a data message (with ticket id + optional attachments) per participant.  
3. Attachments use the same CDN upload path as 1:1 (`/v4/attachments/form/upload` + in-server media when enabled).  
4. Sends via `SignalNetwork.tickets` → **chat**, which checks open/membership and delivers envelopes.

No `storage.signal.org` in that path.

### Replacing storage

| Option | Notes |
|--------|--------|
| Deploy storage-service | Match secrets with chat; set server `storageService.uri` + app `STORAGE_URL` |
| Point `STORAGE_URL` at S3 | Useless — wrong protocol |
| Leave unused | Correct for 1:1 + tickets MVP |

---

## 5. `cdn*.signal.org` → `SIGNAL_CDN*_URL`

### What it is

**Media object** frontends (encrypted blobs). Chat mints **signed upload/download forms**; the app uploads bytes to the CDN host, then sends a message that **references** the attachment id/key.

| URL | Role |
|-----|------|
| `cdn.signal.org` | Profiles, stickers |
| `cdn2.signal.org` | Attachments |
| `cdn3.signal.org` | Attachments / archive media (TUS-style) |

This is the piece closest to “object storage / CDN,” but clients still speak Signal’s signed-form flows, not arbitrary S3 URLs from the app config alone.

### Local mode today

**In-server media MVP:** when the chat server has `attachments.localMedia.enabled: true` and the app is built with `useSelfHostedSignalServer = true`, `SIGNAL_CDN*_URL` points at the same host as `SIGNAL_URL`. Message attachments upload/download against chat (`/attachments/...`). Avatars/stickers (CDN0 PostPolicy) are still out of scope.

Without `localMedia`, forms hit placeholders / prod CDN and fail.

---

## 6. Other hosts (short)

| Host | Build / usage | Needed for tickets + 1:1 text? |
|------|---------------|--------------------------------|
| `cdsi.signal.org` | Contact discovery | No |
| `svr2.signal.org` | PIN / SVR | No |
| `sfu.voip.signal.org` | Group call media | No |
| `updates.signal.org` | Non-Play APK updates | No |
| Stripe / captcha / Giphy | Donations, registration UX, GIFs | No for local ticket MVP |

Full path list: [`network-apis.md`](network-apis.md).

---

## 7. Shared-secret rule

```
Chat server that mints token/form
        │
        │  must share secret / signing key with
        ▼
Companion that validates (storage, CDN upload, SVR, CDSI, …)
```

| Setup | Works? |
|-------|--------|
| All Signal production hosts | Yes |
| Local chat + local companions you deploy with matching secrets | Yes |
| Local chat + Signal prod storage/CDN | **No** |
| Prod chat + your private storage | **No** (unless you somehow shared prod secrets — you don’t) |

Wipe app data when switching `useLocalSignalServer` true ↔ false.

---

## 8. Capability matrix (Android fork view)

| Feature | Primary URL | Local with chat only |
|---------|-------------|----------------------|
| 1:1 text | `SIGNAL_URL` | Works |
| Tickets + ticket text chat | `SIGNAL_URL` (`/v1/tickets`) | Works (with server ticket APIs) |
| Native Signal groups | `STORAGE_URL` + chat auth | Does not work without storage-service |
| Storage Service sync | `STORAGE_URL` | Does not work |
| Attachments / avatars | `SIGNAL_CDN*_URL` + chat forms | Does not work / partial |
| Contact discovery | CDSI | **Works (local)** via `POST /v1/directory/local/lookup` when `USE_SELF_HOSTED_SIGNAL_SERVER` |
| Calls (group media) | SFU + chat | Limited |

---

## 9. Practical guidance

### Do this for MVP

1. `useLocalSignalServer = true` and a reachable `localSignalHost` ([`LOCAL_SERVER.md`](LOCAL_SERVER.md)).  
2. Run Signal-Server test-server.  
3. Use Chats for 1:1 text and the Tickets tab for structured discussions.  
4. Do not depend on GroupsV2 or media uploads yet.

### Later: media

Wire real object storage on the server; change `SIGNAL_CDN*_URL` away from `cdn*.signal.org`.

### Later: native groups / multi-device storage sync

Deploy [storage-service](https://github.com/signalapp/storage-service); align secrets; set `STORAGE_URL` to your instance.

### Do not

- Assume `STORAGE_URL` / CDN prod hosts work with local chat.  
- Implement tickets as GroupsV2.  
- Expect replacing `STORAGE_URL` with a raw S3 bucket URL to work.

---

## 10. Decision tree

```
Self-host / local messaging?
  └─ Point SIGNAL_URL at your Signal-Server
       ├─ 1:1 text              → enough
       ├─ Ticketing             → enough (/v1/tickets on same server)
       ├─ Images / voice notes  → also replace CDN URLs + server object store
       └─ Native GroupsV2       → also deploy storage-service + STORAGE_URL
```

---

## 11. Code & config anchors

| Item | Where |
|------|--------|
| Local / prod URL toggle | `app/build.gradle.kts` (`useLocalSignalServer`, `SIGNAL_URL`, `STORAGE_URL`, `SIGNAL_CDN*_URL`) |
| Network access / Fastly fronts | `.../push/SignalServiceNetworkAccess.kt` |
| Ticket HTTP API client | `lib/libsignal-service/.../tickets/TicketApi.kt` |
| Ticket send | `app/.../tickets/send/TicketMessageSender.kt` |
| Storage / Groups paths | [`network-apis.md`](network-apis.md) §2 |
| Server companion guide | Signal-Server `docs/COMPANION_SERVICES.md` |

---

## 12. Glossary

| Term | Meaning |
|------|---------|
| **Chat / Signal-Server** | `SIGNAL_URL`; production `chat.signal.org` |
| **Storage Service** | `STORAGE_URL`; production `storage.signal.org`; sync + GroupsV2 |
| **CDN 0/2/3** | `SIGNAL_CDN*_URL`; media bytes |
| **GroupsV2** | Native Signal groups (needs storage) |
| **Ticket** | Fork feature on chat server (does not need storage) |

**Bottom line:** Replacing `chat.signal.org` with your Signal-Server gives you normal text chat and ticketing. `storage.signal.org` and `cdn*.signal.org` are optional separate systems for native groups/sync and media — not required for the ticket MVP, and not usable from local chat without your own matching deployments.
