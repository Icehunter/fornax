package dev.icehunter.fornax.metalfx.rt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure logic, no Metal device: fake handles stand in for real ones, and readiness is whatever
 * boolean a test chooses, standing in for a caller's own fact-check (e.g. a command buffer's
 * completed status). */
class StructureSwapTest {

    @Test
    void promotionHappensOnlyOnceReady() {
        StructureSwap swap = new StructureSwap();
        assertEquals(0, swap.live());
        assertEquals(0, swap.offer(11));
        assertEquals(0, swap.promoteIfReady(false), "not ready yet");
        assertEquals(0, swap.live());
        assertEquals(0, swap.promoteIfReady(true), "old live was 0, so nothing to retire");
        assertEquals(11, swap.live());
        assertEquals(0, swap.promoteIfReady(true), "nothing pending to promote a second time");
    }

    @Test
    void aSecondOfferBeforePromotionRetiresTheEarlierNextWithoutItEverBecomingLive() {
        StructureSwap swap = new StructureSwap();
        assertEquals(0, swap.offer(11));
        assertEquals(11, swap.offer(22), "the first next is discarded, never having been live");
        assertEquals(0, swap.promoteIfReady(true), "old live was 0 before this, the only promotion");
        assertEquals(22, swap.live(), "22 is promoted; 11 never appears as live at any point");
    }

    /**
     * Guards against starvation: a build that takes many frames to complete must still eventually
     * promote once ready, not be silently lost or disturbed by repeated not-ready checks while it
     * is outstanding.
     */
    @Test
    void promotionSurvivesManyUnreadyChecksAndFiresOnlyOnceTheOneOutstandingBuildIsReady() {
        StructureSwap swap = new StructureSwap();
        assertEquals(0, swap.offer(11));
        for (int frame = 0; frame < 10; frame++) {
            assertEquals(0, swap.promoteIfReady(false), "not ready yet");
            assertEquals(0, swap.live());
        }
        assertEquals(0, swap.promoteIfReady(true), "old live was 0 before this promotion");
        assertEquals(11, swap.live(), "the one outstanding build promotes once ready");
    }

    @Test
    void promotionReturnsWhicheverStructureWasLiveBeforeIt() {
        StructureSwap swap = new StructureSwap();
        swap.offer(11);
        swap.promoteIfReady(true);
        assertEquals(11, swap.live());
        swap.offer(22);
        long retired = swap.promoteIfReady(true);
        assertEquals(11, retired, "11 was live before this promotion");
        assertEquals(22, swap.live());
    }

    /**
     * Guards against a timeline hazard: two command buffers signalling different values on the same
     * MTLSharedEvent can complete out of order, so a later reading can regress below an earlier one.
     * An entry retired after a high reading, at a value that reading already covers, must not be
     * stalled by a later call that happens to regress: a naive comparison against only the latest raw
     * argument would wrongly treat 5 as unreached.
     */
    @Test
    void drainRetiredToleratesARegressingSignalledValueByRememberingTheHighestSeen() {
        StructureSwap swap = new StructureSwap();
        assertTrue(swap.drainRetired(10).isEmpty(), "nothing queued yet, but this reading is remembered");
        swap.retire(List.of(1L), 5);
        assertEquals(List.of(1L), swap.drainRetired(3),
                "5 was already covered by the remembered high of 10; a regressed read must not stall it");
        assertTrue(swap.drainRetired(3).isEmpty(), "nothing left, and a drained entry must not free again");
    }

    @Test
    void drainRetiredFreesInOrderAndOnlyReachedEntries() {
        StructureSwap swap = new StructureSwap();
        swap.retire(List.of(1L, 2L), 3);
        swap.retire(List.of(3L), 6);
        assertEquals(List.of(), swap.drainRetired(2), "nothing reached yet");
        assertEquals(List.of(1L, 2L), swap.drainRetired(5), "only the first entry is reached");
        assertEquals(List.of(), swap.drainRetired(5), "the first entry drains only once");
        assertEquals(List.of(3L), swap.drainRetired(6));
        assertTrue(swap.drainRetired(100).isEmpty(), "nothing left to retire");
    }

    @Test
    void retiringAnEmptyHandleListQueuesNothing() {
        StructureSwap swap = new StructureSwap();
        swap.retire(List.of(), 0);
        assertTrue(swap.drainRetired(1000).isEmpty());
    }
}
