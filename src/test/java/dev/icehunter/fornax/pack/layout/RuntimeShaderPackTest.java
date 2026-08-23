package dev.icehunter.fornax.pack.layout;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-testable slice of finding 1's fix (see {@code .superpowers/sdd/task-2-report.md}): the
 * "invisible when off" invariant for a vanilla core-shader override, exercised against a fresh
 * {@link RuntimeShaderPack} instance (never the {@code INSTANCE} singleton, so tests can't leak
 * state into each other) on the {@code clientStarted == false} path every other layout test relies
 * on -- {@link RuntimeShaderPack#reload(Map, Map)} only calls into {@code Minecraft.getInstance()}
 * once {@code markClientStarted()} has run, which nothing in this test suite ever calls, so {@link
 * RuntimeShaderPack#getNamespaces} and {@link RuntimeShaderPack#sourceOrNull} are fully exercisable
 * headlessly. The live {@code Minecraft.reloadResourcePacks()} half of {@link
 * RuntimeShaderPack#reload} (and the real end-to-end "curved lightmap disappears in-game" behavior)
 * is only verifiable in a running client -- see the report for what remains live-only.
 */
class RuntimeShaderPackTest {
    private static final Map<String, String> SOURCES = Map.of("shaders/blocks/terrain.fsh", "terrain text");
    private static final Map<String, String> OVERRIDES = Map.of("shaders/core/lightmap.fsh", "curved lightmap text");

    @Test
    void freshInstanceAdvertisesOnlyItsOwnNamespace() {
        RuntimeShaderPack pack = new RuntimeShaderPack(Map.of());
        assertEquals(Set.of(RuntimeShaderPack.NAMESPACE), pack.getNamespaces(PackType.CLIENT_RESOURCES));
    }

    @Test
    void reloadWithVanillaOverridesAdvertisesMinecraftNamespace() {
        RuntimeShaderPack pack = new RuntimeShaderPack(Map.of());
        pack.reload(SOURCES, OVERRIDES);
        assertEquals(Set.of(RuntimeShaderPack.NAMESPACE, "minecraft"), pack.getNamespaces(PackType.CLIENT_RESOURCES));
    }

    @Test
    void clearVanillaOverridesDropsMinecraftNamespaceButKeepsSources() {
        // The core of finding 1, as amended by the shaders-off crash (2026-07-09): every
        // deactivation path must stop serving vanilla overrides, but the fornax_runtime sources
        // must STAY published -- the resource reload the clear fires eagerly recompiles Sodium's
        // still-fornax-flavored terrain pipeline BEFORE the chained renderer reload reverts it to
        // stock, so dropping sources here means "Couldn't find source for
        // fornax_runtime:blocks/terrain" and a hard crash at the next chunk draw.
        // clearVanillaOverrides() is exactly what GraphRunner.unload() now calls.
        RuntimeShaderPack pack = new RuntimeShaderPack(Map.of());
        pack.reload(SOURCES, OVERRIDES);
        assertTrue(pack.getNamespaces(PackType.CLIENT_RESOURCES).contains("minecraft"));

        pack.clearVanillaOverrides();

        assertFalse(pack.getNamespaces(PackType.CLIENT_RESOURCES).contains("minecraft"),
                "clearing overrides must stop advertising the minecraft namespace");
        assertEquals(Set.of(RuntimeShaderPack.NAMESPACE), pack.getNamespaces(PackType.CLIENT_RESOURCES));
        assertNotNull(pack.sourceOrNull("shaders/blocks/terrain.fsh"),
                "the fornax_runtime sources must survive deactivation (Sodium's terrain pipeline"
                        + " recompiles against them during the reload, before the renderer reload"
                        + " swaps it back to stock shaders)");
    }

    @Test
    void clearVanillaOverridesLeavesSourcesIntact() {
        // The master shaders-enabled toggle's OFF path (RuntimeShaderPack.clearVanillaOverrides(),
        // called from ShaderPacksScreen.applyChanges when the pack selection is unchanged): must
        // drop the minecraft-namespace override without touching this pack's own fornax_runtime
        // sources, since GraphRunner deliberately keeps the pack graph loaded across that toggle and
        // GraphRunner.republishVanillaOverride() needs those sources still present to recompute the
        // override on re-enable without a full disk reload.
        RuntimeShaderPack pack = new RuntimeShaderPack(Map.of());
        pack.reload(SOURCES, OVERRIDES);

        pack.clearVanillaOverrides();

        assertFalse(pack.getNamespaces(PackType.CLIENT_RESOURCES).contains("minecraft"),
                "clearVanillaOverrides must stop advertising the minecraft namespace");
        assertEquals("terrain text", pack.sourceOrNull("shaders/blocks/terrain.fsh"),
                "clearVanillaOverrides must not touch this pack's own fornax_runtime sources");
        assertEquals(SOURCES, pack.sourcesSnapshot());
    }

    @Test
    void binaryVanillaOverrideIsServedUnderMinecraftNamespaceAndClearsWithTheRest() throws IOException {
        // Binary counterpart of the invariant above: a vanilla-asset override (e.g. a celestial
        // texture, see VanillaAssetOverrides) must equally advertise "minecraft" and be resolvable
        // via getResource with the exact bytes it was given, then equally disappear on
        // clearVanillaOverrides() -- the "invisible when off" invariant applies to both maps alike.
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        Map<String, byte[]> binaryOverrides =
                Map.of("textures/environment/celestial/moon/full_moon.png", png);

        RuntimeShaderPack pack = new RuntimeShaderPack(Map.of());
        pack.reload(SOURCES, Map.of(), binaryOverrides);

        assertTrue(pack.getNamespaces(PackType.CLIENT_RESOURCES).contains("minecraft"));

        Identifier id = Identifier.fromNamespaceAndPath(
                "minecraft", "textures/environment/celestial/moon/full_moon.png");
        IoSupplier<InputStream> supplier = pack.getResource(PackType.CLIENT_RESOURCES, id);
        assertNotNull(supplier, "binary override must resolve through getResource");
        try (InputStream in = supplier.get()) {
            assertArrayEquals(png, in.readAllBytes());
        }

        pack.clearVanillaOverrides();

        assertFalse(pack.getNamespaces(PackType.CLIENT_RESOURCES).contains("minecraft"),
                "clearing all overrides must stop advertising the minecraft namespace");
        assertNull(pack.getResource(PackType.CLIENT_RESOURCES, id),
                "cleared binary override must no longer resolve");
    }
}
