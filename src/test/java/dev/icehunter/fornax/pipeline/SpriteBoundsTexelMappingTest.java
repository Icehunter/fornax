package dev.icehunter.fornax.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Java uploader and the GLSL reader must agree on how an atlas coordinate maps to a grid cell,
 * and nothing in the build can check that -- the shader side is text.
 *
 * <p>So this pins the Java half, and the shader mirrors it as
 * {@code texelFetch(u_GeomInput0, ivec2(atlasUv * SIZE), 0)}. A disagreement would not fail loudly:
 * it would hand a fragment the rectangle of a neighbouring sprite, which reads as one texture
 * bleeding into another rather than as anything obviously broken.
 */
public class SpriteBoundsTexelMappingTest {
    /** Mirrors the fill loop's cell range for a sprite spanning [u0,u1). */
    private static int firstCell(float u) {
        return Math.max(0, (int) Math.floor(u * SpriteBoundsTexture.size()));
    }

    private static int lastCell(float u) {
        return Math.min(SpriteBoundsTexture.size() - 1, (int) Math.ceil(u * SpriteBoundsTexture.size()) - 1);
    }

    @Test
    void aSpriteClaimsEveryCellItsCoordinatesFallIn() {
        // A 1/16 sprite at the origin: any coordinate inside it must land on a cell the fill loop
        // wrote, or that fragment reads a zero rectangle and silently loses its bounds.
        float u0 = 0.0f, u1 = 1.0f / 16.0f;
        int first = firstCell(u0), last = lastCell(u1);
        for (float u = u0; u < u1; u += (u1 - u0) / 64.0f) {
            int cell = (int) (u * SpriteBoundsTexture.size());
            assertTrue(cell >= first && cell <= last,
                    "coordinate " + u + " maps to cell " + cell + ", outside the filled range "
                            + first + ".." + last);
        }
    }

    @Test
    void adjacentSpritesDoNotOverlapCells() {
        // Two sprites side by side must not both claim a boundary cell, or whichever was written
        // last wins and one of them silently gets the other's rectangle.
        float aEnd = 1.0f / 16.0f;
        assertEquals(firstCell(aEnd), lastCell(aEnd) + 1,
                "the cell after one sprite's last must be the next sprite's first");
    }

    @Test
    void theGridIsFineEnoughForTheSmallestBlockSprite() {
        // A 16px sprite on a 4096px atlas spans 1/256 of it. The grid must give it at least two
        // cells, or rounding could leave it unrepresented entirely.
        float span = 1.0f / 256.0f;
        assertTrue(lastCell(span) - firstCell(0.0f) >= 1,
                "grid too coarse: the smallest block sprite would fit in under two cells");
    }
}
