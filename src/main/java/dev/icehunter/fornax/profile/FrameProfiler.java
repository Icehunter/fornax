package dev.icehunter.fornax.profile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-JVM rolling-stats aggregator for per-label millisecond timings: no blaze3d imports, so it's
 * unit-testable without a GPU device. {@link PassTimer} supplies graphics-encoder brackets and
 * {@link ComputePassTimer} supplies raw compute-queue dispatch durations, both converted to ms and
 * handed to {@link #record}; nothing here assumes GPU timestamps specifically, so any millisecond
 * duration source can feed it.
 *
 * <p>Each label keeps its own last-{@link #WINDOW}-samples ring; {@link #snapshot()} reports them in
 * first-seen label order so a HUD (a later task) renders a stable row order frame to frame.
 */
public final class FrameProfiler {
    public static final int WINDOW = 240;
    // A hard cap, so a pack that reuses one label over and over cannot grow this without limit.
    public static final int MAX_GPU_TIMINGS_PER_FRAME = 256;

    public static final String LABEL_FRAME = "frame";
    public static final String LABEL_GRAPH = "graph graphics";
    // Named for what the bracket actually measures: Sodium's terrain draws complete BEFORE
    // GraphRunner.finish() ever runs, so the graph's geometry slot contributes only GraphRunner's
    // own dwell there -- a "terrain" row would misattribute real terrain GPU cost to this engine.
    public static final String LABEL_TERRAIN = "geometry dwell";

    public record Stat(String label, double avgMs, double p95Ms, int samples) {
    }

    /** One measured start and end time in the named timer group. Two groups can share a real GPU
     * queue and their clocks are not lined up, so do not compare times across groups. */
    public record GpuTiming(String label, long frameId, String queue, long beginTicks, long endTicks,
                            double timestampPeriodNs, int timestampValidBits, double elapsedMs) {
    }

    private static final Comparator<GpuTiming> GPU_TIMING_ORDER = Comparator
            .comparingLong(GpuTiming::frameId).thenComparing(GpuTiming::label)
            .thenComparing(GpuTiming::queue).thenComparingLong(GpuTiming::beginTicks)
            .thenComparingLong(GpuTiming::endTicks).thenComparingDouble(GpuTiming::timestampPeriodNs)
            .thenComparingInt(GpuTiming::timestampValidBits).thenComparingDouble(GpuTiming::elapsedMs);

    /** One generic per-frame VALUE (not a timing) -- e.g. a queue depth or a population fraction. See
     * {@link #recordValue}/{@link #valueSnapshot}. */
    public record ValueStat(String label, double value) {
    }

    private final Map<String, Deque<Double>> samples = new LinkedHashMap<>();
    // Deliberately separate from `samples`: a VALUE (a point-in-time count/fraction, e.g. a queue
    // depth) has no meaningful rolling avg/p95 the way a millisecond timing does -- {@link #record}'s
    // ring-buffer averaging would just smear a real "24 pending right now" into a misleading trailing
    // mean. Latest-value-wins is the correct semantics here, so this is its own minimal map rather
    // than overloading `samples` with a window size of 1.
    private final Map<String, Double> values = new LinkedHashMap<>();
    private double lastFrameMs;
    private final GpuTiming[][] gpuTimings = new GpuTiming[WINDOW][MAX_GPU_TIMINGS_PER_FRAME];
    private final int[] gpuTimingCounts = new int[WINDOW];
    private final long[] gpuFrameIds = new long[WINDOW];
    private long lastIssuedRenderFrameId;
    private long currentRenderFrameId;
    private long gpuTimelineDrops;
    private long gpuUncorrelatedDrops;
    private long realPresentCalls;
    private long generatedPresentCalls;

    /** Starts tracking one render frame. Callers keep this ID and pass it back with GPU results
     * that arrive late. */
    public long beginRenderFrame() {
        lastIssuedRenderFrameId = Math.incrementExact(lastIssuedRenderFrameId);
        currentRenderFrameId = lastIssuedRenderFrameId;
        int slot = (int) ((currentRenderFrameId - 1) % WINDOW);
        Arrays.fill(gpuTimings[slot], 0, gpuTimingCounts[slot], null);
        gpuTimingCounts[slot] = 0;
        gpuFrameIds[slot] = currentRenderFrameId;
        return currentRenderFrameId;
    }

    /** Zero before the first frame, or after reset until another frame starts. */
    public long currentRenderFrameId() {
        return currentRenderFrameId;
    }

    public void recordGpu(String label, long frameId, String queue, long beginTicks, long endTicks,
                          double timestampPeriodNs, int timestampValidBits, double elapsedMs) {
        if (frameId <= 0 || frameId > currentRenderFrameId || currentRenderFrameId - frameId >= WINDOW) {
            recordValue("gpu uncorrelated drops", ++gpuUncorrelatedDrops);
            return;
        }
        int slot = (int) ((frameId - 1) % WINDOW);
        if (gpuFrameIds[slot] != frameId) {
            // A reset cancelled this pending query, even though its ID still fits within WINDOW.
            recordValue("gpu uncorrelated drops", ++gpuUncorrelatedDrops);
            return;
        }
        record(label, elapsedMs);
        int count = gpuTimingCounts[slot];
        if (count == MAX_GPU_TIMINGS_PER_FRAME) {
            recordValue("gpu timeline drops", ++gpuTimelineDrops);
            return;
        }
        gpuTimings[slot][count] = new GpuTiming(label, frameId, queue, beginTicks, endTicks,
                timestampPeriodNs, timestampValidBits, elapsedMs);
        gpuTimingCounts[slot] = count + 1;
    }

    /** Results stay in frame order even when queues finish out of order. Some results may be missing. */
    public List<GpuTiming> snapshotGpuTimings() {
        List<GpuTiming> out = new ArrayList<>();
        for (int slot = 0; slot < WINDOW; slot++) {
            for (int i = 0; i < gpuTimingCounts[slot]; i++) {
                out.add(gpuTimings[slot][i]);
            }
        }
        out.sort(GPU_TIMING_ORDER);
        return List.copyOf(out);
    }

    /** Counts present calls that returned. A returned call does not prove the frame reached the screen. */
    public void recordPresentation(boolean generated) {
        if (generated) {
            recordValue("generated present calls", ++generatedPresentCalls);
        } else {
            recordValue("real present calls", ++realPresentCalls);
        }
    }

    public void record(String label, double ms) {
        Deque<Double> window = samples.computeIfAbsent(label, k -> new ArrayDeque<>(WINDOW));
        window.addLast(ms);
        if (window.size() > WINDOW) {
            window.removeFirst();
        }
        if (LABEL_FRAME.equals(label)) {
            lastFrameMs = ms;
        }
    }

    public List<Stat> snapshot() {
        List<Stat> out = new ArrayList<>(samples.size());
        for (Map.Entry<String, Deque<Double>> e : samples.entrySet()) {
            double[] sorted = e.getValue().stream().mapToDouble(Double::doubleValue).toArray();
            int n = sorted.length;
            if (n == 0) {
                continue;
            }
            double sum = 0;
            for (double v : sorted) {
                sum += v;
            }
            double avg = sum / n;
            Arrays.sort(sorted);
            int p95Index = Math.min(Math.max((int) Math.ceil(0.95 * n) - 1, 0), n - 1);
            out.add(new Stat(e.getKey(), avg, sorted[p95Index], n));
        }
        return out;
    }

    public double frameTotalMs() {
        return lastFrameMs;
    }

    /** Publishes (overwriting any prior value for {@code label}) one generic per-frame VALUE -- e.g.
     * a queue depth, a harvested-this-frame count, or a population fraction -- alongside the
     * millisecond timings {@link #record} tracks. Minimal and generic on purpose: any future per-frame
     * scalar a HUD wants to surface can reuse this same seam without a bespoke field. */
    public void recordValue(String label, double value) {
        values.put(label, value);
    }

    /** Counters reset along with the measurement window, even if the code that reports them keeps
     * running. */
    public void incrementCounter(String label) {
        values.merge(label, 1.0, Double::sum);
    }

    /** Snapshot of every {@link #recordValue}-published value, in first-seen label order (mirrors
     * {@link #snapshot()}'s own ordering guarantee so a HUD renders a stable row order frame to
     * frame). */
    public List<ValueStat> valueSnapshot() {
        List<ValueStat> out = new ArrayList<>(values.size());
        for (Map.Entry<String, Double> e : values.entrySet()) {
            out.add(new ValueStat(e.getKey(), e.getValue()));
        }
        return out;
    }

    public void reset() {
        samples.clear();
        values.clear();
        lastFrameMs = 0;
        for (int slot = 0; slot < WINDOW; slot++) {
            Arrays.fill(gpuTimings[slot], 0, gpuTimingCounts[slot], null);
        }
        Arrays.fill(gpuTimingCounts, 0);
        Arrays.fill(gpuFrameIds, 0);
        currentRenderFrameId = 0;
        gpuTimelineDrops = 0;
        gpuUncorrelatedDrops = 0;
        realPresentCalls = 0;
        generatedPresentCalls = 0;
        // Keep lastIssuedRenderFrameId: GPU queries from before the reset may still come back with old IDs.
    }
}
