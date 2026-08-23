package dev.icehunter.fornax.config;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code gbuffer_resolve.fsh}'s debug branch chain hardcodes {@code debugView == 10} (and, since
 * Part C of the shadow-consistency live fix, {@code == 12}) for its resolve-branched views; nothing
 * in the Java compiler would catch that shader drifting from this enum, so pin both sides of the
 * contract explicitly. {@code MATERIAL_ID} (ordinal 10) is the last value of the shader's original
 * 0-10 chain -- {@code VOXEL_RAYMARCH} (ordinal 11) is deliberately NOT a resolve branch: it is an
 * engine-owned override presented by {@code VoxelDebugRaymarchPass}, so the gap at ordinal 11 is
 * correct, not a bug. {@code RT_SHADOW} (ordinal 12) resumes resolve branching immediately after
 * that gap.
 */
class GBufferDebugViewTest {
    @Test
    void materialIdIsOrdinalTen() {
        assertEquals(10, GBufferDebugView.MATERIAL_ID.ordinal());
    }

    @Test
    void materialIdIsTheLastResolveBranchedValue() {
        // The resolve shader's original u_Param3 chain covers 0..10; the highest ordinal it branches
        // on is MATERIAL_ID at 10, and every value at or below it is a resolve-shader view.
        assertEquals(10, GBufferDebugView.MATERIAL_ID.ordinal());
    }

    @Test
    void voxelRaymarchIsTheOnlyValueBeyondTheOriginalResolveBranches() {
        assertEquals(11, GBufferDebugView.VOXEL_RAYMARCH.ordinal());
    }

    @Test
    void rtShadowIsOrdinalTwelve() {
        // RT_SHADOW resumes resolve branching at ordinal 12, skipping VOXEL_RAYMARCH's gap at 11 --
        // see gbuffer_resolve.fsh's "debugView == 12" branch. No longer the last value: the HDR+bloom
        // milestone appended three terminal-pass (tonemap.fsh) views after it.
        assertEquals(12, GBufferDebugView.RT_SHADOW.ordinal());
    }

    @Test
    void hdrTerminalViewsAppendAfterRtShadow() {
        // SCENE_HDR/BLOOM/EXPOSURE branch in tonemap.fsh (ordinals 13/14/15), NOT gbuffer_resolve.fsh;
        // ordinals are load-bearing for those branches, so pin them.
        assertEquals(13, GBufferDebugView.SCENE_HDR.ordinal());
        assertEquals(14, GBufferDebugView.BLOOM.ordinal());
        assertEquals(15, GBufferDebugView.EXPOSURE.ordinal());
        assertEquals(16, GBufferDebugView.EMITTER_LIGHT.ordinal(),
                "EMITTER_LIGHT resumes resolve branching (gbuffer_resolve.fsh debugView == 16)");
    }

    @Test
    void waterPrepassIsOrdinalSeventeen() {
        // Deferred Water Task 1 spike: WATER_PREPASS is a second engine-owned override (mirroring
        // VOXEL_RAYMARCH's own gap at ordinal 11), presented by WaterPrepassDebugPass instead of
        // either resolve branch chain -- appended after EMITTER_LIGHT so it never shifts EMITTER_LIGHT
        // (16) or any resolve/tonemap-branched ordinal below it. No longer the last value: the M1
        // DDA sun-shadow prototype appended a third engine-owned override after it.
        assertEquals(17, GBufferDebugView.WATER_PREPASS.ordinal());
    }

    @Test
    void celestialShadowVoxelIsOrdinalEighteen() {
        // M1 DDA sun-shadow prototype (voxel-default-lighting design): a third engine-owned override
        // (mirroring VOXEL_RAYMARCH's and WATER_PREPASS's own gaps), presented by
        // CelestialShadowVoxelDebugPass instead of either resolve branch chain -- appended after
        // WATER_PREPASS so it never shifts any earlier ordinal. No longer the last value: the
        // surface-emission instrument appended a resolve-branched view after it.
        assertEquals(18, GBufferDebugView.CELESTIAL_SHADOW_VOXEL.ordinal());
    }

    @Test
    void surfaceEmissionIsOrdinalNineteen() {
        // Lantern-glow hunt instrumentation (2026-07-21): resolve-branched view of gMaterial.a's
        // emission nibble (gbuffer_resolve.fsh debugView == 19, tonemap.fsh passthrough) -- no
        // longer the last value: the analytic-direct isolation instrument appended after it.
        assertEquals(19, GBufferDebugView.SURFACE_EMISSION.ordinal());
    }

