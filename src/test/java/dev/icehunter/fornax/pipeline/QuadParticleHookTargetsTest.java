package dev.icehunter.fornax.pipeline;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins every vanilla member {@code QuadParticleDeferredMixin} injects into.
 *
 * <p><b>Why this is a test and not a comment.</b> That mixin runs under {@code defaultRequire: 1}, so
 * an injector that matches nothing is not a missing feature -- it is a crash before the title screen.
 * The members it names live on a package-private record and a private static method, which no
 * compiler checks and no other test touches, and the whole reason the mixin exists is that particles
 * bypass the chokepoint every other geometry hook shares. So there is nothing else in the build that
 * would notice a rename.
 *
 * <p>It also stands in for a launch. The rule here is that the game is never started to find out; the
 * cheapest thing that turns "the injectors should still match" into a fact is to assert the shapes
 * they match against, offline, against the same jar the mod compiles with.
 */
public class QuadParticleHookTargetsTest {

    private static final String PREPARED_GROUP =
            "net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer$PreparedGroup";

    @Test
    void executeGroupKeepsTheSignatureTheHeadInjectorDeclares() throws Exception {
        Method executeGroup = null;
        for (Method m : QuadParticleFeatureRenderer.class.getDeclaredMethods()) {
            if (m.getName().equals("executeGroup")) {
                executeGroup = m;
            }
        }
        assertNotNull(executeGroup, "QuadParticleFeatureRenderer.executeGroup is gone");
        assertEquals(4, executeGroup.getParameterCount(),
                "executeGroup's parameter list changed; the @Inject handler mirrors it exactly");
        assertEquals(int.class, executeGroup.getParameterTypes()[1],
                "executeGroup's second parameter is the index into `groups` the mixin reads");
        assertEquals(boolean.class, executeGroup.getParameterTypes()[3],
                "executeGroup's trailing boolean must stay a boolean for the handler to match");
    }

    /**
     * The trailing boolean is {@code strictlyOrdered}, NOT translucency, and reading it as the arm
     * flag was the obvious wrong turn: {@code FeatureRenderDispatcher.PreparedGroup.execute} passes
     * its own {@code strictlyOrdered} field there, while the particle arm lives on
     * {@code QuadParticleFeatureRenderer.PreparedGroup.translucent}. The two are unrelated, and using
     * the parameter would have deferred the translucent arm on any strictly-ordered group.
     */
    @Test
    void theArmFlagLivesOnThePreparedGroupAndNotOnTheParameter() throws Exception {
        Class<?> group = Class.forName(PREPARED_GROUP);
        Field translucent = group.getDeclaredField("translucent");
        assertEquals(boolean.class, translucent.getType(),
                "PreparedGroup.translucent is what the accessor mixin exposes");
        Field layers = group.getDeclaredField("layers");
        assertTrue(Map.class.isAssignableFrom(layers.getType()),
                "PreparedGroup.layers is what the per-layer translucency scan walks");
    }

    @Test
    void theRendererStillHoldsItsPreparedGroupsInAShadowableField() throws Exception {
        Field groups = QuadParticleFeatureRenderer.class.getDeclaredField("groups");
        assertEquals(List.class, groups.getType(),
                "@Shadow declares this as List; the descriptor has to match exactly");
        assertTrue(Modifier.isFinal(groups.getModifiers()),
                "@Shadow declares this @Final");
    }

    /**
     * {@code setPipeline} is called from {@code drawLayers}, NOT from {@code executeGroup} -- which is
     * why the two wrappers sit on different methods and share a decision through a static rather than
     * both hanging off one method. A refactor that inlined {@code drawLayers} would silently move the
     * call and take the pipeline substitution with it.
     */
    @Test
    void drawLayersIsStillTheStaticMethodThatSetsThePipeline() {
        Method drawLayers = null;
        for (Method m : QuadParticleFeatureRenderer.class.getDeclaredMethods()) {
            if (m.getName().equals("drawLayers")) {
                drawLayers = m;
            }
        }
        assertNotNull(drawLayers, "QuadParticleFeatureRenderer.drawLayers is gone");
        assertTrue(Modifier.isStatic(drawLayers.getModifiers()),
                "drawLayers must stay static -- the @WrapOperation handler for it is static too, and"
                        + " Mixin requires the two to agree");
    }

