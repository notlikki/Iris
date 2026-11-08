# IRIS — Steganographic Image Signing & Recovery

![Java](https://img.shields.io/badge/Java-21%2B-orange)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.3-6DB33F)
![React](https://img.shields.io/badge/React-19-61DAFB)
![Node](https://img.shields.io/badge/Node-20.19%2B-339933)
![Tests](https://img.shields.io/badge/tests-36_passing-brightgreen)

IRIS embeds an invisible, HMAC-bound key across an image and keeps the untouched original. Re-upload a tampered, cropped, or re-compressed copy and IRIS reads the key, finds the record, restores the exact original, and reports what changed.

> **Repository access.** This repository is private. To let anyone clone it, make it public under **Settings → General → Danger Zone → Change visibility**, or invite collaborators. The commands below assume you already have access.

---

## Contents

- [What it does](#what-it-does)
- [Detection layers](#detection-layers)
- [Quick start](#quick-start-terminal-only)
- [Development mode](#development-mode)
- [Usage](#usage)
- [API](#api)
- [Configuration](#configuration)
- [Project layout](#project-layout)
- [Troubleshooting](#troubleshooting)
- [Design limits](#design-limits)
- [Security notes](#security-notes)

---

## What it does

1. **Sign** — upload an image. IRIS generates a random key, embeds it invisibly in the pixels, and stores the untouched original with its SHA-256 checksums and perceptual hashes.
2. **Verify** — upload a copy. IRIS tries the exact key, then the robust watermark, then perceptual matching, in that order.
3. **Recover** — on a match, download the stored original bytes and inspect a tamper report: match method, perceptual distance, changed-pixel percentage, mean difference, dimension status, and a difference map.
4. **Audit** — every sign, verify, view, download, and delete is recorded without file paths or image contents.

The original image is never modified.

## Detection layers

| Layer | Technique | Survives | Fails on |
|---|---|---|---|
| **LSB** | Token + MAC + CRC-32 in the low bit of every RGB channel, repeated at 5 spread offsets | Lossless re-saves (PNG/BMP), local edits outside the payload windows | JPEG, resizing, filters |
| **Robust** | Stronger texture-weighted sine watermark in each 16×16 block; clipped soft votes, multiple sync candidates, bounded CRC-syndrome error repair, crop-phase search, and bounded resize normalization. Validates marker, CRC-32 and HMAC | Tested JPEG conversion/compression, tone changes, crops, resizing, and combined edits described below | Severe information loss, unsearched transforms, heavy rotation, filtering |
| **pHash** | 64-bit perceptual hash stored in four cardinal orientations, compared by minimum Hamming distance | Resize, 90/180/270° rotation, colour changes, re-encoding | Different images |

The key is 64 bits and the embedded authentication tag is a truncated HMAC-SHA-256. Only records whose key validates against the server secret are accepted.

---

## Quick start (terminal only)

Everything below runs from a terminal. You do not need to download a ZIP.

### 1. Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| Git | any recent | cloning the repository |
| JDK | **21 or newer** | backend |
| Node.js + npm | **20.19+ or 22.12+** (Vite 8 requirement) | frontend |
| Maven | not required | the repository includes `./mvnw` |

Install examples:

**macOS (Homebrew)**

```bash
brew install git node
brew install --cask temurin@21
```

**Ubuntu / Debian**

```bash
sudo apt update
sudo apt install -y git openjdk-21-jdk nodejs npm
```

If your distro ships Node older than 20.19, install Node 22 instead:

```bash
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash
# restart the terminal, then:
nvm install 22
```

**Windows (PowerShell, winget)**

```powershell
winget install Git.Git
winget install EclipseAdoptium.Temurin.21.JDK
winget install OpenJS.NodeJS.LTS
```

Verify the versions before continuing:

```bash
java -version      # 21 or newer
node -v            # v20.19+ or v22.12+
npm -v
git --version
```

### 2. Clone

```bash
git clone https://github.com/notlikki/iris.git
cd iris
```

Already have the source? Skip to step 3.

### 3. Build the frontend and start the app

IRIS runs as a single local server: Spring Boot serves both the API and the built React app.

```bash
cd frontend
npm ci
npm run build

cd ../backend
./mvnw spring-boot:run
```

On Windows, use `mvnw.cmd spring-boot:run`.

The first `./mvnw` run downloads Maven 3.10 into your user cache. Subsequent runs are instant.

### 4. Open the app

Go to **http://localhost:8080**.

The database and uploads are created on first run under `backend/data/`.

### 5. Run the tests

```bash
cd backend
./mvnw test
```

Expected result:

```text
Tests run: 36, Failures: 0, Errors: 0, Skipped: 0
```

Frontend checks:

```bash
cd frontend
npm run lint
npm run build
```

---

## Development mode

For hot reload, run the two servers separately.

**Terminal 1 — backend**

```bash
cd backend
./mvnw spring-boot:run
```

**Terminal 2 — frontend**

```bash
cd frontend
npm ci
npm run dev
```

Open **http://localhost:5173**. Vite proxies `/api` to port 8080.

---

## Usage

**Sign an image**

1. Pick **Sign** in the left panel.
2. Drop a JPEG, PNG, or BMP (up to 15 MB) and press **Sign image**.
3. The signed copy, embedded key, checksums, and protection layers appear on the right. Click **Restore original** to download the untouched original.

**Upgrade an older signed copy**

Select a registered image and press **Re-sign**. IRIS re-embeds the stored original with the current watermark strength and replaces the signed file; the key and record stay the same. Do this for downloads signed before the current embedding was introduced.

**Verify and recover**

1. Switch to **Verify**.
2. Upload a possibly tampered copy and press **Verify and recover**.
3. If a record matches, inspect the comparison slider and the difference map, then download the original.
4. The match method tells you how it was identified:

| Method | Meaning |
|---|---|
| `LSB` | Exact key read from a lossless copy |
| `SPREAD` | Authenticated robust key recovered, including after bounded crop/resize normalization |
| `PHASH` | Watermark unreadable; matched by perceptual hash (rotation, resize) |
| `NONE` | No record matched |

**Command line equivalents**

```bash
# Sign
curl -F "file=@photo.png" http://localhost:8080/api/records

# Verify a tampered copy
curl -F "file=@tampered.jpg" http://localhost:8080/api/verify
```

---

## API

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/records` | Sign an image (`multipart/form-data`, field `file`) |
| `GET` | `/api/records` | List registered images, newest first |
| `GET` | `/api/records/{id}` | Record metadata and embedded-key info |
| `GET` | `/api/records/{id}/original` | Stream the untouched original |
| `GET` | `/api/records/{id}/signed` | Stream the signed image |
| `GET` | `/api/records/{id}/download` | Download the original as `restored_<name>` |
| `DELETE` | `/api/records/{id}` | Delete the record, its files, and linked verifications |
| `POST` | `/api/records/{id}/resign` | Re-embed the stored original with the current watermark strength |
| `POST` | `/api/verify` | Verify an uploaded image and recover its record |
| `GET` | `/api/verifications/{id}/candidate` | Stream the uploaded candidate |
| `GET` | `/api/verifications/{id}/diff` | Stream the difference map |
| `GET` | `/api/audit-logs` | Recent sanitized audit events |

`POST /api/verify` returns `matched`, `method`, the matched record, perceptual distance, similarity, changed-pixel metrics, and URLs for the candidate, difference map, and restore download.

---

## Configuration

| Property | Default | Environment variable | Description |
|---|---|---|---|
| `iris.storage.upload-dir` | `data/storage` | `IRIS_STORAGE_DIR` | Originals, signed files, verification artifacts |
| `iris.storage.max-width` | `4096` | — | Maximum image width |
| `iris.storage.max-height` | `4096` | — | Maximum image height |
| `iris.storage.max-pixels` | `16000000` | — | Maximum total pixels |
| `iris.signing.secret` | `iris-local-dev-secret-change-me` | `IRIS_SIGNING_SECRET` | Secret used for the embedded HMAC |
| `spring.datasource.url` | `jdbc:h2:file:./data/irisdb` | `IRIS_DB_PATH` | File-backed H2 database |
| `iris.frontend-dir` | `../frontend/dist` | `IRIS_FRONTEND_DIR` | Built frontend served by Spring Boot |
| `spring.servlet.multipart.max-file-size` | `15MB` | — | Maximum upload size |

Example overrides:

```bash
export IRIS_SIGNING_SECRET="$(openssl rand -hex 32)"
export IRIS_DB_PATH="./data/irisdb"
export IRIS_STORAGE_DIR="./data/storage"
```

Records, verifications, and audit events persist in the file database at `backend/data/irisdb`. Changing the signing secret after records exist stops their embedded MACs from validating; perceptual matching still identifies them.

---

## Project layout

```text
iris/
├── backend/
│   ├── mvnw, mvnw.cmd           Maven wrapper (no local Maven needed)
│   ├── .mvn/                    Wrapper configuration
│   ├── pom.xml                  Spring Boot 3.3 / Java 21
│   └── src/
│       ├── main/java/com/iris/
│       │   ├── stego/           Steganography, WatermarkCrypto,
│       │   │                    PerceptualHash, ImageCodec
│       │   ├── controller/      RecordController, VerificationController
│       │   ├── service/         ImageRecordService, VerificationService,
│       │   │                    FileStorageService, ImageValidator, AuditService
│       │   ├── model/           ImageRecord, Verification, AuditEvent
│       │   ├── repository/      Spring Data JPA repositories
│       │   ├── dto/             API transfer objects
│       │   └── config/          Storage, CORS, security headers
│       ├── main/resources/      application.yml
│       └── test/java/com/iris/  36 tests
└── frontend/
    ├── package.json
    ├── vite.config.ts           dev proxy for /api
    └── src/
        ├── App.tsx              sign/verify dashboard
        ├── components/          ActionPanel, RecordList, RecordViewer,
        │                        VerificationViewer, ImageCompareSlider
        └── types.ts
```

---

## Troubleshooting

| Problem | Fix |
|---|---|
| `java -version` is below 21 | Install JDK 21+ and point `JAVA_HOME` at it |
| `You need Node 20.19+ or 22.12+` | Upgrade Node with `nvm install 22` or nodejs.org |
| `./mvnw: Permission denied` | `chmod +x mvnw` |
| Port 8080 already in use | `lsof -i :8080` then kill the PID, or run `./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8081` |
| Blank page at `localhost:8080` | Run `cd frontend && npm run build` before starting the backend |
| `npm ci` fails on a stale install | `rm -rf node_modules && npm ci` |
| H2 says the database is locked | Only one backend can use the file DB; stop the other instance |
| `git clone` returns 404/403 | The repository is private; make it public or accept a collaborator invite |
| First backend run is slow | `./mvnw` is downloading Maven and dependencies |

---

## Design limits

These are inherent constraints, not missing features:

- No invisible watermark can be guaranteed to survive arbitrary editing. Removing most pixels, extreme downsampling, heavy blur, or repainting can destroy its information. Visual matching remains a separate, approximate fallback.
- Geometry recovery is a finite search: registered dimensions scaled by crop fractions (1, 7/8, 3/4, 5/8, 1/2, 3/8, 1/4), followed by common inverse scales of the candidate. It is capped at 48 attempts and 160 million normalized pixels (16 million per attempt, 4096 px per side), with records ordered by perceptual closeness. A transform outside those fractions, or a very large registry, can still miss.
- Older signed copies can be upgraded in place with **Re-sign**, which re-embeds the stored original at the current strength without changing the record or key.
- Automated synthetic-image tests recover the actual token and MAC after 50% and 70% resizing, 150% enlargement, PNG/BMP/JPEG conversion, a crop followed by 50% resizing and JPEG conversion, a 5/8 crop followed by 50% resizing, and an opaque local edit followed by JPEG quality 0.35 compression. These are reproducible test cases, not universal survival guarantees. Quality is checked with a minimum 30 dB PSNR on a representative synthetic image; visual acceptability still depends on content.
- Error repair now combines CRC-syndrome subsets: up to four corrupted payload bits among the 64 weakest, or up to six among the 28 weakest, plus known-marker restoration. These are conditional search limits, not guaranteed correction for arbitrary errors. This decoder improvement also applies to existing signed copies without changing their embedded format.
- Additional stress tests run six attacks across three synthetic fixtures: JPEG quality 0.10 and 0.05, quarter-size resizing, a 3×3 box blur, a crop retaining 40% of the area, and crop → half-size resize → JPEG quality 0.10. Unsigned versions of the same attacks must not acquire an authenticated payload. The previously failing combined attack is now covered by regression assertions.
- The resized search accepts a result only when its HMAC is valid and its token exists in the registry. It does not promote a perceptual match to an authenticated watermark match.
- Recovery restores the stored original bytes. It does not regenerate pixels that were never stored, so the database and storage are the source of truth.
- The embedded authentication tag is a truncated HMAC (32 bits) over a 64-bit key. It binds a demonstration record against forgery but is not a full-strength cryptographic signature.
- The LSB layer survives only lossless formats. Re-saving as JPEG drops it and relies on the robust layer.
- The watermark is designed to be invisible; very smooth areas can show faint texture under close inspection.

## Security notes

- Uploads are validated by magic bytes and decoded headers; extensions and client MIME types are never trusted.
- Filenames never become filesystem paths. Server-generated UUID keys plus normalized path checks prevent traversal.
- SHA-256 is computed for both the original and the signed file.
- API errors are sanitized; stack traces, SQL, and storage paths are never returned.
- Security headers (`nosniff`, `DENY`, `Referrer-Policy`, `Cache-Control`) are injected on every response.
- The audit log records operations without recording file contents or storage paths.