    @Test
    void analyticDirectIsOrdinalTwenty() {
        // Analytic-direct isolation instrumentation (2026-07-22 "niche back wall not lit" hunt):
        // resolve blacks the scene (gbuffer_resolve.fsh debugView == 20), direct_light_analytic
        // stays live at 8x instrument gain, tonemap.fsh passes 20 through untonemapped -- no longer
        // the last value: the LabPBR decode audit appended one resolve-branched view after it.
        assertEquals(20, GBufferDebugView.ANALYTIC_DIRECT.ordinal());
    }

    @Test
    void envSpecRatioIsOrdinalTwentyOne() {
        // LabPBR decode audit (2026-08-09): exposure-independent ratio instrument -- packs
        // envSpecLuma/diffuseLuma/ratio into one pixel rather than raw magnitudes, since raw
        // linear in the 0.01-0.15 range renders visually black and cannot answer "how large".
        // Resolve-branched (gbuffer_resolve.fsh debugView == 21), deep in the pixel's shading
        // rather than the early G-buffer-read chain, since neither term exists until then.
        // tonemap.fsh passes 21 through untonemapped like every other resolve-branched view.
        // No longer the last value: the decomposition triple appended after it.
        assertEquals(21, GBufferDebugView.ENV_SPEC_RATIO.ordinal());
    }

    @Test
    void envDecompTripleIsOrdinalsTwentyTwoThroughTwentyFour() {
        // LabPBR decode audit (2026-08-09): the ratio (21) named the specular path as ~50x
        // brighter than diffuse for the same surroundings; these three report every term it is
        // built from (skyMiss/ambientColour/wideEnclosure/reflWide, reflColor/sharpAvail/reflEnv/
        // specularAlbedo, NdotV/mat.alpha/surfaceF0), split across three ordinals because one vec4
        // cannot hold eleven values. Resolve-branched (gbuffer_resolve.fsh debugView == 22/23/24),
        // same deep-placement shape as ENV_SPEC_RATIO. No longer the last values: the local-light/
        // AO follow-up pair appended after them.
        assertEquals(22, GBufferDebugView.ENV_DECOMP_SKY.ordinal());
        assertEquals(23, GBufferDebugView.ENV_DECOMP_MIX.ordinal());
        assertEquals(24, GBufferDebugView.ENV_DECOMP_MAT.ordinal());
    }

    @Test
    void envDecompLocalAoPairIsOrdinalsTwentyFiveAndTwentySix() {
        // LabPBR decode audit (2026-08-09) follow-up: does the diffuse path see the same local-
        // light/AO picture the specular path's wideEnclosure/envAccess already folds in? R/G/B/A
        // for ordinal 25 = diffuseWithHeld/blockRadiance/skyLight/envAccess; for ordinal 26 =
        // wideHorizon/litDiffuse/litResult.vanillaAO (the diffuse path's ACTUAL applied AO factor,
        // exposed via a new PlagueLitResult field -- no shading maths changed)/the raw `ao` scalar
        // the specular path's envAccess consumes directly. Resolve-branched (gbuffer_resolve.fsh
        // debugView == 25/26), same deep-placement shape as ENV_SPEC_RATIO. No longer the last
        // values: the residual instrument appended after them.
        assertEquals(25, GBufferDebugView.ENV_DECOMP_LOCAL.ordinal());
        assertEquals(26, GBufferDebugView.ENV_DECOMP_AO.ordinal());
    }

    @Test
    void envDecompResidualIsOrdinalTwentySeven() {
        // LabPBR decode audit (2026-08-09): closes the litDiffuse/diffuseWithHeld ratio question by
        // measurement -- litDiffuse = kD * albedo * diffuseWithHeld is the entire relationship
        // (gbuffer_resolve.fsh:1242), but a luma-reduced readback can only report dot(vec3,
        // weights), and that does not commute with a product unless the vectors share hue. R/G/B =
        // albedoLuma/kDLuma/diffuseWithHeldLuma (the three multiplicands); A = litDiffuseLuma /
        // max(R*G*B, 1e-6), the residual -- 1.0 if luma commutes cleanly, anything else is the gap,
        // measured rather than guessed. Resolve-branched (gbuffer_resolve.fsh debugView == 27),
        // same deep-placement shape as ENV_SPEC_RATIO. No longer the last value: the Round 10
        // albedo-identity pair appended after it.
        assertEquals(27, GBufferDebugView.ENV_DECOMP_RESIDUAL.ordinal());
    }

