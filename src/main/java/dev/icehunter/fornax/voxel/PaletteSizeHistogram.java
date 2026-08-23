package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.FornaxMod;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Palette-size diagnostic (2026-07-20): {@link SectionHarvester#MAX_PALETTE_ENTRIES} allocates a
 * FIXED N entries x {@code BrickGridUpload.PALETTE_ENTRY_BYTES} (64) = N*64 B PER SLOT in the
 * palette buffer, regardless of how many distinct block states a real section actually harvests. At
 * the original 256 that was 244 MiB at the default render-distance-12 window (diameter 25, 15625
 * slots) -- roughly 65% of the voxel grid's total VRAM, and the hard blocker on the 3 GB GTX 1060
 * tier. This class exists to collect REAL DATA before touching that constant: a lightweight,
 * always-on histogram of every real {@link SectionHarvester#harvest} call's palette size, dumped on
 * demand via {@code dev.icehunter.fornax.debug.FornaxDebugKeys}' palette-histogram-dump keybind. A
 * live-world census on 2026-07-20 (16000 sections) found p99 = 32 and a true max of 54 with zero
 * cap-hits, which is what took {@code MAX_PALETTE_ENTRIES} from 256 to 96 (91.6 MiB at the same
 * window, ~152 MiB reclaimed) -- see that constant's own doc for the full rationale. This diagnostic
 * stays on unconditionally afterward: its cap-hits counter is the only signal that would tell us 96
 * ever proves too tight for a denser world.
 *
 * <p>{@link #record} runs on Sodium's chunk-build worker threads -- potentially several at once,
 * one per worker, see {@code ChunkBuilderMeshingTaskMixin} -- so every counter here is lock-free and
 * allocates nothing per call: an {@link AtomicLongArray} for the histogram buckets (one {@code
 * getAndIncrement} per harvest), {@link LongAdder} for the running section/size-sum/cap-hit counts
 * (built for exactly this high-contention-increment shape, cheaper than a single shared {@code
 * AtomicLong} under many-thread contention), and an {@link AtomicLong} compare-and-swap loop for the
 * running max (a plain field read on the common "not a new max" path). No synchronized block, no
 * per-harvest allocation -- this must never be measurable next to the harvest work it instruments,
 * so it stays on unconditionally rather than needing a config gate a player has to remember to flip.
 */
public final class PaletteSizeHistogram {
    /**
     * Bucket upper bounds: a palette size lands in the first bucket whose bound is {@code >=} that
     * size. Chosen to resolve the interesting low end (most sections are near-uniform stone/air,
     * i.e. size 1-8) finely while still tracking the byte-addressable ceiling (256) exactly.
     */
    static final int[] BUCKET_UPPER_BOUNDS = {1, 2, 4, 8, 16, 32, 48, 64, 96, 128, 192, 256};

    /** {@code PALETTE_ENTRY_BYTES} lives on {@code BrickGridUpload}, not duplicated here, so the VRAM
     * math below can never silently drift from the real packed stride. Package-private on that class
     * -- readable from here because both classes share the {@code voxel} package. */
    private static final long PALETTE_ENTRY_BYTES = BrickGridUpload.PALETTE_ENTRY_BYTES;

    /** Slot count at the default render-distance-12 window (diameter {@code 2*12+1 = 25}, {@code
     * 25^3 = 15625} slots) -- the reference point the task brief and every VRAM figure in this
     * class's log output is measured against. Not read from live config: this diagnostic answers
     * "what would MAX_PALETTE_ENTRIES cost AT THE DEFAULT window", independent of whatever radius
     * the current session happens to be running. */
    private static final long REFERENCE_SLOT_COUNT = 25L * 25L * 25L;

    private static final AtomicLongArray BUCKET_COUNTS = new AtomicLongArray(BUCKET_UPPER_BOUNDS.length);
    private static final LongAdder TOTAL_SECTIONS = new LongAdder();
    private static final LongAdder SUM_PALETTE_SIZE = new LongAdder();
    private static final LongAdder CAP_HITS = new LongAdder();
    private static final AtomicLong MAX_PALETTE_SIZE = new AtomicLong();

    /** Every {@link #LOG_INTERVAL_SECTIONS}th harvest also logs the histogram automatically, so a
     * play session produces evidence in the log even if the player never touches the dump keybind. */
    private static final long LOG_INTERVAL_SECTIONS = 2000;

    private PaletteSizeHistogram() {
    }

    /**
     * Records one harvested section's real palette size. Called once per {@link
     * SectionHarvester#harvest} -- see that method's own call site for why {@code hitCap} is the
     * harvester's real overflow flag (more than {@link SectionHarvester#MAX_PALETTE_ENTRIES} distinct
     * states were present) rather than merely {@code paletteSize == MAX_PALETTE_ENTRIES}, which a
     * section with EXACTLY {@link SectionHarvester#MAX_PALETTE_ENTRIES} distinct states and no
     * overflow would also report.
     */
    public static void record(int paletteSize, boolean hitCap) {
        BUCKET_COUNTS.getAndIncrement(bucketIndexFor(paletteSize));
        TOTAL_SECTIONS.increment();
        SUM_PALETTE_SIZE.add(paletteSize);
        if (hitCap) {
            CAP_HITS.increment();
        }
        long observedMax = MAX_PALETTE_SIZE.get();
        while (paletteSize > observedMax
                && !MAX_PALETTE_SIZE.compareAndSet(observedMax, paletteSize)) {
            observedMax = MAX_PALETTE_SIZE.get();
        }

        long total = TOTAL_SECTIONS.sum();
        if (total % LOG_INTERVAL_SECTIONS == 0) {
            dumpToLog("periodic (every " + LOG_INTERVAL_SECTIONS + " sections)");
        }
    }

    /** F-key-driven on-demand dump -- see {@code FornaxDebugKeys}. */
    public static void dumpToLog() {
        dumpToLog("on-demand");
    }

    /** First bucket index whose {@link #BUCKET_UPPER_BOUNDS} entry is {@code >= paletteSize}. Pure
     * and package-visible so {@code PaletteSizeHistogramTest} exercises the real bucketing math
     * directly, with no atomic-counter state involved. Falls back to the last bucket for a
     * hypothetical out-of-range input rather than throwing -- {@link SectionHarvester#harvest} never
     * actually produces a size above {@link SectionHarvester#MAX_PALETTE_ENTRIES} (96, itself one of
     * {@link #BUCKET_UPPER_BOUNDS}'s own values, well under this array's last bound of 256 -- kept at
     * 256 rather than shrunk to match so the histogram still has headroom to notice if the cap is ever
     * raised again), but this stays defensive rather than trusting that invariant blindly. */
    static int bucketIndexFor(int paletteSize) {
        for (int i = 0; i < BUCKET_UPPER_BOUNDS.length; i++) {
            if (paletteSize <= BUCKET_UPPER_BOUNDS[i]) {
                return i;
            }
        }
        return BUCKET_UPPER_BOUNDS.length - 1;
    }

    /** Smallest bucket upper bound whose cumulative count covers at least {@code percentile} of
     * {@code total} -- i.e. "at least this fraction of sections used no more than this many
     * entries." A bucketed approximation (real p50/p95/p99 could land anywhere inside the bucket's
     * range), which is exactly what makes it cheap to maintain from fixed-size atomic counters
     * instead of a sorted per-section sample list. Package-visible + pure for direct testing. */
    static int percentileBucketUpperBound(long[] counts, long total, double percentile) {
        if (total <= 0) {
            return 0;
        }
        long target = (long) Math.ceil(percentile * total);
        long cumulative = 0;
        for (int i = 0; i < counts.length; i++) {
            cumulative += counts[i];
            if (cumulative >= target) {
                return BUCKET_UPPER_BOUNDS[i];
            }
        }
        return BUCKET_UPPER_BOUNDS[BUCKET_UPPER_BOUNDS.length - 1];
    }

    /** Bytes the palette buffer would need at {@link #REFERENCE_SLOT_COUNT} if {@code
     * candidateMaxEntries} replaced {@link SectionHarvester#MAX_PALETTE_ENTRIES} -- {@code
     * candidateMaxEntries * PALETTE_ENTRY_BYTES} per slot, times the reference slot count. Pure and
     * package-visible for direct testing (mirrors {@code BrickGridUpload.PALETTE_BYTES_PER_SLOT}'s
     * own per-slot formula, just parameterized on the candidate instead of the live constant). */
    static long vramBytesForCandidate(int candidateMaxEntries) {
        return (long) candidateMaxEntries * PALETTE_ENTRY_BYTES * REFERENCE_SLOT_COUNT;
    }

    private static void dumpToLog(String trigger) {
        long total = TOTAL_SECTIONS.sum();
        if (total == 0) {
            FornaxMod.LOGGER.info("[Fornax][palette] histogram dump ({}) -- no sections harvested yet", trigger);
            return;
        }

        long[] counts = new long[BUCKET_UPPER_BOUNDS.length];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = BUCKET_COUNTS.get(i);
        }
        long capHits = CAP_HITS.sum();
        long max = MAX_PALETTE_SIZE.get();
        double mean = SUM_PALETTE_SIZE.sum() / (double) total;
        int p50 = percentileBucketUpperBound(counts, total, 0.50);
        int p95 = percentileBucketUpperBound(counts, total, 0.95);
        int p99 = percentileBucketUpperBound(counts, total, 0.99);

        // Locale.ROOT throughout (matches ProfilerLogDump/PassTimer's own precedent): a '%.1f' under a
        // comma-decimal locale would silently corrupt this log for anyone parsing it, human or script.
        FornaxMod.LOGGER.info("[Fornax][palette] histogram dump ({}) -- {} sections sampled, max={}, "
                + "mean={}, p50<={}, p95<={}, p99<={}, cap-hits={} ({}%)", trigger, total, max,
                String.format(Locale.ROOT, "%.1f", mean), p50, p95, p99, capHits,
                String.format(Locale.ROOT, "%.2f", 100.0 * capHits / total));

        for (int i = 0; i < BUCKET_UPPER_BOUNDS.length; i++) {
            String line = String.format(Locale.ROOT, "  size<=%-3d : %6d sections (%6.2f%%)",
                    BUCKET_UPPER_BOUNDS[i], counts[i], 100.0 * counts[i] / total);
            FornaxMod.LOGGER.info("[Fornax][palette]{}", line);
        }

        double currentMib = vramBytesForCandidate(SectionHarvester.MAX_PALETTE_ENTRIES) / (1024.0 * 1024.0);
        FornaxMod.LOGGER.info("[Fornax][palette] MAX_PALETTE_ENTRIES candidates at window diameter 25 "
                + "({} slots, {} B/entry) -- current {} -> {} MiB:", REFERENCE_SLOT_COUNT, PALETTE_ENTRY_BYTES,
                SectionHarvester.MAX_PALETTE_ENTRIES, String.format(Locale.ROOT, "%.1f", currentMib));

        for (int candidate : BUCKET_UPPER_BOUNDS) {
            double candidateMib = vramBytesForCandidate(candidate) / (1024.0 * 1024.0);
            double deltaMib = candidateMib - currentMib;
            String line = String.format(Locale.ROOT, "  %-3d entries -> %7.1f MiB (%s%.1f MiB)",
                    candidate, candidateMib, deltaMib >= 0 ? "+" : "-", Math.abs(deltaMib));
            FornaxMod.LOGGER.info("[Fornax][palette]{}", line);
        }
    }
}
