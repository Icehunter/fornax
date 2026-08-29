package dev.icehunter.fornax.config;

import java.util.List;

/**
 * Selects which raw G-buffer attachment the resolve pass writes to the screen instead of
 * the final lit terrain, for verifying the deferred render targets are populated correctly. Legacy
 * values keep their original shader ids 0-40; new views use explicit ids returned by
 * {@link #shaderId()} so appending or archiving a UI entry cannot silently change the shader ABI.
 * Ordinal 11 ({@link
 * #VOXEL_RAYMARCH}) is NOT a resolve branch -- see its own doc comment. Ordinals 13-15
 * ({@link #SCENE_HDR}, {@link #BLOOM}, {@link #EXPOSURE}) are terminal-pass branches in {@code
 * tonemap.fsh}, not {@code gbuffer_resolve.fsh} -- see their own doc comments.
 */
public enum GBufferDebugView {
    OFF,
    NORMALS,
    ALBEDO,
    MATERIAL,
    MOTION,
    SSAO,
    TAA,
    AO,
    /**
     * Grayscale view of the block-light level stored in gMaterialOut's alpha channel. Light-emitting
     * blocks and surfaces near them read bright; skylit-only terrain reads black. Drives the
     * ambient-occlusion exemption for emissive surfaces.
     */
    BLOCK_LIGHT,
    /**
     * Raw SSR buffer: {@code SsrManager.getBlendedView()}'s rgb (reflected color) premultiplied by
     * its alpha (hit confidence) -- misses (confidence 0) show black. Lets reflection data be
     * inspected directly, independent of the composite's Fresnel/smoothness gating in the default
     * lit branch.
     */
    SSR,
    /**
     * Material category id (from blocks.toml), unpacked from gNormalOut.a and hashed to a distinct
     * hue per category; uncategorized (id 0) reads black. Verifies the blockstate->id->vertex->
     * G-buffer channel end to end.
     */
    MATERIAL_ID,
    /**
     * Engine-owned voxel brick-grid raymarch debug view (ordinal 11). Unlike every value above, this
     * is NOT a {@code gbuffer_resolve.fsh} {@code u_Param3} branch -- that shader only handles 0-10, so
     * an {@code ordinal()} of 11 lands on its default (normal lit) branch. Instead, {@link
     * dev.icehunter.fornax.pass.voxel.VoxelDebugRaymarchPass} DDA-raymarches the harvested brick grid
     * on a compute queue and blits the result over the whole native frame at {@code
     * GameRenderer.renderLevel} RETURN, bypassing the pack's own presented output entirely. It exists
     * to prove the harvest/window/upload pipeline (Tasks 1-11) produces a grid whose shape matches the
     * real world -- see {@code VoxelDebugRaymarchPass}. Kept in this same enum (rather than a parallel
     * toggle) so it rides the existing Sodium Engine-page debug-view dropdown with no new UI mechanism.
     */
    VOXEL_RAYMARCH,
    // ^ RETIRED, retained only to hold ordinal 11 (see isSelectable). Its presentation path -- a
    // per-frame compute dispatch plus mapped readback, blitted over the native frame -- could wedge the
    // GPU, and on macOS a wedged GPU takes WindowServer down with it: a hard power-off, not a recoverable
    // crash. Selecting it does nothing; GameRendererMixin has no call site for it. The class behind
    // it lives on because it also owns the voxel grid the sun-shadow path needs every frame.
    /**
     * The sun/moon shadow-map visibility factor as grayscale -- 0/black = fully shadowed, 1/white =
     * fully lit. Resolve-branch ordinal 12 (gbuffer_resolve.fsh), sourced from {@code
     * sampleSunShadow}'s PCF-filtered depth-map compare against {@code builtin.sunShadowMap}.
     * Instrumentation for diagnosing shadow instability: shows the visibility mask itself, isolating
     * shadow-map sampling behavior from the lit composite.
     *
     * <p>The name refers to the voxel-ray-traced shadow mask ({@code rtDirect}) this ordinal no
     * longer renders -- it shows the sun/moon shadow-map visibility factor instead -- and keeps the
     * {@code RT_SHADOW} name (rather than {@code SUN_SHADOW}) because {@link FornaxConfig} persists
     * {@link FornaxSettings#debugView} through Gson's default enum handling, which
     * serializes/deserializes by {@link Enum#name()} with no fallback for unrecognized names:
     * renaming would silently break this field on any existing {@code fornax.json}.
     */
    RT_SHADOW,
    /**
     * Raw HDR scene color (rgba16f sceneHdr) shown WITHOUT exposure or tonemapping -- values above 1.0
     * clamp on display, which is the point: it lets the HDR headroom that emissive surfaces and bright
     * sky now occupy be inspected directly. Terminal-pass ordinal 13, branched in tonemap.fsh (NOT
     * gbuffer_resolve.fsh -- sceneHdr is resolve_hdr's OUTPUT, only readable downstream in tonemap).
     */
    SCENE_HDR,
    /**
     * The bloom pyramid's top level (bloomUp0) shown directly -- the blurred over-bright energy that
     * gets additively composited before tonemapping. Black when BLOOM_ENABLED is compiled off (the
     * target stays allocation-cleared). Terminal-pass ordinal 14, branched in tonemap.fsh.
     */
    BLOOM,
    /**
     * The auto-exposure scalar visualized as grayscale -- the adapted target exposure the tonemap pass
     * applies. Reflects the 1x1 exposure accumulator's log-luminance EMA; mid-gray under manual-only
     * exposure (AUTO_EXPOSURE off). Terminal-pass ordinal 15, branched in tonemap.fsh.
     */
    EXPOSURE,
    /**
     * Raw emitter light-volume sample at the surface point -- the trilinearly-sampled propagated
     * field, before u_EmitterStrength/saturation shaping, shown as color. Proves injection +
     * propagation BEFORE the composite is trusted (the instrument-first house method; the voxel
     * debug raymarch precedent). Resolve-branch ordinal 16 in gbuffer_resolve.fsh, live only when
     * BOTH HDR_ENABLE and EMITTER_LIGHTS are compiled on (the volume binding only exists on the
     * resolve_hdr_el graph entry); otherwise the branch compiles out and the view falls through to
     * the lit path. tonemap.fsh passes ordinal 16 through UNtonemapped (HDR values clamp on
     * display -- the point), exactly like the 1-12 passthrough.
     */
    EMITTER_LIGHT,
    /**
     * Engine-owned water pre-pass debug view (ordinal 17, appended last -- inserted mid-list would
     * shift every ordinal after it out from under {@code gbuffer_resolve.fsh}'s/{@code tonemap.fsh}'s
     * own hardcoded {@code u_DebugView} branch numbers, which this enum's own class javadoc requires
     * stay in lockstep). Like {@link #VOXEL_RAYMARCH}, this is NOT a resolve/tonemap branch -- those
     * shaders only handle ordinals up to {@link #EMITTER_LIGHT} (16), so ordinal 17 falls through to
     * their default (normal lit) branch. Instead {@link dev.icehunter.fornax.pass.water.
     * WaterPrepassDebugPass} samples {@code WaterSurfaceManager.getNormalView()} directly and blits
     * it over the already-restored native frame at {@code GameRenderer.renderLevel} RETURN,
     * overriding the pack's own presented output -- same bypass shape {@code VoxelDebugRaymarchPass}
     * uses, minus the compute half (this view samples an already-rasterized texture, no raymarch
     * needed). Deferred Water Task 1 spike instrumentation: proves the {@code WATER_PREPASS} render
     * pass actually rasterizes water surface content into {@code waterNormal} -- xyz remapped from
     * signed [-1,1] to displayable [0,1], so a real water-facing normal reads as a tinted color and a
     * cleared (no-water) pixel reads mid-gray. Superseded by a real graph-wired debug branch once
     * Water Round Task 2 adds {@code builtin.waterNormal} as a resolvable graph input.
     */
    WATER_PREPASS,
    /**
     * Engine-owned M1 DDA sun-shadow prototype debug view (ordinal 18, appended last -- same
     * "inserted mid-list would shift every ordinal after it" rule as {@link #WATER_PREPASS}).
     * Like {@link #VOXEL_RAYMARCH} and {@link #WATER_PREPASS}, this is NOT a resolve/tonemap branch
     * -- those shaders only handle ordinals up to {@link #EMITTER_LIGHT} (16) plus {@link
     * #WATER_PREPASS}'s own bypass (17), so ordinal 18 falls through to their default (normal lit)
     * branch. Instead {@link dev.icehunter.fornax.pass.voxel.CelestialShadowVoxelDebugPass} samples the
     * pack's own real graph target {@code celestialVisVoxel} directly (an r8 texture a pack's {@code
     * celestial_shadow} fullscreen fragment pass writes every frame -- see the pack's own {@code
     * graph.toml} for the DDA/window-addressing details) and blits it grayscale
     * over the already-restored native frame at {@code GameRenderer.renderLevel} RETURN, overriding
     * the pack's own presented output -- the exact same bypass shape {@link #WATER_PREPASS} uses
     * (a real, already-produced target with nothing left to dispatch on this pass's own side, unlike
     * {@link #VOXEL_RAYMARCH}'s own compute half). 1.0/white = lit, 0.0/black = occluded by the
     * brick-grid DDA; this is Milestone 1's acceptance instrument -- see
     * {@code docs/superpowers/specs/2026-07-17-voxel-default-lighting-design.md}.
     */
    CELESTIAL_SHADOW_VOXEL,
    /**
     * Combined surface-emission / material-id instrument. G = gMaterialOut.a's low emission nibble
     * ({@code unpackSurfaceEmission}, material_packing.glsl -- the exact value gbuffer_resolve.fsh's
     * self-glow term and bloom_extract.fsh's emitter classification both consume; exact k/15 steps).
     * R = 1.0 where the material id (decoded from gNormal.a, byte-identical to {@link #MATERIAL_ID})
     * is 0/uncategorized. B = material id / 32 where nonzero. Resolve-branch ordinal 19 in {@code
     * gbuffer_resolve.fsh}; {@code tonemap.fsh} passes it through untonemapped like the 1-12
     * passthrough. Reading a missing emissive glow: RED means the v_MaterialId chain is the break
     * (id 0 locks emission to zero); BLUE with no green means the id arrives but terrain.fsh's
     * emission math produced zero; BLUE-GREEN means the nibble is populated and the break is
     * downstream (check {@link #BLOOM}). Pair with {@link #BLOCK_LIGHT} (8, the alpha byte's other
     * nibble) to confirm the packed byte itself is sane.
     */
    SURFACE_EMISSION,
    /**
     * Analytic direct light IN ISOLATION (ordinal 20, appended last per this enum's lockstep rule).
     * Resolve-branch in {@code gbuffer_resolve.fsh}: outputs pure black for every pixel (sky
     * included), so sceneHdr holds ONLY what the additive {@code direct_light_analytic} pass
     * contributes on top; that pass stays live for this ordinal and applies a documented 8x
     * instrument gain so dim-but-alive contributions are distinguishable from dead-zero.
     * {@code tonemap.fsh} passes it through untonemapped. Isolates direct light in a way the
     * settings chain cannot: EMITTER_LIGHTS is the root of the lighting dependency chain and must
     * never be toggled as a diagnostic. Caveats: translucent geometry still writes sceneHdr and can
     * contaminate the view -- read it in an opaque scene.
     */
    ANALYTIC_DIRECT,
    /**
     * Exposure-independent instrument for the environment-specular magnitude question -- packs
     * THREE numbers into one pixel rather than a colour meant to be looked at: R = the environment
     * specular term's luminance ({@code reflEnv * specularAlbedo}, the exact quantity Plague's
     * {@code PLAGUE_ENV_SPECULAR} pack option gates before adding it to {@code lit}), G = that same
     * pixel's diffuse-term luminance ({@code litDiffuse}, Plague's {@code kD * albedo *
     * diffuseWithHeld}), B = R / max(R + G, 1e-4). Resolve-branch ordinal 21 in {@code
     * gbuffer_resolve.fsh}, appended last per this enum's own lockstep rule. Unlike ordinals 1-12
     * this one does NOT branch in the early G-buffer-read block -- neither quantity exists as a
     * value until that pack's material/lighting decode for the pixel has already run, so the
     * branch sits at the point in the shader where both are actually computed, same shape as
     * {@link #ANALYTIC_DIRECT}'s deep placement. Read regardless of whether
     * {@code PLAGUE_ENV_SPECULAR} is currently compiled in. {@code tonemap.fsh} passes it through
     * untonemapped.
     *
     * <p>Packs the RATIO rather than two separate raw-linear views because raw linear values in the
     * 0.01-0.15 range render as visually black, so a few-percent-too-bright contribution and a
     * correct one would look identical on screen; pairs with {@link
     * dev.icehunter.fornax.pipeline.EnvSpecularRatioReadback} for an on-demand numeric crosshair
     * readback -- exact numbers settle what no palette or side-by-side comparison can.
     * LabPBR decode audit instrumentation. INVALID OVER WATER: {@code
     * water_composite.fsh} runs after this branch and overwrites/blends water pixels with the real
     * composited reflection, with no debug-view awareness at all -- point the crosshair at opaque
     * geometry.
     */
    ENV_SPEC_RATIO,
    /**
     * Decomposition instrument, part 1 of 6 (with {@link #ENV_DECOMP_MIX}, {@link #ENV_DECOMP_MAT},
     * {@link #ENV_DECOMP_LOCAL}, {@link #ENV_DECOMP_AO}, {@link #ENV_DECOMP_RESIDUAL}) -- {@link
     * #ENV_SPEC_RATIO} named the specular path as ~50x brighter than diffuse for the same
     * surroundings; these ordinals report every term the ratio is built from, luma-reduced,
     * so the wrong factor is read off the crosshair rather than guessed at. R = {@code skyMiss}
     * (raw directional dome sample toward the reflection vector), G = {@code ambientColour}
     * (Fornax's SkyProbe average, scaled to a hemisphere-integrated diffuse-ambient table -- the
     * specific question this ordinal exists to answer: both describe the same sky, so if they are
     * in different units, whichever is larger is a candidate for the gap on its own), B = {@code
     * wideEnclosure} (the ground/block-light fallback radiance), A = {@code reflWide} (the
     * roughness-fallback lobe, ground/sky/enclosure already mixed). Resolve-branch ordinal 22 in
     * {@code gbuffer_resolve.fsh}, same deep-placement/number-carrier/water-invalid shape as
     * {@link #ENV_SPEC_RATIO}. LabPBR decode audit instrumentation.
     */
    ENV_DECOMP_SKY,
    /**
     * Decomposition instrument, part 2 of 3. R = {@code reflColor} (the sharp/mirror-direction
     * term: an SSR hit blended with a sky miss), G = {@code sharpAvail} (already a scalar -- the
     * blend weight between {@code reflColor} and {@code reflWide}, driven by roughness and
     * {@code u_SsrStrength}), B = {@code reflEnv} ({@code reflColor * sharpAvail + reflWide * (1 -
     * sharpAvail)}, the fully-mixed environment radiance), A = {@code specularAlbedo} (the Lazarov
     * split-sum energy term -- bounded, F0-and-roughness-derived, and the term this whole audit
     * confirmed is NOT where the ~50x lives). Resolve-branch ordinal 23 in {@code
     * gbuffer_resolve.fsh}, same shape as {@link #ENV_SPEC_RATIO}. LabPBR decode audit instrumentation.
     */
    ENV_DECOMP_MIX,
    /**
     * Decomposition instrument, part 3 of 6. R = {@code NdotV} (already a scalar), G = {@code
     * mat.alpha} (already a scalar -- the decoded GGX roughness, floored at the sun's angular
     * radius), B = {@code surfaceF0} ({@code plagueMaterialF0(mat, albedo)}, luma-reduced), A =
     * unused (three values, not four -- {@code gbuffer_resolve.fsh}'s own branch leaves it an
     * explicit 0.0 rather than repeating an earlier one). Resolve-branch ordinal 24, same shape as
     * {@link #ENV_SPEC_RATIO}. LabPBR decode audit instrumentation.
     */
    ENV_DECOMP_MAT,
    /**
     * Decomposition instrument, part 4 of 5 -- follow-up question, not a conclusion: measured
     * {@code wideEnclosure} is 0.74170 on the acacia door (a lantern lights it) against 0.16235 on
     * the iron control (no local light). Does the diffuse path see the same local-light picture the
     * specular path's {@code wideEnclosure}/{@code reflWide} ({@link #ENV_DECOMP_SKY}) already
     * folds in? R = {@code diffuseWithHeld} (luma-reduced -- the diffuse radiance the direct/ambient
     * lighting model plus held-light produces, before albedo multiplies it), G = {@code
     * blockRadiance} (luma-reduced -- the local block-light/held-light term the SPECULAR path's
     * {@code wideEnclosure} is built from, at {@code gbuffer_resolve.fsh}), B = {@code skyLight}
     * (already a scalar -- vanilla's own sky-light lightmap value for this fragment), A = {@code
     * envAccess} (already a scalar -- the specular/wide path's own sky-and-AO gate, {@code clamp(ao
     * * hereLight / openLight, 0, 1)}). Resolve-branch ordinal 25, same deep-placement shape as
     * {@link #ENV_SPEC_RATIO}. LabPBR decode audit instrumentation.
     */
    ENV_DECOMP_LOCAL,
    /**
     * Decomposition instrument, part 5 of 6 -- the AO/occlusion comparison {@link
     * #ENV_DECOMP_LOCAL} sets up: does the diffuse path apply the SAME occlusion to local light
     * that the specular path's {@code envAccess} applies to {@code wideEnclosure}, or a different
     * one? R = {@code wideHorizon} (already a scalar, content-side term repeated for convenience),
     * G = {@code litDiffuse} (luma-reduced, repeated from {@link #ENV_SPEC_RATIO} for convenience),
     * B = the diffuse path's ACTUAL applied AO factor -- Plague's {@code litResult.vanillaAO}
     * ({@code plagueVanillaAO}'s reshaped output, {@code shaders/include/main_lighting.glsl}: {@code
     * shade = directionShade * vanillaAO}, applied inside the squared {@code blockLighting +
     * sceneLighting^2} bracket -- exposed for this instrument only, via a new {@code
     * PlagueLitResult.vanillaAO} field; no shading maths changed, it stores an already-computed
     * local), A = the RAW {@code ao} scalar (labPBR AO x SSAO, {@code gbuffer_resolve.fsh}'s own
     * {@code float ao = ...}) the specular/wide path's {@code envAccess} consumes DIRECTLY,
     * unreshaped -- if B and A differ substantially, the two paths are not applying the same
     * occlusion to the same input. Resolve-branch ordinal 26, same shape as {@link
     * #ENV_SPEC_RATIO}. LabPBR decode audit instrumentation.
     */
    ENV_DECOMP_AO,
    /**
     * Decomposition instrument, part 6 of 6 -- closes the ratio question ({@link #ENV_SPEC_RATIO})
     * by measurement instead of estimate. {@code litDiffuse = kD * albedo * diffuseWithHeld} is the
     * ENTIRE relationship ({@code gbuffer_resolve.fsh:1242}, one line, no square root or other scale
     * between the two), but a luma-reduced readback can only report {@code dot(vec3, weights)}, and
     * {@code dot(a*b*c, w)} is not generally {@code dot(a,w)*dot(b,w)*dot(c,w)} unless the three
     * vectors share hue. R = {@code albedoLuma}, G = {@code kDLuma}, B = {@code
     * diffuseWithHeldLuma} (the three multiplicands, each luma-reduced independently), A = {@code
     * litDiffuseLuma / max(R*G*B, 1e-6)} -- 1.0 if luma commutes with the product cleanly; anything
     * else is the luma-of-product vs product-of-lumas gap, measured rather than guessed. Resolve-
     * branch ordinal 27, same shape as {@link #ENV_SPEC_RATIO}. LabPBR decode audit instrumentation.
     */
    ENV_DECOMP_RESIDUAL,
    /**
     * Follow-up to the LabPBR decode audit: every ordinal above ASSUMED what
     * gAlbedo's raw byte and {@code v_RawTint} contain at runtime -- reasoned from {@code
     * FornaxChunkVertex.java} plus vanilla's shade table, never measured. This closes half of that
     * gap, purely from values {@code gbuffer_resolve.fsh} already has in scope -- no terrain.fsh
     * cooperation needed. R = the RAW byte written to gAlbedo, luma ({@code albedoSample.rgb} before
     * decode -- exactly "the value actually written to gAlbedoOut"). G = that SAME byte decoded,
     * luma ({@code albedo}, the quantity {@link #ENV_DECOMP_RESIDUAL} already calls
     * {@code albedoLuma}, repeated here per the request to land it beside R in one press). B, A
     * unused (two values, not four). Resolve-branch ordinal 28 in {@code gbuffer_resolve.fsh}.
     * Only meaningful while {@link #ENV_DECOMP_ALBEDO_IDENTITY_INPUTS}'s pack-side gate,
     * {@code u_AlbedoIdentityDebug}, is OFF -- when it is on, gAlbedo holds that ordinal's
     * diagnostic floats instead of real colour, and decoding them here would be meaningless.
     */
    ENV_DECOMP_ALBEDO_WRITE_VS_READ,
    /**
     * Companion to {@link #ENV_DECOMP_ALBEDO_WRITE_VS_READ}: the identity's other two terms. The
     * claim under test is {@code texLuma * tintLuma == albedoLuma}, and this ordinal supplies
     * texLuma and tint's own r/g/b (a face SHADE is neutral grey, a biome TINT is not -- the three
     * channels tell those cases apart on sight; tintLuma is derived from them downstream in {@link
     * dev.icehunter.fornax.pipeline.EnvSpecularRatioReadback} rather than carried as a fifth value).
     * Neither term exists anywhere in {@code gbuffer_resolve.fsh}'s own scope -- both are
     * terrain.fsh-fragment-local (the raw atlas sample and {@code v_RawTint}) -- so unlike every
     * ordinal above, this one requires a SECOND, independent toggle on the pack side,
     * {@code u_AlbedoIdentityDebug} (a runtime option bridged through {@code u_PbrSettings}, exactly
     * like {@code u_PomDebug}), because terrain.fsh is a DEFERRED geometry program and never
     * receives {@code u_Param3}/debugView at all -- selecting this ordinal alone cannot make
     * terrain.fsh repaint anything. With the pack option ON, terrain.fsh overwrites gAlbedo with R
     * = raw atlas sample luma (before any decode or multiply), G/B/A = {@code v_RawTint.rgb}; this
     * ordinal reads those four bytes back RAW, skipping the normal sRGB decode (they were never
     * display-encoded colour). Resolve-branch ordinal 29. With the pack option OFF, this reads the
     * real (encoded) albedo bytes misinterpreted as four diagnostic floats -- meaningless, and the
     * two toggles must be set together for a real reading.
     */
    ENV_DECOMP_ALBEDO_IDENTITY_INPUTS,
    /**
     * Number carrier, same shape as {@link #ENV_SPEC_RATIO} through {@link
     * #ENV_DECOMP_ALBEDO_IDENTITY_INPUTS}. Measures the underwater closure's actual applied values
     * rather than re-deriving them from the formula.
     *
     * <p>The closure keys on {@code length(worldPos)} -- radial distance from a camera-relative
     * origin -- which makes the {@code smoothstep}'s transition surface a SPHERE centred on the eye;
     * a sphere intersecting the view frustum draws a curved, camera-following edge. Retuning
     * near/far/width only slides that sphere in and out; it cannot remove the edge. The closure is
     * {@code plagueGetWaterFog} (Beer-Lambert, asymptotic, no boundary anywhere by construction),
     * which takes one scale instead of a near/far pair, so two of the original four channels no
     * longer exist.
     *
     * <p>Channels now: R = {@code uwClosureScale} (the exponential's scale distance, blocks),
     * G = {@code uwClosureDist} ({@code length(worldPos)}, radial distance from the eye, blocks),
     * B = {@code horizonClosure} (the 0..1 blend weight actually applied at this pixel), A =
     * {@code uwVisibilityMult} (3..6, the clear-noon vs rain/night blend factor). Resolve-branch
     * ordinal 30 in {@code gbuffer_resolve.fsh}. Deep branch, same reason as the ordinals above it
     * -- none of R/G/B/A exist as values until the underwater closure block has run.
     *
     * <p>Gated on {@code fragSubmerged} (the eye is underwater, looking at opaque terrain/seabed),
     * not on being a water surface -- unlike {@link #ENV_SPEC_RATIO} and its siblings, this reading
     * is valid at this framing. Point the crosshair at submerged seabed or terrain while the camera
     * itself is underwater; reads all-zero (the branch's own explicit fallback) otherwise.
     */
    UW_CLOSURE_DEBUG,
    /**
     * Shadow-wedge instrument, part 1 of 3 (with {@link #SHADOW_QUERY_2}, {@link
     * #SHADOW_QUERY_3}). Reads the REAL {@code sunVisibility()} call's own internals at the
     * crosshair directly, rather than reasoning indirectly about a large, elevation-periodic
     * misshadowed region on solid terrain through player-relative face culling, caster-list frustum
     * margin, shadow bias, sun/moon direction, or texel density by distance or resolution -- none of
     * which explains what's actually on screen on its own. R = {@code sunDir.x}, G = {@code
     * sunDir.y}, B = {@code sunDir.z}, A = {@code ndotl} at this fragment. Resolve-branch ordinal 31
     * in {@code gbuffer_resolve.fsh}, same deep-placement/number-carrier shape as {@link
     * #ENV_SPEC_RATIO}. Appended last per this enum's own lockstep rule.
     */
    SHADOW_QUERY_1,
    /**
     * Shadow-wedge investigation, part 2 of 3. R = {@code shadowUv.x}, G = {@code shadowUv.y}, B =
     * whether both lie in {@code [0,1]} (1.0 = inside the shadow map's coverage window, 0.0 =
     * outside it), A = {@code sunVisibility()}'s own real return value for this fragment -- the
     * exact number the lit composite uses, not a re-derivation. Resolve-branch ordinal 32, same
     * shape as {@link #SHADOW_QUERY_1}.
     */
    SHADOW_QUERY_2,
    /**
     * Shadow-wedge investigation, part 3 of 3. R = {@code rawDepth} (the fragment's light-space Z
     * before the {@code *0.2} compression), G = {@code refDepth} ({@code rawDepth*0.2}, the actual
     * value compared against the shadow map), B = {@code storedDepth} (the raw texel sampled from
     * the shadow map at {@code shadowUv} -- needs a second, plain-{@code sampler2D} binding of the
     * same {@code sunShadowMap} target, since the existing binding is a {@code sampler2DShadow}
     * comparison sampler and can only return pass/fail). A unused. Resolve-branch ordinal 33, same
     * shape as {@link #SHADOW_QUERY_1}.
     */
    SHADOW_QUERY_3,
    // ^ GLINT_QUERY_1-4 (ordinals 34-37) held a shadow-map-based instrument for
    // water_composite.fsh's glintShadowVis kill-switch, superseded by glint_occlusion.fsh's
    // screen-space raymarch. Removed rather than left dead: the shader branches that fed them no
    // longer exist, so selecting them would silently do nothing. Safe to remove -- they were the
    // last four ordinals, appended after nothing, so no later ordinal shifts.
    /**
     * Reads {@code glint_occlusion.fsh}'s own real output directly instead of deriving
     * {@code lightDir.y} from the formulas, since the two diverge for moon glitter above water.
     * Measures the pass's actual {@code activeVisibility}/{@code trueSunVisibility}/
     * {@code moonVisibility} at the crosshair: independent shadow-march results for the light
     * currently driving glitter, the true sun, and the moon, since {@code glint_occlusion.fsh}
     * traces all three every frame regardless of which body is above the horizon. {@code (-1,0,0)}
     * means the crosshair isn't on a water texel (never actually sampled by the real consumer
     * either); a genuine {@code (0,0,0)} means every traced direction is occluded or below the
     * horizon. Reads a DIFFERENT target ({@code glintOcclusion}) than every ordinal above it. See
     * {@link dev.icehunter.fornax.pipeline.EnvSpecularRatioReadback#targetFor}. Not the last value:
     * the underwater-glint quad follows it.
     */
    GLINT_OCCLUSION_QUERY,
    /**
     * Underwater-glint instrument, part 1 of 4 (with {@link #UW_GLINT_2}, {@link #UW_GLINT_3},
     * {@link #UW_GLINT_4}). R = {@code uwSunAlignment}, G = {@code uwMoonAlignment}, B =
     * {@code uwFresnel}: {@code water_composite.fsh} tracks the sun and moon as independent
     * alignment terms, each feeding its own lobe and glint downstream. Written by
     * {@code water_composite.fsh}'s TRANSLUCENT blend pass, so A is always exactly 1.0 by
     * construction, three values per ordinal not four, same shape as the retired GLINT_QUERY
     * instrument. Reads {@code sceneHdrComposited}, not {@code sceneHdr}. See {@link
     * dev.icehunter.fornax.pipeline.EnvSpecularRatioReadback#targetFor}.
     */
    UW_GLINT_1,
    /**
     * Underwater-glint instrument, part 2 of 4. RGB = {@code uwEyeFilter.rgb}: the exponential
     * depth-absorption filter applied to both the sun- and moon-filtered glint colour. Same shape as
     * {@link #UW_GLINT_1}.
     */
    UW_GLINT_2,
    /**
     * Underwater-glint instrument, part 3 of 4. R = {@code uwSunGlint}, G = {@code uwMoonGlint}:
     * each celestial body's own lobe-times-horizon-fade-times-microcoverage-times-shadow term,
     * before either is filtered by eye colour or scaled by strength/skyVis. B =
     * {@code u_UnderwaterSunGlitterStrength}, the runtime slider (0.0-2.0, default 1.0), read here
     * for a direct cross-check against whatever the user finds in pack settings. Same shape as
     * {@link #UW_GLINT_1}.
     */
    UW_GLINT_3,
    /**
     * Underwater-glint instrument, part 4 of 4. RGB = {@code uwGlintContribution.rgb}: the actual
     * term added into {@code surface}, i.e. the final answer to "is anything real being added to
     * the pixel at all."
     */
    UW_GLINT_4,
    /**
     * Underwater-glint instrument, part 5 of 5 -- the instrument Stage 0 of the celestial rework
     * decision calls for. Reads three raw inputs behind one candidate cause instead of a further
     * inference: {@code dot(uwEyeRay, waveNormal)} going negative at the shaded fragment, which the
     * {@code clamp(...,0,1)} on {@code uwCosIncident} floors to 0 -- driving {@code uwFresnel} to
     * exactly 1.0 (measured) regardless of the true geometry, and implying the fragment under the
     * crosshair is not the water surface overhead at all. R = {@code waveNormal.y}, G = {@code
     * NdotV}, B = {@code worldPos.y} -- this order matches {@code water_composite.fsh:308}'s actual
     * write, {@code vec4(waveNormal.y, NdotV, worldPos.y, 1.0)}, NOT R/G/B channel-name order; the
     * two differ, and a mismatched formatter here produces confident, plausible, wrong numbers
     * rather than an error -- see {@code EnvSpecularRatioReadback}'s formatter for the live
     * channel-order pin. Reading negative {@code waveNormal.y} paired with positive {@code
     * NdotV} means the normal is view-facing by construction, not evidence of a flipped/misoriented
     * surface; a negative {@code worldPos.y} means the shaded fragment sits at or below the camera,
     * not the surface above it -- the prime suspect for Bug C being the water surface's top face
     * missing from the prepass when the camera is inside that water volume. Written by {@code
     * water_composite.fsh}'s TRANSLUCENT blend pass, so A is always exactly 1.0 by construction --
     * three values, not four, same shape as {@link #UW_GLINT_1}. Reads {@code sceneHdrComposited},
     * same as the rest of this quintet -- see {@link
     * dev.icehunter.fornax.pipeline.EnvSpecularRatioReadback#targetFor}. Appended last per this
     * enum's own lockstep rule.
     */
    UW_GLINT_5,
    /**
     * Full-screen linearized shadow-map visualization -- unlike every ordinal above, NOT a crosshair
     * readback: {@link dev.icehunter.fornax.pipeline.EnvSpecularRatioReadback} has no formatter case
     * for it, by design, since there is nothing to print. Exists purely as a selectable constant so
     * the pack's own resolve branch can key on it and paint the shadow map's raw/linearized depth
     * across the whole frame -- the highest-information instrument available for the Bug A
     * investigation (a captured-but-wrong-surface shadow map is either visibly present in this view
     * or it is not; that one look splits the investigation in half). Depends on the same raw,
     * non-comparison sampler binding {@link #SHADOW_QUERY_3} needs -- see {@code
     * dev.icehunter.fornax.pass.shadow.ShadowMapManager#RAW_TARGET}. Appended last per this enum's
     * own lockstep rule.
     */
    SHADOW_MAP_VIEW,
    /** Valid water-volume ray interval: red = valid, green = segment length, blue = submerged. */
    WATER_SHAFT_INTERVAL,
    /** Refractive ray-tube focusing inside the water-volume march. */
    WATER_SHAFT_REFRACTIVE_FOCUS,
    /** Mean sun/moon shadow-map visibility sampled by the water-volume march. */
    WATER_SHAFT_SHADOW_VISIBILITY,
    /** Display-mapped, unfiltered in-scattered radiance emitted by the water-volume march. */
    WATER_SHAFT_RAW_SCATTER,
    /**
     * Conductor-chain instrument, part 1 of 7: the decoded material at the crosshair
     * -- R/G/B = {@code surfaceF0}, A = {@code metalness}. Number carrier for {@link
     * dev.icehunter.fornax.pipeline.EnvSpecularRatioReadback}, same contract as {@link
     * #ENV_SPEC_RATIO}; shader id 68 ({@code DBG_CONDUCTOR_F0}, {@code gbuffer_resolve.fsh}).
     * The seven parts walk one pixel's specular chain end to end: decode, energy, mirror content,
     * wide content, finished environment term, direct sun term, final HDR. They read the real frame
     * because composition verified against modelled scenes does not reliably predict its behavior on
     * the real frame. Unlike the earlier instrument ordinals these ARE selectable, so the F9 cycle
     * can reach them without a config edit.
     */
    CONDUCTOR_F0,
    /** Part 2: R/G/B = {@code specularAlbedo} (split-sum + multi-scatter energy), A = {@code
     * reflSmoothness}. Shader id 69. See {@link #CONDUCTOR_F0}. */
    CONDUCTOR_ENERGY,
    /** Part 3: R/G/B = {@code reflColor} (mirror content: SSR hit + sky-miss blend), A = {@code
     * sharpAvail}. Shader id 70. See {@link #CONDUCTOR_F0}. */
    CONDUCTOR_MIRROR,
    /** Part 4: R/G/B = {@code reflWide} (smeared-lobe content), A = {@code wideTraceTrust}.
     * Shader id 71. See {@link #CONDUCTOR_F0}. */
    CONDUCTOR_WIDE,
    /** Part 5: R/G/B = {@code reflEnv} after every shadow cut and desaturation, A = {@code
     * envShadowDim}. Shader id 72. See {@link #CONDUCTOR_F0}. */
    CONDUCTOR_ENV,
    /** Part 6: R/G/B = the direct sun specular term as composed ({@code brdf.specular *
     * sunVisibilityHere * sunColour}), A = {@code sunVisibilityHere}. Shader id 73. See {@link
     * #CONDUCTOR_F0}. */
    CONDUCTOR_DIRECT,
    /** Part 7: R/G/B = the finished HDR {@code lit} this pixel hands the tonemapper, A = its
     * luma. If this reads warm while the screen shows white, the whitening lives in the tonemap.
     * Shader id 74. See {@link #CONDUCTOR_F0}. */
    CONDUCTOR_LIT;