    @Test
    void envDecompAlbedoIdentityPairIsOrdinalsTwentyEightAndTwentyNine() {
        // Round 10 (LabPBR decode audit, 2026-08-09) follow-up: every ordinal above ASSUMED what
        // gAlbedo's raw byte and v_RawTint contain at runtime, never measured it. 28 = rawWrittenLuma
        // (gAlbedo's raw byte, undecoded) / decodedAlbedoLuma (same byte, decoded -- repeats ordinal
        // 27's albedoLuma), purely from gbuffer_resolve.fsh's own scope, valid only while
        // u_AlbedoIdentityDebug is off. 29 = texLuma / v_RawTint.rgb, terrain.fsh-fragment-local
        // values with no other egress path, requiring a SECOND pack-side toggle
        // (u_AlbedoIdentityDebug, bridged through u_PbrSettings like u_PomDebug) because terrain.fsh
        // is a deferred geometry program with no u_Param3/debugView access at all. Resolve-branched
        // (gbuffer_resolve.fsh debugView == 28/29). Appended last so nothing earlier shifts.
        assertEquals(28, GBufferDebugView.ENV_DECOMP_ALBEDO_WRITE_VS_READ.ordinal());
        assertEquals(29, GBufferDebugView.ENV_DECOMP_ALBEDO_IDENTITY_INPUTS.ordinal());
    }

    @Test
    void uwClosureDebugIsOrdinalThirty() {
        // Underwater visibility-closure investigation (2026-08-10): reads back the applied
        // uwClosureNear/uwClosureFar/uwClosureWidth/horizonClosure at the crosshair, gated on
        // fragSubmerged rather than on hitting a water surface -- unlike the ENV_DECOMP_ALBEDO_*
        // pair above, pointing at submerged terrain is the correct framing here, not a caveat.
        // No longer the last value: the shadow-wedge investigation triple appended after it.
        assertEquals(30, GBufferDebugView.UW_CLOSURE_DEBUG.ordinal());
    }

    @Test
    void shadowQueryTripleIsOrdinalsThirtyOneThroughThirtyThreeAppendedLast() {
        // Shadow-wedge investigation (2026-08-10): six independent theories for a large,
        // elevation-periodic misshadowed region on solid terrain (player-relative face culling,
        // caster-list frustum margin, shadow bias collapse, sun/moon direction desync, texel
        // density by distance, texel density by resolution) were each eliminated in turn without
        // explaining the symptom. This triple reads sunVisibility()'s own real internals at the
        // crosshair instead of a seventh guess: 31 = sunDir/ndotl, 32 = shadowUv/inRange/visibility,
        // 33 = rawDepth/refDepth/storedDepth. Appended last so nothing earlier shifts.
        assertEquals(31, GBufferDebugView.SHADOW_QUERY_1.ordinal());
        assertEquals(32, GBufferDebugView.SHADOW_QUERY_2.ordinal());
        assertEquals(33, GBufferDebugView.SHADOW_QUERY_3.ordinal());
    }

    @Test
    void shadowQueryThreeIsOrdinalThirtyThree() {
        // GLINT_QUERY_1-4 (ordinals 34-37) briefly lived after SHADOW_QUERY_3: a shadow-map-based
        // instrument for water_composite.fsh's glintShadowVis kill-switch. Removed 2026-08-10 once
        // the shader branches feeding them were deleted in favour of glint_occlusion.fsh's
        // screen-space raymarch -- the numbers this instrument surfaced (a real, valid shadow-map
        // depth recording the wrong surface) are what justified dropping the shadow map for the
        // glint entirely. Safe removal: they were the last four ordinals, nothing appended after.
        assertEquals(33, GBufferDebugView.SHADOW_QUERY_3.ordinal());
    }

