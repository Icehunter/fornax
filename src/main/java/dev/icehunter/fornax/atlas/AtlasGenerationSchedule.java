package dev.icehunter.fornax.atlas;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;

/**
 * Defers a mirrored atlas location's actual rebuild by a few render-loop-separated poll ticks after
 * its previous generation was released, so the release genuinely has time to be reclaimed before the new
 * generation is allocated -- see {@code TextureAtlasReleaseGenerationMixin}'s own doc for why a
 * plain wait-idle-then-close, tried first, does not achieve that on this backend.
 *
 * <p><b>Why frames, not a synchronous flush.</b> Blaze3D's own destroy ring
 * ({@code VulkanCommandEncoder}'s {@code DestructionQueue}) only reclaims a closed texture's VRAM
 * on the SECOND {@code VulkanCommandEncoder.submit()} after it was closed, and {@code submit()} is
 * called exactly once per real rendered frame, immediately before present -- confirmed by
 * disassembling the real client jar. {@code TextureAtlas.upload()} runs release, vanilla's own new
 * allocation, and every Fornax rebuild inside one synchronous call with zero submits in between, so
 * nothing done entirely inside that call can force reclamation. Forcing extra synchronous submits
 * mid-reload was considered and rejected: each one also runs {@code awaitSubmitCompletion} (a 5s
 * timeout that would stall the frame) and an unconditional
 * {@code TracyGpuProfiler.endFrame()} with no matching begin, from a context Blaze3D never expects.
 * {@code TextureAtlas.cycleAnimationFrames} is invoked at most once per render-loop iteration, so
 * three poll invocations guarantee at least the two intervening submits the destroy ring needs. It
 * may run less often than displayed frames, making the neutral window longer but never shorter.
 * Waiting across real render iterations mirrors {@code TargetRegistry}'s own
 * {@code RETIRE_GENERATIONS} generation-retirement ring -- the one place in this codebase that
 * already solves this exact class of problem for a different resource.
 *
 * <p><b>Why this needs no CPU/GPU split.</b> An async {@code prepare()}/{@code apply()} queue on a
 * background executor risks tripping a command-buffer backpressure timeout by uploading both lanes
 * in one frame, and risks a supersession path closing a still-published, reused lane. Neither
 * failure mode exists here, because nothing here runs off the render thread and nothing splits CPU compositing
 * from GPU upload -- {@link #tick} performs the exact same synchronous
 * {@code LabPbrAtlasPair.rebuild}/{@code BlockAtlasOverflow.rebuild} calls this reload would have
 * made immediately, just a few frames later than today.
 */
public final class AtlasGenerationSchedule {
    /**
     * Animation-poll invocations to wait after a release before rebuilding. The destroy ring needs
     * exactly 2 submits to reclaim a closed texture; this adds one poll of margin, matching
     * {@code TargetRegistry.RETIRE_GENERATIONS + 1}'s own margin over the same underlying
     * constraint.
     */
    private static final int RETIRE_POLLS = 3;

    /** The independently releasable parts of one mirrored-atlas generation. */
    public enum RebuildScope {
        NONE(false, false),
        SIDECARS_ONLY(true, false),
        BLOCK_OVERFLOW_ONLY(false, true),
        BLOCK_FULL(true, true);

        private final boolean rebuildSidecars;
        private final boolean rebuildBlockResources;

        RebuildScope(boolean rebuildSidecars, boolean rebuildBlockResources) {
            this.rebuildSidecars = rebuildSidecars;
            this.rebuildBlockResources = rebuildBlockResources;
        }

        public boolean rebuildSidecars() {
            return this.rebuildSidecars;
        }

        public boolean rebuildBlockResources() {
            return this.rebuildBlockResources;
        }

        RebuildScope merge(RebuildScope other) {
            boolean sidecars = this.rebuildSidecars || other.rebuildSidecars;
            boolean blockResources = this.rebuildBlockResources || other.rebuildBlockResources;
            if (sidecars && blockResources) {
                return BLOCK_FULL;
            }
            if (sidecars) {
                return SIDECARS_ONLY;
            }
            if (blockResources) {
                return BLOCK_OVERFLOW_ONLY;
            }
            return NONE;
        }
    }

    private record Pending(int pollsRemaining, SpriteLoader.Preparations preparations,
                           ResourceManager resourceManager, @Nullable BlockAtlasPagedLayout layout,
                           RebuildScope scope) {
        Pending withOneFewerFrame() {
            return new Pending(pollsRemaining - 1, preparations, resourceManager, layout, scope);
        }
    }

    private static final Map<Identifier, Pending> PENDING = new HashMap<>();

    private AtlasGenerationSchedule() {
    }

    /** Whether a release was scheduled for {@code location} and its rebuild is still pending. */
    public static synchronized boolean hasPending(Identifier location) {
        return PENDING.containsKey(location);
    }

    /** Chooses which resources an atlas upload must retire and rebuild. */
    public static RebuildScope scopeFor(Identifier location, boolean sidecarsUnchanged) {
        if (location.equals(TextureAtlas.LOCATION_BLOCKS)) {
            return sidecarsUnchanged ? RebuildScope.BLOCK_OVERFLOW_ONLY : RebuildScope.BLOCK_FULL;
        }
        return sidecarsUnchanged ? RebuildScope.NONE : RebuildScope.SIDECARS_ONLY;
    }

    /**
     * Records that {@code location}'s previous generation was just released and its rebuild should
     * wait {@link #RETIRE_POLLS} render-loop-separated polls. A second call before the first's countdown reaches
     * zero (a second pack switch before the first finished waiting) resets the clock and replaces
     * the stashed reload data. Its scope is merged with the pending one, so a later overflow-only
     * reload cannot downgrade already-required sidecar work.
     */
    public static synchronized void scheduleRelease(Identifier location,
                                                     SpriteLoader.Preparations preparations,
                                                     ResourceManager resourceManager,
                                                     @Nullable BlockAtlasPagedLayout layout,
                                                     RebuildScope scope) {
        if (scope == RebuildScope.NONE) {
            return;
        }
        Pending previous = PENDING.get(location);
        RebuildScope merged = previous == null ? scope : previous.scope().merge(scope);
        PENDING.put(location,
                new Pending(RETIRE_POLLS, preparations, resourceManager, layout, merged));
    }

    /**
     * Advances {@code location}'s countdown by one animation poll; once it reaches zero, performs the
     * deferred rebuild synchronously (CPU compositing and GPU upload together, exactly as an
     * un-deferred reload would have done) and clears the pending entry. A no-op when nothing is
     * pending for this location.
     */
    public static synchronized void tick(Identifier location) {
        Pending pending = PENDING.get(location);
        if (pending == null) {
            return;
        }
        if (pending.pollsRemaining() > 1) {
            PENDING.put(location, pending.withOneFewerFrame());
            return;
        }
        SpriteLoader.Preparations preparations = pending.preparations();
        ResourceManager resourceManager = pending.resourceManager();
        if (pending.scope().rebuildSidecars()) {
            LabPbrAtlasPair.rebuild(location,
                    () -> NormalMapAtlasReloadListener.build(location, preparations, resourceManager),
                    () -> MaterialMapAtlasReloadListener.build(location, preparations, resourceManager));
        }
        if (pending.scope().rebuildBlockResources()) {
            BlockAtlasOverflow.rebuild(pending.layout());
        }
        PENDING.remove(location);
    }
}
