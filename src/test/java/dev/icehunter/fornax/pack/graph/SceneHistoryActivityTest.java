package dev.icehunter.fornax.pack.graph;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.pack.BlocksSpec;
import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.PackModel;
import dev.icehunter.fornax.pipeline.FornaxRenderState;
import dev.icehunter.fornax.pipeline.SceneHistory;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Exercises the real getter with a retained registry, as a master shaders-off toggle leaves it.
 * Inert texture handles avoid allocating a GPU; reflection installs only the otherwise private
 * registry/pack references and restores them after each test without triggering a client reload.
 */
class SceneHistoryActivityTest {
    private Object previousRegistry;
    private Object previousPack;
    private boolean previousActive;
    private TargetInstance history;

    @BeforeEach
    void installRetainedHistory() throws ReflectiveOperationException {
        previousRegistry = field(GraphRunner.class, "registry").get(null);
        previousPack = field(GraphRunner.class, "currentPack").get(null);
        previousActive = FornaxRenderState.isActive();
        GraphSpec graph = new GraphSpec(Map.of(), List.of());
        TargetRegistry registry = TargetRegistry.create(graph, Map.of());
        GpuTexture current = texture("history current");
        GpuTexture previous = texture("history previous");
        history = new TargetInstance(SceneHistory.TARGET, TargetFormat.RGBA8, 1, 1, true,
                current, view(current), previous, view(previous));
        targets(registry).put(SceneHistory.TARGET, history);
        field(GraphRunner.class, "registry").set(null, registry);
        // Only pack presence matters to the activity predicate; no manifests or client are loaded.
        field(GraphRunner.class, "currentPack").set(null,
                new PackModel(Path.of("."), null, graph, null, Map.of(), BlocksSpec.empty()));
        FornaxRenderState.latch(true);
    }

    @AfterEach
    void restoreRenderState() throws ReflectiveOperationException {
        field(GraphRunner.class, "registry").set(null, previousRegistry);
        field(GraphRunner.class, "currentPack").set(null, previousPack);
        FornaxRenderState.latch(previousActive);
    }

    @Test
    void inactiveGraphHidesRetainedHistoryFromTheCopyHook() {
        FornaxRenderState.latch(false);
        assertNull(GraphRunner.sceneHistoryTarget(),
                "a selected but disabled pack must not keep the end-of-frame GPU copy alive");
    }

    @Test
    void activeGraphStillReturnsItsRetainedHistory() {
        assertSame(history, GraphRunner.sceneHistoryTarget());
    }

    @Test
    void packUnloadHidesRetainedHistoryBeforeTheLatchChanges() throws ReflectiveOperationException {
        field(GraphRunner.class, "currentPack").set(null, null);
        assertNull(GraphRunner.sceneHistoryTarget(),
                "a still-active latch cannot make an unloaded pack's target available");
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, TargetInstance> targets(TargetRegistry registry)
            throws ReflectiveOperationException {
        return (Map<String, TargetInstance>) field(TargetRegistry.class, "targets").get(registry);
    }

    private static GpuTexture texture(String label) {
        return new GpuTexture(0, label, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1) {
            @Override public void close() {}
            @Override public boolean isClosed() { return false; }
        };
    }

    private static GpuTextureView view(GpuTexture texture) {
        return new GpuTextureView(texture, 0, 1) {
            @Override public void close() {}
            @Override public boolean isClosed() { return false; }
        };
    }
}
