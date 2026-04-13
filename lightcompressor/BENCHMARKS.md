# HLS Performance Benchmarks

Benchmark results for HLS encoding pipeline optimizations.

## Test Configuration

- **Device**: Pixel 9a (Android 16)
- **Codec**: H.265 (HEVC)
- **Test file**: PXL_20260408_160302893.mp4 (Pixel 4K, 36s)
- **Segment duration**: 6 seconds
- **Renditions**: 360p, 540p, 720p, 1080p, 4K

---

## Baseline (2026-04-13)

Before optimizations. Commit: `0567949` (master)

### Per-rendition totals

| Rendition | videoFrameCopy | audioSampleCopy | segmentWrite |
|-----------|----------------|-----------------|--------------|
| 720p (720x1280) | 80ms | 1150ms | 303ms |
| 1080p (1080x1920) | 138ms | 979ms | 411ms |
| 4K (2160x3840) | 225ms | 1385ms | 872ms |

### Per-segment breakdown (avg for full segments)

| Rendition | annexB | moofMeasure | moofWrite | mdat |
|-----------|--------|-------------|-----------|------|
| 720p | ~32ms | ~0.9ms | ~0.8ms | ~8ms |
| 1080p | ~45ms | ~0.6ms | ~0.6ms | ~9ms |
| 4K | ~91ms | ~0.7ms | ~0.5ms | ~31ms |

---

## Optimization #1: Eliminate double moof + Optimize annexB (2026-04-13)

Branch: `feature/hls-encoding-performance`

### Changes

1. **Eliminate double moof write** — Calculate moof box size analytically instead of serializing twice to measure
2. **Optimize annexB conversion** — Single-pass boundary scan, single allocation, direct copy (avoids intermediate List<ByteArray> and ByteArrayOutputStream)

### Per-rendition totals

| Rendition | videoFrameCopy | audioSampleCopy | segmentWrite |
|-----------|----------------|-----------------|--------------|
| 360p (360x640) | 42ms | 1484ms | 124ms |
| 540p (540x960) | 68ms | 1435ms | 196ms |
| 720p (720x1280) | 81ms | 1268ms | 267ms |
| 1080p (1080x1920) | 122ms | 1215ms | 384ms |
| 4K (2160x3840) | 210ms | 1149ms | 800ms |

### Per-segment breakdown (avg for full segments)

| Rendition | annexB | moofWrite | mdat |
|-----------|--------|-----------|------|
| 720p | ~27ms | ~1.1ms | ~7ms |
| 1080p | ~42ms | ~0.8ms | ~10ms |
| 4K | ~82ms | ~0.5ms | ~32ms |

### Improvement vs Baseline

| Metric | 720p | 1080p | 4K |
|--------|------|-------|-----|
| annexB | -16% | -7% | -10% |
| moof (measure+write) | -35% | -33% | -58% |
| segmentWrite total | -12% | -7% | -8% |

---

## Future Optimization Candidates

- **audioSampleCopy** (~1200-1500ms) — Dominated by `MediaExtractor.readSampleData()` JNI calls, not allocation
- **videoFrameCopy** (~80-210ms) — ByteArray allocation required for sample ownership
- **BoxWriter buffer pooling** — Many small ByteArrayOutputStream allocations for nested boxes
