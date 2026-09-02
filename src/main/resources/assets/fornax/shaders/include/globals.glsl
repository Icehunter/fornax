// Fornax per-region uniform block ("u_Globals").
//
// Byte layout is a hard interface contract, not creative content: this buffer is written on the
// Java side by Sodium's own UniformBufferManager.update() (unmodified) followed by Fornax's own
// mixin appending additional writes afterward, and it is bound to shader stages as a single GPU
// buffer object shared by every terrain draw. The first block of members below therefore has to
// describe the exact same std140 field sequence Sodium's own update() writes, in order: projection,
// modelView, fogColor, fogEnvironmentalRange, fogRenderRange, atlasTexelSize, texCoordShrink,
// fadePeriodInverse, useRgssFilter.
//
// Buffer-sharing contract: a GLSL/SPIR-V uniform block only has to be as large as what it declares
// -- binding a larger backing buffer than a shader's interface block needs is legal under both the
// OpenGL and Vulkan uniform-buffer-binding rules. Fornax's mixin widens the one backing buffer
// Sodium allocates so every shader bound against it reads correctly from the same physical buffer:
// Sodium's own unmodified shaders declare only the fields above, while Fornax's terrain/post
// shaders declare the full struct below, appended in the same buffer rather than a second uniform
// buffer object.
#ifdef FORNAX_COMPUTE_GLOBALS
layout(std140, set = 0, binding = FORNAX_GLOBALS_BINDING) uniform u_Globals {
#else
layout(std140) uniform u_Globals {
#endif
    mat4 u_ProjectionMatrix;
    mat4 u_ModelViewMatrix;

    vec4 u_FogColor;
    vec2 u_EnvironmentFog;
    vec2 u_RenderFog;

    vec2 u_TexelSize;
    vec2 u_TexCoordShrink;

    float u_FadePeriodInv;
    bool u_UseRGSS;

    // --- Fornax extension fields below. Appended by UniformBufferManagerMixin's @WrapOperation
    // around the same Std140Builder instance's terminal get() call, so the two blocks above/below
    // this comment are written by two different call sites but land in one contiguous buffer.
    // GLSL's own std140 offset rules place these members at the same byte offsets the Java-side
    // append produces, since both sides apply the same alignment rules to the same declared type
    // sequence -- no offset needs to be hand-computed or kept in sync separately.

    // Previous frame's projection/model-view pair, snapshotted by PreviousFrameCameraTransform
    // once per frame before the current frame's camera moves. Consumed by terrain.vsh to build a
    // matching previous-frame clip-space position for motion-vector reprojection.
    mat4 u_PrevProjectionMatrix;
    mat4 u_PrevModelViewMatrix;

    // Current and previous frame's sub-pixel TAA jitter offset, in NDC units (see CameraJitter).
    // terrain.vsh subtracts both from their respective frame's clip-space position before the
    // motion-vector delta so jitter wobble never shows up as reprojection error.
    vec2 u_JitterOffset;
    vec2 u_PrevJitterOffset;

    // CPU-computed inverse(u_ProjectionMatrix * u_ModelViewMatrix), shared by every full-screen
    // pass that needs to turn a depth-buffer sample back into a camera-relative world position
    // (SSAO, SSR, the G-buffer resolve) without paying for a per-fragment matrix inverse.
    mat4 u_InvProjModelView;

    // This frame's sun/moon shadow light view-projection matrix (ShadowCamera.compute's combined
    // proj*view), camera-relative like every other matrix above -- composes directly with a
    // camera-relative world position (e.g. gbuffer_resolve's u_InvProjModelView reconstruction) to
    // sample sunShadowMap. Written by UniformBufferManagerMixin's append (see its own doc comment
    // for why the value is safe to read regardless of which terrain draw's update() call last wrote
    // it); only meaningful while the pack's SHADOWS compile option is enabled -- an identity
    // matrix otherwise (see ShadowFrameState).
    mat4 u_SunViewProj;

    // Voxel light-volume window geometry (emitter-lights milestone): xyz = the toroidal window's
    // center SECTION coordinates, w = its diameter in sections. Read by gbuffer_resolve.fsh to map
    // an absolute world position into a window slot + cell (same floorMod addressing as
    // VoxelWindow.slotFor / the light compute passes). Zero-diameter before the window first
    // activates -- the resolve-side sampler treats that as "no volume data".
    ivec4 u_VoxelWindow;

    // The player camera's ABSOLUTE world position this frame (EmitterFrameState). A vec3 may only
    // be directly followed by a vec4-or-larger-aligned member (the scalar-after-vec3 std140 law:
    // a scalar/vec2 placed right after it would land in its tail pad on exactly one side of the
    // Java/GLSL interface) -- the sky tail below is all vec4s, so it is safe immediately after.
    // absolute worldPos = u_CameraAbs + the camera-relative worldPos this pass reconstructs.
    vec3 u_CameraAbs;

    // --- Sky tail (bytes 496..560; written by GlobalUniformsWriteMixin) ---
    //
    // The DATA lanes below (every field except the two did-cancel flags) come from SkyProbe, read
    // live off the camera's own environment attribute probe every frame, in every dimension,
    // regardless of which passes ran and regardless of whether this pack owns the sky. A pack does
    // NOT have to define SKY_PROCEDURAL to read a real sky colour, rain level, sun angle or moon
    // phase -- that used to be true and was a bug, not a contract: these lanes were committed only
    // down the sky-cancellation branch, so any pack that let vanilla draw the sky read zeroes for
    // all of it, and a zero vec3 is a plausible colour rather than a visible failure. See SkyProbe.
    //
    // The two DID-CANCEL flags (u_SkyColor.w, u_SkyState.z) remain conditional and should: they are
    // not facts about the world but the record of a decision the engine made this frame, committed
    // by the sky/clouds pass mixins and read by the pack so its paint decision cannot drift from
    // the cancellation.
    vec4 u_SkyColor;      // rgb = sky color attribute (ALWAYS populated); w = 1.0 iff vanilla's sky
                          //       pass was CANCELLED this frame (the pack must paint sky), else 0.0
                          //       (pack must discard)
    vec4 u_SunriseColor;  // rgb = sunrise/sunset color attribute; w = star brightness 0..1
    vec4 u_SkyCelestial;  // xyz = TRUE sun direction (moon = -xyz); w = moon phase index 0..7.
                          //       Distinct from u_PassParams.u_SunDirection.xyz, which is the
                          //       ACTIVE light -- the sun by day, the MOON once it sets. Use this
                          //       one to ask "is it day"; that one to shade and shadow.
    vec4 u_SkyState;      // x = rain level 0..1, y = sun angle (radians), z = 1.0 iff vanilla's
                          //       CLOUDS pass was CANCELLED this frame (the pack must paint
                          //       clouds), else 0.0 (pack must discard); w = wind clock (ticks
                          //       since world start, wrapped -- see LevelRendererCloudsPassMixin)

    // --- Water tail (bytes 560..576; x = camera-in-water flag, computed LIVE inline by
    // GlobalUniformsWriteMixin at write time from the camera's fluid state -- deliberately NOT
    // routed through SkyFrameState, whose commits only run when the sky pass runs and would leave
    // the flag stale-stuck in skyless dimensions). Appended after another vec4 (u_SkyState), so this is std140-safe by the
    // same "vec4 after vec4" rule the sky tail itself relies on -- no scalar-after-vec3 hazard.
    // Widens u_Globals from 560 to 576 bytes; every layout constant (UniformBufferManagerMixin's
    // ring-buffer size, this doc comment) moves in lockstep.
    // x = 1.0 iff the camera eye is in WATER specifically (unchanged since this lane was added).
    // y = which fluid the eye is in, as the enum Iris/OptiFine packs know as isEyeInWater:
    //     0 none, 1 water, 2 lava, 3 powder snow. Widened into a lane that was already reserved and
    //     zero-filled, so x keeps its exact prior meaning -- a pack that only asks "am I underwater"
    //     is unaffected, and one that needs to tell lava from powder snow now can.
    // z = the WATER SURFACE altitude above a submerged camera, in world blocks -- found by a
    //     bounded upward scan from the camera block while the fluid is water (the engine-side
    //     answer to Iris's trapEyeAltitude-based waterAltitude custom uniform). Continuous depth
    //     is then surfaceAltitude - u_CameraAbs.y, which is what lets a pack's depth darkening be
    //     smooth in the camera's own Y instead of quantized to vanilla's integer sky-light levels
    //     (the proxy it replaces stepped visibly per block and read any roof as abyssal depth).
    //     0.0 when not submerged; consumers must branch on x, like every other lane here.
    // w = signed camera water-crossing envelope: -1..0 while entering water (1 second),
    //     +1..0 after exiting (1 second), and 0 when inactive. The engine publishes the event and
    //     its lifetime; packs decide whether it drives lens droplets, distortion, particles, or
    //     nothing. Reuses the reserved lane, so the block remains 800 bytes.
    vec4 u_WaterState;    // x = 1.0 iff the camera eye is in water this frame
                          //       (CameraRenderState.fogType == FogType.WATER), else 0.0;
                          //       z = water surface altitude when submerged (see above)

    // --- Shadow-map tail (bytes 576..592; x = the shared radial-distortion bias, computed once per
    // frame by ShadowCamera.shadowMapBias(shadowDistance, resolution) -- FLOORED AT 0 below the
    // full-detail radius (25.6 blocks at the 2048 default map), see that method's own doc comment
    // for the derivation -- and committed alongside u_SunViewProj via
    // ShadowFrameState.commit(viewProj, bias) -- see that class's doc comment). Appended after
    // u_WaterState, another vec4, so this is std140-safe by the same "vec4 after vec4" rule every
    // other tail here relies on. FOUR shader sites across two repos read this ONE field rather
    // than recomputing the bias themselves, so none of them can drift apart (see
    // ShadowCamera.shadowMapBias's own javadoc for the full inventory: this repo's shadow.vsh write
    // side; Plague's shadow_entities.vsh write side, gbuffer_resolve.fsh's sampleSunShadow read,
    // and its shadow debug-view branch) -- each site's own doc comment carries the shared
    // xy/(length(xy)*bias+(1-bias)) formula. Widens u_Globals from 576 to 592 bytes; every layout
    // constant (UniformBufferManagerMixin's ring-buffer size, this doc comment) moves in lockstep.
    vec4 u_ShadowMapParams; // x = shadow-map radial-distortion bias (1 - R/shadowDistance with
                             //     R = the full-detail radius derived from the map resolution and
                             //     the centre-texel target, floored at 0); yzw reserved

    // --- Camera-sky-light tail (bytes 592..608; x = the vanilla SKY light level (LightLayer.SKY,
    // 0..15) AT THE CAMERA'S OWN BLOCK POSITION this frame, normalized to 0..1, computed LIVE by
    // GlobalUniformsWriteMixin from Minecraft.getInstance().level.getBrightness(LightLayer.SKY,
    // mainCamera().blockPosition()) -- same "live, every frame, every dimension" shape as
    // u_WaterState.x/u_SkyState.w rather than a frame-state holder, for the identical reason: this
    // read site runs unconditionally in GlobalUniforms.write(), so there is no pass-gating gap to
    // work around. This is the CAMERA-side enclosure signal the border/atmospheric-fog cave-damping
    // comments in gbuffer_resolve.fsh/terrain.fsh have documented as a queued follow-on since the
    // fog-polish round: those files' existing per-FRAGMENT skyLight-based caveDamp approximation
    // stays as-is for the outdoor "distant roofed patch" case (where the camera itself is NOT
    // enclosed), but the pack combines it with this per-CAMERA value for the "player is genuinely
    // underground, real cave sightline" case, which the fragment-only signal's ~96-block distance
    // cap could never reach. Appended after another vec4 (u_ShadowMapParams), so this is
    // std140-safe by the same "vec4 after vec4" rule every tail above relies on. Widens u_Globals
    // from 592 to 608 bytes; every layout constant (UniformBufferManagerMixin's ring-buffer size,
    // this doc comment) moves in lockstep.
    // .y carries what FALLS at the camera: 0 none, 1 rain, 2 snow -- vanilla's own
    // Biome.getPrecipitationAt at the camera's block, the same query WeatherEffectRenderer runs per
    // column, so a pack is dry in exactly the biomes vanilla is dry in and is told snow rather than
    // raining in a taiga. Camera-local ON PURPOSE and NOT interchangeable with the per-block
    // precipitation flag in a_Normal.w: that one answers "has rain soaked THIS surface" (and must be
    // per-block, or a shoreline dries from one side), while this answers "what is in the air around
    // the eye" -- which is all a full-screen precipitation pass can ask, having no per-column biome
    // data. Both exist; neither replaces the other.
    // .z carries the TERRAIN RENDER DISTANCE IN BLOCKS (renderDistance chunks * 16), 0.0 when there
    // are no client options (headless). Identical in value and derivation to
    // u_PassParams.u_Param2 -- and that is exactly why it is here: u_Param2 is filled per pass BY
    // NAME in GraphRunner, and a GEOMETRY pass has no u_PassParams block at
    // all, so the number is unreachable from one. A forward geometry program applying the same
    // border fog as the resolve has to anchor it to the same render distance or the two disagree
    // about where the world ends -- which reads as a banner still vivid against terrain that has
    // already dissolved into the sky, i.e. the very seam the fog exists to remove.
    // NOT u_RenderFog.y, which is the documented FALLBACK and a different quantity: it tracks
    // vanilla's fog ATTRIBUTE distances rather than the chunk grid, so it can sit beyond the real
    // cutoff and leave the veil below 1.0 exactly where geometry ends.
    // Written into a lane this block already reserved and zero-filled, so .x and .y keep their exact
    // prior meanings and no existing pack changes behaviour. Verified unread before use: a grep over
    // every Plague and Fornax shader found zero references to .z or .w in ANY form -- no component
    // access, no swizzle, no index, no whole-vector use.
    // .w carries the CLOUD ALTITUDE THE GAME IS USING, in world blocks -- vanilla's own cloudHeight
    // argument to addCloudsPass, captured by LevelRendererCloudsPassMixin.
    //
    // Read rather than derived, and that is the whole value of it. Vanilla's overworld figure is 192,
    // but mods move it (Sodium Extra ships a cloud_height option), so a pack that hard-codes 192 puts
    // its clouds somewhere the player can see is wrong. Querying the SOURCE instead would mean
    // knowing which source won, which differs per mod; this is the final argument, after everything
    // that wanted to change it already has, so there is nothing left to be compatible with.
    //
    // 0.0 until the first cloud pass of the session, and in any dimension that never registers one.
    // A CONSUMER MUST BRANCH ON THAT rather than take it literally -- a cloud deck anchored on a
    // literal 0 collapses to bedrock on frame one. It is NOT reset per frame (unlike the two
    // did-cancel flags, which are records of a per-frame decision): this is a setting, and the last
    // value the game reported stays true until it reports another.
    vec4 u_CameraSkyLight; // x = camera-block vanilla sky light, 0..1 normalized;
                           // y = precipitation at camera: 0 none, 1 rain, 2 snow;
                           // z = terrain render distance in blocks (== u_Param2);
                           // w = game's cloud altitude in blocks, 0.0 if not yet known

    // UNJITTERED inverse(projection * modelView) (bytes 608..672, TAAU jitter-immunity round,
    // 2026-07-22) -- the same inverse as u_InvProjModelView above but from CameraJitter's captured
    // pre-jitter projection. For WORLD-SPACE lookups only (light-volume sampling, voxel shadow-ray
    // origins): their lattice/voxel addressing must not wobble with the per-frame jitter sequence
    // (the VoxelWaterReflExtra DDA precedent). Screen-space reconstruction MUST keep using the
    // jittered u_InvProjModelView -- it has to agree with the rasterized G-buffer. mat4 after a
    // vec4, std140-safe; UniformBufferManagerMixin's 672 size constant moves in lockstep.
    mat4 u_InvProjModelViewNoJitter;

    // Per-frame scalars (bytes 672..688) that Iris/OptiFine packs depend on and Fornax previously had
    // no equivalent of.
    //   x = frameCounter -- monotonic, wrapped at 720720 (highly composite, so `mod N` cycles evenly
    //       for small N with no discontinuity at the wrap). Drives animated noise, temporal dither
    //       rotation, anything that must differ frame to frame.
    //   y = the camera block's BLOCK light, 0..1. Completes vanilla's eyeBrightness pair; the sky
    //       component lives in u_CameraSkyLight.x.
    //   z = thunder level, 0..1. Distinct from u_SkyState.x (rain): storms are what packs gate
    //       lightning and heavy-weather effects on, and rain level alone cannot express them.
    //   w = surface WETNESS, 0..1 -- how wet the world IS, not how hard it is raining. Ramps up as
    //       rain soaks in and decays far more slowly after it stops, so it lags u_SkyState.x in both
    //       directions and equals it only at rest. Use THIS, not the rain level, to darken albedo
    //       and smooth surfaces: driving wetness off rain directly snaps the whole world wet and dry
    //       the instant weather changes, which reads as a rendering glitch rather than as weather.
    //       A pack cannot build it itself -- a shader has no frame-to-frame memory to accumulate
    //       into -- which is why the engine owes it. Same semantic as OptiFine/Iris `wetness`; the
    //       model lives in WetnessAccumulator.
    vec4 u_FrameState;

    // Held-light tail (bytes 688..704): the light level of what the player is HOLDING, per hand,
    // normalized 0..1 (so 15 -- a lantern -- reads 1.0).
    //   x = main hand, y = off hand, zw reserved.
    //
    // Vanilla surfaces this nowhere a shader can reach: the held item is drawn by the hand renderer,
    // and nothing tells the world pass that a light source is riding the camera. Every pack that
    // lights the world from a held torch needs the engine to supply it -- the established shader ABI
    // for the pair is heldBlockLightValue/heldBlockLightValue2, and a pack's held-light path is inert
    // without them.
    //
    // LEVEL only. Colour, falloff and the position offset from eye to hand are the PACK's decisions;
    // baking any of them here would be the engine dictating a look. See HeldLight.java.
    //
    // Only block items report a level, since only a block has a light emission -- a torch item is a
    // BlockItem whose block emits 14. A non-block item that a pack would want lit reads 0; closing
    // that needs a data-driven table, not a guess here.
    vec4 u_HeldLight;

    // Weather anchor (bytes 704..720): xyz = the player BODY's interpolated world position, with no
    // head bob, no walk sway and no view roll. w reserved.
    //
    // THIS IS NOT u_CameraAbs, AND THE DIFFERENCE IS THE WHOLE POINT. u_CameraAbs is committed from
    // Sodium's drawChunkLayer camera position -- the animated render camera, bob included. Anything
    // that reconstructs a world position from the depth buffer is unaffected, because the bob rides
    // in the projection and cancels on both sides of `worldPos + u_CameraAbs`. But anything that
    // builds a world position by adding its OWN offset to the camera -- a weather volume, a particle
    // spawn region, any camera-centred field -- inherits the bob directly and swims with every
    // footstep. Precipitation shipped exactly that way and slid sideways as the player walked.
    //
    // So: simulate and anchor weather against THIS, and project with the animated camera. Weather
    // follows world physics, the active region follows the player's body, and only the final view
    // transform follows the bobbing head.
    vec4 u_WeatherAnchor;

    // Camera delta (bytes 720..736): xyz = how far the camera moved since the PREVIOUS frame, in
    // blocks (this frame minus last). w reserved.
    //
    // THE MISSING HALF OF u_PrevProjectionMatrix/u_PrevModelViewMatrix, and without it those two are
    // unusable outside the vertex stage. Every world position in this engine is CAMERA-RELATIVE and
    // the camera's translation lives entirely in the per-region offset, so both model-view matrices
    // carry rotation ONLY -- their difference says how the eye TURNED and nothing about how far it
    // travelled. terrain.vsh never notices, because it gets the translation for free as
    // u_PrevRegionOffset; a full-screen pass reconstructing a position from a depth buffer has it
    // nowhere, and could previously reproject correctly only for a camera that did not move.
    //
    // Usage is one line: given a CURRENT-camera-relative position P (e.g. from u_InvProjModelView),
    // `P + u_CameraDelta.xyz` is the same world point expressed relative to the PREVIOUS frame's
    // camera, ready for u_PrevProjectionMatrix * u_PrevModelViewMatrix. It lands on exactly the
    // position terrain.vsh builds from u_PrevRegionOffset, because it is the same pairing --
    // DrawContextVKMixin derives both region offsets from the same two camera snapshots this delta is
    // differenced from.
    //
    // Differenced in DOUBLE on the Java side and only then narrowed (see CameraMotionState): a float
    // holding an absolute coordinate near the world border has a quantum larger than ten frames of
    // travel, so subtracting two uploaded absolute positions in the shader could never have worked
    // even if the previous one were uploaded.
    vec4 u_CameraDelta;

    // Generic local-actor ABI (bytes 736..800). This exposes facts about the player or controlled
    // vehicle; packs remain responsible for deciding whether those facts drive water, snow, grass,
    // particles, or nothing at all.
    //   Position: xyz interpolated absolute position, w actor kind
    //             (0 none, 1 player, 2 boat, 3 other vehicle).
    //   Motion:   xyz current-minus-previous frame displacement, w clamped real frame seconds.
    //   Shape:    xy horizontal forward direction, z half width, w half length (blocks).
    //   Fluid:    x fluid kind (0 none, 1 water, 2 lava), y surface contact (0/1),
    //             z vertical speed (blocks/second), w temporal-history reset (0/1).
    vec4 u_LocalActorPosition;
    vec4 u_LocalActorMotion;
    vec4 u_LocalActorShape;
    vec4 u_LocalActorFluid;

    // World clock (bytes 800..816): the DAY the world is on, from this dimension's own day clock
    // (Level.getDefaultClockTime()).
    //   x = day index, floor(dayTime / 24000). Exact as a float past 16 million days.
    //   y = fraction through that day. z, w reserved, zero-filled.
    //
    // Not u_SkyState.w. That lane is getGameTime(), immune to /time set, doDaylightCycle and the
    // clock rate: right for a wind clock, wrong as a calendar. A pack keying weather on it sees the
    // sun cross hundreds of days while its day count advances by a fraction of one, with no error.
    //
    // Split because a float32 holds 24 mantissa bits: a single ticks/24000 loses tick resolution
    // past about 350 days. A pack cannot derive this either, since the moon phase gives the day
    // only modulo 8.
    vec4 u_WorldClock;

    // World bounds (bytes 816..832): the shape of the world the camera is in.
    //   x = sea level, blocks.
    //   y = lowest buildable Y (inclusive).
    //   z = one above the highest buildable Y (exclusive, the game's own convention).
    //   w = dimension: 0 other or custom, 1 overworld, 2 nether, 3 end.
    //
    // Zero-first on w so the zero-fill default is the honest "unknown", as every other lane's
    // enum does. A pack cannot derive any of these: sea level and height are per-dimension data
    // the shader never sees, and the dimension has no colour or angle that identifies it.
    vec4 u_WorldBounds;
};
