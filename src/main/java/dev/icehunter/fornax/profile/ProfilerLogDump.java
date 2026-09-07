package dev.icehunter.fornax.profile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Formats profiler dumps requested by hand. Plain JVM code, no GPU or GUI code, so formatting
 * and sorting never run on the per-frame collection path. */
public final class ProfilerLogDump {
    /** 90 FPS frame budget in milliseconds. */
    public static final double BUDGET_MS = 11.1;
    // Keep dumps readable while still keeping enough frames to see results that come back late.
    private static final int TIMELINE_FRAMES = 30;
    private static final String HEADER_FORMAT = "  %-28s %8s %8s %7s %6s";
    private static final String ROW_FORMAT = "  %-28s %8.3f %8.3f %7d %6s";

    private ProfilerLogDump() {
    }

    /** Old call shape, kept for compatibility: the given number is one latest reading, not an
     * AVG/P95 pair. */
    public static String format(List<FrameProfiler.Stat> stats, double frameTotalMs) {
        return format(stats, List.of(), List.of(), "")
                + String.format(Locale.ROOT, "\n  Latest frame: %.3f ms (separate from rolling statistics)", frameTotalMs);
    }

    public static String format(FrameProfiler profiler, String metadata) {
        return format(profiler.snapshot(), profiler.valueSnapshot(), profiler.snapshotGpuTimings(), metadata);
    }

    /** Every row given, including frame and CPU/cadence rows, keeps its real sample count. */
    public static String format(List<FrameProfiler.Stat> stats, List<FrameProfiler.ValueStat> values,
                                List<FrameProfiler.GpuTiming> gpuTimings, String metadata) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "[Fornax] Frame profile (ms, budget %.3f):", BUDGET_MS)).append('\n');
        sb.append(String.format(Locale.ROOT, HEADER_FORMAT, "LABEL", "AVG", "P95", "SAMPLES", "GRADE"));
        for (FrameProfiler.Stat stat : stats) {
            sb.append('\n').append(String.format(Locale.ROOT, ROW_FORMAT,
                    stat.label(), stat.avgMs(), stat.p95Ms(), stat.samples(), grade(stat.avgMs())));
        }
        if (stats.isEmpty()) {
            sb.append("\n  No timing samples");
        }
        sb.append("\n  frame: Vulkan world span (renderLevel HEAD through history tail); excludes HUD and presentation;")
                .append(" includes dependency/idle spans, not total GPU busy time.");
        if (!metadata.isBlank()) {
            sb.append("\n  ").append(metadata);
        }
        if (!values.isEmpty()) {
            sb.append("\n  Latest counters/values (not timing distributions; presentation calls are not scanout):");
            for (FrameProfiler.ValueStat value : values) {
                sb.append('\n').append(String.format(Locale.ROOT, "  %-28s %.3f", value.label(), value.value()));
            }
        }
        sb.append("\n  Queue names identify timer domains and may share a physical queue;")
                .append(" clocks are not established as comparable. Do not infer cross-domain overlap.");
        appendTimeline(sb, gpuTimings);
        return sb.toString();
    }

    private static void appendTimeline(StringBuilder sb, List<FrameProfiler.GpuTiming> timings) {
        List<FrameProfiler.GpuTiming> ordered = new ArrayList<>(timings);
        ordered.sort(Comparator.comparingLong(FrameProfiler.GpuTiming::frameId)
                .thenComparing(FrameProfiler.GpuTiming::label).thenComparing(FrameProfiler.GpuTiming::queue)
                .thenComparingLong(FrameProfiler.GpuTiming::beginTicks).thenComparingLong(FrameProfiler.GpuTiming::endTicks)
                .thenComparingDouble(FrameProfiler.GpuTiming::timestampPeriodNs)
                .thenComparingInt(FrameProfiler.GpuTiming::timestampValidBits)
                .thenComparingDouble(FrameProfiler.GpuTiming::elapsedMs));
        int start = ordered.size();
        int frames = 0;
        long previousFrame = 0;
        for (int i = ordered.size() - 1; i >= 0; i--) {
            long frame = ordered.get(i).frameId();
            if (frame != previousFrame) {
                if (frames == TIMELINE_FRAMES) {
                    break;
                }
                frames++;
                previousFrame = frame;
            }
            start = i;
        }
        sb.append("\n  Resolved GPU intervals: last ").append(frames)
                .append(" source render frame IDs with samples; partial coverage (queries may be pending or dropped).");
        for (int i = start; i < ordered.size(); i++) {
            FrameProfiler.GpuTiming timing = ordered.get(i);
            sb.append('\n').append(String.format(Locale.ROOT,
                    "  gpu frame=%d label=%s queue=%s beginTicks=%s endTicks=%s periodNs=%.6f validBits=%d elapsedMs=%.6f",
                    timing.frameId(), timing.label(), timing.queue(), Long.toUnsignedString(timing.beginTicks()),
                    Long.toUnsignedString(timing.endTicks()), timing.timestampPeriodNs(),
                    timing.timestampValidBits(), timing.elapsedMs()));
        }
    }

    /** {@code >=90%} of {@link #BUDGET_MS} grades RED, {@code >=60%} grades YEL, otherwise GRN. */
    static String grade(double ms) {
        double fraction = ms / BUDGET_MS;
        if (fraction >= 0.90) {
            return "RED";
        }
        if (fraction >= 0.60) {
            return "YEL";
        }
        return "GRN";
    }
}
