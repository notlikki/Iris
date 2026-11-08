# IRIS — Steganographic Image Signing & Recovery

![Java](https://img.shields.io/badge/Java-21%2B-orange)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.3-6DB33F)
![React](https://img.shields.io/badge/React-19-61DAFB)
![Node](https://img.shields.io/badge/Node-20.19%2B-339933)
![Tests](https://img.shields.io/badge/tests-52_passing-brightgreen)

IRIS embeds an invisible, registry-authenticated key across an image and keeps the untouched original. Re-upload a tampered, cropped, or re-compressed copy and IRIS reads the key, finds the record, restores the exact original, and reports what changed.

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
| **LSB** | Key + CRC-32 in the low bit of every RGB channel, repeated at 5 spread offsets | Lossless re-saves (PNG/BMP), local edits outside the payload windows | JPEG, resizing, filters |
| **Robust V3** | Rate-1/2 convolutional encoding with soft Viterbi decoding, whitened bits, and independent repetitions on 8×8 and 16×16 grids. Texture adaptation and edge erasures limit distortion. Crop/scale search and reference-guided orientation correction precede key lookup | Tested real photos, compression, resizing, cropping, blur, painting, cardinal rotations and small rotations; see measured failures below | Severe information loss, some combined crop/resize/JPEG cases, perspective changes, and transformations outside the bounded search |
| **pHash** | 64-bit perceptual hash stored in four cardinal orientations, compared by minimum Hamming distance | Resize, 90/180/270° rotation, colour changes, re-encoding | Different images |

New V3 copies carry a 96-bit key generated with `SecureRandom`, plus a CRC-32 inside the error-corrected payload. A recovered key must exist in the registry. V2 copies and legacy V1 copies (64-bit key + 32-bit HMAC) remain readable. Registry lookup identifies a record; it is not a public-key ownership signature.

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
Tests run: 40, Failures: 0, Errors: 0, Skipped: 0
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

Select a V2/V3 registered image and press **Re-sign** to regenerate its signed copy with V3. The 96-bit key and record stay the same. Previously downloaded files do not change. For a legacy V1 record, register its stored original again to obtain a new 96-bit key; re-sign returns an explicit upgrade message instead of silently breaking old-key verification.

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
| `iris.signing.secret` | `iris-local-dev-secret-change-me` | `IRIS_SIGNING_SECRET` | Secret used to validate legacy v1 HMACs |
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
│       └── test/java/com/iris/  40 tests
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

### What the system guarantees

- A recovered watermark is accepted only when its key exists in the registry; legacy v1 copies also validate their HMAC. A perceptual match is never promoted to an authenticated match.
- Exact originals are matched by SHA-256 checksum before any approximate fallback.
- Existing V2/V3 records can be upgraded with **Re-sign** while preserving their 96-bit key. V1 remains verifiable; upgrading it requires a new registration.
- The suite runs 52 tests covering error correction, independent watermark channels, real-photo edits, reference alignment, legacy compatibility, storage and API behavior. The offline benchmark compares V3 with a frozen pre-upgrade baseline across six real images and two generated fixtures. Measured recovery, quality, latency, false acceptances and remaining regressions are recorded in [the benchmark report](backend/docs/watermark-benchmark.md).

### Inherent limits

- No invisible watermark can be guaranteed to survive arbitrary editing. Removing most pixels, extreme downsampling, heavy blur, or repainting can destroy its information; visual matching is a separate, approximate fallback.
- Reference-guided alignment estimates cardinal orientation, mirroring and a small rotation (a coarse ±12° scan with bounded refinement) using at most two nearby stored originals. Correlation only guides correction: key decoding and registry lookup must still succeed. Perspective and arbitrary crop registration are not implemented.
- Geometry recovery is a finite search: registered dimensions scaled by crop fractions (1, 7/8, 3/4, 5/8, 1/2, 3/8, 1/4), then common inverse scales of the candidate. It is capped at 48 attempts and 160 million normalized pixels (16 million per attempt, 4096 px per side), with records ordered by perceptual closeness. Transforms outside those fractions, or a very large registry, can still miss.
- V3 uses a terminated constraint-length-7 convolutional code with generators (171, 133) in octal, soft Viterbi decoding and CRC-32 validation. CRC-syndrome repair is retained for older formats. Neither mechanism guarantees recovery after arbitrary corruption.
- The LSB layer survives only lossless formats. Re-saving as JPEG drops it and relies on the robust layer.
- Recovery restores the stored original bytes. It does not regenerate pixels that were never stored, so the database and storage are the source of truth.
- The watermark is designed to be invisible; very smooth areas can show faint texture under close inspection.

## Security notes

- Uploads are validated by magic bytes and decoded headers; extensions and client MIME types are never trusted.
- Filenames never become filesystem paths. Server-generated UUID keys plus normalized path checks prevent traversal.
- SHA-256 is computed for both the original and the signed file.
- API errors are sanitized; stack traces, SQL, and storage paths are never returned.
- Security headers (`nosniff`, `DENY`, `Referrer-Policy`, `Cache-Control`) are injected on every response.
- The audit log records operations without recording file contents or storage paths.


## Reproduce the robustness benchmark

Fixtures are bundled under `backend/src/test/resources/watermark` with attribution and checksums; tests do not download data.

```bash
cd backend
./mvnw test
java -Djava.awt.headless=true -cp target/classes:target/test-classes \
  com.iris.WatermarkBenchmark target/watermark-benchmark.csv
```

The benchmark runs ten conditions per fixture, including an unchanged control, and two negative checks. It reports actual key recovery rather than perceptual matching. V2 uses the frozen pre-upgrade geometry search; V3 includes the new alignment and error-corrected channels. Timing is local, single-run timing rather than a production load test. The screenshot fixture uses the platform's logical monospace font, so its exact pixels can vary across systems.
