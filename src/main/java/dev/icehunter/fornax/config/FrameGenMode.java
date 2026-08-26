package dev.icehunter.fornax.config;

/**
 * How aggressively MetalFX frame generation double-presents, read every frame by {@code
 * FrameGenPass.armed()}/{@code FrameGenPass.mode()} and by {@code
 * dev.icehunter.fornax.pipeline.FrameGenPacer#update}.
 *
 * <p>{@link #AUTO} and {@link #ALWAYS} are two different policies, not two points on one
 * tuning curve: {@link #AUTO}'s hysteresis band is bounded strictly below {@code 0.5 *
 * displayHz} because the engaged-state loop rate cannot exceed that under FIFO (see {@code
 * FrameGenPacer}'s own header): no threshold in that band can ever produce an
 * always-engaged policy. {@link #ALWAYS} exists as a separate escape hatch for exactly the case
 * {@link #AUTO} is deliberately unable to serve: a player who has already capped their own
 * frame rate at or below half the display refresh and wants the assist unconditionally.
 */
public enum FrameGenMode {
    /** Frame generation is disarmed; {@code FrameGenPass.armed()} is false. */
    OFF,

    /**
     * Today's adaptive policy: {@code FrameGenPacer}'s hysteresis engages only when render fps
     * has genuinely dropped meaningfully below the display's own refresh rate, and disengages
     * once the machine no longer needs the assist, so arming this never makes real fps worse
     * than doing nothing.
     */
    AUTO,

    /**
     * Bypasses the pacer: every armed frame double-presents, regardless of measured fps. Under
     * FIFO/vsync this holds the real frame rate at roughly {@code displayHz / 2} for as long as
     * this mode is selected: exactly right when the player has already capped there (e.g. 60
     * FPS on a 120 Hz panel, wanting 60 real + 60 generated), and a regression on an uncapped
     * session that could otherwise render above that ceiling on its own.
     */
    ALWAYS
}
