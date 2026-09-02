package dev.icehunter.fornax.voxel;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the access widener the climate harvest depends on. An unapplied widener breaks
 * compilation of {@code PrecipCoarseClipmapUpload}, which is loud. A member renamed upstream
 * leaves the widener line matching nothing, with only a loader warning. This test names the
 * members directly.
 */
final class BiomeClimateAccessContractTest {

    @Test
    void theWidenerIsDeclaredAndWiredIntoTheBuild() throws IOException {
        String widener = Files.readString(Path.of("src/main/resources/fornax.accesswidener"));
        // "official": MC 26.2 ships already-named jars and Loom runs with obfuscation disabled, so the
        // one namespace the jar processor accepts is official. "named" is refused at build time.
        assertTrue(widener.startsWith("accessWidener v2 official"));
        assertTrue(widener.contains("accessible class net/minecraft/world/level/biome/Biome$ClimateSettings"));
        assertTrue(widener.contains("accessible field net/minecraft/world/level/biome/Biome climateSettings"));
        assertTrue(widener.contains("accessible method net/minecraft/world/level/biome/Biome getTemperature"
                + " (Lnet/minecraft/core/BlockPos;I)F"));

        String modJson = Files.readString(Path.of("src/main/resources/fabric.mod.json"));
        assertTrue(modJson.contains("\"accessWidener\": \"fornax.accesswidener\""),
                "the loader applies the widener only when fabric.mod.json names it");
        String build = Files.readString(Path.of("build.gradle"));
        assertTrue(build.contains("accessWidenerPath = file(\"src/main/resources/fornax.accesswidener\")"),
                "loom applies the widener at compile time only when build.gradle names it");
    }

    @Test
    void theGameStillDeclaresTheMembersTheWidenerNames() throws NoSuchMethodException, NoSuchFieldException {
        // Declared members are found whether or not the widener has made them public; the test
        // pins that they still exist under these names with these signatures.
        Method temperature = Biome.class.getDeclaredMethod("getTemperature", BlockPos.class, int.class);
        assertEquals(float.class, temperature.getReturnType());
        assertEquals("climateSettings", Biome.class.getDeclaredField("climateSettings").getName());
        assertEquals(float.class, Biome.class.getDeclaredMethod("getBaseTemperature").getReturnType());
    }
}
