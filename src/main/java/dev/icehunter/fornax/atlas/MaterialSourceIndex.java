package dev.icehunter.fornax.atlas;

import com.mojang.blaze3d.platform.NativeImage;
import dev.icehunter.fornax.FornaxMod;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;

/** Immutable, unfiltered material-source evidence for one set of atlas sprite identities.
 * Counts describe source texels, never visible coverage, light colour or emitted flux. */
public final class MaterialSourceIndex {
    // Independent diagnostic facts, not shader styling choices. Bit positions are the CPU ABI.
    public static final int MISSING_MAP = 1;
    public static final int ANIMATED = 1 << 1;
    public static final int UNREADABLE = 1 << 2;
    public static final int UNKNOWN_SPRITE = 1 << 3;
    public static final int UNSUPPORTED_GEOMETRY = 1 << 4;
    public static final int CROPPED_UV = 1 << 5;
    public static final int NO_ATLAS = 1 << 6;
    private static final AtomicLong NEXT_GENERATION = new AtomicLong();
    public static final MaterialSourceIndex EMPTY = new MaterialSourceIndex(0, Map.of());

    public record Summary(int flags, int texelCount, int zeroTexels, int positiveTexels,
                          int unprovidedTexels, int maxEmissionArgb) {
        public Summary {
            if (texelCount < 0 || zeroTexels < 0 || positiveTexels < 0 || unprovidedTexels < 0
                    || (long) zeroTexels + positiveTexels + unprovidedTexels != texelCount) {
                throw new IllegalArgumentException("material-source texel counts must partition the source");
            }
        }

        public boolean authoredCandidate() { return this.positiveTexels > 0; }
        public boolean supported() { return this.flags == 0; }
        public boolean unknown() { return (this.flags & ~MISSING_MAP) != 0; }
        public Summary withFlags(int additional) {
            return new Summary(this.flags | additional, this.texelCount, this.zeroTexels,
                    this.positiveTexels, this.unprovidedTexels, this.maxEmissionArgb);
        }
    }

    private final long generation;
    private final Map<TextureAtlasSprite, Summary> summaries;

    public MaterialSourceIndex(Map<TextureAtlasSprite, Summary> summaries) {
        this(NEXT_GENERATION.incrementAndGet(), summaries);
    }

    private MaterialSourceIndex(long generation, Map<TextureAtlasSprite, Summary> summaries) {
        this.generation = generation;
        this.summaries = Collections.unmodifiableMap(new IdentityHashMap<>(summaries));
    }

    public long generation() { return this.generation; }

    public static MaterialSourceIndex current() {
        MaterialMapAtlas atlas = MaterialMapAtlas.getInstance();
        return atlas == null ? EMPTY : atlas.sourceIndex();
    }

    /** A same-name sprite from another reload is deliberately not accepted. */
    public Summary lookup(@Nullable TextureAtlasSprite sprite) {
        return this.summaries.getOrDefault(sprite,
                unavailable(this.generation == 0 ? NO_ATLAS : UNKNOWN_SPRITE));
    }

    MaterialSourceIndex rebind(Collection<TextureAtlasSprite> sprites) {
        Map<Identifier, Summary> byName = new HashMap<>();
        this.summaries.forEach((sprite, summary) -> byName.put(sprite.contents().name(), summary));
        Map<TextureAtlasSprite, Summary> rebound = new IdentityHashMap<>();
        for (TextureAtlasSprite sprite : sprites) {
            rebound.put(sprite, byName.getOrDefault(sprite.contents().name(), unavailable(UNKNOWN_SPRITE)));
        }
        return new MaterialSourceIndex(rebound);
    }

    public static Summary unavailable(int flags) { return new Summary(flags, 0, 0, 0, 0, 0); }

    /** LabPBR 1.3 alpha: 0 authored off, 1..254 authored positive, 255 unprovided.
     * Scan every source texel before filtering; one weak border texel is still evidence. */
    static Summary summarize(NativeImage source) {
        int zero = 0, positive = 0, unprovided = 0, maximum = 0;
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int argb = source.getPixel(x, y);
                int alpha = argb >>> 24;
                if (alpha == LabPbrEmissionSentinel.UNAUTHORED) unprovided++;
                else if (alpha == 0) zero++;
                else {
                    positive++;
                    if (alpha > (maximum >>> 24)) maximum = argb;
                }
            }
        }
        return new Summary(0, Math.multiplyExact(source.getWidth(), source.getHeight()),
                zero, positive, unprovided, maximum);
    }

    static Summary initialSummary(LabPbrSidecarSurvey.Entry entry, ResourceManager resources,
                                  LabPbrAnimationMetadata.Lookup animation) {
        int animationFlags = !animation.usable() ? UNREADABLE : animation.metadata() != null ? ANIMATED : 0;
        if (entry.id() == null) {
            // The existing survey merges unreadable and absent PNGs. Resolve that distinction here:
            // a corrupt present file is unknown, never certified as authored zero or missing.
            var located = LabPbrSidecarLocator.sidecarId(entry.sprite(), "_s");
            return unavailable((located.isPresent() && resources.getResource(located.get()).isEmpty()
                    ? MISSING_MAP : UNREADABLE) | animationFlags);
        }
        if (animationFlags != 0) return unavailable(animationFlags);
        return unavailable(UNREADABLE); // Becomes known only after successful raw-source decoding.
    }

    /** Cache images have already been filtered, so cache hits still inspect the original PNG. */
    static Summary readStatic(LabPbrSidecarSurvey.Entry entry, ResourceManager resources) {
        if (entry.id() == null) return unavailable(UNREADABLE);
        var resource = resources.getResource(entry.id());
        if (resource.isEmpty()) return unavailable(UNREADABLE);
        try (InputStream in = resource.get().open();
             NativeImage source = NativeImage.read(NativeImage.Format.RGBA, in)) {
            if ((long) source.getWidth() * entry.sprite().contents().height()
                    != (long) source.getHeight() * entry.sprite().contents().width()) {
                return unavailable(UNREADABLE);
            }
            return summarize(source);
        } catch (IOException failure) {
            FornaxMod.LOGGER.warn("[LabPBR] Could not read material-source evidence for {}", entry.id(), failure);
            return unavailable(UNREADABLE);
        }
    }
}
