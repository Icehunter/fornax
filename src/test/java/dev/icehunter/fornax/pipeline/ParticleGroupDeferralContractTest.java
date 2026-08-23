package dev.icehunter.fornax.pipeline;

import com.mojang.blaze3d.pipeline.ColorTargetState;
import dev.icehunter.fornax.pack.GeometrySlot;
import net.minecraft.client.renderer.RenderPipelines;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The translucent particle arm must never be deferred into the G-buffer.
 *
 * <p>Two independent facts make it disqualifying, and either alone is enough. It draws during
 * {@code executeTranslucentAfterTerrain}, AFTER the graph has resolved at the return of
 * {@code executeSolid}, so deferring it writes a G-buffer nothing will read and the particles simply
 * vanish -- the same failure {@code 95fc3b2} reverted for translucent entities. And
 * {@code TRANSLUCENT_PARTICLE} blends while a deferred variant drops the blend, which turns
 * high-resolution partial-alpha smoke into solid flashing rectangles under TAAU jitter -- the exact
 * regression {@code SmokeParticleLayerMixin} exists to prevent, and which moving smoke and flame onto
 * that arm was the fix for.
 *
 * <p>That makes it a rule worth pinning rather than commenting: the solid arm and the translucent arm
 * differ by one boolean at the call site, and the cheapest imaginable edit -- widening the gate so
 * "particles are deferred" rather than "the solid arm is deferred" -- re-breaks a shipped fix and a
 * user-visible one. The properties below are asserted over every combination of the surrounding
 * conditions, so no combination of pack state, frame phase or G-buffer availability can be the one
 * that lets a translucent group through.
 */
public class ParticleGroupDeferralContractTest {

    /** The five inputs, as a bit mask, so the rule can be swept exhaustively. */
    private static boolean deferralFor(int bits) {
        return DeferredGeometryPipelines.wantsDeferredParticleGroup(
                (bits & 1) != 0,   // groupTranslucent
                (bits & 2) != 0,   // anyLayerTranslucent
                (bits & 4) != 0,   // packActive
                (bits & 8) != 0,   // shadowPhase
                (bits & 16) != 0); // gBufferPresent
    }

    @Test
    void aTranslucentGroupIsNeverDeferred() {
        for (int bits = 0; bits < 32; bits++) {
            if ((bits & 1) == 0) {
                continue;
            }
            assertFalse(deferralFor(bits),
                    "a translucent particle group was accepted for deferral (input bits " + bits + ")."
                            + " It draws after the graph resolves, so deferring it makes it invisible,"
                            + " and it blends, which the deferred variant drops.");
        }
    }

    @Test
    void aGroupHoldingAnyTranslucentLayerIsNeverDeferred() {
        for (int bits = 0; bits < 32; bits++) {
            if ((bits & 2) == 0) {
                continue;
            }
            assertFalse(deferralFor(bits),
                    "a particle group holding a translucent layer was accepted for deferral (input bits "
                            + bits + "). The group flag is taken from the first submit only, so the"
                            + " layer scan is what decides what is actually in the group.");
        }
    }

    /**
     * The rule must not be vacuously false, or the two assertions above would pass against a
     * predicate that simply never defers anything and the whole fix would be dead.
     */
    @Test
    void theSolidArmIsDeferredWhenEverythingElseIsInPlace() {
        assertTrue(DeferredGeometryPipelines.wantsDeferredParticleGroup(
                        false, false, true, false, true),
                "the solid particle arm must be deferred when a pack is active, the frame is not in"
                        + " the shadow replay and a G-buffer exists -- otherwise ~30 particle families"
                        + " stay painted over by the tonemap.");
    }

