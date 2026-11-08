# IRIS watermark robustness benchmark

Measured locally on 8 November 2026. The new V3 pipeline recovered **76/80 conditions (95%)**, versus **59/80 (73.75%)** for the frozen pre-upgrade V2 pipeline. V3 produced lower pixel distortion on every fixture. Both pipelines rejected all 16 negative checks each. Four V3 conditions still failed, including two regressions listed below.

## What changed

- A new robust payload uses rate-1/2 convolutional coding, constraint length 7, generators (171, 133) in octal, six terminating zero bits, soft Viterbi decoding, and CRC-32 validation.
- Whitened coded bits repeat independently on 8×8 and 16×16 grids. Both channels are tested independently. Texture adaptation and treating strong natural edges as uncertain reduce large pixel changes.
- Geometry recovery handles cardinal rotations and mirroring. Reference-guided alignment estimates orientation and small rotation using at most two nearby originals; an actual decoded registry key is still mandatory.
- Keys use 12 bytes from SecureRandom. Existing V1 and V2 copies remain readable. Re-sign upgrades V2/V3 records without changing their key. V1's shorter key requires registering its original again; the API returns an explicit upgrade message.
- The UI separates “Hidden key recovered”, “Exact file identified”, and “Possible visual match”. It no longer calls a visually inferred key a recovered key.

## Results

| Metric | Frozen V2 baseline | V3 |
|---|---:|---:|
| Recovered conditions | 59/80 | 76/80 |
| Accepted unsigned/wrong-key negatives | 0/16 | 0/16 |
| Mean signing PSNR, higher is less distortion | 30.43 dB | 31.85 dB |
| Lowest fixture PSNR | 27.50 dB | 29.19 dB |
| Median recovery time | 7.9 ms | 8.7 ms |
| 95th percentile recovery time | 905.8 ms | 1512.7 ms |

Core recovery timing covers native decoding, bounded geometry search and (for V3) reference alignment. It excludes upload handling and database writes. It is a single local run, not a production load test. Hard failures take longer in V3 because of additional decoding/alignment work.

| Fixture | V2 recovery | V3 recovery | V2 PSNR dB | V3 PSNR dB |
|---|---:|---:|---:|---:|
| astronaut | 8/10 | 9/10 | 28.16 | 30.96 |
| camera | 7/10 | 10/10 | 30.43 | 31.12 |
| coffee | 8/10 | 10/10 | 29.05 | 30.28 |
| chelsea | 6/10 | 9/10 | 28.36 | 29.19 |
| rocket | 8/10 | 10/10 | 32.03 | 32.60 |
| brick | 7/10 | 9/10 | 27.50 | 30.41 |
| smooth | 8/10 | 10/10 | 36.78 | 37.23 |
| screenshot | 7/10 | 9/10 | 31.18 | 33.04 |

Six real fixtures cover a portrait, a grayscale outdoor photo, coffee/wood texture, a cat, a launch photo and a brick texture. Rocket and brick were held out while choosing signal settings. Two fixtures are generated: a smooth gradient and a text-heavy screenshot. Fixture attribution and SHA-256 checksums are bundled with the repository.

The ten conditions per image are: unchanged control; JPEG quality 0.35; JPEG quality 0.10; half-size resize; an off-grid crop retaining approximately 56% of the area; that crop followed by half-size resize and JPEG 0.35; 90° rotation; 5° rotation; 3×3 box blur; and painting over approximately 11% of the image. JPEG quality is the Java ImageIO encoder setting, not a percentage of file size. Each version also checks the unsigned source and an image carrying a different key. No perceptual-hash match counts as successful watermark recovery.

## Remaining failures and regressions

V3 failures:

- astronaut: `crop-half-jpeg`.
- chelsea: `crop-half-jpeg`.
- brick: `jpeg10`.
- screenshot: `crop-half-jpeg`.

Two cases regressed compared with V2:

- astronaut: `crop-half-jpeg` (V2 recovered the key; V3 did not).
- brick: `jpeg10` (V2 recovered the key; V3 did not).

This is a net improvement across this finite dataset, not proof that every edit or image type is supported. The benchmark uses one fixed payload for reproducibility; separate unit tests cover random payloads and five additional embedded keys. Zero false acceptances in 16 checks does not establish a statistical false-acceptance rate. Perspective warping, print/camera capture, AI repainting and large arbitrary rotations are not covered.

Quality is lower distortion than V2 on all fixtures, but faint banding remains visible in some 2× inspection crops. PSNR does not prove perceptual invisibility. The supplied quality comparison shows original, V2 and V3 crops side by side.

## Validation and reproduction

- 52 backend tests cover the code, independent channels, real-photo recovery, a fine-only small image, randomized payload/key checks, legacy compatibility and API behavior.
- Frontend production build passes. Lint finishes with one existing `react(set-state-in-effect)` warning in App.tsx.
- Backend tests now explicitly run headless and use Mockito's subclass mock maker, so no temporary test setup or JVM attachment is needed.

From the backend directory:

```bash
./mvnw test
java -Djava.awt.headless=true -cp target/classes:target/test-classes \
  com.iris.WatermarkBenchmark target/watermark-benchmark.csv
```

`BenchmarkBaselineRecovery` freezes the prior geometry search; it is only test code. The benchmark compares the prior V2 stack with the V3 stack, so gains include geometry alignment as well as the new watermark. All fixtures are offline. Exact screenshot pixels and timings can vary by platform and font.

Raw measurements are in `watermark-benchmark.csv`. Image sources and licenses: https://scikit-image.org/docs/stable/api/skimage.data.html