    /**
     * The two {@code @WrapOperation} targets are matched by full descriptor, so a changed overload
     * would match nothing. Checked against the constant pool because there is no reflective way to
     * ask which calls a method body makes.
     */
    @Test
    void bothWrappedCallsStillAppearInTheClassWithTheDescriptorsTheMixinNames() throws Exception {
        String constants = readClassBytesAsLatin1(QuadParticleFeatureRenderer.class);
        assertTrue(constants.contains("createRenderPass"),
                "executeGroup no longer opens its own render pass");
        assertTrue(constants.contains("(Ljava/util/function/Supplier;"
                        + "Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/Optional;"
                        + "Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)"
                        + "Lcom/mojang/blaze3d/systems/RenderPass;"),
                "the createRenderPass overload the render-pass wrapper targets is gone");
        assertTrue(constants.contains("setPipeline"),
                "drawLayers no longer sets a pipeline");
        assertTrue(constants.contains("(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"),
                "the setPipeline descriptor the pipeline wrapper targets is gone");
    }

    /**
     * The Fabulous ternary the forward route reads.
     *
     * <p>{@code executeGroup} draws a translucent group into {@code LevelRenderer.particlesTarget()}
     * when that is non-null and into {@code mainRenderTarget} otherwise. The forward substitution is
     * refused in the first case, because the particles target is a separate transparency buffer that
     * vanilla composites later -- not the already-tonemapped frame the pack's display transform assumes
     * it is blending into. That refusal is only correct while the mixin reads the SAME accessor vanilla
     * does, so both halves are pinned: the accessor's existence and shape here, and its presence in
     * {@code executeGroup}'s own constant pool below.
     */
    @Test
    void theFabulousParticlesTargetAccessorIsStillTheOneVanillaBranchesOn() throws Exception {
        Method particlesTarget = LevelRenderer.class.getDeclaredMethod("particlesTarget");
        assertEquals(RenderTarget.class, particlesTarget.getReturnType(),
                "LevelRenderer.particlesTarget() must still hand back a RenderTarget; the mixin only"
                        + " asks whether it is null");
        assertTrue(Modifier.isPublic(particlesTarget.getModifiers()),
                "particlesTarget() must stay reachable without an accessor mixin");
        Field levelRenderer = Minecraft.class.getDeclaredField("levelRenderer");
        assertEquals(LevelRenderer.class, levelRenderer.getType(),
                "Minecraft.levelRenderer is how the mixin reaches particlesTarget()");
    }

    /**
     * {@code executeGroup} must still consult {@code particlesTarget}. If vanilla stopped branching on
     * it, the mixin's Fabulous refusal would be guarding against something that no longer happens --
     * and, worse, the real target choice would have moved somewhere the mixin does not read.
     */
    @Test
    void executeGroupStillChoosesItsTargetFromParticlesTarget() throws Exception {
        String constants = readClassBytesAsLatin1(QuadParticleFeatureRenderer.class);
        assertTrue(constants.contains("particlesTarget"),
                "QuadParticleFeatureRenderer no longer references particlesTarget; vanilla's target"
                        + " choice has moved and the forward route's Fabulous refusal is now blind");
        assertTrue(constants.contains("mainRenderTarget"),
                "the non-Fabulous half of vanilla's target ternary is gone");
    }

    /** Latin-1 keeps every byte a distinct char, so UTF8 constant-pool entries survive as substrings. */
    private static String readClassBytesAsLatin1(Class<?> type) throws Exception {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            assertNotNull(in, "could not read " + resource);
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
