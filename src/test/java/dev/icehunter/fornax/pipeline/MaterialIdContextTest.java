package dev.icehunter.fornax.pipeline;

import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Checks that {@code setAll} and {@code snapshotForVertex} match the setters and getters they
 * replace: a bulk write must land in the same slots the single setters would, and a bulk read
 * must see the same values the single getters would. */
class MaterialIdContextTest {
    @AfterEach
    void reset() {
        MaterialIdContext.clear();
    }

    @Test
    void setAllWritesEveryFactTheIndividualSettersWould() {
        MaterialIdContext.setAll(0x1234, Biome.Precipitation.SNOW, 13, 0x55, 2);

        assertEquals(0x1234, MaterialIdContext.get());
        assertEquals(MaterialIdContext.PRECIPITATION_SNOW, MaterialIdContext.getPrecipitation());
        assertEquals(13, MaterialIdContext.getLightEmission());
        assertEquals(0x55, MaterialIdContext.getBlockClass());
        assertEquals(2, MaterialIdContext.getAtlasPage());
    }

    @Test
    void setAllAndTheIndividualSettersAgreeOnPrecipitationEncoding() {
        MaterialIdContext.setAll(0, Biome.Precipitation.RAIN, 0, 0, 0);
        assertEquals(MaterialIdContext.PRECIPITATION_RAIN, MaterialIdContext.getPrecipitation());

        MaterialIdContext.setAll(0, Biome.Precipitation.NONE, 0, 0, 0);
        assertEquals(MaterialIdContext.PRECIPITATION_NONE, MaterialIdContext.getPrecipitation());
    }

    @Test
    void setAllRejectsLightEmissionOutsideVanillasZeroToFifteenRange() {
        assertThrows(IllegalArgumentException.class,
                () -> MaterialIdContext.setAll(0, Biome.Precipitation.NONE, 16, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> MaterialIdContext.setAll(0, Biome.Precipitation.NONE, -1, 0, 0));
    }

    @Test
    void setAllRejectsBlockClassFlagsOutsideTheMask() {
        assertThrows(IllegalArgumentException.class,
                () -> MaterialIdContext.setAll(0, Biome.Precipitation.NONE, 0, BlockClasses.MASK + 1, 0));
    }

    @Test
    void setAllRejectsANegativeAtlasPage() {
        assertThrows(IllegalArgumentException.class,
                () -> MaterialIdContext.setAll(0, Biome.Precipitation.NONE, 0, 0, -1));
    }

    /** A bulk write followed by a bulk read must agree with the values read one at a time. This
     * is the exact swap {@link VertexFacts#snapshot()} makes for its four per-quad reads. */
    @Test
    void snapshotForVertexMatchesTheIndividualGettersAfterSetAll() {
        MaterialIdContext.setAll(0x00FF, Biome.Precipitation.SNOW, 9, BlockClasses.MASK, 3);

        MaterialIdContext.Snapshot snapshot = MaterialIdContext.snapshotForVertex();

        assertEquals(MaterialIdContext.get(), snapshot.materialId());
        assertEquals(MaterialIdContext.getPrecipitation(), snapshot.precipitation());
        assertEquals(MaterialIdContext.getLightEmission(), snapshot.lightEmission());
        assertEquals(MaterialIdContext.getBlockClass(), snapshot.blockClassFlags());
    }

    @Test
    void clearResetsEveryFactSetAllCanWrite() {
        MaterialIdContext.setAll(0x1234, Biome.Precipitation.SNOW, 13, BlockClasses.MASK, 2);
        MaterialIdContext.clear();

        assertEquals(0, MaterialIdContext.get());
        assertEquals(MaterialIdContext.PRECIPITATION_RAIN, MaterialIdContext.getPrecipitation(),
                "an unresolved block must stay wet, same neutral default as before this lane existed");
        assertEquals(0, MaterialIdContext.getLightEmission());
        assertEquals(BlockClasses.NONE, MaterialIdContext.getBlockClass());
        assertEquals(0, MaterialIdContext.getAtlasPage());
    }
}