    /**
     * Stable integer consumed by pack shaders through {@code u_Param3}. Legacy values retain their
     * historical ordinal ids; new views live in a separate range so code cannot accidentally
     * reintroduce ordinal coupling without a test failure.
     */
    public int shaderId() {
        return switch (this) {
            case WATER_SHAFT_INTERVAL -> 64;
            case WATER_SHAFT_REFRACTIVE_FOCUS -> 65;
            case WATER_SHAFT_SHADOW_VISIBILITY -> 66;
            case WATER_SHAFT_RAW_SCATTER -> 67;
            case CONDUCTOR_F0 -> 68;
            case CONDUCTOR_ENERGY -> 69;
            case CONDUCTOR_MIRROR -> 70;
            case CONDUCTOR_WIDE -> 71;
            case CONDUCTOR_ENV -> 72;
            case CONDUCTOR_DIRECT -> 73;
            case CONDUCTOR_LIT -> 74;
            default -> ordinal();
        };
    }

    /** Ordered pack-owned graph targets the engine may present for this view. */
    public List<String> graphTargetCandidates() {
        return switch (this) {
            case CELESTIAL_SHADOW_VOXEL -> List.of("celestialVisVoxelFull", "celestialVisVoxel");
            case WATER_SHAFT_INTERVAL, WATER_SHAFT_REFRACTIVE_FOCUS,
                    WATER_SHAFT_SHADOW_VISIBILITY, WATER_SHAFT_RAW_SCATTER ->
                    List.of("waterVolumeScatterRaw");
            default -> List.of();
        };
    }

