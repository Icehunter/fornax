package dev.icehunter.fornax.atlas;

import com.mojang.blaze3d.platform.NativeImage;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.mixin.vanilla.SpriteContentsAccessor;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.AnimationFrame;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Immutable copy of the public albedo animation metadata that owns a sidecar's frame clock. */
record LabPbrAnimationMetadata(List<LabPbrAnimationState.Frame> frames, int frameColumns,
                               boolean interpolate) {
    enum Status {
        STATIC,
        ANIMATED,
        INVALID
    }

    record Lookup(Status status, @Nullable LabPbrAnimationMetadata metadata) {
        Lookup {
            if ((status == Status.ANIMATED) != (metadata != null)) {
                throw new IllegalArgumentException("only animated metadata may carry a timeline");
            }
        }

        boolean usable() {
            return status != Status.INVALID;
        }
    }

    LabPbrAnimationMetadata {
        frames = List.copyOf(frames);
        if (frames.isEmpty() || frameColumns <= 0) {
            throw new IllegalArgumentException("animation timeline and frame columns must be present");
        }
    }

    static Lookup inspect(TextureAtlasSprite sprite, ResourceManager resources) {
        Optional<net.minecraft.resources.Identifier> albedoId =
                LabPbrSidecarLocator.albedoId(sprite);
        if (albedoId.isEmpty()) {
            return new Lookup(Status.INVALID, null);
        }
        Optional<Resource> albedo = resources.getResource(albedoId.get());
        if (albedo.isEmpty()) {
            return new Lookup(Status.INVALID, null);
        }
        try {
            Optional<AnimationMetadataSection> section =
                    albedo.get().metadata().getSection(AnimationMetadataSection.TYPE);
            NativeImage image = ((SpriteContentsAccessor) (Object) sprite.contents()).fornax$originalImage();
            return classify(section, image.getWidth(), image.getHeight(),
                    sprite.contents().width(), sprite.contents().height());
        } catch (IOException | IllegalArgumentException failure) {
            FornaxMod.LOGGER.warn("[LabPBR] Could not validate albedo animation metadata for {}; leaving sidecars neutral",
                    sprite.contents().name(), failure);
            return new Lookup(Status.INVALID, null);
        }
    }

    static Lookup classify(Optional<AnimationMetadataSection> section,
                           int sheetWidth, int sheetHeight,
                           int frameWidth, int frameHeight) {
        if (section.isEmpty()) {
            return new Lookup(Status.STATIC, null);
        }
        LabPbrAnimationMetadata metadata = fromSection(
                section.get(), sheetWidth, sheetHeight, frameWidth, frameHeight);
        if (metadata == null) {
            return new Lookup(Status.INVALID, null);
        }
        int frameCount = (sheetWidth / frameWidth) * (sheetHeight / frameHeight);
        return frameCount == 1
                ? new Lookup(Status.STATIC, null)
                : new Lookup(Status.ANIMATED, metadata);
    }

    @Nullable
    static LabPbrAnimationMetadata fromSection(AnimationMetadataSection section,
                                                int sheetWidth, int sheetHeight,
                                                int frameWidth, int frameHeight) {
        if (sheetWidth <= 0 || sheetHeight <= 0 || frameWidth <= 0 || frameHeight <= 0
                || sheetWidth % frameWidth != 0 || sheetHeight % frameHeight != 0) {
            return null;
        }
        int columns = sheetWidth / frameWidth;
        int frameCount = columns * (sheetHeight / frameHeight);
        if (frameCount <= 0) {
            return null;
        }

        List<AnimationFrame> authored = section.frames().orElseGet(() -> {
            List<AnimationFrame> sequential = new ArrayList<>(frameCount);
            for (int index = 0; index < frameCount; index++) {
                sequential.add(new AnimationFrame(index));
            }
            return sequential;
        });
        if (authored.isEmpty()) {
            return null;
        }

        List<LabPbrAnimationState.Frame> timeline = new ArrayList<>(authored.size());
        for (AnimationFrame frame : authored) {
            if (frame.index() < 0 || frame.index() >= frameCount) {
                return null;
            }
            timeline.add(new LabPbrAnimationState.Frame(frame.index(),
                    frame.timeOr(section.defaultFrameTime())));
        }
        return new LabPbrAnimationMetadata(timeline, columns, section.interpolatedFrames());
    }
}
