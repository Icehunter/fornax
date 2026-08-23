package dev.icehunter.fornax.atlas;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDevice;
import dev.icehunter.fornax.FornaxMod;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Finds every sprite's labPBR sidecar and measures it, BEFORE the sidecar atlas is sized.
 *
 * <p>This has to run first because the atlas's size depends on it: see {@link PbrSidecarAtlasScale}
 * for why a sidecar's own resolution -- not the albedo's -- decides how many texels its slot needs.
 * Doing the lookup here also means the blit pass does not repeat it, so this is one resource
 * resolution per sprite in total rather than the one it already cost. Each present sidecar is read
 * in full (not just its header) so its bytes can feed both the dimension read and a content digest
 * ({@link Entry#contentHash()}) -- one I/O pass, not two, and the digest is what lets
 * {@link LabPbrAtlasFingerprint} detect a file re-exported in place.
 */
public final class LabPbrSidecarSurvey {
    /**
     * One sprite and its sidecar, if it has one.
     *
     * @param sprite the block-atlas sprite this sidecar belongs to
     * @param id     the sidecar's resource id, or {@code null} if the pack ships none (or ships one
     *               that cannot be measured, which is treated the same way -- see {@link PngHeader})
     * @param width       the sidecar's own pixel width; 0 when {@code id} is {@code null}
     * @param height      the sidecar's own pixel height, which for an animated sidecar is the whole
     *                    vertical frame strip
     * @param contentHash SHA-256 of the sidecar file's raw bytes, or {@code null} when {@code id} is
     *                    {@code null}. Exists so {@link LabPbrAtlasFingerprint} can detect a sidecar
     *                    re-exported in place at the same resolution -- the one change nothing else
     *                    in this codebase can see (no mtime, no size, no digest is tracked anywhere
     *                    else). Reading it costs only the I/O this survey already pays to open the
     *                    file; the digest is computed over bytes already in memory for the header
     *                    read below, not a second file read.
     */
    public record Entry(TextureAtlasSprite sprite, @Nullable Identifier id, int width, int height,
                        byte @Nullable [] contentHash) {}

    /**
     * @param entries   one per sprite, in the order given
     * @param found     how many entries carry a sidecar
     * @param maxRatio  the largest {@code sidecarWidth / albedoWidth} seen, at least 1
     */
    public record Result(List<Entry> entries, int found, int maxRatio) {}

    private LabPbrSidecarSurvey() {
    }

    /** Surveys {@code sprites} for sidecars with the given suffix ({@code "_n"} or {@code "_s"}). */
    public static Result survey(Collection<TextureAtlasSprite> sprites, ResourceManager resourceManager,
                                String suffix) {
        List<Entry> entries = new ArrayList<>(sprites.size());
        int found = 0;
        int maxRatio = 1;
        // One instance reused across every sprite: MessageDigest.digest() resets its internal state
        // as part of returning the result (its own contract), so this is safe to call repeatedly --
        // cheaper than a fresh provider lookup (getInstance) per sidecar.
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every JDK implementation is required to support SHA-256 (java.security.MessageDigest's
            // own class doc, "Standard Algorithm Name Documentation") -- this cannot happen on any
            // JVM this mod runs on.
            throw new AssertionError("SHA-256 unavailable", e);
        }

        for (TextureAtlasSprite sprite : sprites) {
            Optional<Identifier> located = LabPbrSidecarLocator.sidecarId(sprite, suffix);
            if (located.isEmpty()) {
                entries.add(new Entry(sprite, null, 0, 0, null));
                continue;
            }
            Identifier id = located.get();
            Optional<Resource> resource = resourceManager.getResource(id);
            PngHeader.Size size = null;
            byte[] contentHash = null;
            if (resource.isPresent()) {
                try (InputStream in = resource.get().open()) {
                    // Read once, into memory: the header parse and the digest both need the same
                    // bytes, and these files are small (a labPBR sidecar, not the albedo) -- reading
                    // fully up front is one I/O pass instead of two, and is what makes the digest
                    // free beyond the read this survey already paid for.
                    byte[] bytes = in.readAllBytes();
                    size = PngHeader.read(new ByteArrayInputStream(bytes));
                    if (size != null) {
                        contentHash = digest.digest(bytes);
                    }
                } catch (IOException e) {
                    FornaxMod.LOGGER.warn("[LabPBR] Could not measure {}; treating it as absent", id, e);
                }
            }

            if (size == null) {
                entries.add(new Entry(sprite, null, 0, 0, null));
                continue;
            }

            found++;
            entries.add(new Entry(sprite, id, size.width(), size.height(), contentHash));
            // WIDTH only. An animated sidecar is a vertical strip of frames, so its height is a
            // multiple of the sprite's -- reading a ratio off it would size the atlas for an
            // animation's frame count rather than for its resolution.
            int albedoWidth = Math.max(1, sprite.contents().width());
            maxRatio = Math.max(maxRatio, ceilDiv(size.width(), albedoWidth));
        }

        return new Result(entries, found, maxRatio);
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    /**
     * The device's largest supported {@code RGBA8_UNORM} texture dimension -- the same limit
     * {@code TextureAtlas} sizes itself against, asked the same way, so the sidecar atlases can
     * never be more permissive than the block atlas they mirror. 16384 on Apple silicon.
     */
    public static int maxTextureDimension(GpuDevice device) {
        try {
            return device.getDeviceInfo().limits().maxTextureSizeForFormat(GpuFormat.RGBA8_UNORM);
        } catch (RuntimeException e) {
            // A device that will not answer is not a reason to guess big. 16384 is the smallest
            // limit any device this runs on reports, so falling back to it degrades rather than
            // overcommits.
            FornaxMod.LOGGER.warn("[LabPBR] Device did not report a max texture size; assuming 16384", e);
            return 16384;
        }
    }
}