    @Test
    void everyOtherPreconditionIsAlsoRequired() {
        assertFalse(DeferredGeometryPipelines.wantsDeferredParticleGroup(false, false, false, false, true),
                "no pack is active, so there is no program to defer to");
        assertFalse(DeferredGeometryPipelines.wantsDeferredParticleGroup(false, false, true, true, true),
                "the shadow replay re-runs executeSolid; particles do not cast and must not re-enter"
                        + " the G-buffer there");
        assertFalse(DeferredGeometryPipelines.wantsDeferredParticleGroup(false, false, true, false, false),
                "there is no G-buffer to defer into");
    }

    /**
     * The two arms live in DIFFERENT tables, and which table each is in IS the contract.
     *
     * <p>{@code TRANSLUCENT_PARTICLE} used to sit in {@link GeometryPipelineMap} on the argument that
     * an unmapped pipeline inside a deferred group would bind a one-colour-target pipeline into a
     * five-attachment pass. That argument was checked before the move and is unreachable:
     * {@code QuadParticleDeferredMixin}'s head gate walks every layer and returns
     * {@code ParticleGroupRoute.VANILLA} the moment {@code slotOf()} is null, so an unmapped layer
     * rewrites no pass and binds no variant. The mixed-group case is therefore strictly safer after
     * the move than before it.
     */
    @Test
    void theTwoArmsResolveThroughTheTwoDIFFERENTTables() {
        assertTrue(GeometrySlot.PARTICLES == GeometryPipelineMap.slotOf(RenderPipelines.OPAQUE_PARTICLE),
                "OPAQUE_PARTICLE must map to 'particles' -- it is the arm the fix actually defers");
        assertTrue(GeometryPipelineMap.slotOf(RenderPipelines.TRANSLUCENT_PARTICLE) == null,
                "TRANSLUCENT_PARTICLE must NOT be in the deferred table. The two tables are disjoint by"
                        + " construction (ForwardPipelineMap.put throws on an overlap), and four call"
                        + " sites read 'GeometryPipelineMap.slotOf(p) != null' as meaning exactly"
                        + " 'defer this draw'.");
        assertTrue(GeometrySlot.PARTICLES_TRANSLUCENT
                        == ForwardPipelineMap.slotOf(RenderPipelines.TRANSLUCENT_PARTICLE),
                "TRANSLUCENT_PARTICLE must map to 'particles_translucent' in the FORWARD table -- that"
                        + " is what fogs campfire smoke without deferring it");
        assertTrue(ForwardPipelineMap.slotOf(RenderPipelines.OPAQUE_PARTICLE) == null,
                "OPAQUE_PARTICLE must never be forwarded. It draws BEFORE the graph resolves, so a"
                        + " forward draw there would be painted over by the tonemap -- which is the"
                        + " exact bug 8e55ec9 closed by deferring it.");
    }

    /**
     * The forward slot's own contract, checked here rather than assumed from the map entry.
     *
     * <p>A forward slot must never cast a shadow (its draws are cancelled at HEAD during the replay,
     * so a caster that claimed to cast would simply be dropped) and must never be deferrable.
     */
    @Test
    void theTranslucentArmsSlotIsForwardAndNonCasting() {
        assertTrue(GeometrySlot.PARTICLES_TRANSLUCENT.rendersForward(),
                "particles_translucent is the mechanism, not just the name -- rendersForward() is what"
                        + " splices u_PackOptions into the program and copies the blend verbatim");
        assertFalse(GeometrySlot.PARTICLES_TRANSLUCENT.castsShadow(),
                "a forward slot composites into the tonemapped frame; it has nothing to contribute to"
                        + " the shadow map and its draws are cancelled during the replay anyway");
        assertFalse(GeometryPipelineMap.isMapped(GeometrySlot.PARTICLES_TRANSLUCENT),
                "nothing may map particles_translucent as deferrable");
    }

