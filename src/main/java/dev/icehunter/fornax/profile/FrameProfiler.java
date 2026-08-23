package dev.icehunter.fornax.profile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-JVM rolling-stats aggregator for per-label millisecond timings -- no blaze3d imports, so it's
 * unit-testable without a GPU device. {@link PassTimer} is the sole GPU-facing producer ({@code
 * bracketBegin}/{@code bracketEnd} pairs converted to ms and handed to {@link #record}), but nothing
 * here assumes GPU timestamps specifically; any millisecond duration source could feed it.
 *
 * <p>Each label keeps its own last-{@link #WINDOW}-samples ring; {@link #snapshot()} reports them in
 * first-seen label order so a HUD (a later task) renders a stable row order frame to frame.
 */
public final class FrameProfiler {
    public static final int WINDOW = 240;

    public static final String LABEL_FRAME = "frame";
    // Named for what the bracket actually measures: Sodium's terrain draws complete BEFORE
    // GraphRunner.finish() ever runs, so the graph's geometry slot contributes only GraphRunner's
    // own dwell there -- a "terrain" row would misattribute real terrain GPU cost to this engine.
    public static final String LABEL_TERRAIN = "geometry dwell";

    public record Stat(String label, double avgMs, double p95Ms, int samples) {
    }

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
    }
}
