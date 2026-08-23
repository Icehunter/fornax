package dev.icehunter.fornax.pipeline;

import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the vanilla members {@code SpriteLoaderSidecarStitchMixin} injects into.
 *
 * <p>Same reasoning as {@link QuadParticleHookTargetsTest}: that mixin runs under
 * {@code defaultRequire: 1}, so an injector matching nothing is a crash before the title screen, not
 * a quietly missing feature. It targets a PRIVATE method by its full descriptor and shadows a
 * PRIVATE field, neither of which any compiler checks, and the game is never launched to find out.
 *
 * <p>The descriptor matters specifically because {@code @ModifyVariable} is being used to replace the
 * sprite list: it names {@code stitch}'s three-argument form explicitly, so a change to the argument
 * list or the return type breaks the match even though the method name survives.
 */
public class SpriteLoaderStitchHookTargetTest {

    @Test
    void stitchKeepsTheDescriptorTheModifyVariableDeclares() {
        Method stitch = null;
        for (Method m : SpriteLoader.class.getDeclaredMethods()) {
            if (m.getName().equals("stitch")) {
                assertNotNull(m);
                stitch = m;
            }
        }
        assertNotNull(stitch, "SpriteLoader.stitch is gone; the mixin names it by descriptor");
        assertEquals(3, stitch.getParameterCount(), "stitch's argument list changed");
        assertEquals(List.class, stitch.getParameterTypes()[0],
                "argument 0 is the sprite list the mixin replaces; @ModifyVariable index 1");
        assertEquals(int.class, stitch.getParameterTypes()[1]);
        assertEquals(Executor.class, stitch.getParameterTypes()[2]);
        assertEquals(SpriteLoader.Preparations.class, stitch.getReturnType(),
                "the descriptor in the mixin's method= spells this return type out");
        assertTrue(!Modifier.isStatic(stitch.getModifiers()),
                "an instance method: @ModifyVariable index 1 is the first ARGUMENT only because"
                        + " index 0 is `this`");
    }

    @Test
    void spriteLoaderStillCarriesTheAtlasIdentityTheFilterGatesOn() throws Exception {
        // The filter must touch the BLOCK atlas and nothing else -- the items and GUI atlases have
        // sidecars too, and Fornax does not build sidecar atlases for them, so dropping theirs would
        // be a change with no consumer to justify it. That gate reads this field.
        Field location = SpriteLoader.class.getDeclaredField("location");
        assertEquals(Identifier.class, location.getType());
        assertTrue(!Modifier.isStatic(location.getModifiers()), "shadowed as an instance field");
    }

    @Test
    void theBlockAtlasIdentifierIsStillWhatTheAtlasReportsAsItsLocation() {
        // SpriteLoader.create(atlas) takes atlas.location(), so the constant the gate compares
        // against has to be the same kind of identifier -- a texture path, not an atlas registry
        // name. If those ever diverge the gate silently never fires and the filter does nothing,
        // which is the failure that reports itself as "nothing changed".
        assertEquals("minecraft", TextureAtlas.LOCATION_BLOCKS.getNamespace());
        assertEquals("textures/atlas/blocks.png", TextureAtlas.LOCATION_BLOCKS.getPath());
    }
}
