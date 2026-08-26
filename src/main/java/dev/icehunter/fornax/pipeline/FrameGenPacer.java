package dev.icehunter.fornax.pipeline;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.config.FrameGenMode;
import net.minecraft.client.Minecraft;

/**
 * Adaptive engage/disengage decision for MetalFX frame generation's double-present seam ({@code
 * FrameGenPass}/{@code FrameGenPresenter}, both {@code metalfx}/{@code pass}).
 *
 * <p><b>Problem this fixes</b> (live-measured): the original design always double-presented
 * whenever armed -- one generated image, then the real image, every single frame -- which under
 * FIFO/vsync caps the REAL frame rate at {@code displayHz / 2} (the compositor accepts exactly one
 * image per vblank, and generation submits two). On a 120Hz panel, a scene the machine could
 * already render at 90fps got throttled down to 60 real + 60 generated -- frame generation must
 * never reduce the real frame rate below what the hardware can already deliver on its own. The fix
 * is to only pay the double-present cost ("engage") when the machine genuinely needs the assist:
 * render fps meaningfully below what the display can already show every real frame.
 *
 * <p><b>Hysteresis</b>: engage and disengage use DIFFERENT thresholds ({@link #ENGAGE_FRACTION} <
 * {@link #DISENGAGE_FRACTION}) rather than one shared crossing point, and holding the previous
 * state in the band between them. A single threshold at, say, 0.5x displayHz would flap engaged/
 * disengaged every few frames for any scene whose render fps happens to hover near that exact
 * value -- each transition costs a one-frame warm-up gap on re-engage (see {@link
 * dev.icehunter.fornax.metalfx.FrameGenPass#runIfEnabled}'s own header) and a visible generated-
 * frame pop-in/out. The band between {@link #ENGAGE_FRACTION} and {@link #DISENGAGE_FRACTION}
 * absorbs that jitter: a scene has to cross meaningfully past the OPPOSITE threshold to flip
 * state, not just twitch around one line.
 *
 * <p><b>{@link dev.icehunter.fornax.config.FrameGenMode#ALWAYS} is a separate policy, not a
 * fourth tuning value</b>: it bypasses {@link #computeEngaged} entirely and forces {@link
 * #engaged} true every frame. The hysteresis law below governs {@link
 * dev.icehunter.fornax.config.FrameGenMode#AUTO} only. See that enum's own header for why no
 * threshold inside the band below could ever produce an always-engaged policy.
 *
 * <p><b>Both thresholds MUST sit strictly below 0.5 -- this is not a free tuning choice</b> (a
 * code-review catch that found the ORIGINAL 0.45/0.55 band, straddling 0.5, self-latches): while
 * ENGAGED, this pass's own double-present pins the measured render-thread loop -- the very
 * {@code emaIntervalNanos} this class reads -- at very close to {@code displayHz / 2} (see this
 * class's "Problem this fixes" paragraph above: FIFO accepts one image per vblank, engaged submits
 * two per loop iteration, so the loop itself can only run at half the compositor's own cadence).
 * That measured rate is a CEILING while engaged, not a typical value -- it is very close to the
 * fastest the loop can ever be observed running in that state, regardless of how much faster the
 * machine could actually render. A {@link #DISENGAGE_FRACTION} at or above 0.5 can therefore never
 * be exceeded once engaged: {@code renderFps} asymptotically approaches {@code 0.5 * displayHz}
 * from below and the {@code renderFps > DISENGAGE_FRACTION * displayHz} check in {@link
 * #computeEngaged} is permanently false, latching the engaged state for the rest of the session no
 * matter how much headroom the machine actually has. {@link #ENGAGE_FRACTION}/{@link
 * #DISENGAGE_FRACTION} are set to 0.40/0.48 specifically so the ~0.5x engaged-state ceiling sits
 * ABOVE {@link #DISENGAGE_FRACTION} (0.5 > 0.48) -- the disengage branch stays reachable from
 * engaged. Genuinely heavy scenes (rendering below {@link #ENGAGE_FRACTION} = 0.40x even WITHOUT
 * the engaged-state throttle in play, since the disengaged branch measures the machine's own
 * uncapped rate) still correctly engage and stay engaged.
 *
 * <p><b>Single decision, single writer</b>: {@link #update} is called exactly once per frame, from
 * {@code FrameGenPass.runIfEnabled} (the one place both the interpolator-arming decision and the
 * present seam's later behavior ultimately trace back to -- see that method's own header). Every
 * other reader ({@code FrameGenPresenter}'s cadence log) only ever calls {@link #engaged()} /
 * {@link #engagedFrameCount()} / {@link #disengagedFrameCount()} -- read-only, never a second
 * independent computation -- so there is no way for two places in one frame to disagree about
 * whether generation is engaged.
 */
public final class FrameGenPacer {
    /**
     * Engage (double-present) once render fps drops below this fraction of the display refresh.
     * MUST stay comfortably below 0.5 -- see this class's header for why 0.5 is the ceiling render
     * fps can ever reach while engaged (double-present under FIFO), not just an arbitrary number.
     */
    public static final double ENGAGE_FRACTION = 0.40;

    /**
     * Disengage (single-present, zero framegen cost) once render fps climbs above this fraction.
     * MUST stay strictly below 0.5 -- see this class's header: the engaged-state loop rate can
     * approach but never exceed {@code 0.5 * displayHz}, so a threshold at or above 0.5 can never
     * be crossed and permanently latches the engaged state.
     */
    public static final double DISENGAGE_FRACTION = 0.48;

    private static final int FALLBACK_DISPLAY_HZ = 60;
    private static final long DISPLAY_HZ_REQUERY_INTERVAL_NANOS = 2_000_000_000L; // 2s

