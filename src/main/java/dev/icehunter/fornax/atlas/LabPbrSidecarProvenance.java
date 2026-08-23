package dev.icehunter.fornax.atlas;

import java.util.Objects;

/** A descriptor captured from one immutable registry generation. */
public record LabPbrSidecarProvenance(long generation, LabPbrSidecarDescriptor descriptor) {
    public LabPbrSidecarProvenance {
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        Objects.requireNonNull(descriptor, "descriptor");
    }
}
