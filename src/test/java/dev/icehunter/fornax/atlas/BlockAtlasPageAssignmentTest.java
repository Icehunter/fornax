package dev.icehunter.fornax.atlas;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockAtlasPageAssignmentTest {
    @Test
    void aBlockWithNoSpritesResolvesToPageZero() {
        BlockAtlasPageAssignment.Assignment assignment = BlockAtlasPageAssignment.resolve(List.of());

        assertEquals(0, assignment.page());
        assertFalse(assignment.split());
    }

    @Test
    void aBlockWhoseSpritesAllShareOnePageIsNotFlaggedAsSplit() {
        BlockAtlasPageAssignment.Assignment assignment =
                BlockAtlasPageAssignment.resolve(List.of(2, 2, 2));

        assertEquals(2, assignment.page());
        assertFalse(assignment.split());
    }

    @Test
    void aBlockWhoseSpritesSplitAcrossPagesTakesTheLowestAndIsFlagged() {
        BlockAtlasPageAssignment.Assignment assignment =
                BlockAtlasPageAssignment.resolve(List.of(3, 1, 2));

        assertEquals(1, assignment.page());
        assertTrue(assignment.split());
    }

    @Test
    void orderOfTheInputCollectionDoesNotAffectTheResult() {
        BlockAtlasPageAssignment.Assignment forward = BlockAtlasPageAssignment.resolve(List.of(0, 1, 2));
        BlockAtlasPageAssignment.Assignment reversed = BlockAtlasPageAssignment.resolve(List.of(2, 1, 0));

        assertEquals(forward, reversed);
    }

    @Test
    void aNegativePageIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new BlockAtlasPageAssignment.Assignment(-1, false));
    }
}
