package dev.icehunter.fornax.atlas;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Resource-wide exact-owner index for LabPBR {@code _n} and {@code _s} texture sidecars.
 *
 * <p>The registry catalogs resources only. It does not bind textures or imply that a rendering
 * domain currently consumes the sidecars. Every refresh publishes one immutable generation so
 * asynchronously prepared work can reject a stale resource-pack snapshot.
 */
public final class LabPbrSidecarRegistry {
    private static final String TEXTURE_ROOT = "textures";
    private static final String NORMAL_SUFFIX = "_n.png";
    private static final String MATERIAL_SUFFIX = "_s.png";
    private static final LabPbrSidecarRegistry ACTIVE = new LabPbrSidecarRegistry();

    private final AtomicReference<Snapshot> snapshot =
            new AtomicReference<>(new Snapshot(0L, Map.of()));

    /** Rebuilds the process-wide registry used by reload integration. */
    public static long refreshActive(ResourceManager resourceManager) {
        return ACTIVE.refresh(resourceManager);
    }

    /** Returns the process-wide registry without changing its generation. */
    public static LabPbrSidecarRegistry active() {
        return ACTIVE;
    }

    /**
     * Scans the active resource view for every texture sidecar and atomically publishes a snapshot.
     */
    public synchronized long refresh(ResourceManager resourceManager) {
        Objects.requireNonNull(resourceManager, "resourceManager");
        Map<Identifier, LabPbrSidecarDescriptor> descriptors = new HashMap<>();
        for (Identifier sidecar : resourceManager
                .listResources(TEXTURE_ROOT, LabPbrSidecarRegistry::isSidecar)
                .keySet()) {
            addSidecar(descriptors, sidecar);
        }

        long generation = snapshot.get().generation() + 1L;
        snapshot.set(new Snapshot(generation, Map.copyOf(descriptors)));
        LabPbrGeometryBindings.onSidecarGenerationPublished(generation);
        return generation;
    }

    /** Exact descriptor for {@code owner}, or a neutral descriptor when it supplies no sidecars. */
    public LabPbrSidecarDescriptor descriptor(Identifier owner) {
        Objects.requireNonNull(owner, "owner");
        Snapshot current = snapshot.get();
        return current.descriptors().getOrDefault(owner, LabPbrSidecarDescriptor.neutral(owner));
    }

    /** Captures an owner's descriptor together with the immutable generation that supplied it. */
    public LabPbrSidecarProvenance prepare(Identifier owner) {
        Objects.requireNonNull(owner, "owner");
        Snapshot current = snapshot.get();
        LabPbrSidecarDescriptor descriptor = current.descriptors()
                .getOrDefault(owner, LabPbrSidecarDescriptor.neutral(owner));
        return new LabPbrSidecarProvenance(current.generation(), descriptor);
    }

    /**
     * Resolves prepared work only against its original generation. A stale result becomes neutral.
     */
    public LabPbrSidecarDescriptor resolve(LabPbrSidecarProvenance provenance) {
        Objects.requireNonNull(provenance, "provenance");
        Snapshot current = snapshot.get();
        Identifier owner = provenance.descriptor().owner();
        if (provenance.generation() != current.generation()) {
            return LabPbrSidecarDescriptor.neutral(owner);
        }
        return current.descriptors().getOrDefault(owner, LabPbrSidecarDescriptor.neutral(owner));
    }

    public long generation() {
        return snapshot.get().generation();
    }

    private static boolean isSidecar(Identifier id) {
        String path = id.getPath();
        return path.startsWith(TEXTURE_ROOT + "/")
                && (path.endsWith(NORMAL_SUFFIX) || path.endsWith(MATERIAL_SUFFIX));
    }

    private static void addSidecar(
            Map<Identifier, LabPbrSidecarDescriptor> descriptors, Identifier sidecar) {
        String path = sidecar.getPath();
        boolean normal = path.endsWith(NORMAL_SUFFIX);
        String suffix = normal ? NORMAL_SUFFIX : MATERIAL_SUFFIX;
        String ownerPath = path.substring(0, path.length() - suffix.length()) + ".png";
        Identifier owner = Identifier.fromNamespaceAndPath(sidecar.getNamespace(), ownerPath);
        LabPbrSidecarDescriptor descriptor =
                descriptors.getOrDefault(owner, LabPbrSidecarDescriptor.neutral(owner));
        descriptors.put(owner, normal ? descriptor.withNormal(sidecar) : descriptor.withMaterial(sidecar));
    }

    private record Snapshot(long generation, Map<Identifier, LabPbrSidecarDescriptor> descriptors) {}
}
