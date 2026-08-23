package dev.icehunter.fornax.pipeline;

/**
 * The two per-frame DID-CANCEL flags in the sky uniform tail: whether Fornax cancelled vanilla's
 * sky pass ({@code u_SkyColor.w}) and whether it cancelled vanilla's clouds pass
 * ({@code u_SkyState.z}). The resolve shader paints procedural sky/clouds exactly when the matching
 * flag is 1, so "painted but vanilla also drew" and "cancelled but nothing painted" (stale-frame
 * garbage through LOAD semantics) are both impossible by construction -- each flag IS the
 * cancellation, not a parallel re-derivation of the same conditions that could drift.
 *
 * <p>This class used to carry the sky's DATA too -- sky colour, sunrise colour, star brightness,
 * sun direction, moon phase, rain level, sun angle -- committed by the same
 * {@code LevelRendererSkyPassMixin} call site. That was wrong in a way that cost real debugging
 * time: the commit only ran down the branch that cancels vanilla's sky, which requires the pack's
 * {@code SKY_PROCEDURAL} compile option, so any pack that did not paint its own sky read every one
 * of those lanes as zero. The engine was withholding data because of a styling decision. All of it
 * moved to {@link SkyProbe}, which reads the same values live from the camera's environment
 * attribute probe every frame in every dimension -- see that class for the full rationale and for
 * the two other consumers that were quietly reading zeroes. What remains here is what genuinely IS
 * conditional: a record of a decision this engine made this frame.
 *
 * <p>Committed on the render thread during sky/clouds pass registration (before the frame graph
 * executes, therefore before any terrain draw writes u_Globals); read on the same thread by the
 * uniform tail writer. Single-writer/same-thread -- plain fields suffice. Unlike the sibling
 * frame-state holders ({@code EmitterFrameState}, {@code ShadowFrameState},
 * {@code PreviousFrameCameraTransform}), which mark fields volatile because their values can be
 * read from Sodium's background meshing/build threads, both of this class's call sites are
 * render-thread by construction, so there is no cross-thread publication to order.
 */
public final class SkyFrameState {
    private static float skyDidCancel;
    // u_SkyState.z: the clouds did-cancel flag, committed by LevelRendererCloudsPassMixin.
    // commitSky() below zeroes this, mirroring the sky lane -- vanilla itself calls addSkyPass
    // before addCloudsPass every frame (bytecode-verified against the real MC 26.2 jar), so
    // LevelRendererSkyPassMixin's commitSky (this class's other writer) always lands before
    // LevelRendererCloudsPassMixin's own commitClouds() call the same frame; a frame where vanilla
    // clouds draw simply never overwrites the reset 0, which is exactly the "no cancellation
    // happened" value the shader expects.
    private static float cloudsDidCancel;

    // u_CameraSkyLight.w: the cloud altitude the GAME is using this frame, in world blocks, or 0.0
    // if no cloud pass has run yet this session.
    //
    // WHY THIS IS READ RATHER THAN COMPUTED. Vanilla's overworld cloud height is 192, but mods move
    // it -- Sodium Extra ships a `cloud_height` option, and a pack that hard-codes 192 puts its
    // clouds somewhere the player can see is wrong. The tempting fix is to query the source
    // (DimensionSpecialEffects, or a mod's own config) and that is exactly the fragile version: it
    // needs to know WHICH source won, and the answer is different for every mod that changes it.
    //
    // Instead this is the ARGUMENT vanilla passes to addCloudsPass -- the final value, after every
    // mod that wanted to change it already has. There is nothing left to be compatible with. Any
    // mod that alters the height by any mechanism visible at that call boundary is picked up with
    // no code here naming it.
    //
    // DELIBERATELY NOT RESET PER FRAME, unlike the did-cancel flags either side of it. Those are
    // records of a decision made this frame and are meaningless carried forward; this is a SETTING,
    // and the last value the game reported stays true until the game reports another. Zeroing it
    // each frame would blank it on exactly the frames the cloud pass does not run, which is when a
    // consumer most needs the last known good answer.
    private static float cloudAltitude;

    private SkyFrameState() {
    }

    /**
     * Commits the sky did-cancel flag (u_SkyColor.w), set by LevelRendererSkyPassMixin during
     * sky-pass registration. Called unconditionally every frame that {@code addSkyPass} runs,
     * whether or not the pass was actually cancelled -- there is no longer an "inactive" variant,
     * because there is no longer any data here that a non-cancelling frame would need to zero.
     *
     * @param cancelled  true iff the mixin actually cancelled vanilla's sky pass this frame
     */
    public static void commitSky(boolean cancelled) {
        skyDidCancel = cancelled ? 1.0f : 0.0f;
        // See the field comment on cloudsDidCancel for why this reset must run before
        // LevelRendererCloudsPassMixin's commitClouds() call this frame.
        cloudsDidCancel = 0f;
    }

    /**
     * Commits the clouds did-cancel flag (u_SkyState.z), set by LevelRendererCloudsPassMixin
     * during addCloudsPass registration -- after {@link #commitSky}'s per-frame reset (see the
     * field comment), so this is always the last write to this lane each frame.
     *
     * @param cancelled  true iff the mixin actually cancelled vanilla's clouds pass this frame
     */
    public static void commitClouds(boolean cancelled) {
        cloudsDidCancel = cancelled ? 1.0f : 0.0f;
    }

    /**
     * Records the cloud altitude the game is using, in world blocks -- see the field comment for
     * why this is read from vanilla's own argument rather than derived.
     *
     * <p>Committed on EVERY frame vanilla registers a clouds pass, whether or not Fornax cancelled
     * it. The height is a property of the world and the player's settings, not of our decision to
     * paint, and a pack that has clouds switched off still wants the number the moment it switches
     * them on.
     *
     * @param altitude  vanilla's own {@code cloudHeight} argument to {@code addCloudsPass}
     */
    public static void commitCloudAltitude(float altitude) {
        // Guard the value rather than trusting it: a mod is free to pass anything here, and a NaN
        // would propagate into the uniform, through the pack's plane intersections, and out as a
        // sky-wide white band with no error anywhere -- the same failure class the march's own
        // horizontal-ray epsilon exists to prevent. A non-finite or non-positive height is
        // indistinguishable from "not told yet", so it reads as that.
        cloudAltitude = (Float.isFinite(altitude) && altitude > 0.0f) ? altitude : 0.0f;
    }

    public static float skyboxFlag() { return skyDidCancel; }

    public static float cloudsFlag() { return cloudsDidCancel; }

    /** The game's cloud altitude in world blocks, or 0.0 if no cloud pass has run this session. */
    public static float cloudAltitude() { return cloudAltitude; }
}
