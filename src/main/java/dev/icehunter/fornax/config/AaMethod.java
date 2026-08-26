package dev.icehunter.fornax.config;

/**
 * The engine's own, mutually exclusive anti-aliasing/upscaling method -- replaces the pack-owned
 * {@code TAA_ENABLED} compile option as the single source of truth for whether/how the frame is
 * temporally resolved. {@link dev.icehunter.fornax.pack.graph.EngineDefines} turns a method into the
 * {@code FX_*} compile facts a pack's own {@code enabled_if}/GLSL can react to; {@link
 * dev.icehunter.fornax.pass.taa.CameraJitter} turns it into a jitter sequence.
 *
 * <ul>
 *   <li>{@code OFF} -- no temporal resolve, no jitter, native resolution.</li>
 *   <li>{@code TAA} -- temporal AA at native resolution (today's behavior).</li>
 *   <li>{@code SSAA} -- supersample-then-downsample; no jitter (every sample already lands every
 *       frame at the higher render resolution).</li>
 *   <li>{@code TAAU} -- render below native and temporally upscale/reconstruct.</li>
 *   <li>{@code METALFX} -- render below native (same {@code TaauRatio} scales/jitter as TAAU) and
 *       upscale through Apple's ML temporal scaler ({@code MetalFxUpscalePass}) instead of the
 *       engine's own reconstruct. macOS/Apple-silicon only, runtime-probed
 *       ({@code MetalFxSupport}); the settings UI hides it when unavailable and the seam falls
 *       back to the TAAU reconstruct if a persisted config carries it onto unsupported hardware
 *       (or the scaler fails mid-session).</li>
 * </ul>
 */
public enum AaMethod {
    OFF, TAA, SSAA, TAAU, METALFX;

    /** Whether the projection matrix should carry a per-frame sub-pixel jitter for this method. */
    public boolean wantsJitter() {
        return this == TAA || this == TAAU || this == METALFX;
    }

    /**
     * Whether a history (previous-frame) buffer is needed. Always {@code true} engine-side
     * regardless of method: SSR's own reprojection depends on last frame's content existing even
     * with temporal AA/upscaling entirely off.
     */
    public boolean wantsHistory() {
        return true;
    }

    /**
     * Whether this method resolves through pack-graph resources (G-buffer motion/depth and the
     * engine sceneHistory target) at end of frame. TAA/TAAU need them for the engine reconstruct;
     * METALFX needs the same inputs for the ML scaler and writes its output through the same
     * sceneHistory slot. When those resources are not being WRITTEN (shaders disabled, no pack, or a
     * transient resize/world-join rebuild window), the frame must render plain -- reaching the
     * end-of-frame resolve anyway causes a hard crash under METALFX when they are absent, and heavy
     * vanilla ghosting when they are merely stale. "Not being written" is strictly weaker than
     * "null": see {@code TemporalInputs}, which owns that distinction.
     *
     * <p>TAA/TAAU and METALFX both need these resources but do different things with them: TAA/TAAU
     * run the ENGINE's own temporal reconstruction/blend pass ({@code ReconstructPass}) over the
     * jittered samples. METALFX instead owns its temporal history internally in Apple's ML scaler,
     * so {@code ReconstructPass} (and its sceneHistory write) is skipped entirely -- the
     * native->history end-of-frame copy runs in its place, which is what SSR's reprojection needs
     * regardless of method.
     */
    public boolean needsGraphResources() {
        return this == TAA || this == TAAU || this == METALFX;
    }

    /** Whether this method renders into an offscreen target distinct from the display target. */
    public boolean needsOffscreenTarget() {
        return this != OFF;
    }
}
