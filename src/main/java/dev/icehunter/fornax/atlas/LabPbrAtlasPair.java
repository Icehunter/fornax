package dev.icehunter.fornax.atlas;

import dev.icehunter.fornax.FornaxMod;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Atomically published normal/material mirrors for one exact vanilla atlas owner.
 *
 * <p>The two LabPBR lanes are one resource generation. Readers must never observe a normal atlas
 * from one upload beside a material atlas from another, so the pair is installed with one map
 * write.
 *
 * <p>{@link #rebuild}/{@link #replace} still close the previous pair only after the new one
 * builds, as a correctness fallback for any caller reached without a prior release. Changed atlas
 * generations normally rebuild through {@link AtlasGenerationSchedule}, after the release hook's
 * render-loop-separated retirement window; an unchanged block generation deliberately retains the
 * exact pair and schedules only its albedo overflow/grid resources. Old and new WERE briefly
 * double-resident by design here (Blaze3D's frame-fenced deferred destroy made that seem safe)
 * until that doubling was live-caught contributing to a native out-of-memory crash during a
 * resource-pack switch.
 */
public final class LabPbrAtlasPair implements AutoCloseable {
    private static final Map<Identifier, LabPbrAtlasPair> INSTANCES = new HashMap<>();

    private final NormalMapAtlas normal;
    private final MaterialMapAtlas material;

    public LabPbrAtlasPair(NormalMapAtlas normal, MaterialMapAtlas material) {
        this.normal = Objects.requireNonNull(normal, "normal");
        this.material = Objects.requireNonNull(material, "material");
    }

    public NormalMapAtlas normal() {
        return this.normal;
    }

    public MaterialMapAtlas material() {
        return this.material;
    }

    @Nullable
    public static synchronized LabPbrAtlasPair get(Identifier atlasLocation) {
        return INSTANCES.get(Objects.requireNonNull(atlasLocation, "atlasLocation"));
    }

    public static void publish(Identifier atlasLocation, LabPbrAtlasPair replacement) {
        replace(atlasLocation, Objects.requireNonNull(replacement, "replacement"));
    }

    /**
     * Removes a failed generation or publishes a complete one before retiring the old pair.
     *
     * <p>Closes only whichever LANE actually changed, not the whole previous pair: {@link #rebuild}
     * skips a lane's rebuild entirely (returning the SAME {@link NormalMapAtlas}/
     * {@link MaterialMapAtlas} instance) when {@link LabPbrAtlasFingerprint} finds nothing relevant
     * changed since the last build, and that can legitimately happen for one lane while the other
     * genuinely rebuilds -- e.g. a pack ships a new {@code _n} map with an unchanged {@code _s} one.
     * Closing the previous pair wholesale in that case would close the MATERIAL atlas the new pair is
     * about to keep installed, out from under itself. Comparing lane-by-lane keeps a full rebuild
     * (both lanes differ), a full skip (neither differs), and a partial skip (one differs) all
     * correct through the same path.
     */
    public static void replace(Identifier atlasLocation, @Nullable LabPbrAtlasPair replacement) {
        Objects.requireNonNull(atlasLocation, "atlasLocation");
        LabPbrAtlasPair previous;
        synchronized (LabPbrAtlasPair.class) {
            previous = replacement == null
                    ? INSTANCES.remove(atlasLocation)
                    : INSTANCES.put(atlasLocation, replacement);
        }
        if (previous == null || previous == replacement) {
            return;
        }
        boolean normalClosed = replacement == null || previous.normal() != replacement.normal();
        boolean materialClosed = replacement == null || previous.material() != replacement.material();
        if (normalClosed) {
            previous.normal().close();
        }
        if (materialClosed) {
            previous.material().close();
        }
        if (replacement == null && (normalClosed || materialClosed)) {
            // Every build was already logged (see each listener's own INFO line); nothing logged a
            // release before this, which is exactly what made three back-to-back resource-pack
            // switches accumulating past available VRAM invisible in the log.
            FornaxMod.LOGGER.info("[LabPBR] Released sidecar atlas pair for {}: normal={} material={}",
                    atlasLocation, normalClosed, materialClosed);
        }
    }

    /**
     * Builds both lanes as one transaction. Any null/throwing lane invalidates the old generation,
     * closes partial new work, and leaves readers on semantic-neutral bindings.
     *
     * <p>Either supplier may return the CURRENTLY INSTALLED atlas unchanged -- {@link
     * LabPbrAtlasFingerprint}-driven skip, see {@code NormalMapAtlasReloadListener.build}/{@code
     * MaterialMapAtlasReloadListener.build} -- so {@code current} is captured up front and every
     * close below is gated on "is this object actually being discarded", never on "did a supplier
     * run". A failure still tears down BOTH lanes (this method's existing contract: nothing installed
     * survives a failed generation), but a reused lane was already closed by {@link #replace}'s own
     * teardown of the previous pair, and closing it again here would be a double-close.
     */
    public static void rebuild(Identifier atlasLocation,
                               Supplier<NormalMapAtlas> normalBuilder,
                               Supplier<MaterialMapAtlas> materialBuilder) {
        Objects.requireNonNull(normalBuilder, "normalBuilder");
        Objects.requireNonNull(materialBuilder, "materialBuilder");
        LabPbrAtlasPair current = get(atlasLocation);
        NormalMapAtlas normal = null;
        MaterialMapAtlas material = null;
        try {
            normal = normalBuilder.get();
            if (normal != null) {
                material = materialBuilder.get();
            }
            if (normal == null || material == null) {
                replace(atlasLocation, null);
                closeIfFresh(current, normal, material);
                return;
            }
            publish(atlasLocation, new LabPbrAtlasPair(normal, material));
        } catch (RuntimeException failure) {
            replace(atlasLocation, null);
            closeIfFresh(current, normal, material);
            throw failure;
        }
    }

    private static void closeIfFresh(@Nullable LabPbrAtlasPair current,
                                     @Nullable NormalMapAtlas normal,
                                     @Nullable MaterialMapAtlas material) {
        if (normal != null && (current == null || current.normal() != normal)) {
            normal.close();
        }
        if (material != null && (current == null || current.material() != material)) {
            material.close();
        }
    }

    static synchronized void clear() {
        INSTANCES.values().forEach(LabPbrAtlasPair::close);
        INSTANCES.clear();
    }

    public void tickAnimations() {
        this.normal.tickAnimations();
        this.material.tickAnimations();
    }

    @Override
    public void close() {
        this.normal.close();
        this.material.close();
    }
}