    /** Human-readable settings/F9 label; no numeric implementation values leak into the UI. */
    public String label() {
        return switch (this) {
            case OFF -> "Off";
            case NORMALS -> "Normals";
            case ALBEDO -> "Albedo";
            case MATERIAL -> "Material";
            case MOTION -> "Motion";
            case SSAO -> "SSAO";
            case TAA -> "TAA";
            case AO -> "Ambient Occlusion";
            case BLOCK_LIGHT -> "Block Light";
            case SSR -> "SSR";
            case MATERIAL_ID -> "Material ID";
            case VOXEL_RAYMARCH -> "Voxel Raymarch";
            case RT_SHADOW -> "Sun Shadow";
            case SCENE_HDR -> "Scene HDR";
            case BLOOM -> "Bloom";
            case EXPOSURE -> "Exposure";
            case EMITTER_LIGHT -> "Emitter Light";
            case WATER_PREPASS -> "Water Pre-Pass";
            case CELESTIAL_SHADOW_VOXEL -> "Celestial Shadow Voxel (M1)";
            case SURFACE_EMISSION -> "Surface Emission";
            case ANALYTIC_DIRECT -> "Analytic Direct";
            case ENV_SPEC_RATIO -> "Env Specular Ratio";
            case ENV_DECOMP_SKY -> "Env Decomp: Sky";
            case ENV_DECOMP_MIX -> "Env Decomp: Mix";
            case ENV_DECOMP_MAT -> "Env Decomp: Material";
            case ENV_DECOMP_LOCAL -> "Env Decomp: Local Light";
            case ENV_DECOMP_AO -> "Env Decomp: AO";
            case ENV_DECOMP_RESIDUAL -> "Env Decomp: Residual";
            case ENV_DECOMP_ALBEDO_WRITE_VS_READ -> "Env Decomp: Albedo Write vs Read";
            case ENV_DECOMP_ALBEDO_IDENTITY_INPUTS -> "Albedo Identity Inputs";
            case UW_CLOSURE_DEBUG -> "Underwater Closure Debug";
            case SHADOW_QUERY_1 -> "Shadow Query: Direction";
            case SHADOW_QUERY_2 -> "Shadow Query: UV/Visibility";
            case SHADOW_QUERY_3 -> "Shadow Query: Depth";
            case GLINT_OCCLUSION_QUERY -> "Glint Occlusion Query";
            case UW_GLINT_1 -> "UW Glint: Alignment/Fresnel";
            case UW_GLINT_2 -> "UW Glint: Eye Filter";
            case UW_GLINT_3 -> "UW Glint: Sun+Moon/Strength";
            case UW_GLINT_4 -> "UW Glint: Contribution";
            case UW_GLINT_5 -> "UW Glint: Incidence/Position";
            case SHADOW_MAP_VIEW -> "Shadow Map (raw)";
            case WATER_SHAFT_INTERVAL -> "Water Shafts: Interval";
            case WATER_SHAFT_REFRACTIVE_FOCUS -> "Water Shafts: Refractive Focus";
            case WATER_SHAFT_SHADOW_VISIBILITY -> "Water Shafts: Shadow Visibility";
            case WATER_SHAFT_RAW_SCATTER -> "Water Shafts: Raw Scatter";
            case CONDUCTOR_F0 -> "Conductor: F0/Metalness";
            case CONDUCTOR_ENERGY -> "Conductor: Energy/Smoothness";
            case CONDUCTOR_MIRROR -> "Conductor: Mirror Content";
            case CONDUCTOR_WIDE -> "Conductor: Wide Content/Trust";
            case CONDUCTOR_ENV -> "Conductor: Env Result/Cut";
            case CONDUCTOR_DIRECT -> "Conductor: Direct Sun Term";
            case CONDUCTOR_LIT -> "Conductor: Final HDR";
        };
    }

