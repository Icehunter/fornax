package dev.icehunter.fornax.atlas;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.pack.GeometrySlot;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

/** Resolves the two LabPBR lanes bound by eligible deferred non-terrain draws. */
public final class LabPbrGeometryBindings {
    public enum Source {
        DIRECT,
        ATLAS,
        BLOCK_ATLAS,
        NEUTRAL
    }

    public record Binding(GpuTextureView normalView, GpuTextureView materialView,
                          GpuSampler normalSampler, GpuSampler materialSampler) {
        public Binding {
            Objects.requireNonNull(normalView, "normalView");
            Objects.requireNonNull(materialView, "materialView");
            Objects.requireNonNull(normalSampler, "normalSampler");
            Objects.requireNonNull(materialSampler, "materialSampler");
        }
    }

    static final Identifier BANNER_ATLAS = Identifier.withDefaultNamespace(
            "textures/atlas/banner_patterns.png");
    private static final GenerationOwnedCache<Identifier, LabPbrDirectTexturePair> DIRECT =
            new GenerationOwnedCache<>();

    private LabPbrGeometryBindings() {
    }

    public static Source sourceFor(GeometrySlot slot, Identifier owner) {
        Objects.requireNonNull(slot, "slot");
        Objects.requireNonNull(owner, "owner");
        if (slot == GeometrySlot.BLOCK_ENTITIES) {
            return Source.BLOCK_ATLAS;
        }
        if (slot == GeometrySlot.ENTITIES && BANNER_ATLAS.equals(owner)) {
            return Source.ATLAS;
        }
        if (slot == GeometrySlot.ENTITIES && owner.getPath().startsWith("textures/entity/")) {
            return Source.DIRECT;
        }
        return Source.NEUTRAL;
    }

    public static boolean hasLabPbrSidecars(GeometrySlot slot) {
        return slot == GeometrySlot.ENTITIES || slot == GeometrySlot.BLOCK_ENTITIES;
    }

    /** Atlas owners with an authored consumer in this slice; all others remain allocation-free. */
    public static boolean isMirroredAtlasOwner(Identifier owner) {
        return TextureAtlas.LOCATION_BLOCKS.equals(owner) || BANNER_ATLAS.equals(owner);
    }

    public static Binding resolve(Identifier owner, ResourceManager resources) {
        return resolve(GeometrySlot.ENTITIES, owner, resources);
    }

    public static synchronized Binding resolve(GeometrySlot slot, Identifier owner,
                                               ResourceManager resources) {
        Source source = sourceFor(slot, owner);
        return switch (source) {
            case DIRECT -> direct(owner, resources);
            case ATLAS -> atlas(owner);
            case BLOCK_ATLAS -> blockAtlas();
            case NEUTRAL -> neutral();
        };
    }

    public static synchronized Binding neutral() {
        return new Binding(LabPbrNeutralTextures.normalView(),
                LabPbrNeutralTextures.materialView(), normalSampler(), materialSampler());
    }

    private static Binding blockAtlas() {
        return atlas(TextureAtlas.LOCATION_BLOCKS);
    }

    private static Binding atlas(Identifier owner) {
        LabPbrAtlasPair pair = LabPbrAtlasPair.get(owner);
        return pair == null
                ? neutral()
                : new Binding(pair.normal().getTextureView(), pair.material().getTextureView(),
                        normalSampler(), materialSampler());
    }

    private static Binding direct(Identifier owner, ResourceManager resources) {
        LabPbrSidecarRegistry registry = LabPbrSidecarRegistry.active();
        long generation = registry.generation();
        DIRECT.transitionTo(generation);

        LabPbrSidecarDescriptor descriptor = registry.resolve(registry.prepare(owner));
        if (!descriptor.hasAnySidecar()) {
            return neutral();
        }
        LabPbrDirectTexturePair pair = DIRECT.getOrCreate(owner,
                ignored -> LabPbrDirectTexturePair.create(owner, resources, descriptor));
        GpuTextureView normal = pair.normalView();
        GpuTextureView material = pair.materialView();
        return new Binding(normal == null ? LabPbrNeutralTextures.normalView() : normal,
                material == null ? LabPbrNeutralTextures.materialView() : material,
                normalSampler(), materialSampler());
    }

    // Deferred entity and block-entity programs cannot select an integer LOD, so keep the normal
    // atlas at its authored base level on this path.
    private static GpuSampler normalSampler() {
        return RenderSystem.getSamplerCache().getSampler(
                AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR, FilterMode.LINEAR, false);
    }

    // _s carries categorical data (metal index 230-255, the porosity/SSS 64/65 split, the alpha-255
    // "no emission" sentinel), so no two mip levels may ever blend. The terrain path closes that hole
    // shader-side -- packs that consume u_MaterialTex there are expected to sample at an integer LOD
    // (see DefaultChunkRendererTextureBindMixin's own comment on this exact hazard). This binding has
    // no equivalent lever: it is read by vanilla-authored entity/block-entity programs Fornax does not
    // control, so there is no shader to add a snap to. The fifth argument here is therefore `false`,
    // not `true` -- SamplerCache's mipmapEnable=false branch passes OptionalDouble.of(0.0) as maxLod,
    // which clamps sampling to level 0 and makes the inter-level blend weight exactly zero the only
    // way this path can guarantee it: by never reading a second level at all.
    private static GpuSampler materialSampler() {
        return RenderSystem.getSamplerCache().getSampler(
                AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, false);
    }

    /** Called at registry publication so old direct GPU pairs retire even without another draw. */
    static synchronized void onSidecarGenerationPublished(long generation) {
        DIRECT.transitionTo(generation);
    }

    static synchronized long directGeneration() {
        return DIRECT.generation();
    }

    /** Small generation-owned cache whose transition closes every prior-generation resource once. */
    static final class GenerationOwnedCache<K, V extends AutoCloseable> {
        private final Map<K, V> values = new HashMap<>();
        private long generation = -1L;

        void transitionTo(long nextGeneration) {
            if (this.generation == nextGeneration) {
                return;
            }
            for (V value : this.values.values()) {
                try {
                    value.close();
                } catch (Exception failure) {
                    dev.icehunter.fornax.FornaxMod.LOGGER.warn(
                            "[LabPBR] Could not retire a direct sidecar pair", failure);
                }
            }
            this.values.clear();
            this.generation = nextGeneration;
        }

        V getOrCreate(K key, Function<K, V> factory) {
            V current = this.values.get(key);
            if (current != null) {
                return current;
            }
            V created = factory.apply(key);
            this.values.put(key, created);
            return created;
        }

        void put(K key, V value) {
            this.values.put(key, value);
        }

        int size() {
            return this.values.size();
        }

        long generation() {
            return this.generation;
        }
    }
}