    // Starts disengaged: the conservative default for a session/re-arm with no measurement yet --
    // see #update's early-return for why a not-yet-warmed-up clock never engages on a guess.
    private static boolean engaged;

    private static int cachedDisplayHz = FALLBACK_DISPLAY_HZ;
    private static long lastDisplayHzQueryNanos;
    private static boolean loggedFallbackOnce;

    private static long engagedFrames;
    private static long disengagedFrames;

    private FrameGenPacer() {
    }

    /**
     * Recomputes engagement for THIS frame from the render clock's current EMA-smoothed interval
     * ({@code FrameClock#emaIntervalNanos()}), applying the hysteresis band above under {@link
     * FrameGenMode#AUTO} (or forcing engagement under {@link FrameGenMode#ALWAYS}, see this class's
     * header), then tallies the outcome into the per-cadence-window counters {@link
     * #engagedFrameCount()}/{@link #disengagedFrameCount()}. Under {@link FrameGenMode#AUTO},
     * no-ops (holding whatever state is already set, without tallying) when the clock has not
     * produced a real interval yet: {@code emaIntervalNanos <= 0} means either the very first
     * armed frame ever, or the frame right after a {@code FrameClock#reset()} (resize,
     * deactivate/re-arm). Engaging off a meaningless/zero interval would misfire on exactly the
     * frames this pacer most needs to get right. {@link FrameGenMode#ALWAYS} has no such warm-up
     * gate: it forces engagement from the very first call, since it carries no fps-based decision
     * to warm up in the first place.
     */
    public static void update(long emaIntervalNanos, FrameGenMode mode) {
        if (mode == FrameGenMode.ALWAYS) {
            engaged = true;
        } else if (emaIntervalNanos > 0) {
            double renderFps = 1.0e9 / (double) emaIntervalNanos;
            engaged = computeEngaged(engaged, renderFps, displayRefreshHz(), mode);
        } else {
            return;
        }
        if (engaged) {
            engagedFrames++;
        } else {
            disengagedFrames++;
        }
    }

    /**
     * Pure engagement decision, split out from {@link #update} so the engage/disengage math is
     * testable without a live {@code Minecraft}/GLFW window (the only thing {@link #update} adds is
     * sourcing {@code renderFps}/{@code displayHz} from the real clock and window). Under {@link
     * FrameGenMode#ALWAYS}, unconditionally {@code true}. Under {@link FrameGenMode#AUTO}, held
     * state persists between calls: HOLD in the band between the two thresholds, not just at the
     * exact crossing points.
     */
    static boolean computeEngaged(boolean currentlyEngaged, double renderFps, int displayHz,
            FrameGenMode mode) {
        if (mode == FrameGenMode.ALWAYS) {
            return true;
        }
        if (currentlyEngaged) {
            return !(renderFps > DISENGAGE_FRACTION * displayHz);
        }
        return renderFps < ENGAGE_FRACTION * displayHz;
    }

    /** This frame's engagement decision, as of the last {@link #update}. */
    public static boolean engaged() {
        return engaged;
    }

    /**
     * The display's refresh rate in Hz, from {@code Window.getRefreshRate()} (backed by {@code
     * GLX._getRefreshRate}, javap-confirmed: {@code glfwGetWindowMonitor} -- falling back to {@code
     * glfwGetPrimaryMonitor} when windowed/undecorated leaves it null -- then {@code
     * glfwGetVideoMode(...).refreshRate()}, which GLFW itself defines as 0 when the platform cannot
     * report one). Re-queried at most once every {@value #DISPLAY_HZ_REQUERY_INTERVAL_NANOS}ns
     * (monitor/mode changes are rare; querying every frame would be a GLFW round trip for no
     * benefit) rather than every frame. Falls back to {@value #FALLBACK_DISPLAY_HZ} and logs once
     * (never per-frame) whenever the query returns non-positive.
     */
    public static int displayRefreshHz() {
        long now = System.nanoTime();
        if (now - lastDisplayHzQueryNanos < DISPLAY_HZ_REQUERY_INTERVAL_NANOS) {
            return cachedDisplayHz;
        }
        lastDisplayHzQueryNanos = now;
        int hz = Minecraft.getInstance().getWindow().getRefreshRate();
        if (hz <= 0) {
            if (!loggedFallbackOnce) {
                loggedFallbackOnce = true;
                FornaxMod.LOGGER.info(
                        "[Fornax] framegen pacing: display refresh rate unobtainable (got {}), assuming {}Hz",
                        hz, FALLBACK_DISPLAY_HZ);
            }
            hz = FALLBACK_DISPLAY_HZ;
        }
        cachedDisplayHz = hz;
        return hz;
    }

    /** Frames tallied engaged since the last {@link #resetFrameCounts()} (cadence-log window). */
    public static long engagedFrameCount() {
        return engagedFrames;
    }

    /** Frames tallied disengaged since the last {@link #resetFrameCounts()} (cadence-log window). */
    public static long disengagedFrameCount() {
        return disengagedFrames;
    }

    /** Called by the cadence logger after printing a window's counts. */
    public static void resetFrameCounts() {
        engagedFrames = 0;
        disengagedFrames = 0;
    }

    /**
     * Back to the conservative disengaged default; called from {@code FrameGenPass#deactivate()} so
     * a later re-arm (config toggle, {@code aaMethod} switch back to METALFX) starts from a clean
     * measurement rather than carrying over whatever state the previous armed session ended in.
     */
    public static void reset() {
        engaged = false;
        resetFrameCounts();
    }
}
