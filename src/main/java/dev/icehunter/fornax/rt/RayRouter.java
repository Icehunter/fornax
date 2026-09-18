package dev.icehunter.fornax.rt;

import dev.icehunter.fornax.pass.shadow.TerrainShadowResult;
import dev.icehunter.fornax.util.GpuFatalErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Walks the installed providers from the highest fidelity down, letting each answer what the ones
 * above it left unanswered.
 *
 * <p>Static and render-thread confined, like every other per-frame pass state in this engine.
 * Installation is engine-internal: the graph rebuild hands over the providers this platform can
 * construct, so a machine with no Metal simply has a shorter list rather than a disabled feature.
 *
 * <p>Two failure axes, deliberately separate. <b>Per frame</b>: {@link #beginFrame()} asks every
 * provider whether it is ready, and one that is not is skipped for the whole frame and asked again
 * next frame, which is how a still-building acceleration structure behaves. <b>Permanent for this
 * pack load</b>: a provider that throws is marked failed and never runs again until the next
 * {@link #install}, because a traversal that threw once has no state a later frame can trust.
 *
 * <p>A failure during the celestial cascade invalidates the whole image, not just that tier's
 * texels. The image carries no per-tier mask, so a partially written failure is indistinguishable
 * texel by texel from a good answer, and the cost of being coarse here is one frame of raster
 * shadows rather than a frame of wrong ones.
 */
public final class RayRouter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Fornax");

    /** Highest tier first, so the walk is "best answer wins and nothing below overwrites it". */
    private static final Comparator<RayProvider> BEST_FIRST =
            Comparator.comparingInt((RayProvider provider) -> provider.tier().ordinal()).reversed();

    private static List<RayProvider> providers = List.of();

    /**
     * Whether any pass in the active pack asks buffer-form queries.
     *
     * <ul>
     *   <li>A tier answers those against structures its celestial fill built earlier in the frame,
     *       so a fill skipped for want of a shadow reader leaves a query pass with no structure
     *       and no error.
     *   <li>{@link #install} clears it, so a pack swap cannot leave the old pack's demand standing.
     * </ul>
     */
    private static boolean queryDemand;
    private static final EnumSet<RayTier> failed = EnumSet.noneOf(RayTier.class);
    private static final EnumSet<RayTier> readyThisFrame = EnumSet.noneOf(RayTier.class);
    private static final Map<RayTier, String> lastReason = new EnumMap<>(RayTier.class);
    private static RayTier floor = RayTier.NONE;
    private static long frameNanos;

    /** Where the per-tier rows go. The graph interpreter's profiler in production. */
    private static BiConsumer<String, Double> profiler = RayRouter::recordToFrameProfiler;

    private RayRouter() {
    }

    /**
     * Replaces the installed set, closing whatever was installed before and clearing every failure
     * latch. Called on graph rebuild.
     *
     * @throws IllegalStateException when two providers claim the same tier, naming both, since the
     *                               tier is what a pack compares and a duplicate makes the answer
     *                               depend on list order
     */
    /** Declares what the active pack asks for. Call after {@link #install}. */
    public static void setQueryDemand(boolean value) {
        queryDemand = value;
    }

    public static boolean queryDemand() {
        return queryDemand;
    }

    public static void install(List<RayProvider> installed) {
        queryDemand = false;
        Objects.requireNonNull(installed, "providers");
        List<RayProvider> next = new ArrayList<>(installed);
        Map<RayTier, RayProvider> byTier = new EnumMap<>(RayTier.class);
        for (RayProvider provider : next) {
            Objects.requireNonNull(provider, "provider");
            RayTier tier = Objects.requireNonNull(provider.tier(), "provider tier");
            if (tier == RayTier.NONE) {
                throw new IllegalStateException(provider.getClass().getName()
                        + " claims tier NONE, which is the value an unanswered record carries");
            }
            RayProvider previous = byTier.put(tier, provider);
            if (previous != null) {
                throw new IllegalStateException("two providers claim tier " + tier + ": "
                        + previous.getClass().getName() + " and " + provider.getClass().getName());
            }
        }
        close();
        next.sort(BEST_FIRST);
        providers = List.copyOf(next);
    }

    /**
     * Raises the floor: no provider below {@code minimum} runs. {@link RayTier#NONE} clears it.
     * This is how a pack that would rather have raster than an approximation says so.
     */
    public static void tierFloor(RayTier minimum) {
        floor = Objects.requireNonNull(minimum, "tier floor");
    }

    public static RayTier tierFloor() {
        return floor;
    }

    /**
     * Evaluates readiness once for the frame and publishes the previous frame's cascade time. Must
     * run before any query; a provider not asked here is treated as not ready.
     */
    public static void beginFrame() {
        profiler.accept("RT cascade CPU", frameNanos * 1e-6);
        frameNanos = 0;
        // The trusted radius is rebuilt from nothing every frame and widened by each tier that
        // answers, so it must be cleared here rather than by whichever provider happens to exist.
        // A platform with no Metal installs no tier at all and would otherwise publish last
        // frame's radius forever.
        TerrainShadowResult.invalidateTrustedRadius();
        readyThisFrame.clear();
        for (RayProvider provider : providers) {
            RayTier tier = provider.tier();
            boolean ready = false;
            if (!failed.contains(tier) && tier.ordinal() >= floor.ordinal()) {
                try {
                    provider.beginFrame();
                    RayReadiness readiness = provider.readiness();
                    ready = readiness.ready();
                    report(tier, readiness);
                } catch (RuntimeException error) {
                    fail(provider, "readiness", error);
                }
            }
            if (ready) {
                readyThisFrame.add(tier);
            }
            profiler.accept("rt_tier" + tier.ordinal() + "_ready", ready ? 1.0 : 0.0);
        }
    }

    /**
     * The exact-geometry tier, run where the terrain shadow pass runs today so its trace stays
     * overlapped with the terrain draw.
     */
    public static void phaseOne(CelestialFill request) {
        // Every tier, not just the exact one. The split existed to give the approximate tiers a
        // grid updated later in the frame, but that update runs after the pass loop, so they were
        // always tracing the previous frame's grid anyway and the split bought nothing.
        //
        // What it cost was a frame of camera lag. Tracing here, before the terrain draw, gives the
        // GPU the whole draw to finish, so the publish that follows waits on work already done
        // instead of blocking the render thread on work just submitted.
        fill(request, RayTier.HARDWARE_MESH, RayTier.SOFTWARE_VOXEL);
    }

    /**
     * The approximate tiers, run after this frame's voxel grid is current. Anything phase one left
     * unanswered is theirs; anything they leave unanswered is the pack's, exactly as today.
     */
    /**
     * Delivers what phase one traced. Runs where a pack's passes can first read the result, far
     * enough after the trace that the wait is satisfied rather than blocking.
     */
    public static void publish() {
        long started = System.nanoTime();
        try {
            // Lowest tier first: every tier fills the same image, so the last one to write it holds
            // the final contents. The first that delivers ends the walk.
            for (int i = providers.size() - 1; i >= 0; i--) {
                RayProvider provider = providers.get(i);
                if (runnable(provider) && provider.publishCelestialVisibility()) {
                    return;
                }
            }
            // Nobody delivered. The target still holds an older frame's image and its own validity
            // flag, and a pack cannot tell that from a fresh one, so it has to be cleared here.
            // Leaving it is how a failed tier shows up as a shadow cast by nothing.
            TerrainShadowResult.invalidate();
        } finally {
            frameNanos += System.nanoTime() - started;
        }
    }

    /** Answers a buffer-form query, best tier first. Providers that do not serve the kind sit out. */
    public static void answer(BufferQuery query) {
        Objects.requireNonNull(query, "query");
        if (query.rayCount() == 0) {
            return;
        }
        long started = System.nanoTime();
        try {
            for (RayProvider provider : providers) {
                if (!runnable(provider) || !provider.answers(query.kind())) {
                    continue;
                }
                long entered = System.nanoTime();
                try {
                    provider.answer(query);
                } catch (RuntimeException error) {
                    fail(provider, "buffer query", error);
                } finally {
                    profiler.accept("RT tier" + provider.tier().ordinal() + " query CPU",
                            (System.nanoTime() - entered) * 1e-6);
                }
            }
        } finally {
            frameNanos += System.nanoTime() - started;
        }
    }

    /** Closes every installed provider and clears all latches. */
    public static void close() {
        for (RayProvider provider : providers) {
            try {
                provider.close();
            } catch (RuntimeException error) {
                GpuFatalErrors.rethrowIfFatal(error);
                LOGGER.warn("Fornax ray provider {} failed to close", provider.getClass().getName(), error);
            }
        }
        providers = List.of();
        failed.clear();
        readyThisFrame.clear();
        lastReason.clear();
        frameNanos = 0;
    }

    /** Whether this tier threw and is out for the rest of this pack load. */
    static boolean hasFailed(RayTier tier) {
        return failed.contains(tier);
    }

    /** Test seam: the graph interpreter's profiler is not loadable in a plain unit test context. */
    static void profiler(BiConsumer<String, Double> sink) {
        profiler = sink == null ? RayRouter::recordToFrameProfiler : sink;
    }

    static List<RayProvider> installed() {
        return providers;
    }

    /**
     * The installed provider of this concrete type, if any.
     *
     * <p>Exists for the one thing a request record cannot carry: a provider whose geometry source
     * is a renderer-owned object that must be read at a specific point on the render thread. The
     * caller hands it over by its own type rather than through the neutral request, so the coupling
     * is visible at the call site instead of hidden in a field the router knows nothing about.
     */
    public static <T extends RayProvider> Optional<T> provider(Class<T> type) {
        Objects.requireNonNull(type, "provider type");
        for (RayProvider provider : providers) {
            if (type.isInstance(provider)) {
                return Optional.of(type.cast(provider));
            }
        }
        return Optional.empty();
    }

    private static void fill(CelestialFill request, RayTier highest, RayTier lowest) {
        Objects.requireNonNull(request, "celestial fill");
        long started = System.nanoTime();
        try {
            for (RayProvider provider : providers) {
                RayTier tier = provider.tier();
                if (tier.ordinal() > highest.ordinal() || tier.ordinal() < lowest.ordinal()) {
                    continue;
                }
                if (!runnable(provider)) {
                    continue;
                }
                long entered = System.nanoTime();
                try {
                    provider.fillCelestialVisibility(request);
                } catch (RuntimeException error) {
                    fail(provider, "celestial fill", error);
                    TerrainShadowResult.invalidate();
                } finally {
                    // CPU time in this tier's fill, not GPU time: the call encodes and submits, and
                    // the work completes later. It is what separates a tier that is expensive to
                    // drive from one that is expensive to run, which a single frame total cannot.
                    profiler.accept("RT tier" + tier.ordinal() + " CPU",
                            (System.nanoTime() - entered) * 1e-6);
                }
            }
        } finally {
            frameNanos += System.nanoTime() - started;
        }
    }

    private static boolean runnable(RayProvider provider) {
        RayTier tier = provider.tier();
        return readyThisFrame.contains(tier) && !failed.contains(tier)
                && tier.ordinal() >= floor.ordinal();
    }

    private static void fail(RayProvider provider, String during, RuntimeException error) {
        GpuFatalErrors.rethrowIfFatal(error);
        failed.add(provider.tier());
        readyThisFrame.remove(provider.tier());
        LOGGER.warn("Fornax ray tier {} ({}) failed during {} and is disabled until the next pack load",
                provider.tier(), provider.getClass().getName(), during, error);
    }

    /** Logged on transition only: a reason repeated every frame is noise that hides the change. */
    private static void report(RayTier tier, RayReadiness readiness) {
        String reason = readiness.ready() ? "" : readiness.reason();
        if (reason.equals(lastReason.get(tier))) {
            return;
        }
        lastReason.put(tier, reason);
        if (readiness.ready()) {
            LOGGER.info("Fornax ray tier {} is answering", tier);
        } else {
            LOGGER.info("Fornax ray tier {} is not answering: {}", tier, reason);
        }
    }

    private static void recordToFrameProfiler(String label, double value) {
        if (label.startsWith("rt_tier")) {
            dev.icehunter.fornax.pack.graph.GraphRunner.frameProfiler().recordValue(label, value);
        } else {
            dev.icehunter.fornax.pack.graph.GraphRunner.frameProfiler().record(label, value);
        }
    }
}