    /**
     * THE BLEND SURVIVES, and it must be proved on the constant rather than argued in a comment.
     *
     * <p>{@code forwardVariantOf} copies {@code base.getColorTargetStates()} verbatim, so the variant's
     * blend IS this object. If vanilla ever stopped declaring one, the forward variant would silently
     * become unblended and the user's 128x smoke would go back to solid flashing rectangles under TAAU
     * jitter -- the regression {@code SmokeParticleLayerMixin} and {@code 30942a6} exist to prevent.
     */
    @Test
    void theTranslucentArmStillCarriesTheBlendTheForwardVariantCopies() {
        assertTrue(RenderPipelines.TRANSLUCENT_PARTICLE.getColorTargetState().blendFunction().isPresent(),
                "TRANSLUCENT_PARTICLE lost its blend function. The forward variant copies"
                        + " getColorTargetStates() verbatim, so there would be nothing left to copy and"
                        + " partial-alpha smoke would composite as opaque rectangles.");
        assertEquals(1, RenderPipelines.TRANSLUCENT_PARTICLE.getColorTargetStates().length,
                "the forward variant copies every colour target state one for one; a second target"
                        + " would mean vanilla's own pass changed shape and the substitution is no"
                        + " longer state-preserving");
    }

    /** The FORWARD rule, swept over every combination of its five inputs. */
    private static boolean forwardFor(int bits) {
        return DeferredGeometryPipelines.wantsForwardParticleGroup(
                (bits & 1) != 0,   // groupTranslucent
                (bits & 2) != 0,   // allLayersTranslucent
                (bits & 4) != 0,   // packActive
                (bits & 8) != 0,   // shadowPhase
                (bits & 16) != 0); // separateParticlesTarget
    }

    @Test
    void theSolidArmIsNeverForwarded() {
        for (int bits = 0; bits < 32; bits++) {
            if ((bits & 1) != 0) {
                continue;
            }
            assertFalse(forwardFor(bits),
                    "a NON-translucent particle group was accepted for the forward route (input bits "
                            + bits + "). The solid arm draws before the graph resolves, so a forward"
                            + " draw there is painted over by the tonemap -- it must be DEFERRED.");
        }
    }

    @Test
    void theTranslucentArmIsForwardedWhenEverythingElseIsInPlace() {
        assertTrue(DeferredGeometryPipelines.wantsForwardParticleGroup(true, true, true, false, false),
                "the translucent particle arm must take the forward route when a pack is active, the"
                        + " frame is not in the shadow replay and Fabulous is off -- otherwise campfire"
                        + " smoke stays unfogged in front of hazed terrain");
    }

    @Test
    void everyForwardPreconditionIsAlsoRequired() {
        assertFalse(DeferredGeometryPipelines.wantsForwardParticleGroup(true, false, true, false, false),
                "a group holding a non-translucent layer is mixed; it stays vanilla in both directions");
        assertFalse(DeferredGeometryPipelines.wantsForwardParticleGroup(true, true, false, false, false),
                "no pack is active, so there is no program to substitute");
        assertFalse(DeferredGeometryPipelines.wantsForwardParticleGroup(true, true, true, true, false),
                "the shadow replay must not acquire particle draws");
        assertFalse(DeferredGeometryPipelines.wantsForwardParticleGroup(true, true, true, false, true),
                "with Improved Transparency on the translucent group draws into"
                        + " LevelRenderer.particlesTarget(), a separate transparency buffer vanilla"
                        + " composites later -- NOT the already-tonemapped frame the forward program's"
                        + " colour space assumes");
    }

