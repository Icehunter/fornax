package dev.icehunter.fornax.pack.graph;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.atlas.AtlasGenerationSchedule;
import dev.icehunter.fornax.atlas.ArrayTextures;
import dev.icehunter.fornax.atlas.BlockAtlasOverflow;
import dev.icehunter.fornax.atlas.LabPbrAtlasPair;
import dev.icehunter.fornax.atlas.LabPbrNeutralTextures;
import dev.icehunter.fornax.atlas.MaterialMapAtlas;
import dev.icehunter.fornax.atlas.NormalMapAtlas;
import dev.icehunter.fornax.pack.GraphSpec;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the real resolver and atlas lifetime; only GPU allocation/upload is replaced. */
class GraphInputResolverLabPbrTest {
    private final TargetRegistry registry = TargetRegistry.create(new GraphSpec(Map.of(), List.of()), Map.of());
    private Field deviceField;

    @BeforeEach
    void installHeadlessDevice() throws Exception {
        deviceField = RenderSystem.class.getDeclaredField("DEVICE");
        deviceField.setAccessible(true);
        assertNull(deviceField.get(null), "this fixture must not replace a live GPU device");
        assertNull(LabPbrAtlasPair.get(TextureAtlas.LOCATION_BLOCKS));
        deviceField.set(null, new HeadlessDevice());
    }

    @AfterEach
    void restoreHeadlessState() throws Exception {
        LabPbrAtlasPair.replace(TextureAtlas.LOCATION_BLOCKS, null);
        var close = LabPbrNeutralTextures.class.getDeclaredMethod("closeCurrent");
        close.setAccessible(true);
        close.invoke(null);
        deviceField.set(null, null);
        // The final rebuild sees no GPU and drains without allocating; no pending global state leaks.
        while (AtlasGenerationSchedule.hasPending(TextureAtlas.LOCATION_BLOCKS)) {
            AtlasGenerationSchedule.tick(TextureAtlas.LOCATION_BLOCKS);
        }
    }

    @Test
    void retiredMaterialAtlasResolvesNeutralUntilTheReplacementPublishes() throws Exception {
        checkRetirement("builtin.materialAtlas", false, 0xff000000);
    }

    @Test
    void retiredNormalAtlasResolvesNeutralUntilTheReplacementPublishes() throws Exception {
        checkRetirement("builtin.normalAtlas", true, 0xff8080ff);
    }

    @Test
    void absentSidecarsResolveSemanticallyNeutralRawTexturesAsWellAsViews() {
        assertEquals(0xff000000, ((HeadlessTexture) GraphInputResolver.resolveTexture(
                "builtin.materialAtlas", registry)).argb);
        assertEquals(0xff8080ff, ((HeadlessTexture) GraphInputResolver.resolveTexture(
                "builtin.normalAtlas", registry)).argb);
    }

