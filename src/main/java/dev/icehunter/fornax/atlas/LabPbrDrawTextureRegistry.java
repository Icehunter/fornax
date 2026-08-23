package dev.icehunter.fornax.atlas;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.resources.Identifier;

/** Exact albedo-owner provenance captured while a {@link PreparedRenderType} is prepared. */
public final class LabPbrDrawTextureRegistry {
    private static final Map<PreparedRenderType, Entry> OFFLINE_FALLBACK = new IdentityHashMap<>();

    private LabPbrDrawTextureRegistry() {
    }

    public static synchronized void remember(PreparedRenderType prepared, Identifier sampler0Owner) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(sampler0Owner, "sampler0Owner");
        Entry entry = new Entry(sampler0Owner, LabPbrSidecarRegistry.active().generation());
        Object target = prepared;
        if (target instanceof PreparedRenderTypeLabPbrOwner attached) {
            attached.fornax$setLabPbrOwner(entry.owner(), entry.generation());
        } else {
            // Unit tests do not run the Fabric mixin transformer. Identity semantics here mirror the
            // attached runtime field and deliberately avoid PreparedRenderType record equality.
            OFFLINE_FALLBACK.put(prepared, entry);
        }
    }

    public static synchronized Optional<Identifier> ownerOf(PreparedRenderType prepared) {
        return entryOf(prepared).map(Entry::owner);
    }

    /** True only when the prepared draw and the live sidecar index belong to one reload generation. */
    public static synchronized boolean isCurrent(PreparedRenderType prepared) {
        return entryOf(prepared)
                .map(entry -> entry.generation() == LabPbrSidecarRegistry.active().generation())
                .orElse(false);
    }

    /** Test/reset seam; production lifetime is generation-owned through {@link #remember}. */
    public static synchronized void clear() {
        OFFLINE_FALLBACK.clear();
    }

    private static Optional<Entry> entryOf(PreparedRenderType prepared) {
        Objects.requireNonNull(prepared, "prepared");
        Object target = prepared;
        if (target instanceof PreparedRenderTypeLabPbrOwner attached) {
            Identifier owner = attached.fornax$getLabPbrOwner();
            return owner == null
                    ? Optional.empty()
                    : Optional.of(new Entry(owner, attached.fornax$getLabPbrGeneration()));
        }
        return Optional.ofNullable(OFFLINE_FALLBACK.get(prepared));
    }

    private record Entry(Identifier owner, long generation) {
    }
}