    @Test
    void glintOcclusionQueryIsOrdinalThirtyFour() {
        // Moon-glitter investigation (2026-08-10): sun glitter above water confirmed correct, but
        // moon glitter above water confirmed still fully absent even fully unobstructed, and static
        // tracing found nothing wrong in every formula touching lightDir.y. Reads
        // glint_occlusion.fsh's own real lightDir/occlusion output at the crosshair instead of a
        // further guess -- no shader-side debug branch needed, the pass's rgba16f output target
        // carries lightDir in .gba unconditionally every frame. No longer the last value: the
        // underwater-glint quad appended after it.
        assertEquals(34, GBufferDebugView.GLINT_OCCLUSION_QUERY.ordinal());
    }

    @Test
    void uwGlintQuadIsOrdinalsThirtyFiveThroughThirtyEightAppendedLast() {
        // Underwater-glint investigation (2026-08-10): the underwater sun glint stayed fully absent
        // even at the easiest possible geometry (sun at zenith, straight up), and hand-verified
        // static tracing found uwSolarLobe at its exact maximum there -- the alignment math itself
        // is not the defect. This quad reads everything downstream instead of a further guess: 35 =
        // uwSunAlignment/uwSolarLobe/uwFresnel, 36 = uwEyeFilter, 37 = skyVis/uwGlint/
        // underwaterSunGlitterStrength, 38 = the actual uwGlintContribution added to the pixel.
        // Appended last so nothing earlier shifts.
        assertEquals(35, GBufferDebugView.UW_GLINT_1.ordinal());
        assertEquals(36, GBufferDebugView.UW_GLINT_2.ordinal());
        assertEquals(37, GBufferDebugView.UW_GLINT_3.ordinal());
        assertEquals(38, GBufferDebugView.UW_GLINT_4.ordinal());
    }

    @Test
    void uwGlintFiveIsOrdinalThirtyNine() {
        // Celestial rework decision, Stage 0 (2026-08-11): the instrument the decision doc's own
        // §0 calls for. Reads the three raw inputs (uwCosIncident, worldPos.y, waveNormal.y) the
        // "dot(uwEyeRay, waveNormal) went negative" theory rests on, instead of a further inference
        // from UW_GLINT_1-4's downstream values.
        assertEquals(39, GBufferDebugView.UW_GLINT_5.ordinal());
    }

    @Test
    void shadowMapViewIsOrdinalFortyAppendedLast() {
        // Celestial rework decision, Stage 0 (2026-08-11): a full-screen linearized shadow-map
        // visualization, NOT a crosshair readback -- EnvSpecularRatioReadback has no formatter case
        // for it by design. Appended before the water-shaft and conductor blocks so nothing
        // earlier shifts.
        assertEquals(40, GBufferDebugView.SHADOW_MAP_VIEW.ordinal());
        assertEquals(52, GBufferDebugView.values().length);
    }

    @Test
    void conductorProbesUseStableShaderIdsContinuingTheNewRange() {
        // Conductor-chain instrument (2026-08-22): seven crosshair-readback ordinals walking one
        // pixel's specular chain. Ids continue the stable range the water-shaft views opened
        // (64..67), so the pack's DBG_CONDUCTOR_* defines never depend on enum ordinals.
        assertEquals(68, GBufferDebugView.CONDUCTOR_F0.shaderId());
        assertEquals(69, GBufferDebugView.CONDUCTOR_ENERGY.shaderId());
        assertEquals(70, GBufferDebugView.CONDUCTOR_MIRROR.shaderId());
        assertEquals(71, GBufferDebugView.CONDUCTOR_WIDE.shaderId());
        assertEquals(72, GBufferDebugView.CONDUCTOR_ENV.shaderId());
        assertEquals(73, GBufferDebugView.CONDUCTOR_DIRECT.shaderId());
        assertEquals(74, GBufferDebugView.CONDUCTOR_LIT.shaderId());
        // Selectable on purpose, unlike the earlier instrument ordinals: the F9 cycle is the
        // intended route to them, with the measure key pressed once a probe is active.
        assertTrue(GBufferDebugView.CONDUCTOR_F0.isSelectable());
        assertTrue(GBufferDebugView.CONDUCTOR_LIT.isSelectable());
    }

