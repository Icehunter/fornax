package dev.icehunter.fornax.mixin.vanilla;

import dev.icehunter.fornax.pipeline.BannerBaseLayerRoute;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BannerRenderer;
import net.minecraft.world.item.DyeColor;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BannerBaseLayerRouteTest {
    @Test
    void deferredFlagReceivesTheAuthoredBaseDye() {
        for (DyeColor dye : DyeColor.values()) {
            assertEquals(dye.getTextureDiffuseColor(),
                    BannerBaseLayerRoute.deferredFlagColor(dye), dye.getName());
        }
    }

    @Test
    void onlyTheRedundantForwardBannerBaseLayerIsSuppressed() {
        assertTrue(BannerBaseLayerRoute.suppressForwardLayer(Sheets.BANNER_PATTERN_BASE));
        assertFalse(BannerBaseLayerRoute.suppressForwardLayer(Sheets.BANNER_BASE));
        assertFalse(BannerBaseLayerRoute.suppressForwardLayer(Sheets.SHIELD_PATTERN_BASE));
    }

    @Test
    void bannerRendererInterceptionIsInstalled() throws Exception {
        String mixins = Files.readString(Path.of("src/main/resources/fornax.mixins.json"));
        assertTrue(mixins.contains("vanilla.BannerRendererBaseLayerMixin"));

        Class<?> mixin = Class.forName(
                "dev.icehunter.fornax.mixin.vanilla.BannerRendererBaseLayerMixin");
        ModifyArg tint = Arrays.stream(mixin.getDeclaredMethods())
                .map(method -> method.getAnnotation(ModifyArg.class))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow();
        assertEquals("submitBanner", tint.method()[0]);
        assertEquals(1, tint.at().ordinal());
        assertEquals(5, tint.index());
        assertTrue(tint.at().target().contains("SubmitNodeCollector;submitModel"));

        Inject suppress = Arrays.stream(mixin.getDeclaredMethods())
                .map(method -> method.getAnnotation(Inject.class))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow();
        assertEquals("submitPatternLayer", suppress.method()[0]);
        assertEquals("HEAD", suppress.at()[0].value());
        assertTrue(suppress.cancellable());

        String bannerRendererBytecode = new String(
                BannerRenderer.class.getResourceAsStream("/net/minecraft/client/renderer/"
                        + "blockentity/BannerRenderer.class").readAllBytes(),
                java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(bannerRendererBytecode.contains("submitBanner"));
        assertTrue(bannerRendererBytecode.contains("submitPatternLayer"));
        assertTrue(bannerRendererBytecode.contains("SubmitNodeCollector"));
        assertTrue(bannerRendererBytecode.contains("submitModel"));
    }
}
