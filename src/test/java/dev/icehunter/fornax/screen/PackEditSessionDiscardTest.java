package dev.icehunter.fornax.screen;

import dev.icehunter.fornax.pack.PackModel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for {@link PackEditSession#discard()}: {@code mixin.yacl.YACLScreenCloseMixin} wires
 * YACL's "Undo" button to it. This is the secondary mechanism for reverting an abandoned edit:
 * a live-previewed RUNTIME edit that gets abandoned must revert {@code PackEditSession#staged} (and
 * {@code PackEditSession#committed}, reachable only via {@link PackEditSession#getApplied}) back to
 * the last-applied snapshot, not just leave the GPU buffer's write orphaned.
 *
 * <p>{@link PackEditSession#discard()} calls {@code GraphRunner.updateRuntimeValues} directly (not
 * through the injectable {@code PackEditSession#runtimePreviewHook} test seam -- see that field's own
 * doc), so these tests can't observe the GPU write itself headlessly; per the precedent {@link
 * PackEditSessionStageQuietTest} already relies on, {@code GraphRunner}'s static path silently no-ops
 * outside a real client launch (null {@code optionsBuffer}), so calling {@code discard()} here is
 * safe and exercises every OTHER effect: the plain Java bookkeeping this class actually owns.
 */
class PackEditSessionDiscardTest {

    @Test
    void discardRevertsStagedBackToAppliedAndClearsDirty() {
        PackModel model = PackFixtures.miniModelWithRuntimeSlider();
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model));

        session.stage("u_Demo", "0.9"); // simulates a dragged slider's live-preview listener write
        assertTrue(session.isDirty());

        session.discard();

        assertEquals("0.5", session.get("u_Demo"), "discard() must revert the staged (listener) view");
        assertFalse(session.isDirty(), "discard() must leave the session clean");
    }

    @Test
    void discardResyncsCommittedEvenWhenStageQuietHadAdvancedItAheadOfApplied() {
        // Reproduces the exact scenario committed's doc warns about: a Save that got abandoned
        // mid-flight (or, after this fix, a since-superseded binding-setter call) can leave `committed`
        // ahead of the real persist snapshot. discard() must resync it back down too, or the YACL
        // binding GETTER (MetaBinding.current / getApplied) would keep reporting the abandoned value
        // as if it were still in effect.
        PackModel model = PackFixtures.miniModelWithRuntimeSlider();
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model));

        session.stageQuiet("u_Demo", "0.9");
        assertEquals("0.9", session.getApplied("u_Demo"));

        session.discard();

        assertEquals("0.5", session.getApplied("u_Demo"),
                "discard() must resync committed back to the real persist snapshot too");
        assertEquals("0.5", session.get("u_Demo"));
    }

    @Test
    void discardOnAnAlreadyCleanSessionIsANoOpThatStaysClean() {
        PackModel model = PackFixtures.miniModelWithRuntimeSlider();
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model));

        session.discard();

        assertFalse(session.isDirty());
        assertEquals("0.5", session.get("u_Demo"));
        assertEquals("0.5", session.getApplied("u_Demo"));
    }

    @Test
    void discardRevertsOnlyTheOptionsThatWereActuallyStagedAcrossAMultiOptionSession() {
        PackModel model = PackFixtures.miniModelWithFourRuntimeSliders();
        PackEditSession session = new PackEditSession(model, PackFixtures.defaultValues(model));

        Map<String, String> plan = new LinkedHashMap<>();
        plan.put("u_A", "0.9");
        plan.put("u_C", "0.1");
        session.stageAll(plan);
        assertTrue(session.isDirty());

        session.discard();

        assertEquals("0.5", session.get("u_A"));
        assertEquals("0.5", session.get("u_B"));
        assertEquals("0.5", session.get("u_C"));
        assertEquals("0.5", session.get("u_D"));
        assertFalse(session.isDirty());
    }
}
