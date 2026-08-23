package dev.icehunter.fornax.profile;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.pack.graph.GraphRunner;
import dev.icehunter.fornax.pass.FrameGenPresenter;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Top-left corner HUD: per-pass avg/p95 GPU timings graded against {@link ProfilerLogDump#BUDGET_MS},
 * plus the frame total. Toggled by {@code FornaxSettings#profilerOverlay} (keybind or Engine-page
 * row); a second keybind ({@link #dumpToLog()}) writes a full breakdown table to the log via {@link
 * ProfilerLogDump}.
 *
 * <p>{@link FrameProfiler#snapshot()} allocates and sorts a list every call, so this never calls it
 * from {@link #extractRenderState} directly -- it refreshes a cached copy at {@link
 * #REFRESH_INTERVAL_NANOS} and draws that cache every frame. An empty cache (nothing ever recorded --
 * an unsupported backend, or a pack with no graph loaded) renders a single "timestamps unavailable"
 * line rather than an empty or garbled panel; a legitimate zero timing (Apple's GL backend reports
 * zero GPU timestamps) still renders as an ordinary 0.00 value.
 */
public final class ProfilerOverlay implements HudElement {
    private static final long REFRESH_INTERVAL_NANOS = 250_000_000L; // ~4 Hz

    private static final int PANEL_COLOR = 0x90000000;
    private static final int PAD = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int GRN = 0xFF7DD87D;
    private static final int YEL = 0xFFFFD867;
    private static final int RED = 0xFFE06666;
    private static final int LABEL_COLOR = 0xFFE0E0E0;
    private static final String UNAVAILABLE_LINE = "timestamps unavailable";

    private boolean hasCached;
    private long lastRefreshNanos;
    private List<FrameProfiler.Stat> cachedPasses = List.of();
    private List<FrameProfiler.ValueStat> cachedValues = List.of();
    private double cachedFrameTotalMs;
    private int cachedWidth;

    // FrameGenPresenter.overlayLine() is null whenever frame generation isn't armed this session
    // (config off, unsupported hardware, or a prior failure) -- the row is simply absent then, zero
    // overlay change from before this feature existed.
    @Nullable
    private String cachedFrameGenLine;

    private ProfilerOverlay() {
    }

    public static void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("fornax", "profiler_overlay"), new ProfilerOverlay());
    }

    /**
     * Logs a full current-frame breakdown at INFO. Always takes a fresh {@link
     * FrameProfiler#snapshot()} -- this is a one-off user action (keybind press), not a per-frame
     * render read, so the HUD's throttled cache doesn't apply here.
     */
    public static void dumpToLog() {
        FrameProfiler profiler = GraphRunner.frameProfiler();
        List<FrameProfiler.Stat> passes = passesOnly(profiler.snapshot());
        FornaxMod.LOGGER.info(ProfilerLogDump.format(passes, profiler.frameTotalMs()));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        if (!FornaxConfig.get().profilerOverlay) {
            return;
        }
        refreshIfDue();

        Font font = Minecraft.getInstance().font;
        int lineCount = cachedPasses.isEmpty()
                ? 1
                : cachedPasses.size() + cachedValues.size() + 1 + (cachedFrameGenLine != null ? 1 : 0);
        int x = 4;
        int y = 4;
        int width = cachedWidth;
        int height = PAD * 2 + lineCount * LINE_HEIGHT;

        g.fill(x, y, x + width, y + height, PANEL_COLOR);
        int textY = y + PAD;

        if (cachedPasses.isEmpty()) {
            g.text(font, UNAVAILABLE_LINE, x + PAD, textY, LABEL_COLOR);
            return;
        }

        for (FrameProfiler.Stat stat : cachedPasses) {
            String line = String.format(Locale.ROOT, "%-14s %5.2f %5.2f", stat.label(), stat.avgMs(), stat.p95Ms());
            g.text(font, line, x + PAD, textY, colorFor(stat.avgMs()));
            textY += LINE_HEIGHT;
        }
        // Generic per-frame VALUE rows (queue depths, harvest/clear counts, population fraction --
        // see FrameProfiler#recordValue) -- not millisecond timings, so no avg/p95/grade color, just
        // the raw number in the neutral label color.
        for (FrameProfiler.ValueStat stat : cachedValues) {
            String line = String.format(Locale.ROOT, "%-14s %8.2f", stat.label(), stat.value());
            g.text(font, line, x + PAD, textY, LABEL_COLOR);
            textY += LINE_HEIGHT;
        }
        String frameLine = String.format(Locale.ROOT, "%-14s %5.2f", "frame", cachedFrameTotalMs);
        g.text(font, frameLine, x + PAD, textY, colorFor(cachedFrameTotalMs));

        if (cachedFrameGenLine != null) {
            textY += LINE_HEIGHT;
            g.text(font, cachedFrameGenLine, x + PAD, textY, LABEL_COLOR);
        }
    }

    private void refreshIfDue() {
        long now = System.nanoTime();
        if (hasCached && now - lastRefreshNanos < REFRESH_INTERVAL_NANOS) {
            return;
        }
        hasCached = true;
        lastRefreshNanos = now;

        FrameProfiler profiler = GraphRunner.frameProfiler();
        cachedPasses = passesOnly(profiler.snapshot());
        cachedValues = profiler.valueSnapshot();
        cachedFrameTotalMs = profiler.frameTotalMs();
        cachedFrameGenLine = FrameGenPresenter.overlayLine();

        Font font = Minecraft.getInstance().font;
        int widest = font.width(UNAVAILABLE_LINE);
        for (FrameProfiler.Stat stat : cachedPasses) {
            widest = Math.max(widest, font.width(String.format(Locale.ROOT, "%-14s %5.2f %5.2f", stat.label(), stat.avgMs(), stat.p95Ms())));
        }
        for (FrameProfiler.ValueStat stat : cachedValues) {
            widest = Math.max(widest, font.width(String.format(Locale.ROOT, "%-14s %8.2f", stat.label(), stat.value())));
        }
        widest = Math.max(widest, font.width(String.format(Locale.ROOT, "%-14s %5.2f", "frame", cachedFrameTotalMs)));
        if (cachedFrameGenLine != null) {
            widest = Math.max(widest, font.width(cachedFrameGenLine));
        }
        cachedWidth = widest + PAD * 2;
    }

    /**
     * Excludes {@link FrameProfiler#LABEL_FRAME}: that bracket's own row is surfaced separately (via
     * {@link FrameProfiler#frameTotalMs()}), never duplicated as one more entry among the passes.
     *
     * <p>Also drops any label that isn't part of the CURRENT rebuild's pass set (see {@link
     * GraphRunner#activePassNames()}) -- {@link FrameProfiler} rolling samples are reset on every pack
     * teardown ({@code GraphRunner.closeCurrent()}), so this is defense-in-depth for the narrow window
     * before the next rebuild's runners have landed, not the primary fix. {@link
     * FrameProfiler#LABEL_TERRAIN} is exempted: it's an always-on engine bracket recorded directly by
     * {@code GraphRunner}, not a pack-declared pass name, so it never appears in {@code
     * activePassNames()}.
     */
    private static List<FrameProfiler.Stat> passesOnly(List<FrameProfiler.Stat> stats) {
        Set<String> active = GraphRunner.activePassNames();
        return stats.stream()
                .filter(stat -> !FrameProfiler.LABEL_FRAME.equals(stat.label()))
                .filter(stat -> FrameProfiler.LABEL_TERRAIN.equals(stat.label()) || active.contains(stat.label()))
                .toList();
    }

    private static int colorFor(double ms) {
        return switch (ProfilerLogDump.grade(ms)) {
            case "RED" -> RED;
            case "YEL" -> YEL;
            default -> GRN;
        };
    }
}
