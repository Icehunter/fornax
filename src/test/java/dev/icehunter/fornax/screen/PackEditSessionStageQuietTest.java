package dev.icehunter.fornax.screen;

import dev.icehunter.fornax.pack.PackModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PackEditSessionStageQuietTest {

    @Test
    void stageQuietRecordsValueWithoutLivePreview() {
        PackModel model = PackFixtures.miniModelWithRuntimeSlider();
        AtomicInteger previewCalls = new AtomicInteger();
        // Three-argument test seam: injects a counting hook in place of GraphRunner.updateRuntimeValues
        // so the "no live preview" claim is an actual invocation count, not just "did not throw" --
        // GraphRunner's static path silently no-ops headlessly (null optionsBuffer), so the old
        // no-exception assertion passed identically whether or not stageQuiet routed through it.
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model),
                values -> previewCalls.incrementAndGet());
        session.stageQuiet("u_Demo", "0.75");
        assertEquals("0.75", session.get("u_Demo"));
        assertTrue(session.isDirty());
        assertEquals(0, previewCalls.get(), "stageQuiet must not fire the live-preview hook");
    }

    @Test
    void stageAllQuietStagesAllValuesAndMarksDirtyWithoutPreview() {
        PackModel model = PackFixtures.miniModelWithFourRuntimeSliders();
        AtomicInteger previewCalls = new AtomicInteger();
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model),
                values -> previewCalls.incrementAndGet());
        Map<String, String> plan = new LinkedHashMap<>();
        plan.put("u_A", "0.10");
        plan.put("u_B", "0.20");
        plan.put("u_C", "0.30");
        plan.put("u_D", "0.40");
        session.stageAllQuiet(plan);
        assertEquals("0.10", session.get("u_A"));
        assertEquals("0.20", session.get("u_B"));
        assertEquals("0.30", session.get("u_C"));
        assertEquals("0.40", session.get("u_D"));
        assertTrue(session.isDirty());
        assertEquals(0, previewCalls.get(), "stageAllQuiet must not fire the live-preview hook");
    }

    @Test
    void stageFiresLivePreviewHookExactlyOnceForARuntimeOption() {
        PackModel model = PackFixtures.miniModelWithRuntimeSlider();
        AtomicInteger previewCalls = new AtomicInteger();
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model),
                values -> previewCalls.incrementAndGet());
        session.stage("u_Demo", "0.75");
        assertEquals("0.75", session.get("u_Demo"));
        assertEquals(1, previewCalls.get(), "stage() on a runtime option must fire the live-preview hook once");
    }

    @Test
    void stageFiresLivePreviewHookOnlyWhenValueActuallyChanges() {
        // Reproduces the crash mechanism directly at the stage() level: YACL's OptionImpl
        // constructor fires an option row's .listener once at build time with its OWN current
        // value -- an unchanged re-stage -- so that fire must cost zero ring rotations, not one.
        PackModel model = PackFixtures.miniModelWithRuntimeSlider();
        AtomicInteger previewCalls = new AtomicInteger();
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model),
                values -> previewCalls.incrementAndGet());

        session.stage("u_Demo", "0.5"); // default is "0.5" -- construction-time re-stage of the same value
        assertEquals(0, previewCalls.get(), "stage() with an unchanged value must not fire the live-preview hook");

        session.stage("u_Demo", "0.50"); // numerically identical, string-different (widget round-trip)
        assertEquals(0, previewCalls.get(),
                "stage() with a numerically-unchanged value must not fire the live-preview hook");

        session.stage("u_Demo", "0.75"); // genuinely changed
        assertEquals(1, previewCalls.get(), "stage() with a changed value must fire the live-preview hook exactly once");
    }

    @Test
    void stageAllFiresZeroPreviewCallsWhenEveryValueIsUnchanged() {
        PackModel model = PackFixtures.miniModelWithFourRuntimeSliders();
        AtomicInteger previewCalls = new AtomicInteger();
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model),
                values -> previewCalls.incrementAndGet());

        Map<String, String> plan = new LinkedHashMap<>();
        plan.put("u_A", "0.5");
        plan.put("u_B", "0.50"); // numerically identical to the "0.5" default, string-different
        plan.put("u_C", "0.5");
        plan.put("u_D", "0.5");
        session.stageAll(plan);

        assertEquals("0.5", session.get("u_A"));
        assertEquals("0.50", session.get("u_B"));
        assertEquals(0, previewCalls.get(), "an all-unchanged stageAll must fire the live-preview hook zero times");
    }

    @Test
    void stageMovesGetButNotGetApplied() {
        // A row's .listener() live-preview write must
        // move ONLY the staged (get) view, never the applied (getApplied) view -- getApplied is
        // the YACL binding GETTER's source of truth, and the listener runs synchronously inside the
        // same STATE_CHANGE that changed() checks immediately after. If stage() moved getApplied too,
        // the getter would agree with the just-set pending value in that same frame and YACL's
        // pending-vs-getter changed() would go false, disabling Save the instant a slider moved.
        PackModel model = PackFixtures.miniModelWithRuntimeSlider();
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model));
        assertEquals("0.5", session.get("u_Demo"));
        assertEquals("0.5", session.getApplied("u_Demo"));

        session.stage("u_Demo", "0.75");

        assertEquals("0.75", session.get("u_Demo"), "stage() must update the staged (listener) view");
        assertEquals("0.5", session.getApplied("u_Demo"),
                "stage() must NOT move the applied (YACL binding getter) view");
    }

    @Test
    void stageQuietMovesGetAppliedImmediatelyButNotTheRealPersistSnapshot() {
        // LIVE-FIX-4's corrected mechanism (see PackEditSession#committed's doc, backed by a javap
        // bytecode trace of YACL 3.9.5's finishOrSave): stageQuiet is the binding SETTER path, called
        // synchronously by YACL's apply-value loop, immediately followed -- still before
        // session.apply() ever runs -- by YACL's own "did the setter take?" re-check of the getter.
        // getApplied (backed by the `committed` field) MUST move right here, or that re-check finds a
        // mismatch, force-reverts the option (re-firing this row's listener with the OLD value,
        // silently undoing the edit) and logs an error. But the REAL persist snapshot (appliedValue,
        // what apply()'s own isDirty()/compileDirty() compare staged against) must NOT move until
        // apply() actually runs -- otherwise apply() would see staged already equal to "applied" and
        // silently no-op, never calling PackValuesFile.save.
        PackModel model = PackFixtures.miniModelWithRuntimeSlider();
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model));

        session.stageQuiet("u_Demo", "0.9");

        assertEquals("0.9", session.get("u_Demo"));
        assertEquals("0.9", session.getApplied("u_Demo"),
                "stageQuiet must advance the getter's view immediately -- satisfies YACL's post-setter re-check");
        assertEquals("0.5", session.appliedValue("u_Demo"),
                "stageQuiet must NOT advance the real persist snapshot -- only apply() may do that");
        assertTrue(session.isDirty(), "apply()'s own dirty-check must still see real work to do after stageQuiet alone");
    }

    @Test
    void stageAllQuietMovesGetAppliedImmediatelyButNotTheRealPersistSnapshot() {
        // Batched counterpart -- the exact path MetaBinding.selectQuiet routes a meta row's binding
        // setter through, so this is the meta-row reproduction of the same bytecode-verified mechanism.
        PackModel model = PackFixtures.miniModelWithFourRuntimeSliders();
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model));
        Map<String, String> plan = new LinkedHashMap<>();
        plan.put("u_A", "0.9");
        plan.put("u_B", "0.1");

        session.stageAllQuiet(plan);

        assertEquals("0.9", session.getApplied("u_A"));
        assertEquals("0.1", session.getApplied("u_B"));
        assertEquals("0.5", session.appliedValue("u_A"), "stageAllQuiet must NOT advance the real persist snapshot");
        assertEquals("0.5", session.appliedValue("u_B"), "stageAllQuiet must NOT advance the real persist snapshot");
        assertTrue(session.isDirty());
    }

    @Test
    void getterAgreesWithPendingImmediatelyAfterSetterEvenBeforeApplyRuns() {
        // Direct reproduction of YACL's finishOrSave call ordering (listener stage, THEN binding
        // setter, THEN -- only after that -- session.apply()): the getter must catch up to the
        // just-edited value the moment the setter runs, well before apply() ever executes, or YACL's
        // own internal consistency check reverts the edit and logs a "value mismatch" error instead
        // of persisting it. This is the exact defect the naive (two-map) version of this fix had,
        // caught by tracing YACL 3.9.5's decompiled/disassembled bytecode before shipping.
        PackModel model = PackFixtures.miniModelWithRuntimeSlider();
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model));

        session.stage("u_Demo", "0.8"); // the row's own live-preview listener (user drag)
        assertNotEquals("0.8", session.getApplied("u_Demo"),
                "getter must still disagree with the live-previewed value -- this is what keeps Save enabled");

        session.stageQuiet("u_Demo", "0.8"); // YACL's apply-value loop calling our binding setter
        assertEquals("0.8", session.getApplied("u_Demo"),
                "getter must now agree with the just-set value -- otherwise YACL's own post-setter " +
                "re-check reverts the edit before session.apply() ever runs");
        assertTrue(session.isDirty(), "the real persist snapshot must still show dirty for apply() to act on");
    }

    @Test
    void getAppliedReflectsThePostApplySnapshotViaTheTestSeam() {
        // apply() itself is unavailable headlessly (it hits FabricLoader.getInstance().getGameDir(),
        // confirmed live outside a real client launch), so this pins getApplied's read path through
        // a session constructed directly over a known "applied" snapshot -- the exact map apply()
        // would have copied staged into on a real client (both `applied` and `committed` start equal
        // to whatever snapshot the constructor is given).
        PackModel model = PackFixtures.miniModelWithRuntimeSlider();
        Map<String, String> appliedSnapshot = new LinkedHashMap<>(PackFixtures.defaultValues(model));
        appliedSnapshot.put("u_Demo", "0.9");

        PackEditSession session = new PackEditSession(model, appliedSnapshot);

        assertEquals("0.9", session.getApplied("u_Demo"));
        assertEquals("0.9", session.get("u_Demo"), "a fresh session's staged view starts equal to applied");
    }

    @Test
    void stageQuietSkipsAStaleReapplyOfTheSessionOpenValueAfterAnotherSetterAlreadyAdvancedTheKey() {
        // Reproduces the live "Quality tab: change a tier, press Save while staying on the tab, it
        // snaps back" bug directly at the session level. YACL's finishOrSave apply-value loop calls
        // EVERY option's setter whenever changed() is true, and changed() can go true for a row the
        // user never touched at all: PackManageScreen builds one OptionImpl per row, across EVERY
        // tab, all at screen-open time, so an untouched row's own YACL-internal pending value stays
        // frozen at the session-open snapshot. When an EARLIER setter in the same loop pass (a meta
        // row sharing this option as one of its assign keys, processed first since the Quality page
        // is listed first in [yacl].pages) advances committed for this key, the untouched row's
        // frozen value now disagrees with the shifted getter, looks "changed" to YACL, and gets
        // handed back to OUR setter as if the user meant it -- exactly what stageQuiet's second call
        // below simulates.
        PackModel model = PackFixtures.miniModelWithFourRuntimeSliders();
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model));

        // The meta's own setter runs first and genuinely advances u_A away from its applied default.
        session.stageQuiet("u_A", "0.8");
        assertEquals("0.8", session.get("u_A"));
        assertEquals("0.8", session.getApplied("u_A"));

        // The untouched sibling raw row's setter fires next in the same loop, handing back its own
        // frozen pending value -- exactly the session's ORIGINAL applied snapshot for u_A ("0.5") --
        // because the user never touched that row directly.
        session.stageQuiet("u_A", "0.5");

        assertEquals("0.8", session.get("u_A"),
                "a stale setter reapplying the pre-session value must not clobber a fresher write");
        assertEquals("0.8", session.getApplied("u_A"),
                "the getter view must also stay on the fresher value, not revert");
    }

    @Test
    void stageQuietStillAppliesAGenuineFirstEditEvenWhenItReproducesTheAppliedSnapshot() {
        // The stale-reapply guard must only fire when something ELSE already moved committed away
        // from the applied snapshot this burst. An untouched key's own (first) setter call, even one
        // that happens to coincide with its own applied value, is a normal write, not "stale" -- there
        // is nothing yet for it to be clobbering.
        PackModel model = PackFixtures.miniModelWithFourRuntimeSliders();
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model));

        session.stageQuiet("u_A", "0.5"); // equals applied, but nothing else has touched u_A yet

        assertEquals("0.5", session.get("u_A"));
        assertEquals("0.5", session.getApplied("u_A"));
    }

    @Test
    void stageAllFiresOncePerCallAndOnlyCarriesTheChangedKeys() {
        // Mixed changed/unchanged batch: the hook must still fire exactly once (stageAll's
        // single-combined-call contract) but the map it receives
        // must contain only the entries that actually changed.
        PackModel model = PackFixtures.miniModelWithFourRuntimeSliders();
        List<Map<String, Float>> captured = new ArrayList<>();
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model), captured::add);

        Map<String, String> plan = new LinkedHashMap<>();
        plan.put("u_A", "0.5"); // unchanged
        plan.put("u_B", "0.9"); // changed
        plan.put("u_C", "0.5"); // unchanged
        plan.put("u_D", "0.1"); // changed
        session.stageAll(plan);

        assertEquals("0.5", session.get("u_A"));
        assertEquals("0.9", session.get("u_B"));
        assertEquals("0.5", session.get("u_C"));
        assertEquals("0.1", session.get("u_D"));
        assertEquals(1, captured.size(), "a mixed changed/unchanged stageAll must fire the hook exactly once");
        assertEquals(Map.of("u_B", 0.9f, "u_D", 0.1f), captured.get(0),
                "the live-preview map must contain only the changed keys");
    }
}
