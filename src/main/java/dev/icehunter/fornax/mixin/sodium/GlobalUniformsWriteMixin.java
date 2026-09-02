package dev.icehunter.fornax.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.Std140Builder;
import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.pass.shadow.ShadowFrameState;
import dev.icehunter.fornax.pass.taa.CameraJitter;
import dev.icehunter.fornax.pipeline.CameraMotionState;
import dev.icehunter.fornax.pipeline.PreviousFrameCameraTransform;
import dev.icehunter.fornax.pipeline.HeldLight;
import dev.icehunter.fornax.pipeline.LocalActorFrameState;
import dev.icehunter.fornax.pipeline.SkyFrameState;
import dev.icehunter.fornax.pipeline.SkyProbe;
import dev.icehunter.fornax.pipeline.WaterSurfaceTracker;
import dev.icehunter.fornax.pipeline.WaterTransitionTracker;
import dev.icehunter.fornax.pipeline.WetnessState;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Unique;
import dev.icehunter.fornax.voxel.EmitterFrameState;
import dev.icehunter.fornax.voxel.VoxelWindow;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.nio.ByteBuffer;

/**
 * Appends Fornax's {@code u_Globals} tail fields (bytes 184..832: two previous-frame camera
 * matrices, current/previous jitter vec2s, {@code u_InvProjModelView}, {@code u_SunViewProj},
 * {@code u_VoxelWindow}, {@code u_CameraAbs}, then the sky tail -- {@code u_SkyColor}, {@code
 * u_SunriseColor}, {@code u_SkyCelestial}, {@code u_SkyState} -- then the one-vec4 water tail,
 * {@code u_WaterState} (Water Round C Task 4), then the one-vec4 shadow tail, {@code
 * u_ShadowMapParams} (the shadow radial-distortion bias), then the one-vec4 camera-sky-light tail,
 * {@code u_CameraSkyLight} (the cave/border-fog enclosure round), the later frame/held-light/
 * weather/camera-motion tails, and finally the four-vec4 generic local-actor ABI) to Sodium's
 * per-frame terrain uniform block.
 *
 * <p>Wraps the terminal {@code Std140Builder.get()} invocation inside {@code GlobalUniforms}'s
 * {@code write(ByteBuffer)} ({@code DynamicUniformStorage.DynamicUniform} contract) so the extra
 * fields land on the same builder instance immediately after the official
 * {@code ...putFloat(fadeInFactor).putInt(useRgbaTextureFiltering)} writes, with zero re-statement
 * of the official fields (no drift risk on upstream changes). See {@code UniformBufferManagerMixin}
 * for the paired 256->608 {@code DynamicUniformStorage} size widening and the separate PBR
 * settings buffer it carries.
 *
 * <p>The record carries the two main-camera matrices as its own {@code projection}/{@code
 * modelView} components, shadowed here for the {@code u_InvProjModelView} product.
 * {@code update(...)} constructs+writes this record exactly once per frame (the
 * {@code hasUpdatedThisFrame} guard), and that single write is the one every terrain draw reads
 * back -- see SodiumWorldRendererOrchestrationMixin's "Matrix delivery" doc for why it always
 * carries the MAIN camera's matrices, never the shadow light's.
 *
 * <p>Layout note (std140): the official chain ends at byte 184; the appended block continues with
 * one ivec4 ({@code u_VoxelWindow}, self-16-aligned at 464) then one vec3 ({@code u_CameraAbs},
 * 480, padded to 496) -- the scalar-after-vec3 landmine structurally cannot occur because every
 * member that follows it (the sky tail, then the water tail, then the shadow tail, then the
 * camera-sky-light tail) is a full vec4. The sky tail itself is four vec4s at 496/512/528/544.
 * Water Round C Task 4 appends one vec4, {@code u_WaterState}, at 560, the shadow-acne fix round
 * appends one further vec4, {@code u_ShadowMapParams}, at 576, and the cave/border-fog enclosure
 * round appends one further vec4, {@code u_CameraSkyLight}, at 592; past the jitter-immunity mat4,
 * {@code u_FrameState}, {@code u_HeldLight} and {@code u_WeatherAnchor}, the water-motion-vector
 * round appends {@code u_CameraDelta} at 720. The local-actor ABI then occupies four vec4s at
 * 736/752/768/784, {@code u_WorldClock} sits at 800 and {@code u_WorldBounds} at 816, ending the
 * block at 832. All are vec4-tail-safe for the same reason, matching both
 * {@code UniformBufferManagerMixin}'s widened {@code DynamicUniformStorage} block size and the
 * {@code globals.glsl} override's declared struct.
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager$GlobalUniforms")
public class GlobalUniformsWriteMixin {
    // State for the u_WaterState.z filter -- static because Sodium constructs a fresh GlobalUniforms
    // record per frame; the filter must outlive the record. The level identity guards against the
    // state outliving the WORLD: log out submerged and back in submerged, or change dimension
    // submerged, and without it the filter would lerp from the previous world's surface altitude
    // for a third of a second.
    @Unique
    private static final WaterSurfaceTracker fornax$waterSurface = new WaterSurfaceTracker();
    @Unique
    private static final WaterTransitionTracker fornax$waterTransition = new WaterTransitionTracker();
    @Unique
    private static Object fornax$smoothedWaterLevel = null;

    @Shadow
    @Final
    private Matrix4f projection;

    @Shadow
    @Final
    private Matrix4f modelView;

    @WrapOperation(
            method = "write",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/buffers/Std140Builder;get()Ljava/nio/ByteBuffer;")
    )
    private ByteBuffer fornax$appendMotionVectorFields(Std140Builder builder, Operation<ByteBuffer> original) {
        Vector2f currentJitter = CameraJitter.currentOffsetNdc();
        Vector2f previousJitter = CameraJitter.previousOffsetNdc();

        // Jitter sequence advances unconditionally; only the UPLOADED uniform is gated. When the
        // active AA method doesn't bake jitter into gl_Position (OFF/SSAA), uploading the real
        // nonzero offsets would give terrain.vsh's motion-vector math a phantom frame-oscillating
        // term on a stationary camera -- upload (0,0) instead.
        boolean wantsJitter = FornaxConfig.get().aaMethod.wantsJitter();
        float currentJitterX = wantsJitter ? currentJitter.x() : 0.0f;
        float currentJitterY = wantsJitter ? currentJitter.y() : 0.0f;
        float previousJitterX = wantsJitter ? previousJitter.x() : 0.0f;
        float previousJitterY = wantsJitter ? previousJitter.y() : 0.0f;

        builder.putMat4f(new Matrix4f(PreviousFrameCameraTransform.getProjection()))
                .putMat4f(new Matrix4f(PreviousFrameCameraTransform.getModelView()))
                .putVec2(currentJitterX, currentJitterY)
                .putVec2(previousJitterX, previousJitterY)
                .putMat4f(new Matrix4f(this.projection).mul(this.modelView).invert())
                // u_SunViewProj: this frame's sun/moon shadow light view-projection matrix,
                // committed by SodiumWorldRendererOrchestrationMixin before the frame's first
                // renderLayer/update() call (its "Ordering guarantee" doc).
                .putMat4f(new Matrix4f(ShadowFrameState.current()));

        // u_VoxelWindow + u_CameraAbs (emitter-lights milestone): window geometry (xyz = center
        // SECTION coords, w = diameter) + absolute camera position for resolve's light-volume cell
        // mapping. ONE putIVec4 then the trailing vec3 -- see the layout note in the class javadoc.
        VoxelWindow.WindowState fornaxWindow = VoxelWindow.currentState();
        builder.putIVec4(fornaxWindow.centerX(), fornaxWindow.centerY(), fornaxWindow.centerZ(),
                        fornaxWindow.diameter())
                .putVec3(EmitterFrameState.camX(), EmitterFrameState.camY(), EmitterFrameState.camZ());

        // Sky tail (bytes 496..560): the sky's DATA, read live from the camera's environment
        // attribute probe right here (SkyProbe) -- the third application of the same live-read
        // pattern the wind clock and eye-in-water flag below already use, and for the same reason.
        // GlobalUniforms.write(...) runs exactly once per frame in EVERY dimension regardless of
        // which passes fire, so a live read here has no pass-gating gap to work around: a holder
        // committed only down the branch that CANCELS vanilla's sky (which requires the pack's
        // SKY_PROCEDURAL option) would leave every other pack reading zeroes for sky colour, rain,
        // sun angle and moon phase, and a zero vec3 is a plausible colour, so that reads as "the
        // ambient looks wrong" rather than as an error. See SkyProbe for the full rationale and
        // vanilla-parity notes.
        //
        // The two DID-CANCEL flags still come from SkyFrameState and still should: they record a
        // decision this engine made this frame, not a fact about the world. u_SkyColor.w is
        // committed at sky-pass registration, u_SkyState.z's clouds flag by
        // LevelRendererCloudsPassMixin later the same frame (see SkyFrameState's field comment for
        // why that ordering is guaranteed); both run before any terrain draw writes u_Globals.
        SkyProbe.Values sky = SkyProbe.read();
        builder.putVec4(sky.skyR(), sky.skyG(), sky.skyB(), SkyFrameState.skyboxFlag());
        builder.putVec4(sky.sunriseR(), sky.sunriseG(), sky.sunriseB(), sky.starBrightness());
        builder.putVec4(sky.sunDirX(), sky.sunDirY(), sky.sunDirZ(), sky.moonPhase());
        // w = wind clock: computed LIVE here instead of read from SkyFrameState, for the exact
        // same reason the u_WaterState.x eye-in-water flag below is computed live rather than read
        // from a frame-state holder committed by a conditionally-invoked pass mixin. A holder
        // committed only from LevelRendererCloudsPassMixin's addCloudsPass HEAD injection
        // (SkyFrameState.commitClouds(cancelled, windClock)) would only advance on frames where
        // that injection fires -- which requires BOTH GraphRunner.packOwnsClouds() true AND
        // vanilla's own addCloudsPass to run at all (vanilla skips addCloudsPass entirely whenever
        // CloudStatus.OFF or the cloud color's alpha is 0, see renderClouds), so disabling clouds
        // would freeze the lane at whatever SkyFrameState's own per-frame reset puts it at,
        // stalling terrain.fsh/water_composite.fsh's `waveClock = u_SkyState.w / 20.0` and
        // freezing wave animation entirely. GlobalUniforms.write(...) runs exactly once per frame
        // in EVERY dimension regardless of cloud settings (this class's own javadoc), so computing
        // the clock live right here has no dependency on addCloudsPass firing at all --
        // LevelRendererCloudsPassMixin still calls SkyFrameState.commitClouds() for the did-cancel
        // flag alone (u_SkyState.z), and both the pack's clouds march and this uniform read the
        // same live clock, so cloud wind and wave wind never diverge when both run.
        long windClockTicks = Minecraft.getInstance().level.getGameTime();
        float windClockPartialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        // Wraps at 2^20 ticks: float precision holds exact integers to 2^24, and the wrap (once
        // per ~14.5 real-time days of world time) lands mid-noise harmlessly -- same wrap/rationale
        // the old LevelRendererCloudsPassMixin computation used.
        float windClock = (float) (windClockTicks % 1_048_576L) + windClockPartialTick;
        // x = rain level 0..1, y = sun angle (radians), z = clouds did-cancel flag, w = wind clock
        // (see globals.glsl's u_SkyState comment -- both sides must stay in lockstep).
        builder.putVec4(sky.rainLevel(), sky.sunAngleRadians(),
                SkyFrameState.cloudsFlag(), windClock);

        // Water tail (bytes 560..576, Water Round C Task 4): x = 1.0 iff the camera eye is in
        // water THIS frame, computed live right here instead of read from a frame-state holder
        // committed by a sky-pass mixin -- a holder committed from LevelRendererSkyPassMixin's
        // addSkyPass HEAD injection (SkyFrameState.commitEyeInWater) would go stale for the whole
        // Nether visit, since addSkyPass is never called in SkyType.NONE dimensions: a player who
        // enters the Nether while submerged would keep the flag frozen at whatever it was on the
        // last Overworld frame. GlobalUniforms.write(...) runs exactly once per frame in EVERY
        // dimension (the
        // hasUpdatedThisFrame guard this class's javadoc already documents), so reading the live
        // camera state right here has no such gap. Minecraft.getInstance().gameRenderer
        // .mainCamera().getFluidInCamera() is the EXACT same call GameRenderer itself uses to
        // populate CameraRenderState.fogType (bytecode-verified against the real MC 26.2 jar,
        // javap -c net.minecraft.client.renderer.GameRenderer: the CameraRenderState.fogType
        // field write is a direct putfield off this invokevirtual's result) -- the same field
        // LevelRendererSkyPassMixin's old cancel guards already compared against FogType.WATER, so
        // this is bit-for-bit the same submersion test, just called from a site that runs every
        // frame instead of one gated on sky-pass registration. FogType.WATER excludes
        // LAVA/POWDER_SNOW (see Camera.getFluidInCamera's own fluid-tag check), matching the T4
        // spec's WATER-only requirement. .y carries the fluid enum, .z the detected surface
        // altitude. .w is a signed camera-crossing envelope: negative while entering, positive while
        // exiting, zero when no transition is active. WaterTransitionTracker owns the one-second
        // lifetimes and resets across worlds, so packs receive data rather than renderer
        // policy and can choose distortion, droplets, audio-reactive effects, or nothing.
        FogType fluidInCamera = Minecraft.getInstance().gameRenderer.mainCamera().getFluidInCamera();
        boolean eyeInWater = fluidInCamera == FogType.WATER;
        // .y widens the same submersion test into the full enum Iris/OptiFine packs expect from
        // isEyeInWater (0 none, 1 water, 2 lava, 3 powder snow). Written into a lane this block
        // already reserved and zero-filled, so .x keeps its exact previous meaning and no existing
        // pack changes behaviour -- packs that only ever asked "am I underwater" still get that.
        float fluidKind = switch (fluidInCamera) {
            case WATER -> 1.0f;
            case LAVA -> 2.0f;
            case POWDER_SNOW -> 3.0f;
            default -> 0.0f;
        };
        // .z = the altitude of the WATER SURFACE above a submerged camera, in world blocks -- the
        // lane that lets a pack's depth darkening be CONTINUOUS in the camera's own Y instead
        // of quantized to vanilla's integer sky-light levels (the proxy it replaces stepped
        // brightness per block while descending and read any roof as abyssal depth).
        //
        // TOPMOST water in the bounded column, not the first non-water block: the first non-water
        // block reads a two-block ledge overhead as "the surface", so swimming out from under any
        // overhang, arch or monument wall would snap the whole frame's darkening 5-15x in ONE
        // frame -- the exact stepping complaint the lane exists to fix. Scanning the whole bound
        // and keeping the TOPMOST water passes through SUBMERGED overhangs (there is
        // still water further up the same column, so the raw altitude is still the real surface)
        // and waterlogged blocks; the constant 96-lookup cost while submerged is one chunk column.
        //
        // A column capped by LAND rather than more water is a different case the topmost-water scan
        // cannot distinguish on its own: under a shore, a hill or a monument wall there is no water
        // above the roof at all, so the raw altitude collapses to the underside of that roof, and
        // caustics would visibly SLIDE DOWN FROM THE TOP over about a third of a second while
        // swimming under an overhang, because the filter below eases toward that collapsed altitude
        // exactly like it would ease toward a real surface. WaterSurfaceTracker tells the two apart
        // by whether the sky is reachable directly above the raw altitude, and holds the last
        // open-sky baseline instead of chasing a ceiling.
        //
        // The residual genuine discontinuity -- surfacing inside an air pocket, or exiting a
        // sealed cave into open ocean, where "the surface above me" really does jump -- is
        // smoothed with a per-frame exponential (0.12/frame: ~0.3 s to 90% at 60 fps; frame-rate
        // dependent and deliberately so, a time source here would couple this lane to the pause
        // menu). The filter SNAPS on the dive-in frame rather than lerping from the previous
        // swim's altitude. 0.0 when not submerged (consumers branch on .x, like every lane here).
        float waterSurfaceAltitude = 0.0f;
        if (Minecraft.getInstance().level != fornax$smoothedWaterLevel) {
            fornax$smoothedWaterLevel = Minecraft.getInstance().level;
            fornax$waterSurface.reset();
        }
        if (eyeInWater) {
            BlockPos cameraBlock = Minecraft.getInstance().gameRenderer.mainCamera().blockPosition();
            BlockPos.MutableBlockPos scan = cameraBlock.mutable();
            int topWaterOffset = 0;
            for (int step = 0; step < 96; step++) {
                if (Minecraft.getInstance().level.getFluidState(scan).is(FluidTags.WATER)) {
                    topWaterOffset = step;
                }
                scan.move(0, 1, 0);
            }
            float raw = (float) (cameraBlock.getY() + topWaterOffset + 1);
            boolean openToSky = Minecraft.getInstance().level.canSeeSky(
                    new BlockPos(cameraBlock.getX(), (int) raw, cameraBlock.getZ()));
            waterSurfaceAltitude = fornax$waterSurface.updateSubmerged(raw, openToSky);
        } else {
            // A DRY CAMERA STILL NEEDS THIS VALUE. The whole scan was gated on eyeInWater, so above
            // water u_WaterState.z stayed 0.0 -- and every shader test asking "is this fragment
            // below the water plane" then compares against zero and answers no. That one missing
            // number is why caustics vanish when you look down at water from outside it: the effect
            // gates on a fragSubmerged flag derived from this altitude.
            //
            // The submerged scan walks UP from the camera to find the top of the column it is in,
            // which is meaningless from outside. From above, walk DOWN to the first water block --
            // its top face is the surface. Same 96-block budget and the same smoothing, so crossing
            // the surface does not pop between the two arms.
            BlockPos cameraBlock = Minecraft.getInstance().gameRenderer.mainCamera().blockPosition();
            BlockPos.MutableBlockPos scan = cameraBlock.mutable();
            int foundY = Integer.MIN_VALUE;
            for (int step = 0; step < 96; step++) {
                if (Minecraft.getInstance().level.getFluidState(scan).is(FluidTags.WATER)) {
                    foundY = scan.getY();
                    break;
                }
                scan.move(0, -1, 0);
            }
            boolean rawFound = foundY != Integer.MIN_VALUE;
            float raw = rawFound ? (float) (foundY + 1) : 0.0f;
            waterSurfaceAltitude = fornax$waterSurface.updateDry(rawFound, raw);
        }
        float waterTransition = fornax$waterTransition.update(
                Minecraft.getInstance().level, eyeInWater, System.nanoTime() * 1.0e-9);
        builder.putVec4(eyeInWater ? 1.0f : 0.0f, fluidKind, waterSurfaceAltitude, waterTransition);

        // Shadow-map tail (bytes 576..592): x = the shared radial-distortion bias, committed by
        // SodiumWorldRendererOrchestrationMixin alongside u_SunViewProj (same "Ordering guarantee"
        // this class's javadoc already documents for that matrix -- both ride the same commit call,
        // so both are always this frame's fresh values, never stale). shadow.vsh's write-side warp
        // and gbuffer_resolve.fsh's read-side warp both read this one field.
        builder.putVec4(ShadowFrameState.currentBias(), 0.0f, 0.0f, 0.0f);

        // Camera-sky-light tail (bytes 592..608, cave/border-fog enclosure round): x = vanilla's
        // SKY light level (LightLayer.SKY, 0..15) AT THE CAMERA'S OWN BLOCK POSITION this frame,
        // normalized to 0..1 -- computed live right here, same "live, every frame, every dimension"
        // shape as the eyeInWater flag and windClock just above (not a frame-state holder committed
        // by a conditionally-invoked pass mixin), for the identical reason: GlobalUniforms.write(...)
        // runs exactly once per frame in EVERY dimension regardless of which passes fire, so this
        // read site has no pass-gating gap to work around. Camera.blockPosition() (javap-verified:
        // public BlockPos blockPosition(), returns Mth.floor of the camera's own Vec3 position) is
        // the same block-quantization Camera uses internally; Level.getBrightness(LightLayer, pos)
        // is inherited from BlockAndLightGetter (javap-verified: public default int
        // getBrightness(LightLayer, BlockPos)), the same lighting-engine query vanilla's own
        // ambient-occlusion/mob-spawning code uses to read a block's current sky exposure. This is
        // the CAMERA-side enclosure signal the cave-damping comments in gbuffer_resolve.fsh/
        // terrain.fsh have queued as a follow-on since the fog-polish round: those files' existing
        // per-FRAGMENT skyLight approximation stays exactly as-is for the outdoor "distant roofed
        // patch" case, and now combines with this per-CAMERA value for the "player is genuinely
        // underground" case the fragment-only signal's distance cap could never reach. yzw reserved
        // (garbage-VRAM law -- no consumer reads them yet).
        BlockPos cameraBlockPos = Minecraft.getInstance().gameRenderer.mainCamera().blockPosition();
        int cameraSkyLightRaw = Minecraft.getInstance().level.getBrightness(LightLayer.SKY, cameraBlockPos);
        float cameraSkyLight = cameraSkyLightRaw / 15.0f;

        // .y = what falls out of the sky AT THE CAMERA, in the same 0/1/2 enum shape u_WaterState.y
        // already uses for fluids: 0 none, 1 rain, 2 snow. Written into a lane this block already
        // reserved and zero-filled, so .x keeps its exact previous meaning and no existing pack
        // changes behaviour.
        //
        // CAMERA-local ON PURPOSE, which is the opposite of the call made for wetness, and the two
        // are not in tension. Wetness is a property of a SURFACE -- has rain soaked THAT block --
        // so a camera-local gate there would dry a savanna beach whenever the player stood on the
        // ocean beside it; the per-block flag riding a_Normal.w (see
        // MaterialIdContext.setPrecipitation) is what wetness uses instead. Precipitation you watch
        // FALL is a different question:
        // it is weather in the air around the eye, vanilla itself only ever draws it in a radius
        // about the camera, and a pack's full-screen pass has no per-column biome data to consult
        // even if it wanted to. Both lanes exist, they answer different questions, neither replaces
        // the other.
        //
        // Biome.getPrecipitationAt(BlockPos, int) is vanilla's own query (javap-verified against the
        // 26.2 jar) -- the same one WeatherEffectRenderer runs per column before deciding to build
        // one -- so a pack gating on this is dry in exactly the biomes vanilla is dry in, and is
        // told SNOW where vanilla would draw snow instead of raining in a taiga. Passing the
        // camera's own Y feeds the height-dependent rain/snow split, which is what lets a mountain
        // peak snow while the valley below it rains.
        float cameraPrecipitation = switch (Minecraft.getInstance().level.getBiome(cameraBlockPos)
                .value().getPrecipitationAt(cameraBlockPos, Minecraft.getInstance().level.getSeaLevel())) {
            case RAIN -> 1.0f;
            case SNOW -> 2.0f;
            case NONE -> 0.0f;
        };
        // .z = TERRAIN RENDER DISTANCE IN BLOCKS, so a GEOMETRY pass can reach it at all.
        //
        // Byte-for-byte the same derivation GraphRunner uses for u_PassParams.u_Param2
        // (options.renderDistance() * 16, zero when there are no client options), and it MUST stay
        // that way: a forward geometry program fogs against this while gbuffer_resolve.fsh fogs
        // against u_Param2, and the border term reaches opacity 1.0 at exactly the render distance it
        // was given. Two different anchors put the veil in two different places, which shows as a
        // banner still vivid against terrain that has already dissolved into the sky.
        //
        // Duplicated rather than shared because the two travel by genuinely different routes -- one
        // is per-pass and matched by name, the other is per-frame and global -- and the alternative
        // was making a geometry pass carry a u_PassParams block it has no binding for. The duplicate
        // is one expression, in two places, both commented at each other.
        float renderDistanceBlocks = 0.0f;
        Minecraft mcForRenderDistance = Minecraft.getInstance();
        if (mcForRenderDistance != null && mcForRenderDistance.options != null) {
            renderDistanceBlocks = mcForRenderDistance.options.renderDistance().get() * 16.0f;
        }
        // .w: the game's own cloud altitude in blocks, captured by LevelRendererCloudsPassMixin from
        // vanilla's argument to addCloudsPass -- the final value after any mod that moves the cloud
        // layer. 0.0 until the first cloud pass of the session; consumers must branch on that rather
        // than take it literally, or a deck anchored on it collapses to bedrock on frame one. This
        // fills the lane that lane's own doc comment reserved.
        builder.putVec4(cameraSkyLight, cameraPrecipitation, renderDistanceBlocks,
                SkyFrameState.cloudAltitude());

        // u_InvProjModelViewNoJitter (bytes 608..672, TAAU jitter-immunity round, 2026-07-22):
        // the SAME inverse product as u_InvProjModelView above but built from CameraJitter's
        // captured UNJITTERED projection -- the exact precedent VoxelWaterReflExtra already uses
        // for its world-space DDA ray ("a sub-pixel jitter cyclically flips which voxel a
        // near-silhouette ray hits, reading as a flash", CameraJitter's own doc). Screen-space
        // passes must keep using the jittered u_InvProjModelView (they must stay consistent with
        // the rasterized G-buffer); this one exists solely for WORLD-SPACE lookups (the resolve's
        // light-volume sample, the analytic pass's shadow-ray origin) whose lattice/voxel
        // addressing must not wobble with the jitter sequence. mat4 after a vec4 -- std140-safe.
        // Widens u_Globals from 608 to 672 bytes; UniformBufferManagerMixin's size constant and
        // globals.glsl's block declaration move in lockstep.
        builder.putMat4f(new Matrix4f(CameraJitter.currentUnjitteredProjection())
                .mul(this.modelView).invert());

        // u_FrameState (bytes 672..688): the small per-frame scalars Iris/OptiFine packs reach for
        // constantly and Fornax had no equivalent of -- frameCounter for anything that must vary
        // frame to frame, the camera's own BLOCK light (the sky component already has its own lane
        // in u_CameraSkyLight, so this completes vanilla's eyeBrightness pair), and thunder, which
        // packs storm effects off and which rain level alone cannot distinguish. A vec4 appended
        // after a mat4 is std140-safe by the same rule every tail above relies on; widens u_Globals
        // from 672 to 688 bytes, and UniformBufferManagerMixin's size constant plus globals.glsl's
        // declaration move in lockstep.
        int cameraBlockLightRaw = Minecraft.getInstance().level.getBrightness(LightLayer.BLOCK,
                Minecraft.getInstance().gameRenderer.mainCamera().blockPosition());
        float thunder = Minecraft.getInstance().level.getThunderLevel(1.0f);
        // The w lane carries surface wetness -- the accumulated state that
        // rain level cannot express on its own (see WetnessAccumulator and globals.glsl's lane doc).
        // Stepped here rather than on a tick, because it must advance with real elapsed time for the
        // ramp to be frame-rate independent; the accumulator clamps a stall's oversized delta itself.
        // Wetness is the world's SOAK RAMP and stays global on purpose. Whether rain actually lands
        // on a given surface is a per-BLOCK fact, carried through a_Normal.w from the chunk mesh as
        // a TYPE -- 0 none, 1 rain, 2 snow (see MaterialIdContext.setPrecipitation) -- so a shader
        // gates this ramp on "that block's type is RAIN" and gets the right answer on both sides of
        // a shoreline and of a snowline.
        //
        // The gate stays per-block rather than camera-local: a camera-local gate would dry a
        // savanna beach whenever the player stood on the ocean beside it, and gating in both places
        // would dry a border from either side. The type exists so a pack does not reach for
        // u_CameraSkyLight.y to answer "is it SNOWING here" and make the same mistake one layer up.
        builder.putVec4(CameraJitter.frameCounter(), cameraBlockLightRaw / 15.0f, thunder,
                WetnessState.step(sky.rainLevel()));

        // u_HeldLight (bytes 688..704): the light level of what the player is HOLDING, per hand,
        // normalized 0..1. Vanilla surfaces this nowhere a shader can reach -- the held item is drawn
        // by the hand renderer and nothing tells the world pass a light source is riding the camera --
        // so every pack that lights from a held torch needs the engine to supply it. The shader ABI
        // for the pair is heldBlockLightValue/heldBlockLightValue2; a pack's held-light path reads
        // those names and does nothing at all without them.
        //
        // LEVEL only: colour, falloff and position offset are the pack's decisions, and baking any of
        // them here would be the engine dictating a look. See HeldLight for the full rationale and for
        // why this is an independent implementation rather than a port of any existing provider
        // interface.
        //
        // Read live here, like the wind clock and the eye-in-water flag above, because a lane fed from
        // a conditionally-invoked pass mixin goes stale on frames that pass does not run. zw reserved,
        // zero-filled (garbage-VRAM law). A vec4 after a vec4 is std140-safe; widens u_Globals from 688
        // to 704 bytes, and UniformBufferManagerMixin's size constant plus globals.glsl move in lockstep.
        builder.putVec4(HeldLight.mainHandNormalized(), HeldLight.offHandNormalized(), 0.0f, 0.0f);

        // u_WeatherAnchor (bytes 704..720): the player BODY's interpolated position -- deliberately
        // NOT the camera. Entity.getPosition(partialTick) is the entity's own interpolated position
        // (javap-verified: public final Vec3 getPosition(float)), so it carries no head bob, no walk
        // sway and no view roll; those live in the camera transform that GameRenderer applies on top.
        //
        // The camera position two tails up (u_CameraAbs) is Sodium's drawChunkLayer argument, which
        // is the animated camera. That is right for anything reconstructing a world position from
        // depth, where the bob cancels, and WRONG for anything that adds its own offset to the
        // camera to build a world position -- a weather volume anchored to it swims with every
        // footstep. Both lanes exist so each site can take the one it actually means.
        //
        // Falls back to the camera position when there is no player (title screen, disconnect), so
        // the lane is never a stale value from a previous world -- the same live-read discipline
        // every tail above follows. A vec4 after a vec4 is std140-safe; widens u_Globals from 704 to
        // 720 bytes, and UniformBufferManagerMixin's size constant plus globals.glsl move in
        // lockstep (GlobalsLayoutContractTest fails the build if they ever do not).
        var localPlayer = Minecraft.getInstance().player;
        float anchorX = EmitterFrameState.camX();
        float anchorY = EmitterFrameState.camY();
        float anchorZ = EmitterFrameState.camZ();
        if (localPlayer != null) {
            float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
            var body = localPlayer.getPosition(partialTick);
            anchorX = (float) body.x;
            anchorY = (float) body.y;
            anchorZ = (float) body.z;
        }
        builder.putVec4(anchorX, anchorY, anchorZ, 0.0f);

        // u_CameraDelta (bytes 720..736): how far the camera moved since the previous frame, in
        // blocks. Not a convenience -- it is the only shader-visible form the camera's TRANSLATION
        // takes outside terrain.vsh's push constants, and without it the two previous-frame matrices
        // eight tails up can only reproject a camera that did not move (see CameraMotionState, and
        // globals.glsl's own lane comment, for why both model-view matrices are rotation-only).
        //
        // Read from a frame-state holder rather than live, unlike the sky/held-light/anchor lanes
        // above, and deliberately: the value is a DIFFERENCE against PreviousFrameCameraTransform,
        // which GraphRunner.finish() overwrites at the end of every frame. Computed here it would be
        // a difference against the wrong snapshot on any frame whose ordering shifted. The commit
        // site is SodiumWorldRendererOrchestrationMixin's opaque HEAD, beside EmitterFrameState's,
        // which is before this write and before that overwrite. w reserved, zero-filled
        // (garbage-VRAM law). A vec4 after a vec4 is std140-safe; widens u_Globals from 720 to 736
        // bytes, and UniformBufferManagerMixin's size constant plus globals.glsl move in lockstep
        // (GlobalsLayoutContractTest fails the build if they ever do not).
        builder.putVec4(CameraMotionState.deltaX(), CameraMotionState.deltaY(),
                CameraMotionState.deltaZ(), 0.0f);

        // Generic pack input, not water policy: position/kind, frame motion/time, heading/shape,
        // then fluid contact/vertical speed/reset. Plague uses it for a wake field today; another
        // pack can use the same facts for snow compression, foliage contact or particles without
        // adding a pack name or mechanism to the renderer.
        LocalActorFrameState.Snapshot actor = LocalActorFrameState.current();
        builder.putVec4(actor.x(), actor.y(), actor.z(), actor.actorKind());
        builder.putVec4(actor.deltaX(), actor.deltaY(), actor.deltaZ(), actor.deltaSeconds());
        builder.putVec4(actor.forwardX(), actor.forwardZ(), actor.halfWidth(), actor.halfLength());
        builder.putVec4(actor.fluidKind(), actor.surfaceContact(), actor.verticalSpeed(),
                actor.reset() ? 1.0f : 0.0f);

        // World clock (bytes 800..816): the calendar, which the wind clock above is not. The day
        // clock follows /time set, doDaylightCycle and its own rate; getGameTime() ignores all
        // three, so a pack keying weather on it sees the sun cross hundreds of days while its day
        // count barely moves.
        //
        // getDefaultClockTime(), not getOverworldClockTime(): this dimension's own clock, so the day
        // count agrees with the dimension-aware SUN_ANGLE the probe hands SkyProbe.
        //
        // Index and fraction split because a float32 carries 24 mantissa bits. Both the division and
        // the modulus are taken on the long, and floorDiv/floorMod agree if a clock runs negative.
        long dayTime = Minecraft.getInstance().level.getDefaultClockTime();
        float dayIndex = (float) Math.floorDiv(dayTime, 24000L);
        float dayFraction = (float) (Math.floorMod(dayTime, 24000L) / 24000.0);
        builder.putVec4(dayIndex, dayFraction, 0.0f, 0.0f);

        // World bounds (bytes 816..832): sea level, the buildable Y range, and which dimension this
        // is. getMaxY() is exclusive, the game's own convention, and published as such. The
        // dimension is the level's identity, not its sky kind: Skybox.NONE would fold the Nether in
        // with every custom skyless dimension, which is exactly the thing a pack needs to tell apart.
        var world = Minecraft.getInstance().level;
        float dimension = world.dimension() == Level.OVERWORLD ? 1.0f
                : world.dimension() == Level.NETHER ? 2.0f
                : world.dimension() == Level.END ? 3.0f
                : 0.0f;
        builder.putVec4(world.getSeaLevel(), world.getMinY(), world.getMaxY(), dimension);

        return original.call(builder);
    }

}
