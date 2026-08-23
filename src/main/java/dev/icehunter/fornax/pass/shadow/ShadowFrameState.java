package dev.icehunter.fornax.pass.shadow;

import org.joml.Matrix4f;

/**
 * Per-frame holder for the sun/moon shadow pass's light view-projection matrix -- the value {@code
 * GlobalUniformsWriteMixin}'s append delivers to every terrain draw's {@code u_Globals} block as
 * {@code u_SunViewProj}. Mirrors {@link dev.icehunter.fornax.pipeline.PreviousFrameCameraTransform}'s
 * own static-holder shape: a tiny, GPU-free per-frame latch, committed once by the shadow
 * orchestration ({@code SodiumWorldRendererOrchestrationMixin}'s shadow-pass hook) before that same
 * frame's opaque terrain draws, and read by every {@code uniformBufferManager.update(...)} call for
 * the rest of the frame -- including the shadow pass's OWN update (which writes the same per-frame
 * value it just committed) and the later opaque/translucent draws (which read the identical,
 * still-current value; see {@code UniformBufferManagerMixin}'s own doc comment for why reading a
 * per-frame-constant value from either update site is safe).
 *
 * <p>{@link #current()} is the identity matrix before the first {@link #commit} of a session (mod
 * init, or a session where {@code SHADOWS} is never compiled on) -- a safe, well-formed placeholder.
 * It is never actually sampled in that state: the shadow orchestration only calls {@link #commit}
 * while {@code SHADOWS} is enabled, and a pack's resolve shader only samples {@code
 * sunShadowMap}/{@code u_SunViewProj} under that same compile option. The default exists purely so
 * {@code GlobalUniformsWriteMixin}'s unconditional per-frame append (it runs whether or not shadows
 * are active, matching every other unconditional {@code u_Globals} field) is never handed a
 * null/undefined value.
 */
public final class ShadowFrameState {
    private static volatile Matrix4f current = new Matrix4f();
    private static volatile float currentBias = 0.0f;

    // The view and projection halves, kept alongside the combined matrix. Geometry submitted for the
    // shadow pass has to be BUILT under the light's camera rather than reprojected afterwards, and
    // that needs the two separately: the view goes into cameraRenderState.viewRotationMatrix and the
    // RenderSystem model-view stack, the projection into cameraRenderState.projectionMatrix.
    private static volatile Matrix4f currentView = new Matrix4f();
    private static volatile Matrix4f currentProj = new Matrix4f();

    private ShadowFrameState() {
    }

    /**
     * Replaces the current frame's light view-projection matrix AND the shared radial-distortion
     * bias ({@link ShadowCamera#shadowMapBias}, computed from the SAME {@code shadowDistance} local
     * {@code ShadowCamera.compute} was just called with at the call site) in one atomic commit, so
     * the two values -- read separately by {@code GlobalUniformsWriteMixin} for {@code
     * u_SunViewProj}/{@code u_ShadowMapParams.x} -- can never drift apart. The matrix is defensively
     * copied -- {@link ShadowCamera#compute} returns a fresh instance per call already, but copying
     * keeps this class's own contract (the stored matrix is never externally mutable) independent of
     * that detail, matching {@code PreviousFrameCameraTransform.commit}'s own defensive-copy
     * precedent.
     */
    public static void commit(Matrix4f lightViewProj, float bias) {
        current = new Matrix4f(lightViewProj);
        currentBias = bias;
    }

    /** As {@link #commit(Matrix4f, float)}, also retaining the view/projection halves. */
    public static void commit(Matrix4f lightView, Matrix4f lightProj, Matrix4f lightViewProj, float bias) {
        currentView = new Matrix4f(lightView);
        currentProj = new Matrix4f(lightProj);
        commit(lightViewProj, bias);
    }

    /** This frame's light VIEW matrix (identity before the first commit). */
    public static Matrix4f currentView() {
        return currentView;
    }

    /** This frame's light PROJECTION matrix (identity before the first commit). */
    public static Matrix4f currentProj() {
        return currentProj;
    }

    /** This frame's committed light view-projection matrix, or the identity matrix if {@link
     * #commit} has never been called (see class javadoc). */
    public static Matrix4f current() {
        return current;
    }

    /** This frame's committed shadow-map radial-distortion bias, or {@code 0.0} (identity warp --
     * see {@link ShadowCamera#distortFactor}) if {@link #commit} has never been called. */
    public static float currentBias() {
        return currentBias;
    }
}
