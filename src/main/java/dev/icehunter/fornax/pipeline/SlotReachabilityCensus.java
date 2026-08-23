package dev.icehunter.fornax.pipeline;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pack.GeometrySlot;
import dev.icehunter.fornax.pack.PackModel;
import dev.icehunter.fornax.pack.PassSpec;
import dev.icehunter.fornax.pack.PassType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Answers, from a running game, the one question the loader structurally cannot: <b>does a declared
 * geometry slot actually receive draws?</b>
 *
 * <p><b>Why this cannot be a validator.</b> {@code GraphValidator.checkAtMostOneGeometryPassPerSlot}
 * deliberately PERMITS a pack to declare a slot the engine does not yet route to -- the vocabulary was
 * published before the hooks existed, so refusing unrouted slots would have made every pack
 * unloadable. That permission is a real hole, and the weather pass fell straight through it: it was
 * declared, it validated, it resolved a program, it compiled, and it was inert for its entire life
 * because {@code WeatherEffectRenderer} never reaches the draw chokepoint. Three engine-side fixes
 * were spent on the wrong axis before anyone read the bytecode. Nothing in the build could have caught
 * it, because "which draws reach this hook" is a runtime fact about vanilla's own renderer.
 *
 * <p><b>What it reports, and why the wording matters.</b> A slot going unreached is NOT by itself a
 * bug: {@code banner_patterns} is only reached when a banner is on screen, and {@code lightning} only
 * during a storm. So this reports after a generous delay and says plainly that there are two possible
 * causes. What makes it useful is the POSITIVE half -- standing in the rain for ten seconds and seeing
 * {@code weather} still absent from the reached list is the evidence that was missing.
 *
 * <p>Three distinct facts are tracked per slot, because they fail differently:
 * <ul>
 *   <li><b>claimed</b> -- the pack declared a geometry pass for it.
 *   <li><b>reached</b> -- a draw carrying a pipeline mapped to it arrived at a hook. Absent means
 *       nothing routes here (the weather failure) or nothing of that kind was on screen.
 *   <li><b>substituted</b> -- the pack's program was actually bound. Reached but never substituted is
 *       the more interesting failure: the hook saw the draw and declined it, and the per-pipeline
 *       decline log says why.
 * </ul>
 */
public final class SlotReachabilityCensus {
    /**
     * How many graph frames to wait before reporting.
     *
     * <p>Reporting on the literal first frame was the obvious design and is useless: a slot's geometry
     * has to be ON SCREEN to be reached, so a one-frame census would name almost every slot and the
     * log would train people to ignore it. Ten seconds at 60fps is long enough to walk outside, and is
     * still early enough to be in the log next to the pack-load lines that explain it.
     */
    private static final int REPORT_AFTER_FRAMES = 600;

    private static final Set<GeometrySlot> REACHED = EnumSet.noneOf(GeometrySlot.class);
    private static final Set<GeometrySlot> SUBSTITUTED = EnumSet.noneOf(GeometrySlot.class);
    private static int frames;
    private static boolean reported;

    private SlotReachabilityCensus() {}

    /** Called on pack change, so a switch re-runs the census against the new pack's claims. */
    public static synchronized void reset() {
        REACHED.clear();
        SUBSTITUTED.clear();
        frames = 0;
        reported = false;
    }

    /** A draw carrying a pipeline mapped to {@code slot} arrived at a hook. */
    public static synchronized void noteSlotReached(GeometrySlot slot) {
        REACHED.add(slot);
    }

    /** The pack's program for {@code slot} was actually bound on a draw. */
    public static synchronized void noteSlotSubstituted(GeometrySlot slot) {
        REACHED.add(slot);
        SUBSTITUTED.add(slot);
    }

    /**
     * Ticked once per graph frame; emits the census exactly once per pack load.
     *
     * <p>Takes the pack rather than reading a static so the report names what THIS pack claimed, and
     * so the whole class stays testable without standing up renderer state.
     */
    public static synchronized void onFrame(@Nullable PackModel pack) {
        if (reported || pack == null) {
            return;
        }
        if (++frames < REPORT_AFTER_FRAMES) {
            return;
        }
        reported = true;
        report(claimedSlots(pack), REACHED, SUBSTITUTED);
    }

    /** Every slot the pack declares a geometry pass for. Pure, so the census can be tested directly. */
    public static Set<GeometrySlot> claimedSlots(PackModel pack) {
        Set<GeometrySlot> claimed = EnumSet.noneOf(GeometrySlot.class);
        for (PassSpec p : pack.graph().passes()) {
            if (p.type() == PassType.GEOMETRY) {
                claimed.add(p.slot() == null ? GeometrySlot.DEFAULT : p.slot());
            }
        }
        return claimed;
    }

    /**
     * The report itself, split out from {@link #onFrame} so its classification can be exercised
     * without a frame loop. Public rather than private for that reason alone -- its test lives in the
     * {@code pack} package, because the census is only worth testing against a really-loaded pack and
     * {@code PackDiscovery.loadFrom} is package-private there.
     */
    public static void report(Set<GeometrySlot> claimed, Set<GeometrySlot> reached,
                              Set<GeometrySlot> substituted) {
        List<String> unreached = new ArrayList<>();
        List<String> reachedNotSubstituted = new ArrayList<>();
        List<String> working = new ArrayList<>();
        for (GeometrySlot slot : claimed) {
            if (!reached.contains(slot)) {
                unreached.add(slot.token() + (slot.isRendered() ? "" : " [ENGINE ROUTES NOTHING HERE]"));
            } else if (!substituted.contains(slot)) {
                reachedNotSubstituted.add(slot.token());
            } else {
                working.add(slot.token());
            }
        }

        FornaxMod.LOGGER.info("[Fornax][census] geometry slots after {} frames -- drawing with the"
                + " pack's own program: {}", REPORT_AFTER_FRAMES,
                working.isEmpty() ? "(none)" : String.join(", ", working));

        if (!reachedNotSubstituted.isEmpty()) {
            // The hook saw the draw and refused it. Always a real problem: the geometry exists and is
            // reaching the right place, and something below declined. The per-pipeline decline log
            // carries the reason.
            FornaxMod.LOGGER.warn("[Fornax][census] claimed slots that RECEIVED draws but never had the"
                    + " pack's program bound: {} -- the hook declined; see the per-pipeline DECLINED"
                    + " lines above for why.", String.join(", ", reachedNotSubstituted));
        }

        if (!unreached.isEmpty()) {
            FornaxMod.LOGGER.warn("[Fornax][census] claimed slots that received NO draws at any hook:"
                    + " {}. Two possible causes and they need opposite fixes: (a) no geometry of that"
                    + " kind has been on screen yet -- stand where some is and check the log again;"
                    + " (b) nothing in the engine routes draws to that slot at all, in which case the"
                    + " pass is inert however correct it looks. A slot marked [ENGINE ROUTES NOTHING"
                    + " HERE] is case (b) by construction -- see GeometrySlot.isRendered().",
                    String.join(", ", unreached));
        }
    }
}
