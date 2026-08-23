package dev.icehunter.fornax.screen;

import dev.icehunter.fornax.pack.MetaSpec;
import dev.icehunter.fornax.pack.PackModel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MetaBindingTest {
    private static MetaSpec meta() {
        return new MetaSpec("Ambient Shading", "", List.of("Off", "Rich"), java.util.Map.of());
    }

    /** One-tier meta pinning a single named runtime option to {@code value} -- burst-test helper. */
    private static MetaSpec singleOptionMeta(String label, String optionName, Object value) {
        Map<String, Object> on = new LinkedHashMap<>();
        on.put(optionName, value);
        Map<String, Map<String, Object>> assign = new LinkedHashMap<>();
        assign.put("On", on);
        return new MetaSpec(label, "", List.of("On"), assign);
    }

    @Test
    void selectableValuesAreTiersOnlyInDeclaredOrder() {
        assertEquals(List.of("Off", "Rich"), MetaBinding.selectableValues(meta()));
    }

    @Test
    void selectableValuesNeverContainCustom() {
        // Custom must be display-only -- reachable as the binding getter's return value (see
        // MetaBinding.current), never as a list entry a click/scroll/keyboard cycle can land on.
        assertFalse(MetaBinding.selectableValues(meta()).contains(MetaBinding.CUSTOM));
        assertFalse(MetaBinding.selectableValues(singleOptionMeta("M", "u_A", 0.9)).contains(MetaBinding.CUSTOM));
    }

    @Test
    void customConstantIsStable() {
        assertEquals("Custom", MetaBinding.CUSTOM);
    }

    @Test
    void selectQuietStagesTierPlanWithoutLivePreview() {
        PackModel model = PackFixtures.miniModelWithFourRuntimeSliders();
        AtomicInteger previewCalls = new AtomicInteger();
        // Three-argument test seam: injects a counting hook in place of GraphRunner.updateRuntimeValues
        // so "no live preview" is an actual invocation count, not just "did not throw" -- GraphRunner's
        // static path silently no-ops headlessly (null optionsBuffer), so the old no-exception
        // assertion passed identically whether or not selectQuiet's stageAllQuiet path fired it.
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model),
                values -> previewCalls.incrementAndGet());
        MetaBinding.selectQuiet(session, singleOptionMeta("M", "u_A", 0.9), "On");
        assertEquals("0.9", session.get("u_A"));
        assertTrue(session.isDirty());
        assertEquals(0, previewCalls.get(), "selectQuiet must not fire the live-preview hook");
    }

    @Test
    void selectQuietCustomIsNoOp() {
        PackModel model = PackFixtures.miniModelWithFourRuntimeSliders();
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model));
        MetaBinding.selectQuiet(session, singleOptionMeta("M", "u_A", 0.9), MetaBinding.CUSTOM);
        assertFalse(session.isDirty());
    }

    @Test
    void selectQuietAdvancesCurrentTierImmediatelyForYaclsPostSetterRecheck() {
        // Same bytecode-verified mechanism as PackEditSessionStageQuietTest's getterAgreesWithPending...
        // test: MetaBinding.current is the meta row's binding GETTER, so it must agree with the
        // just-selected tier immediately after selectQuiet (the binding SETTER) runs, well before
        // session.apply() executes -- otherwise YACL's finishOrSave would revert the meta selection
        // (re-firing the row's listener with the old tier) and log a "value mismatch" error instead
        // of ever persisting it.
        PackModel model = PackFixtures.miniModelWithFourRuntimeSliders();
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model));
        MetaSpec meta = singleOptionMeta("M", "u_A", 0.9);

        assertEquals(MetaBinding.CUSTOM, MetaBinding.current(session, meta)); // applied "0.5" matches no tier

        MetaBinding.selectQuiet(session, meta, "On");

        assertEquals("On", MetaBinding.current(session, meta),
                "current() must resolve the just-selected tier immediately after selectQuiet");
    }

    @Test
    void selectQuietUnknownTierThrows() {
        PackModel model = PackFixtures.miniModelWithFourRuntimeSliders();
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model));
        MetaSpec meta = singleOptionMeta("M", "u_A", 0.9);
        assertThrows(IllegalArgumentException.class,
                () -> MetaBinding.selectQuiet(session, meta, "Ludicrous"));
    }

    @Test
    void fourMetaRowSelectQuietBurstStagesAllFourWithoutWrappingTheRing() {
        // Reproduces the CRITICAL mechanism directly: N=4 changed meta rows in one Save burst means
        // N binding-setter calls inside YACL's single synchronous finishOrSave apply-value loop.
        // Routing all four through MetaBinding.select (stageAll) would fire four separate
        // GraphRunner.updateRuntimeValues ring rotations against a 3-slot ring -- the documented
        // "Cannot wait on a fence for the current submit" crash. Routing all four through
        // selectQuiet (stageAllQuiet) must record every value with zero ring rotations; reaching the
        // final assertions without an exception, plus every value landing correctly, is the same
        // record-only observable PackEditSessionStageQuietTest and selectQuietStagesTierPlan... use.
        // (A literal follow-on session.apply() cannot run in this headless suite -- apply() always
        // calls PackValuesFile.save via PackDiscovery.shaderpacksDir(), which hits
        // FabricLoader.getInstance().getGameDir() and throws IllegalStateException outside a real
        // client launch, confirmed live against this exact fixture; that FabricLoader dependency is
        // pre-existing and orthogonal to this fix -- no test in this suite calls PackEditSession.apply().
        // apply()'s own single-combined-resync behavior over whatever landed in staged is unchanged by
        // this fix and is not what's under test here.)
        PackModel model = PackFixtures.miniModelWithFourRuntimeSliders();
        AtomicInteger previewCalls = new AtomicInteger();
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model),
                values -> previewCalls.incrementAndGet());

        MetaBinding.selectQuiet(session, singleOptionMeta("M1", "u_A", 0.1), "On");
        MetaBinding.selectQuiet(session, singleOptionMeta("M2", "u_B", 0.2), "On");
        MetaBinding.selectQuiet(session, singleOptionMeta("M3", "u_C", 0.3), "On");
        MetaBinding.selectQuiet(session, singleOptionMeta("M4", "u_D", 0.4), "On");

        assertEquals("0.1", session.get("u_A"));
        assertEquals("0.2", session.get("u_B"));
        assertEquals("0.3", session.get("u_C"));
        assertEquals("0.4", session.get("u_D"));
        assertTrue(session.isDirty());
        assertEquals(0, previewCalls.get(), "the 4-meta selectQuiet burst must fire the live-preview hook zero times");
    }

    @Test
    void selectFiresLivePreviewHookOncePerCall() {
        // Contrast case for selectQuiet's zero-firing burst above: MetaBinding.select (the row's own
        // live-drag listener path, ring-safe for exactly ONE call) routes through PackEditSession's
        // batched stageAll, which fires the live-preview hook once per call -- once per tier selection,
        // regardless of how many options that tier's plan touches.
        PackModel model = PackFixtures.miniModelWithFourRuntimeSliders();
        AtomicInteger previewCalls = new AtomicInteger();
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model),
                values -> previewCalls.incrementAndGet());

        MetaBinding.select(session, singleOptionMeta("M1", "u_A", 0.1), "On");
        assertEquals("0.1", session.get("u_A"));
        assertEquals(1, previewCalls.get(), "one select() call must fire the live-preview hook exactly once");

        MetaBinding.select(session, singleOptionMeta("M2", "u_B", 0.2), "On");
        assertEquals(2, previewCalls.get(), "a second select() call must fire the hook exactly once more");
    }

    @Test
    void sixMetaRowConstructionStormWithAlreadyMatchingTiersFiresZeroPreviewCalls() {
        // Reproduces the live crash: PackManageScreen.create builds six meta rows synchronously;
        // YACL's OptionImpl constructor fires each row's .listener ONCE at build time with
        // MetaBinding.current's own return value -- i.e. select() is called with the tier ALREADY
        // in effect. Six such calls in one un-submitted frame must fire the live-preview hook (and
        // therefore rotate the 3-slot GraphRunner ring buffer) zero times; three or more would wrap
        // the ring mid-frame, throwing "IllegalStateException: Cannot wait on a fence for the
        // current submit" (confirmed live).
        PackModel model = PackFixtures.miniModelWithFourRuntimeSliders();
        AtomicInteger previewCalls = new AtomicInteger();
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model),
                values -> previewCalls.incrementAndGet());

        // Each meta pins its option to "0.5" -- the fixture's own declared default, i.e. the value
        // already in effect when the session opens, exactly like a real meta row whose tier already
        // matches the pack's current on-disk values.
        MetaSpec metaA = singleOptionMeta("M1", "u_A", 0.5);
        MetaSpec metaB = singleOptionMeta("M2", "u_B", 0.5);
        MetaSpec metaC = singleOptionMeta("M3", "u_C", 0.5);
        MetaSpec metaD = singleOptionMeta("M4", "u_D", 0.5);
        List<MetaSpec> sixConstructionTimeRows = List.of(metaA, metaB, metaC, metaD, metaA, metaB);

        for (MetaSpec row : sixConstructionTimeRows) {
            assertEquals("On", MetaBinding.current(session, row), "tier must already be in effect before select()");
            MetaBinding.select(session, row, "On");
        }

        assertEquals(0, previewCalls.get(),
                "a six-meta-row construction storm of already-matching tiers must fire zero live-preview calls");
    }

    @Test
    void defaultTierResolvesTierMatchingDeclaredDefaultsNotFirstTier() {
        // Reproduces the "Reset turns everything off" bug directly: u_A's declared default (see
        // PackFixtures.defaultValues) is "0.5". Author "Off" FIRST in values(), pinning a
        // non-default 0.1, and "Rich" SECOND, pinning the actual default 0.5 -- exactly the shape
        // that broke Reset, since the old code took values().get(0) unconditionally regardless of
        // which tier the pack's declared defaults describe.
        PackModel model = PackFixtures.miniModelWithFourRuntimeSliders();
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model));
        Map<String, Object> off = new LinkedHashMap<>();
        off.put("u_A", 0.1);
        Map<String, Object> rich = new LinkedHashMap<>();
        rich.put("u_A", 0.5);
        Map<String, Map<String, Object>> assign = new LinkedHashMap<>();
        assign.put("Off", off);
        assign.put("Rich", rich);
        MetaSpec meta = new MetaSpec("Ambient Shading", "", List.of("Off", "Rich"), assign);

        assertEquals("Rich", MetaBinding.defaultTier(session, "ambient-shading", meta),
                "binding default must be the tier matching the pack's DECLARED DEFAULTS, not values().get(0)");
        assertNotEquals("Off", MetaBinding.defaultTier(session, "ambient-shading", meta),
                "must not silently return the first authored tier when it isn't the declared default");
    }

    @Test
    void defaultTierFallsBackToFirstTierWhenNoTierMatchesDeclaredDefaults() {
        // Neither tier's assign table equals u_A's declared default (0.5) -- a genuine
        // pack-authoring gap (mirrors MetaMatchTest.metaKeysAbsentFromCurrentValuesFallBackToOptionDefault,
        // which proves matchingTier(..., Map.of(), ...) returns null for this exact shape). defaultTier
        // must still return a real, selectable tier (never null/Custom) so Reset never crashes; it
        // falls back to the first authored tier -- the pre-fix behavior -- but only as a diagnosed
        // fallback, not the normal path. Calling it twice with the same metaId must stay idempotent
        // and must not throw on the already-warned second call.
        PackModel model = PackFixtures.miniModelWithFourRuntimeSliders();
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model));
        Map<String, Object> low = new LinkedHashMap<>();
        low.put("u_A", 0.1);
        Map<String, Object> high = new LinkedHashMap<>();
        high.put("u_A", 0.9);
        Map<String, Map<String, Object>> assign = new LinkedHashMap<>();
        assign.put("Low", low);
        assign.put("High", high);
        MetaSpec meta = new MetaSpec("Shadow Detail", "", List.of("Low", "High"), assign);

        assertEquals("Low", MetaBinding.defaultTier(session, "shadow-detail-fallback", meta));
        assertEquals("Low", MetaBinding.defaultTier(session, "shadow-detail-fallback", meta));
    }

    @Test
    void defaultTierNeverReturnsCustom() {
        // Same reasoning the pre-fix code's own comment gave for never defaulting to CUSTOM: a
        // CUSTOM binding default would restage the #2 mismatch bug through the Reset button instead
        // of the cycler (Save's applyValue() -> selectQuiet(..., CUSTOM) is a no-op -> YACL's
        // post-apply verify re-reads the real tier -> mismatch -> logged and auto-reset).
        PackModel model = PackFixtures.miniModelWithFourRuntimeSliders();
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model));
        MetaSpec meta = singleOptionMeta("M", "u_A", 0.9); // "On" pins 0.9; declared default is 0.5 -> no match
        assertNotEquals(MetaBinding.CUSTOM, MetaBinding.defaultTier(session, "single-opt-mismatch", meta));
    }

    @Test
    void tierSelectionRoundTripsThroughTheFullSaveBurstEvenWithAStaleSiblingRawRowInTheLoop() {
        // End-to-end reproduction of the live bug report at the MetaBinding layer, mirroring exactly
        // what one Quality-tab tier selection + Save does across YACL's real finishOrSave call
        // sequence: (1) the row's own LISTENER stages a live preview the instant the user clicks the
        // cycler; (2) Save's apply-value loop calls the meta row's own SETTER first (Quality is listed
        // first in [yacl].pages, so its rows are processed before any raw option's "home tab"); (3) the
        // SAME loop then reaches the untouched sibling raw row for one of the tier's own assign keys --
        // rendered independently on that option's home tab -- whose YACL-internal pending value is
        // still frozen at the session-open snapshot, and hands that stale value to OUR setter, exactly
        // as YACL's own bytecode does. The whole round trip must land on the newly-selected tier, not
        // revert to the pre-edit one.
        PackModel model = PackFixtures.miniModelWithFourRuntimeSliders();
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model));
        MetaSpec meta = singleOptionMeta("Shadow Detail", "u_A", 0.9); // "On" pins u_A to 0.9; applied default is 0.5

        assertEquals(MetaBinding.CUSTOM, MetaBinding.current(session, meta), "starts unmatched (u_A sits at its 0.5 default)");

        // (1) User clicks the Quality-tab cycler -- the row's own listener, live preview.
        MetaBinding.select(session, meta, "On");
        assertEquals("0.9", session.get("u_A"), "the listener must stage the tier's plan immediately");

        // (2) Save's apply-value loop reaches the meta row itself first.
        MetaBinding.selectQuiet(session, meta, "On");
        assertEquals("On", MetaBinding.current(session, meta),
                "the meta row's own getter must resolve the just-applied tier immediately after its setter");

        // (3) The SAME loop then reaches u_A's own independent "home tab" row -- never touched by the
        // user -- whose frozen pending value is the session-open snapshot ("0.5"), handed to our
        // setter exactly like YACL's real apply-value loop would.
        session.stageQuiet("u_A", "0.5");

        assertEquals("0.9", session.get("u_A"),
                "the stale home-tab row's setter call must not revert the tier's freshly-staged value");
        assertEquals("0.9", session.getApplied("u_A"),
                "the getter view (what apply() will persist) must also stay on the tier's value");
        assertEquals("On", MetaBinding.current(session, meta),
                "MetaMatch must still re-derive the just-selected tier from the surviving staged values -- "
                        + "not revert to Custom or the pre-edit tier");
    }

    @Test
    void dependencyMetIsTrueWhenMetaHasNoDependsOn() {
        PackModel model = PackFixtures.miniModelWithBooleanCompileOption();
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model));
        MetaSpec meta = new MetaSpec("Colored Light Reach", "", List.of("Short", "Long"), Map.of(), null);
        assertTrue(MetaBinding.dependencyMet(session, meta));
    }

    @Test
    void dependencyMetIsTrueWhenTheDependedOptionIsApplied() {
        PackModel model = PackFixtures.miniModelWithBooleanCompileOption();
        // Sequential put() only (order-sensitive-fixture memory) -- a one-entry map here, but the
        // convention stays consistent across the suite.
        Map<String, String> applied = new LinkedHashMap<>();
        applied.put("EMITTER_LIGHTS", "1");
        PackEditSession session = new PackEditSession(model, applied);
        MetaSpec meta = new MetaSpec("Colored Light Reach", "", List.of("Short", "Long"), Map.of(), "EMITTER_LIGHTS");
        assertTrue(MetaBinding.dependencyMet(session, meta));
    }

    @Test
    void dependencyMetIsFalseWhenTheDependedOptionIsOff() {
        PackModel model = PackFixtures.miniModelWithBooleanCompileOption();
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model)); // EMITTER_LIGHTS defaults "0"
        MetaSpec meta = new MetaSpec("Colored Light Reach", "", List.of("Short", "Long"), Map.of(), "EMITTER_LIGHTS");
        assertFalse(MetaBinding.dependencyMet(session, meta));
    }

    @Test
    void dependencyMetFailsClosedWhenTheDependedOptionIsAbsentFromTheSession() {
        PackModel model = PackFixtures.miniModelWithBooleanCompileOption();
        PackEditSession session = new PackEditSession(model, Map.of()); // EMITTER_LIGHTS never applied at all
        MetaSpec meta = new MetaSpec("Colored Light Reach", "", List.of("Short", "Long"), Map.of(), "EMITTER_LIGHTS");
        assertFalse(MetaBinding.dependencyMet(session, meta),
                "an absent depended-on option must fail closed (unmet), not throw or default to met");
    }

    @Test
    void recompilesOnSaveIsFalseForAMetaThatAssignsOnlyRuntimeOptions() {
        // LIGHT_REACH's real shape: every tier pins only u_A (runtime), so Save never triggers a
        // GraphRunner.rebuild -- the row should read "Applies live.", not the recompile hint.
        PackModel model = PackFixtures.miniModelWithRuntimeAndCompileOptions();
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model));
        MetaSpec meta = singleOptionMeta("Colored Light Reach", "u_A", 0.9);
        assertFalse(MetaBinding.recompilesOnSave(session, meta));
    }

    @Test
    void recompilesOnSaveIsTrueWhenAnyTierAssignsACompileOption() {
        // SHADOW_DETAIL's real shape: tiers mix a runtime key (u_A here, standing in for
        // u_ShadowSoftness) with a compile key (SHADOW_SAMPLES) -- one compile key anywhere in the
        // meta's tiers is enough to make Save recompile.
        PackModel model = PackFixtures.miniModelWithRuntimeAndCompileOptions();
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model));
        Map<String, Object> low = new LinkedHashMap<>();
        low.put("u_A", 0.1);
        low.put("SHADOW_SAMPLES", 4);
        Map<String, Map<String, Object>> assign = new LinkedHashMap<>();
        assign.put("Low", low);
        MetaSpec meta = new MetaSpec("Shadow Detail", "", List.of("Low"), assign);
        assertTrue(MetaBinding.recompilesOnSave(session, meta));
    }

    @Test
    void recompilesOnSaveIsFalseForAMetaWithNoAssignTiers() {
        PackModel model = PackFixtures.miniModelWithRuntimeAndCompileOptions();
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model));
        MetaSpec meta = new MetaSpec("Empty", "", List.of(), Map.of());
        assertFalse(MetaBinding.recompilesOnSave(session, meta));
    }

    @Test
    void currentResolvesMatchingTierWithoutFullOptionTableCopy() {
        // Guards the MEDIUM fix's refactor of current() from map-materializing stagedView to the
        // lookup-backed SessionLookup: behavior must be unchanged (modulo the LIVE-FIX-4 switch to
        // the committed/getter view -- see the divergence assertions below, and PackEditSession
        // #committed's doc for the bytecode-verified reason the LISTENER and SETTER paths must move
        // current() differently).
        Map<String, Map<String, Object>> assign = new LinkedHashMap<>();
        assign.put("Low", Map.of("u_A", 0.1));
        assign.put("High", Map.of("u_A", 0.9));
        MetaSpec shadowMeta = new MetaSpec("Shadow Detail", "", List.of("Low", "High"), assign);
        PackModel model = PackFixtures.miniModelWithFourRuntimeSliders();
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model));

        assertEquals(MetaBinding.CUSTOM, MetaBinding.current(session, shadowMeta)); // default 0.5 matches neither

        // LIVE-FIX-4: current() is the YACL binding GETTER for every meta row. A LISTENER-path change
        // (stage/stageAll, live-preview) must NOT move it -- this is the exact mechanism the original
        // "Save never enables" bug hinged on: if current() tracked staged, a cycler listener's own
        // stageAll call would satisfy YACL's changed() check in the same synchronous frame it fired.
        session.stage("u_A", "0.9");
        assertEquals(MetaBinding.CUSTOM, MetaBinding.current(session, shadowMeta),
                "current() must not move on a listener-path (stage) change -- only the SETTER path may");

        // The SETTER path (stageQuiet), by contrast, MUST advance current() immediately: YACL's
        // finishOrSave re-reads the getter right after calling the binding setter, synchronously and
        // before session.apply() ever runs, reverting (with a logged error) anything that still
        // disagrees with the pending value (see PackEditSession#committed's doc for the full trace).
        session.stageQuiet("u_A", "0.9");
        assertEquals("High", MetaBinding.current(session, shadowMeta),
                "current() must resolve the just-set tier immediately after the SETTER path (stageQuiet)");
        // ...while the REAL persist snapshot must not move until session.apply() actually runs, or
        // apply()'s own dirty-check would see nothing to persist.
        assertNotEquals("0.9", session.appliedValue("u_A"),
                "the real persist snapshot must not move until session.apply() actually runs");
    }
}
