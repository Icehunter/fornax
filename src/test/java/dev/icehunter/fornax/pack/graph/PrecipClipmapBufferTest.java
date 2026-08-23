package dev.icehunter.fornax.pack.graph;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The per-column precipitation field's addressing and element layout.
 *
 * <p>Everything here is checkable without a device or a world, which is the whole reason the
 * addressing lives in a Vulkan-free class: the consequence of getting it wrong is not a crash but a
 * field that quietly describes the wrong columns, and no shader can report that.
 */
class PrecipClipmapBufferTest {

    @Test
    void byteSizeIsOneWordPerColumnAndFourAligned() {
        assertEquals(128 * 128, PrecipClipmapBuffer.COLUMNS);
        assertEquals(65536L, PrecipClipmapBuffer.BYTE_SIZE);
        // TargetRegistry.ensureBufferSize rejects a size that is not a multiple of 4 (the
        // vkCmdFillBuffer zero-clear contract), so this is a hard requirement, not a preference.
        assertEquals(0L, PrecipClipmapBuffer.BYTE_SIZE % 4);
    }

    @Test
    void theTargetNameIsStable() {
        // Named in graph.toml by every consuming pack and in GraphValidator.ENGINE_BUFFERS. A rename
        // turns a shipped pack's declaration into a load error, so it is pinned as a literal here as
        // well as through the constant.
        assertEquals("precipClipmap", PrecipClipmapBuffer.TARGET);
    }

    @Test
    void aWorldColumnOwnsExactlyOneSlotWhereverTheWindowIs() {
        // The point of toroidal addressing: the slot is a pure function of the column, so nothing has
        // to be re-indexed when the window moves and the field cannot swim under the player.
        Set<Integer> slots = new HashSet<>();
        int visits = 0;
        for (int anchorX = 1234 - 200; anchorX <= 1234 + 200; anchorX += 7) {
            for (int anchorZ = -871 - 200; anchorZ <= -871 + 200; anchorZ += 11) {
                int baseX = PrecipClipmapBuffer.windowBase(anchorX);
                int baseZ = PrecipClipmapBuffer.windowBase(anchorZ);
                // The column only has an element while it is inside the window this anchor produces.
                if (1234 >= baseX && 1234 < baseX + PrecipClipmapBuffer.GRID
                        && -871 >= baseZ && -871 < baseZ + PrecipClipmapBuffer.GRID) {
                    slots.add(PrecipClipmapBuffer.slotFor(1234, -871));
                    visits++;
                }
            }
        }
        assertTrue(visits > 100, "the sweep must actually cover the column -- it visited " + visits);
        assertEquals(1, slots.size());
    }

    @Test
    void a128x128WindowFillsEverySlotExactlyOnce() {
        boolean[] seen = new boolean[PrecipClipmapBuffer.COLUMNS];
        int baseX = PrecipClipmapBuffer.windowBase(-9);
        int baseZ = PrecipClipmapBuffer.windowBase(4097);
        for (int dz = 0; dz < PrecipClipmapBuffer.GRID; dz++) {
            for (int dx = 0; dx < PrecipClipmapBuffer.GRID; dx++) {
                int slot = PrecipClipmapBuffer.slotFor(baseX + dx, baseZ + dz);
                assertFalse(seen[slot], "slot " + slot + " written twice by one window");
                seen[slot] = true;
            }
        }
        for (boolean b : seen) {
            assertTrue(b);
        }
    }

    @Test
    void theTagDistinguishesEveryAliasAConsumerCanReach() {
        // Aliases sit 128 blocks apart. Eight bits of tile index per axis separate the nearest 255 of
        // them, so the first indistinguishable pair is 128 * 256 blocks away -- 30x the world radius.
        int x = 500;
        int z = -200;
        int mine = PrecipClipmapBuffer.tagFor(x, z);
        for (int k = 1; k <= 255; k++) {
            assertNotEquals(mine, PrecipClipmapBuffer.tagFor(x + 128 * k, z), "alias +" + k + " on x");
            assertNotEquals(mine, PrecipClipmapBuffer.tagFor(x, z + 128 * k), "alias +" + k + " on z");
        }
        // Documented limit, asserted so it is a known boundary rather than a surprise.
        assertEquals(mine, PrecipClipmapBuffer.tagFor(x + 128 * 256, z));
    }