    @Test
    void anUndeclaredTargetStillFailsInsteadOfBorrowingAnAtlasFallback() {
        assertThrows(IllegalStateException.class,
                () -> GraphInputResolver.resolveView("absentTarget", registry, Map.of()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void graphArrayInputsResolvePublishedPagesAndNeutralArraysWithoutLosingTheirViewType() throws Exception {
        var neutralTexture = new HeadlessTexture();
        var neutral = new HeadlessView(neutralTexture);
        Field neutralDevice = BlockAtlasOverflow.class.getDeclaredField("neutralDevice");
        neutralDevice.setAccessible(true);
        Field neutralsField = BlockAtlasOverflow.class.getDeclaredField("neutrals");
        neutralsField.setAccessible(true);
        var neutrals = (Map<Integer, ArrayTextures.Allocation>) neutralsField.get(null);
        assertTrue(neutrals.isEmpty());
        neutralDevice.set(null, deviceField.get(null));
        neutrals.put(BlockAtlasOverflow.NEUTRAL_BLACK_RGBA, new ArrayTextures.Allocation(neutralTexture, neutral));
        Field current = BlockAtlasOverflow.class.getDeclaredField("current");
        current.setAccessible(true);
        assertNull(current.get(null));
        try {
            for (String ref : List.of("builtin.blockAtlasPages", "builtin.materialAtlasPages")) {
                assertTrue(GraphValidator.BUILTINS.contains(ref), "array input must validate before a frame runs");
                assertSame(neutral, GraphInputResolver.resolveView(ref, registry, Map.of()));
                assertSame(neutralTexture, GraphInputResolver.resolveTexture(ref, registry));
            }
            var texture = new HeadlessTexture();
            var pages = new HeadlessView(texture);
            var allocation = new ArrayTextures.Allocation(texture, pages);
            Constructor<?> published = current.getType().getDeclaredConstructors()[0];
            published.setAccessible(true);
            current.set(null, published.newInstance(allocation, 3, 12L));
            assertSame(pages, GraphInputResolver.resolveView("builtin.blockAtlasPages", registry, Map.of()));
            assertSame(texture, GraphInputResolver.resolveTexture("builtin.blockAtlasPages", registry));
            current.set(null, null);
            assertSame(neutral, GraphInputResolver.resolveView("builtin.blockAtlasPages", registry, Map.of()));
            Class<?> animations = Class.forName("dev.icehunter.fornax.atlas.LabPbrAnimationSet");
            Field emptyAnimations = animations.getDeclaredField("EMPTY");
            emptyAnimations.setAccessible(true);
            Constructor<MaterialMapAtlas> materialConstructor = MaterialMapAtlas.class.getDeclaredConstructor(
                    GpuTexture.class, GpuTextureView.class, animations, ArrayTextures.Allocation.class, String.class);
            materialConstructor.setAccessible(true);
            var materialTexture = new HeadlessTexture();
            var material = materialConstructor.newInstance(materialTexture, new HeadlessView(materialTexture),
                    emptyAnimations.get(null), allocation, null);
            LabPbrAtlasPair.publish(TextureAtlas.LOCATION_BLOCKS,
                    new LabPbrAtlasPair(lane(NormalMapAtlas.class), material));
            assertSame(pages, GraphInputResolver.resolveView("builtin.materialAtlasPages", registry, Map.of()));
            assertSame(texture, GraphInputResolver.resolveTexture("builtin.materialAtlasPages", registry));
            LabPbrAtlasPair.replace(TextureAtlas.LOCATION_BLOCKS, null);
            assertSame(neutral, GraphInputResolver.resolveView("builtin.materialAtlasPages", registry, Map.of()));
        } finally {
            current.set(null, null);
            neutrals.clear();
            neutralDevice.set(null, null);
            neutral.close(); neutralTexture.close();
        }
    }

    private void checkRetirement(String ref, boolean normal, int neutralArgb) throws Exception {
        LabPbrAtlasPair first = pair();
        LabPbrAtlasPair.publish(TextureAtlas.LOCATION_BLOCKS, first);
        GpuTextureView firstView = normal ? first.normal().getTextureView() : first.material().getTextureView();
        assertSame(firstView, GraphInputResolver.resolveView(ref, registry, Map.of()));
        LabPbrAtlasPair.replace(TextureAtlas.LOCATION_BLOCKS, null);
        assertTrue(firstView.isClosed());
        var preparations = new SpriteLoader.Preparations(1, 1, 0, null, Map.of(),
                CompletableFuture.completedFuture(null));
        AtlasGenerationSchedule.scheduleRelease(TextureAtlas.LOCATION_BLOCKS, preparations, null, null,
                AtlasGenerationSchedule.RebuildScope.BLOCK_FULL);
        // The destroy ring requires two intervening submissions; both pending polls need valid inputs.
        for (int poll = 0; poll < 2; poll++) {
            AtlasGenerationSchedule.tick(TextureAtlas.LOCATION_BLOCKS);
            assertTrue(AtlasGenerationSchedule.hasPending(TextureAtlas.LOCATION_BLOCKS));
            GpuTextureView neutral = GraphInputResolver.resolveView(ref, registry, Map.of());
            assertFalse(neutral.isClosed());
            assertEquals(neutralArgb, ((HeadlessTexture) neutral.texture()).argb);
            assertSame(neutral.texture(), GraphInputResolver.resolveTexture(ref, registry));
        }
        LabPbrAtlasPair replacement = pair();
        LabPbrAtlasPair.publish(TextureAtlas.LOCATION_BLOCKS, replacement);
        GpuTextureView replacementView = normal
                ? replacement.normal().getTextureView() : replacement.material().getTextureView();
        assertSame(replacementView, GraphInputResolver.resolveView(ref, registry, Map.of()));
        assertSame(replacementView.texture(), GraphInputResolver.resolveTexture(ref, registry));
    }

    private static LabPbrAtlasPair pair() throws Exception {
        return new LabPbrAtlasPair(lane(NormalMapAtlas.class), lane(MaterialMapAtlas.class));
    }

    private static <T> T lane(Class<T> type) throws Exception {
        Constructor<T> constructor = type.getDeclaredConstructor(GpuTexture.class, GpuTextureView.class);
        constructor.setAccessible(true);
        var texture = new HeadlessTexture();
        return constructor.newInstance(texture, new HeadlessView(texture));
    }

    private static final class HeadlessDevice extends GpuDevice {
        HeadlessDevice() { super(null, () -> {}); }
        @Override public GpuTexture createTexture(String label, int usage, GpuFormat format,
                int width, int height, int layers, int mips) {
            assertEquals(1, width);
            assertEquals(1, height);
            return new HeadlessTexture();
        }
        @Override public GpuTextureView createTextureView(GpuTexture texture) {
            return new HeadlessView(texture);
        }
        @Override public CommandEncoder createCommandEncoder() {
            return new CommandEncoder(null, null, null) {
                @Override public void writeToTexture(GpuTexture texture, NativeImage image) {
                    ((HeadlessTexture) texture).argb = image.getPixel(0, 0);
                }
            };
        }
    }

    private static final class HeadlessTexture extends GpuTexture {
        int argb;
        boolean closed;
        HeadlessTexture() { super(USAGE_TEXTURE_BINDING, "resolver-test", GpuFormat.RGBA8_UNORM, 1, 1, 1, 1); }
        @Override public void close() { closed = true; }
        @Override public boolean isClosed() { return closed; }
    }

    private static final class HeadlessView extends GpuTextureView {
        boolean closed;
        HeadlessView(GpuTexture texture) { super(texture, 0, 1); }
        @Override public void close() { closed = true; }
        @Override public boolean isClosed() { return closed; }
    }
}
