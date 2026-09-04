package dev.icehunter.fornax.pipeline;

import java.util.List;

/**
 * The member order of the {@code u_PbrSettings} uniform block, as ONE ordered list.
 *
 * <p><b>Why this class exists at all.</b> {@code u_PbrSettings} is a std140 block whose members are
 * matched to Java writes POSITIONALLY -- the GLSL names it and Java writes floats in sequence, and
 * nothing connects the two but convention. Before this class, that convention was a hand-written
 * {@code .putFloat(...)} chain in {@link dev.icehunter.fornax.mixin.sodium.UniformBufferManagerMixin}
 * and a hand-written member list in the pack's {@code terrain.fsh}, kept in step by a comment. A name
 * inserted into one and not the other compiles cleanly on BOTH sides and silently reads its
 * neighbour's value: a wrong exposure, or a POM depth that is really a POM quality. No validation
 * layer can see it, because both sides are individually well-formed.
 *
 * <p>So the ordering is not documented here, it is DEFINED here, once. {@code updatePbrSettings()}
 * iterates this array rather than restating it, which removes the Java half of the drift by
 * construction -- there is no second ordering left for it to disagree with. The GLSL half cannot be
 * removed the same way (the pack owns its own shader text), so it is PINNED instead: {@code
 * PbrSettingsLayoutTest} and {@code PlaguePackLoadsTest} parse the block out of the shader source and
 * assert it name-for-name, in order, against {@link #MEMBERS}.
 *
 * <p><b>APPEND ONLY.</b> No member carries an explicit {@code layout(offset=)}, so appending at the
 * end preserves every existing member's std140 offset and a shader declaring only a SHORT PREFIX of
 * this list stays correct. That is not hypothetical: Fornax's own built-in fallback {@code
 * terrain.fsh} declares two members, and the {@code sample_pack} fixture declares two. Both are
 * legal prefixes and must remain so. Inserting a member in the middle instead moves every offset
 * after it and silently corrupts all three declarations at once.
 *
 * <p><b>Why a block of bridged options exists at all</b>, rather than the generic {@code
 * u_PackOptions} block every fullscreen pass gets: a DEFERRED geometry pass binds Sodium's terrain
 * bind group, which has no {@code u_PackOptions} entry. {@code ShaderChunkRendererBindGroupMixin}
 * appends this small block to that layout instead. Adding {@code u_PackOptions} there and deleting
 * this block is the better end state and is deliberately NOT done here -- the spliced block would
 * redeclare these same names and collide with the pack's hand-written one, so it is an
 * all-at-once migration rather than an incremental one.
 */
public final class PbrSettingsLayout {

    /** One member of the block: the runtime-option name it mirrors, and the value used with no pack loaded. */
    public record Member(String option, float fallback) {}