    /**
     * Whether a player may select this view, from either the settings dropdown or the F9 / Shift+F9
     * cycle. One source of truth for both, so a view can never be excluded from one route and left
     * reachable through the other -- which is exactly how {@link #VOXEL_RAYMARCH} kept costing forced
     * reboots: removed from a menu, still landed on by a keypress.
     *
     * <p>Excluding rather than deleting the constant is deliberate: old configs persist enum names,
     * and old pack shaders may still branch on their stable ids.
     */
    public boolean isSelectable() {
        return switch (this) {
            case VOXEL_RAYMARCH, WATER_PREPASS, CELESTIAL_SHADOW_VOXEL,
                    ENV_SPEC_RATIO, ENV_DECOMP_SKY, ENV_DECOMP_MIX, ENV_DECOMP_MAT,
                    ENV_DECOMP_LOCAL, ENV_DECOMP_AO, ENV_DECOMP_RESIDUAL,
                    ENV_DECOMP_ALBEDO_WRITE_VS_READ, ENV_DECOMP_ALBEDO_IDENTITY_INPUTS,
                    UW_CLOSURE_DEBUG, SHADOW_QUERY_1, SHADOW_QUERY_2, SHADOW_QUERY_3,
                    GLINT_OCCLUSION_QUERY, UW_GLINT_1, UW_GLINT_2, UW_GLINT_3,
                    UW_GLINT_4, UW_GLINT_5 -> false;
            default -> true;
        };
    }
}
