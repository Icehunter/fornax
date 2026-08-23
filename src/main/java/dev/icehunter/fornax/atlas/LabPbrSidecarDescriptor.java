package dev.icehunter.fornax.atlas;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/**
 * The active resource pack's exact LabPBR sidecars for one albedo resource.
 *
 * <p>Presence is independent: an owner may provide only {@code _n}, only {@code _s}, both, or
 * neither. An absent entry is the CPU-side neutral result; consumers must not borrow another
 * owner's sidecar.
 */
public record LabPbrSidecarDescriptor(
        Identifier owner,
        Optional<Identifier> normal,
        Optional<Identifier> material) {
    public LabPbrSidecarDescriptor {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(normal, "normal");
        Objects.requireNonNull(material, "material");
    }

    public static LabPbrSidecarDescriptor neutral(Identifier owner) {
        return new LabPbrSidecarDescriptor(owner, Optional.empty(), Optional.empty());
    }

    public boolean hasAnySidecar() {
        return normal.isPresent() || material.isPresent();
    }

    LabPbrSidecarDescriptor withNormal(Identifier id) {
        return new LabPbrSidecarDescriptor(owner, Optional.of(id), material);
    }

    LabPbrSidecarDescriptor withMaterial(Identifier id) {
        return new LabPbrSidecarDescriptor(owner, normal, Optional.of(id));
    }
}
