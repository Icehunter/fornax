package dev.icehunter.fornax.profile;

import java.util.List;
import java.util.Locale;

/**
 * Pure-JVM formatting seam for the profiler's full-breakdown log dump -- no blaze3d/GUI imports, so
 * the exact table layout is unit-testable without a GPU device or running client. {@link
 * ProfilerOverlay} shares {@link #grade} to color-code the HUD panel against the same budget.
 */
public final class ProfilerLogDump {
    /** 90 FPS frame budget in milliseconds. */
    public static final double BUDGET_MS = 11.1;

    private static final String HEADER_FORMAT = "  %-16s %8s %8s %6s";
    private static final String ROW_FORMAT = "  %-16s %8.3f %8.3f %6s";
    private static final String RULE = "  " + "-".repeat(40);

    private ProfilerLogDump() {
    }

    /**
     * Renders the header, one row per {@code stats} entry (in the given order -- callers exclude
     * {@link FrameProfiler#LABEL_FRAME} themselves, since that bracket's total is supplied separately
     * via {@code frameTotalMs}), a dashed rule, then the frame-total row.
     */
    public static String format(List<FrameProfiler.Stat> stats, double frameTotalMs) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "[Fornax] Frame profile (ms, budget %.3f):", BUDGET_MS)).append('\n');
        sb.append(String.format(Locale.ROOT, HEADER_FORMAT, "PASS", "AVG", "P95", "GRADE")).append('\n');
        for (FrameProfiler.Stat stat : stats) {
            sb.append(String.format(Locale.ROOT, ROW_FORMAT, stat.label(), stat.avgMs(), stat.p95Ms(), grade(stat.avgMs())));
            sb.append('\n');
        }
        sb.append(RULE).append('\n');
        sb.append(String.format(Locale.ROOT, ROW_FORMAT, "frame", frameTotalMs, frameTotalMs, grade(frameTotalMs)));
        return sb.toString();
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