    /**
     * EVERY refusal must name a condition, and it must name the one that actually refused.
     *
     * <p>The bug this pins is not a rendering bug -- it is that {@code route=VANILLA} was logged with
     * none of its five inputs, so a field report could not distinguish "no pack" from "shadow replay"
     * from "Improved Transparency is on", which is what it turned out to be. Swept over all 128
     * combinations so no input can be the one whose refusal goes unnamed, and asserted per-condition
     * below so the text cannot drift onto the wrong cause while still being non-null.
     */
    @Test
    void everyRefusalNamesACondition() {
        for (int bits = 0; bits < 128; bits++) {
            boolean groupTranslucent = (bits & 1) != 0;
            boolean anyLayerTranslucent = (bits & 2) != 0;
            boolean allLayersTranslucent = (bits & 4) != 0;
            boolean packActive = (bits & 8) != 0;
            boolean shadowPhase = (bits & 16) != 0;
            boolean gBufferPresent = (bits & 32) != 0;
            boolean separateParticlesTarget = (bits & 64) != 0;

            ParticleGroupRoute route = ParticleGroupRoute.decide(groupTranslucent, anyLayerTranslucent,
                    allLayersTranslucent, packActive, shadowPhase, gBufferPresent, separateParticlesTarget);
            String reason = ParticleGroupRoute.refusalReason(groupTranslucent, anyLayerTranslucent,
                    allLayersTranslucent, packActive, shadowPhase, gBufferPresent, separateParticlesTarget);

            if (route == ParticleGroupRoute.VANILLA) {
                assertNotNull(reason, "a refused group reported no reason (input bits " + bits + ")."
                        + " A route log that says 'no' without saying why has already cost a launch.");
                assertFalse(reason.startsWith("no condition reported"),
                        "refusalReason is out of step with the rule it explains (input bits " + bits
                                + "): " + reason);
            } else {
                assertNull(reason, "a group that was NOT refused reported a refusal reason (input bits "
                        + bits + "): " + reason);
            }
        }
    }

    /**
     * The reason must name the condition that fired, not merely be non-empty.
     *
     * <p>Each case below leaves exactly one condition false, so the expected cause is unambiguous. The
     * Improved Transparency case is the one that actually happened in the field, and it is asserted to
     * name the SETTING rather than the internal boolean: "Improved Transparency is on" is something the
     * user can act on, and {@code separateParticlesTarget=true} is not.
     */
    @Test
    void theReasonNamesTheConditionThatFired() {
        assertTrue(ParticleGroupRoute.refusalReason(true, true, true, true, false, true, true)
                        .contains("Improved Transparency"),
                "the Improved Transparency refusal must name the setting the user can change");
        assertTrue(ParticleGroupRoute.refusalReason(true, true, true, false, false, true, false)
                        .contains("no Fornax pack"),
                "an inactive pack must be named as such");
        assertTrue(ParticleGroupRoute.refusalReason(true, true, true, true, true, true, false)
                        .contains("shadow-casting replay"),
                "the shadow replay must be named as such");
        assertTrue(ParticleGroupRoute.refusalReason(true, true, false, true, false, true, false)
                        .contains("empty or mixed"),
                "a mixed or empty layer map must be named as such");
        assertTrue(ParticleGroupRoute.refusalReason(false, true, false, true, false, true, false)
                        .contains("translucent layer"),
                "a translucent layer in the solid arm must be named as such");
        assertTrue(ParticleGroupRoute.refusalReason(false, false, false, true, false, false, false)
                        .contains("no G-buffer"),
                "a missing G-buffer must be named as such");
    }

    /**
     * Improved Transparency must be reported ahead of the conditions the user cannot act on.
     *
     * <p>A conjunction has one blocking cause at a time only if the others hold, and in a real session
     * more than one can be false at once -- notably during the shadow replay, which runs every frame.
     * Ordering is therefore a real choice, and this pins it: when Improved Transparency is on AND the
     * layer map is mixed, the setting is what gets reported, because it is the one that changes the
     * outcome when the user acts on it.
     */
    @Test
    void theActionableConditionIsReportedFirst() {
        String reason = ParticleGroupRoute.refusalReason(true, true, false, true, false, true, true);
        assertTrue(reason.contains("Improved Transparency"),
                "with both Improved Transparency on and a mixed layer map, the actionable condition"
                        + " must be the one reported, not the internal one; got: " + reason);
    }