    @Test
    void anElementRoundTripsAndIdentifiesItsOwnColumn() {
        for (int type : new int[] {PrecipClipmapBuffer.TYPE_NONE, PrecipClipmapBuffer.TYPE_RAIN,
                PrecipClipmapBuffer.TYPE_SNOW}) {
            int element = PrecipClipmapBuffer.encode(-1337, 88_000, type);
            assertEquals(type, PrecipClipmapBuffer.typeOf(element));
            assertTrue(PrecipClipmapBuffer.describes(element, -1337, 88_000));
            // The aliased column that shares this slot must NOT be described by it -- that
            // misidentification is exactly what a consumer relies on this to catch.
            assertFalse(PrecipClipmapBuffer.describes(element, -1337 + 128, 88_000));
            assertFalse(PrecipClipmapBuffer.describes(element, -1337, 88_000 + 128));
        }
    }

    @Test
    void theTagOccupiesTheTopSixteenBitsAndNothingElse() {
        // Positioned to match snowField's own element tag bit-for-bit so a consuming shader indexes
        // both fields with one expression. Bits 8..15 are reserved and must stay zero.
        int element = PrecipClipmapBuffer.encode(77, -3, PrecipClipmapBuffer.TYPE_SNOW);
        assertEquals(0xFFFF0000, PrecipClipmapBuffer.TAG_MASK);
        assertEquals(PrecipClipmapBuffer.tagFor(77, -3), element & PrecipClipmapBuffer.TAG_MASK);
        assertEquals(0, (element >> 8) & 0xFF, "bits 8..15 are reserved and must be written zero");
    }

    @Test
    void theWindowBaseFloorDividesRatherThanTruncating() {
        // -1 / 16 is 0 under Java's truncating division and -1 under floor. Truncation puts a
        // 16-block discontinuity in the window that only appears west or north of the origin, which
        // is the classic negative-coordinate bug that is invisible in every test world spawned at 0.
        assertEquals(-64, PrecipClipmapBuffer.windowBase(0));
        assertEquals(-64, PrecipClipmapBuffer.windowBase(15));
        assertEquals(-48, PrecipClipmapBuffer.windowBase(16));
        assertEquals(-80, PrecipClipmapBuffer.windowBase(-1));
        assertEquals(-80, PrecipClipmapBuffer.windowBase(-16));
        assertEquals(-96, PrecipClipmapBuffer.windowBase(-17));
    }

    @Test
    void theWindowAlwaysContainsItsAnchor() {
        for (int anchor = -5000; anchor <= 5000; anchor += 3) {
            int base = PrecipClipmapBuffer.windowBase(anchor);
            assertTrue(anchor >= base && anchor < base + PrecipClipmapBuffer.GRID,
                    "anchor " + anchor + " fell outside its own window [" + base + ", "
                            + (base + PrecipClipmapBuffer.GRID) + ")");
        }
    }

    @Test
    void theCoveredSetChangesOnlyOnSnapBoundaries() {
        // What the snap buys: a column at the far rim is not thrashed in and out of coverage by
        // sub-block anchor jitter, and every change of the covered set costs a re-fill of the columns
        // that entered.
        int changes = 0;
        int previous = PrecipClipmapBuffer.windowBase(0);
        for (int anchor = 1; anchor < 64; anchor++) {
            int base = PrecipClipmapBuffer.windowBase(anchor);
            if (base != previous) {
                assertEquals(0, anchor % PrecipClipmapBuffer.ANCHOR_SNAP);
                changes++;
                previous = base;
            }
        }
        assertEquals(64 / PrecipClipmapBuffer.ANCHOR_SNAP - 1, changes);
    }
}