    @Test
    void waterShaftViewsUseStableShaderIdsOutsideTheLegacyOrdinalAbi() {
        assertEquals(64, GBufferDebugView.WATER_SHAFT_INTERVAL.shaderId());
        assertEquals(65, GBufferDebugView.WATER_SHAFT_REFRACTIVE_FOCUS.shaderId());
        assertEquals(66, GBufferDebugView.WATER_SHAFT_SHADOW_VISIBILITY.shaderId());
        assertEquals(67, GBufferDebugView.WATER_SHAFT_RAW_SCATTER.shaderId());
        assertNotEquals(GBufferDebugView.WATER_SHAFT_INTERVAL.ordinal(),
                GBufferDebugView.WATER_SHAFT_INTERVAL.shaderId(),
                "runtime shader routing must not fall back to enum ordinal");
        assertEquals(GBufferDebugView.values().length,
                new HashSet<>(List.of(GBufferDebugView.values()).stream()
                        .map(GBufferDebugView::shaderId)
                        .toList()).size(),
                "every debug view needs a unique, stable shader id");
    }

    @Test
    void shaftViewsPresentTheLiveRawMarchTarget() {
        List<String> expected = List.of("waterVolumeScatterRaw");
        assertEquals(expected, GBufferDebugView.WATER_SHAFT_INTERVAL.graphTargetCandidates());
        assertEquals(expected, GBufferDebugView.WATER_SHAFT_REFRACTIVE_FOCUS.graphTargetCandidates());
        assertEquals(expected, GBufferDebugView.WATER_SHAFT_SHADOW_VISIBILITY.graphTargetCandidates());
        assertEquals(expected, GBufferDebugView.WATER_SHAFT_RAW_SCATTER.graphTargetCandidates());
        assertTrue(GBufferDebugView.OFF.graphTargetCandidates().isEmpty());
    }

    @Test
    void oldInvestigationViewsAreArchivedWithoutDeletingTheirStableIds() {
        assertTrue(GBufferDebugView.OFF.isSelectable());
        assertTrue(GBufferDebugView.NORMALS.isSelectable());
        assertTrue(GBufferDebugView.SHADOW_MAP_VIEW.isSelectable());
        assertTrue(GBufferDebugView.WATER_SHAFT_INTERVAL.isSelectable());
        assertTrue(GBufferDebugView.WATER_SHAFT_REFRACTIVE_FOCUS.isSelectable());
        assertTrue(GBufferDebugView.WATER_SHAFT_SHADOW_VISIBILITY.isSelectable());
        assertTrue(GBufferDebugView.WATER_SHAFT_RAW_SCATTER.isSelectable());

        assertFalse(GBufferDebugView.VOXEL_RAYMARCH.isSelectable());
        assertFalse(GBufferDebugView.WATER_PREPASS.isSelectable());
        assertFalse(GBufferDebugView.CELESTIAL_SHADOW_VOXEL.isSelectable());
        assertFalse(GBufferDebugView.ENV_SPEC_RATIO.isSelectable());
        assertFalse(GBufferDebugView.ENV_DECOMP_ALBEDO_IDENTITY_INPUTS.isSelectable());
        assertFalse(GBufferDebugView.UW_CLOSURE_DEBUG.isSelectable());
        assertFalse(GBufferDebugView.SHADOW_QUERY_1.isSelectable());
        assertFalse(GBufferDebugView.SHADOW_QUERY_3.isSelectable());
        assertFalse(GBufferDebugView.GLINT_OCCLUSION_QUERY.isSelectable());
        assertFalse(GBufferDebugView.UW_GLINT_1.isSelectable());
        assertFalse(GBufferDebugView.UW_GLINT_5.isSelectable());

        assertEquals(21, GBufferDebugView.ENV_SPEC_RATIO.shaderId());
        assertEquals(39, GBufferDebugView.UW_GLINT_5.shaderId());
        assertEquals(18, GBufferDebugView.CELESTIAL_SHADOW_VOXEL.shaderId());
    }

    @Test
    void userFacingLabelsDescribeTheShaftSignalInsteadOfNumericModes() {
        assertEquals("Water Shafts: Interval", GBufferDebugView.WATER_SHAFT_INTERVAL.label());
        assertEquals("Water Shafts: Refractive Focus",
                GBufferDebugView.WATER_SHAFT_REFRACTIVE_FOCUS.label());
        assertEquals("Water Shafts: Shadow Visibility",
                GBufferDebugView.WATER_SHAFT_SHADOW_VISIBILITY.label());
        assertEquals("Water Shafts: Raw Scatter", GBufferDebugView.WATER_SHAFT_RAW_SCATTER.label());
    }
}