    /**
     * The three-valued decision must be a partition, not a priority list.
     *
     * <p>Swept over all 128 combinations of the seven inputs. If the two rules could ever both fire,
     * {@code ParticleGroupRoute.decide}'s ordering would silently be deciding rendering behaviour, and
     * "which branch fired" would stop being answerable from the inputs -- the failure mode the whole
     * three-valued design exists to avoid.
     */
    @Test
    void theTwoRoutesAreDisjointOverEveryInput() {
        for (int bits = 0; bits < 128; bits++) {
            boolean groupTranslucent = (bits & 1) != 0;
            boolean anyLayerTranslucent = (bits & 2) != 0;
            boolean allLayersTranslucent = (bits & 4) != 0;
            boolean packActive = (bits & 8) != 0;
            boolean shadowPhase = (bits & 16) != 0;
            boolean gBufferPresent = (bits & 32) != 0;
            boolean separateParticlesTarget = (bits & 64) != 0;

            boolean defer = DeferredGeometryPipelines.wantsDeferredParticleGroup(
                    groupTranslucent, anyLayerTranslucent, packActive, shadowPhase, gBufferPresent);
            boolean forward = DeferredGeometryPipelines.wantsForwardParticleGroup(
                    groupTranslucent, allLayersTranslucent, packActive, shadowPhase, separateParticlesTarget);
            assertFalse(defer && forward,
                    "both routes accepted the same group (input bits " + bits + ")");

            ParticleGroupRoute route = ParticleGroupRoute.decide(groupTranslucent, anyLayerTranslucent,
                    allLayersTranslucent, packActive, shadowPhase, gBufferPresent, separateParticlesTarget);
            ParticleGroupRoute expected = defer ? ParticleGroupRoute.DEFER
                    : forward ? ParticleGroupRoute.FORWARD : ParticleGroupRoute.VANILLA;
            assertEquals(expected, route,
                    "the route disagreed with the two rules it is built from (input bits " + bits + ")");
        }
    }

    /**
     * A translucent group can never reach the DEFERRED variant, over every input.
     *
     * <p>This is the assertion that protects the shipped fix. {@code deferredVariantOf} drops the blend
     * outright, so a translucent group reaching it is both invisible (it draws after the resolve) and
     * unblended (solid rectangles). The route being FORWARD or VANILLA -- never DEFER -- is what keeps
     * the deferred builder from ever being handed a translucent layer.
     */
    @Test
    void aTranslucentGroupNeverRoutesToTheDeferredVariant() {
        for (int bits = 0; bits < 128; bits++) {
            if ((bits & 1) == 0 && (bits & 2) == 0) {
                continue;
            }
            ParticleGroupRoute route = ParticleGroupRoute.decide(
                    (bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0, (bits & 8) != 0,
                    (bits & 16) != 0, (bits & 32) != 0, (bits & 64) != 0);
            assertFalse(route == ParticleGroupRoute.DEFER,
                    "a group carrying translucency routed to DEFER (input bits " + bits + "). The"
                            + " deferred variant drops the blend and the draw lands after the resolve.");
        }
    }

    /**
     * The solid arm carries no blend, so its deferred variant matches its base exactly. This is why
     * deferring it needs no pack opt-in the way translucent entities do, and it is a property of
     * vanilla's pipeline rather than of anything here -- so it is checked rather than assumed.
     */
    @Test
    void theSolidArmHasNoBlendForTheDeferredVariantToDrop() {
        for (ColorTargetState target : RenderPipelines.OPAQUE_PARTICLE.getColorTargetStates()) {
            assertTrue(target == null || target.blendFunction().isEmpty(),
                    "OPAQUE_PARTICLE gained a blend function. The deferred variant drops blending"
                            + " outright, so this is no longer a state-preserving substitution and"
                            + " the arm's exemption from the pack-opt-in rule needs revisiting.");
        }
    }
}
