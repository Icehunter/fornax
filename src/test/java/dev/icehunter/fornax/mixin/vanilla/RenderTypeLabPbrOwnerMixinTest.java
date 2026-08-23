package dev.icehunter.fornax.mixin.vanilla;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.icehunter.fornax.atlas.LabPbrDrawTextureRegistry;
import dev.icehunter.fornax.atlas.PreparedRenderTypeLabPbrOwner;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

final class RenderTypeLabPbrOwnerMixinTest {
    @Test
    void runtimeAttachmentInterfaceIsOutsideTheReservedMixinPackage() {
        assertTrue(!PreparedRenderTypeLabPbrOwner.class.getPackageName().contains(".mixin"));
    }

    @AfterEach
    void clearOwners() {
        LabPbrDrawTextureRegistry.clear();
    }

    @Test
    void actualRenderTypeStateAndPrepareReturnInjectionShareTheExactSampler0Owner()
            throws Exception {
        Identifier owner = Identifier.fromNamespaceAndPath(
                "pack", "textures/entity/banner/base.png");
        RenderType renderType = RenderTypes.entitySolid(owner);
        Field stateField = RenderType.class.getDeclaredField("state");
        stateField.setAccessible(true);
        RenderSetup state = (RenderSetup) stateField.get(renderType);
        Field texturesField = RenderSetup.class.getDeclaredField("textures");
        texturesField.setAccessible(true);
        Map<?, ?> textures = (Map<?, ?>) texturesField.get(state);
        Object sampler0 = textures.get("Sampler0");
        assertNotNull(sampler0);
        Method location = sampler0.getClass().getDeclaredMethod("location");
        location.setAccessible(true);
        Identifier exactLocation = (Identifier) location.invoke(sampler0);

        // A plain JUnit JVM has no Minecraft singleton/TextureManager, so calling prepare() itself
        // stops before RETURN. The annotation below pins that exact RETURN seam; the real state and
        // TextureBinding above pin the accessor target; the sibling test executes the attached
        // PreparedRenderType fields that receive the value after transformation.
        PreparedRenderType prepared = new PreparedRenderType(
                null, null, null, null, List.of());
        Method captureOwner = RenderTypeLabPbrOwnerMixin.class.getDeclaredMethod(
                "capturePreparedOwner", PreparedRenderType.class, Identifier.class);
        assertTrue(Modifier.isPrivate(captureOwner.getModifiers()));
        assertTrue(Modifier.isStatic(captureOwner.getModifiers()));
        captureOwner.setAccessible(true);
        captureOwner.invoke(null, prepared, exactLocation);

        assertEquals(owner, exactLocation);
        assertEquals(owner, LabPbrDrawTextureRegistry.ownerOf(prepared).orElseThrow());

        Method callback = RenderTypeLabPbrOwnerMixin.class.getDeclaredMethod(
                "fornax$rememberSampler0Owner", CallbackInfoReturnable.class);
        Inject injection = callback.getAnnotation(Inject.class);
        assertNotNull(injection);
        assertArrayEquals(new String[] {"prepare"}, injection.method());
        assertEquals("RETURN", injection.at()[0].value());
    }

    @Test
    void preparedDrawMixinFieldsRetainExactOwnerAndGeneration() {
        PreparedRenderTypeDeferredMixin attached = new PreparedRenderTypeDeferredMixin() { };
        Identifier owner = Identifier.fromNamespaceAndPath(
                "pack", "textures/entity/chest/normal.png");

        attached.fornax$setLabPbrOwner(owner, 37L);

        assertEquals(owner, attached.fornax$getLabPbrOwner());
        assertEquals(37L, attached.fornax$getLabPbrGeneration());
    }
}