    /**
     * The block's members, IN STD140 ORDER. Every entry names a pack RUNTIME option that already
     * exists with a live slider; this list is a delivery route, never a declaration.
     *
     * <p>Fallbacks are the values Fornax uses when no pack is loaded (or before its options buffer
     * exists), chosen so PBR lighting looks identical to a no-pack build rather than snapping to
     * zero.
     */
    public static final List<Member> MEMBERS = List.of(
            // --- Per-texel bump/AO, the block's original two members ---------------------------
            new Member("u_BumpStrength", 0.5f),
            new Member("u_AOStrength", 1.0f),
            // --- Parallax. All runtime, including the step count: the shader loops to a fixed
            // maximum and breaks early, so quality costs no pipeline rebuild. A compile option would
            // rebuild every pipeline per adjustment, stalling long enough to trip MetalFX's semaphore
            // timeout.
            //
            // The defaults are the conventional ones for this effect: a quarter-block displacement
            // reads as relief on a 16-texel face without the silhouette breaking up at grazing
            // angles, and 32 steps is where the march stops showing stair-stepping on that depth.
            // Both are the values a first-principles pass lands on, and both are common across
            // unrelated implementations of the technique.
            new Member("u_PomDepth", 0.25f),
            new Member("u_PomQuality", 32.0f),
            new Member("u_PomDistance", 32.0f),
            new Member("u_PomAllowCutout", 0.0f),
            new Member("u_PomDebug", 0.0f),
            // Scales the AUTHORED emission lane only -- the labPBR `_s` alpha an artist painted, as
            // distinct from the light level vanilla says a block emits.
            new Member("u_AuthoredEmission", 0.35f),
            // --- Fog and the display transform, for the FORWARD TRANSLUCENT arm ------------------
            //
            // Translucent terrain (glass, stained glass, ice, honey) draws AFTER the deferred resolve,
            // so it compiles the pack terrain shader's forward arm and composites into the
            // already-tonemapped frame. To dissolve into the same veil as the opaque terrain behind
            // it, that arm needs the shared fog and the pack's own display transform -- and exposure,
            // the tonemap curve's shape and the grade are all runtime options.
            //
            // These fallbacks are deliberately NEUTRAL rather than copies of Plague's own defaults
            // (1.65 exposure, 1.05 contrast, 0.25 dark desaturation, ...). They are reachable only
            // when no pack is loaded -- and with no pack loaded there is no pack terrain shader
            // running to read them, so the values are unobservable. A neutral pass-through is the
            // honest thing to put in an unreachable slot; copying a pack's numbers into the engine
            // would be a second, silently-staleable set of that pack's defaults.
            new Member("u_FogDensity", 1.0f),
            new Member("u_FogBorderDensity", 1.0f),
            new Member("u_ScreenBrightness", 0.5f),
            new Member("u_Exposure", 1.0f),
            new Member("u_TmContrast", 1.0f),
            new Member("u_TmWhitePath", 1.0f),
            new Member("u_TmDarkDesaturation", 0.0f),
            new Member("u_Saturation", 1.0f),
            new Member("u_Contrast", 1.0f),
            // --- Three plain-multiplier scalars ----------------------------------------------------
            //
            // Wave strength, snow amount and splash density are runtime sliders, not compile
            // options: none of them is branched on -- each is a plain multiplier -- so a compile
            // option's necessarily-discrete value list buys nothing here but a cycle-button UI. They
            // ride here for the usual reason: their consumer is terrain.fsh.
            //
            // Fallbacks are the pack's own former 100%/60% defaults expressed as scalars. Unlike the
            // display-transform members above these are not reachable neutrally -- with no pack
            // loaded there is no wave field and no snow to scale -- so the value is unobservable
            // either way and matching the pack reads more honestly than 0.
            new Member("u_WaveStrength", 1.0f),
            new Member("u_SnowAmount", 1.0f),
            new Member("u_SplashDensity", 0.6f),
            // --- Underwater depth-darkening floor, bridged for the same reason as the fog pair
            // above: the pack's underwater include takes it as a parameter, and terrain's
            // translucent forward arm (the glass fog site) is a deferred-family program with no
            // u_PackOptions block, so the value must ride here or the identifier is undefined at
            // the first terrain draw. APPENDED, per this class's own rule. Fallback matches the
            // pack's shipped default; unreachable without a pack loaded, like the members above.
            new Member("u_DepthDarkness", 0.10f),
            // LabPBR decode audit (2026-08-09) Round 10 instrument: gates terrain.fsh's gAlbedoOut
            // diagnostic repaint (see GBufferDebugView#ENV_DECOMP_ALBEDO_IDENTITY_INPUTS). Off (0)
            // is inert -- terrain writes its normal composited albedo, unchanged. APPENDED LAST, per
            // this class's own rule.
            new Member("u_AlbedoIdentityDebug", 0.0f),
            // Shared wave-clock multiplier. Geometry consumes it through this bridge while the
            // fullscreen water and shaft passes receive the same option through u_PackOptions.
            // Neutral one preserves the pre-option clock. APPENDED LAST by the std140 ABI rule.
            new Member("u_WaveSpeed", 1.0f),
            // --- Atmosphere, and why all six have to travel together -----------------------------
            //
            // A pack whose sky is computed rather than authored derives its FOG colour from that
            // same sky, and terrain's translucent arm fogs glass with it. So the moment any of
            // these moves, two programs have to agree about it: the fullscreen resolve, which gets
            // them through u_PackOptions, and terrain, which is a deferred-family program and
            // cannot. Bridging five of the six and leaving one behind would be worse than bridging
            // none -- a pane of glass would haze to a slightly different sky than the wall it is
            // set into, which is precisely the seam the pack's own fog notes warn about.
            //
            // Fallbacks are all neutral, and here that word means something exact rather than
            // "whatever the pack ships": each is the multiplier that leaves the physical model at
            // its published values. One standard atmosphere of air, one clear-day aerosol load, one
            // 300-Dobson ozone column, unit gain on both bodies, and a fully dark-adapted eye. With
            // no pack loaded these are unobservable, but unlike the wave and snow members above
            // they are reachable neutrally, so neutral is what they say. APPENDED LAST, in the
            // pack's own declaration order, by the std140 ABI rule.
            new Member("u_AirDensity", 1.0f),
            new Member("u_AirTurbidity", 1.0f),
            new Member("u_AirOzone", 1.0f),
            new Member("u_SunIntensity", 1.0f),
            new Member("u_MoonIntensity", 1.0f),
            new Member("u_NightScotopic", 1.0f),
            // --- The dome, for the same reason and by the same rule ------------------------------
            //
            // The six above describe the air; these describe how a sky is built from it. Terrain's
            // translucent arm fogs toward that sky, so it needs both halves for the same reason it
            // needed the first: a pane of glass and the wall behind it have to agree about the
            // horizon.
            //
            // These were not added by hand. everyRuntimeOptionReachableFromADeferredGeometryProgramIsBridged
            // named each one, and the file that declares it, on the first run after the pack's sky
            // began importing its atmosphere -- which is the job that test exists for, and the
            // reason this list can be extended without the extension being a guess.
            //
            // Fallbacks are the pack's shipped defaults rather than neutral values, and here that is
            // the honest choice: unlike a density multiplier there is no value of these that leaves
            // the sky "unmodified". A twilight span of zero is not an absent sunset, it is an
            // instantaneous one.
            new Member("u_TwilightSpan", 1.0f),
            new Member("u_SkyGradient", 3.6f),
            new Member("u_SunsetBandWidth", 1.3f),
            new Member("u_SunsetBandHeight", 0.5f),
            new Member("u_SunGlowStrength", 1.2f),
            new Member("u_SunGlowTightness", 90.0f),
            new Member("u_MoonGlowStrength", 0.8f),
            new Member("u_MoonGlowTightness", 240.0f),
            new Member("u_SkyBrightness", 1.0f),
            new Member("u_SkyBiomeTint", 0.25f),
            // --- Warmth ---------------------------------------------------------------------------
            //
            // Two of these four the deferred arm will never evaluate: it computes no lighting, so
            // nothing there calls the warmth helper or builds an ambient. They ride here anyway,
            // because reachability is what the bridge contract is about rather than use -- the
            // options are DECLARED in the sky include that terrain imports, so their identifiers
            // exist in that program whether or not a line reads them, and an unbridged one is either
            // a link failure or a silent compile-time default depending on which path built it.
            //
            // Deciding per option would mean re-deciding every time a call site moved. Bridging the
            // set the include declares needs deciding once.
            new Member("u_SunsetSkyWarmth", 0.85f),
            new Member("u_SunsetTemp", 1500.0f),
            new Member("u_SunsetLightWarmth", 0.45f),
            new Member("u_AmbientSkyBleed", 1.0f),
            // --- The fog drive, all seventeen together --------------------------------------------
            //
            // The pack's fog controls round moved the fog model's authored constants onto runtime
            // sliders and added time-of-day and climate modulators; the terrain translucent arm
            // builds the same PlagueFogDrive the fullscreen passes do, so every option the drive
            // macro reaches must ride this bridge. One set, appended LAST in the pack's own
            // declaration order (fog_options.glsl), by the std140 ABI rule -- splitting them
            // across rounds would break the positional lockstep.
            //
            // Fallbacks are the pack's shipped defaults, by the same reasoning as the dome ten
            // above: there is no value of a fog layer height that leaves fog "unmodified", and
            // with no pack loaded nothing reads them anyway.
            new Member("u_FogDistance", 1.0f),
            new Member("u_FogSharpness", 1.0f),
            new Member("u_FogHeight", 26.0f),
            new Member("u_FogHighAltitude", 0.073f),
            new Member("u_FogMorningMist", 1.0f),
            new Member("u_FogNight", 1.0f),
            new Member("u_FogDayVariance", 0.5f),
            new Member("u_FogRainResponse", 1.0f),
            new Member("u_FogRainDepth", 0.98f),
            new Member("u_FogWetMist", 1.0f),
            new Member("u_FogColdMist", 1.0f),
            new Member("u_FogDryClear", 0.5f),
            new Member("u_NetherFogDensity", 1.5f),
            new Member("u_NetherFogDistance", 0.50f),
            new Member("u_FogClimbRise", 0.44f),
            new Member("u_FogCaveGuardLo", 0.05f),
            new Member("u_FogCaveGuardHi", 0.35f),
            new Member("u_FogBorderGateNear", 0.55f),
            new Member("u_FogBorderGateFar", 0.80f),
            // Mist Closeness: how far in the pack's mists pull the fog onset. Landed one round
            // after the seventeen above, so it is APPENDED after them regardless of where the
            // pack's own declaration file placed it -- position is the ABI here, per this
            // class's rule. Fallback is the pack's shipped default, same reasoning as the rest
            // of the fog set.
            new Member("u_FogMistReach", 1.0f),
            // --- The fog feature toggles and per-type Advanced overrides --------------------------
            //
            // The controls-cleanup round: seven tick boxes (each fog behaviour switchable live),
            // the Advanced Overrides gate, and each fog type's own Amount/Distance/Sharpness copy
            // of the main three, engaged only while that type is active AND Advanced is on.
            // Appended as one set, in the pack's own declaration order, by the std140 ABI rule.
            // Fallbacks are the shipped defaults: everything on, Advanced off, overrides neutral.
            new Member("u_FogEnableDistance", 1.0f),
            new Member("u_FogEnableEdge", 1.0f),
            new Member("u_FogEnableMorning", 1.0f),
            new Member("u_FogEnableNight", 1.0f),
            new Member("u_FogEnableWet", 1.0f),
            new Member("u_FogEnableCold", 1.0f),
            new Member("u_FogEnableDry", 1.0f),
            new Member("u_FogAdvanced", 0.0f),
            new Member("u_FogMorningDensity", 1.0f),
            new Member("u_FogMorningDistance", 1.0f),
            new Member("u_FogMorningSharpness", 1.0f),
            new Member("u_FogNightDensity", 1.0f),
            new Member("u_FogNightDistance", 1.0f),
            new Member("u_FogNightSharpness", 1.0f),
            new Member("u_FogWetDensity", 1.0f),
            new Member("u_FogWetDistance", 1.0f),
            new Member("u_FogWetSharpness", 1.0f),
            new Member("u_FogColdDensity", 1.0f),
            new Member("u_FogColdDistance", 1.0f),
            new Member("u_FogColdSharpness", 1.0f),
            new Member("u_FogDryDensity", 1.0f),
            new Member("u_FogDryDistance", 1.0f),
            new Member("u_FogDrySharpness", 1.0f),
            new Member("u_PomShadowStrength", 0.6f),
            // --- Glass refraction -----------------------------------------------------------------
            //
            // Slab thickness for translucent terrain's refraction. Rides this bridge for the usual
            // reason: its only consumer is terrain.fsh's forward arm, which has no u_PackOptions
            // block. Fallback 0.0 because a refracting engine default would be Fornax picking a look.
            // APPENDED LAST by the std140 ABI rule.
            new Member("u_RefractStrength", 0.0f));

    /**
     * Bytes the block occupies, for the ring buffer's allocation.
     *
     * <p>std140 packs consecutive scalars at their natural 4-byte alignment, so this is simply four
     * bytes per member, rounded up to a 16-byte multiple (the alignment a uniform block's total size
     * is required to respect). DERIVED rather than written as a literal: the literal was {@code 32}
     * for exactly eight members, and the whole point of this class is that adding a member must not
     * require remembering a second place.
     */
    public static final int SIZE_BYTES = ((MEMBERS.size() * Float.BYTES) + 15) / 16 * 16;

    private PbrSettingsLayout() {}
}
