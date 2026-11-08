# IRIS — Steganographic Image Signing & Recovery

IRIS invisibly signs an image with a key, keeps the untouched original, and later recovers that original from a tampered re-upload. The key is embedded in the pixels themselves, so the image carries its own pointer back to the database record.

> [!IMPORTANT]
> **Single-user demonstration.** There are no accounts, passwords, or sessions. Anyone with network access to the running service can sign, verify, download, and delete images. Do not expose it directly to the public internet.

---

## How it works

Every upload is validated by magic bytes, decoded, dimension-checked, and then signed with a random 128-bit token.

The token is embedded in three layers, tried in order on verification:

| Layer | Technique | Survives | Fails on |
|---|---|---|---|
| **LSB** | Token + CRC written into the low bit of every RGB channel, repeated at 5 spread offsets | Lossless re-saves (PNG/BMP), local edits outside the payload windows | JPEG, resizing, filters |
| **Robust** | Texture-weighted sine watermark: each 16×16 block carries one token bit as the luminance difference between its two halves; tiled across every block and recovered by majority vote | JPEG re-compression, brightness/contrast shifts, mild noise | Heavy crop, rotation, resize |
| **pHash** | 64-bit perceptual hash of the stored original, compared by Hamming distance | Resize, crop, colour changes, re-encoding | Different images |

If the watermark is unreadable, the perceptual hash is the last resort. On a match, the original stored bytes are returned exactly, alongside a tamper report: perceptual distance, mean pixel difference, changed-pixel percentage, dimension status, and a difference map.

The layers are deliberately layered: exact identification when the file is intact, key recovery when it has been edited, and similarity matching when the key is destroyed. The original is never modified.

---

## Architecture

```
backend/                       Spring Boot 3 / Java 21
├── src/main/java/com/iris/
│   ├── stego/
│   │   ├── Steganography      LSB + robust embedding/extraction
│   │   ├── PerceptualHash     DCT-based 64-bit pHash
│   │   └── ImageCodec         PNG encode/decode
│   ├── controller/            RecordController, VerificationController
│   ├── dto/                   ImageRecordDto, VerificationResponseDto, AuditEventDto
│   ├── exception/             IrisException and sanitized global handler
│   ├── model/                 ImageRecord, Verification, AuditEvent
│   ├── repository/            Spring Data JPA repositories
│   └── service/               ImageRecordService, VerificationService,
│                              FileStorageService, ImageValidator, AuditService
└── src/test/java/com/iris/    Steganography, storage, and integration tests

frontend/                      React 19 + TypeScript + Vite
├── src/components/            ActionPanel, RecordList, RecordViewer,
│                              VerificationViewer, ImageCompareSlider, icons
├── src/types.ts               Contracts mirroring the backend DTOs
└── src/App.tsx                Sign/verify dashboard
```

---

## API

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/records` | Sign an image and create a record (`multipart/form-data`, field `file`) |
| `GET` | `/api/records` | List registered images, newest first |
| `GET` | `/api/records/{id}` | Record metadata and embedded-key info |
| `GET` | `/api/records/{id}/original` | Stream the untouched original |
| `GET` | `/api/records/{id}/signed` | Stream the signed (watermarked) image |
| `GET` | `/api/records/{id}/download` | Download the original as `restored_<name>` |
| `DELETE` | `/api/records/{id}` | Delete the record, its files, and linked verifications |
| `POST` | `/api/verify` | Verify an uploaded image and recover its record |
| `GET` | `/api/verifications/{id}/candidate` | Stream the uploaded candidate |
| `GET` | `/api/verifications/{id}/diff` | Stream the difference map |
| `GET` | `/api/audit-logs` | Recent sanitized audit events |

`POST /api/verify` returns `matched`, `method` (`LSB` / `SPREAD` / `PHASH` / `NONE`), the matched record, perceptual distance, similarity, changed-pixel metrics, and URLs for the candidate, difference map, and restore download.

---

## Configuration

| Property | Default | Environment variable | Description |
|---|---|---|---|
| `iris.storage.upload-dir` | `data/storage` | `IRIS_STORAGE_DIR` | Where originals, signed files, and verification artifacts live |
| `iris.storage.max-width` | `4096` | — | Maximum image width |
| `iris.storage.max-height` | `4096` | — | Maximum image height |
| `iris.storage.max-pixels` | `16000000` | — | Maximum total pixels |
| `iris.frontend-dir` | `../frontend/dist` | `IRIS_FRONTEND_DIR` | Built frontend served by Spring Boot |
| `spring.servlet.multipart.max-file-size` | `15MB` | — | Maximum upload size |

The database is in-memory H2 (`irisdb`), so records and audit events reset when the backend restarts. Stored image files remain on disk.

---

## Run locally

### Single origin (production build)

```bash
cd frontend
npm install
npm run build

cd ../backend
mvn spring-boot:run
```

Open **http://localhost:8080**. Spring Boot serves both the API and the built React app.

### Development (hot reload)

```bash
# terminal 1
cd backend && mvn spring-boot:run

# terminal 2
cd frontend && npm install && npm run dev
```

Open **http://localhost:5173**; Vite proxies `/api` to port 8080.

### Tests

```bash
cd backend
mvn test
```

The suite covers LSB round-trips, robust recovery after JPEG and tone changes, pHash stability, storage/path safety, and the register → verify → recover flow end to end.

---

## Security notes

- Uploads are validated by magic bytes and decoded headers; extensions and client MIME types are never trusted.
- Filenames are never used as filesystem paths. Server-generated UUID keys plus normalized path checks prevent traversal.
- SHA-256 is computed for both the original and the signed file.
- API errors are sanitized; stack traces, SQL, and storage paths are never returned.
- Security headers (`nosniff`, `DENY`, `Referrer-Policy`, `Cache-Control`) are injected on every response.
- The audit log records operations without recording file contents or storage paths.

## Honest limitations

- The robust layer is watermarking, not cryptography. It is designed for accidental edits and ordinary re-encoding, not a determined attacker. Anyone with the original and enough effort can attempt to strip it.
- Crops, rotations, and large resizes can destroy both watermarks; those cases rely on the perceptual-hash fallback and are best-effort.
- Recovery restores the stored original bytes. It does not regenerate pixels that were never stored.
- The LSB layer only survives lossless formats. Re-uploading the signed PNG is exact; re-saving it as JPEG relies on the robust layer.
