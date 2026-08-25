package dev.icehunter.fornax.atlas;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.config.FornaxConfig;
import dev.icehunter.fornax.util.GpuMemoryEstimator;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the LabPBR {@link NormalMapAtlas} from vanilla's freshly-stitched block atlas.
 *
 * <p>Not registered as a standalone Fabric/vanilla {@code PreparableReloadListener}: that would
 * leave the ordering relative to the block atlas stitch undefined, and the GPU device is only
 * guaranteed live on the render thread. Instead the build is triggered from
 * {@link net.caffeinemc.mods.sodium.mixin.core.render.NormalMapAtlasBuilderMixin}, which injects at
 * the {@code RETURN} of {@link net.minecraft.client.renderer.texture.TextureAtlas#upload} -- the
 * exact moment the block atlas is fully stitched and uploaded, on the render thread with the GPU
 * device available, on every resource reload. The mixin passes the same
 * {@link SpriteLoader.Preparations} that vanilla's {@code upload} receives, which already exposes
 * every sprite ({@link SpriteLoader.Preparations#regions()}) and the atlas dimensions, so no
 * additional sprite-enumeration mixin is required.
 */
public final class NormalMapAtlasReloadListener {
    /**
     * ARGB neutral fill for {@code _n}. Per channel, not a stored-Z OpenGL normal map: R=G=128 decode
     * through LabPBR's {@code (value/255)*2-1} formula to X=Y=0 (no lean, flat in-plane direction);
     * B=255 is LabPBR's ambient-occlusion channel, inverted (0=occluded, 255=fully unoccluded), so 255
     * means "no occlusion", not a stored Z component; A=255 is LabPBR's POM-height channel, where 255
     * decodes as 0% depth -- the flat/no-displacement surface, not RGBA opacity.
     */
    private static final int NEUTRAL_NORMAL_ARGB = 0xFF_80_80_FF;

    /**
     * Resident-size multiplier for a full mip chain: a halving pyramid converges on 4/3 of level 0.
     * Used only to budget the atlas -- see {@link PbrSidecarAtlasScale#MAX_ATLAS_BYTES}.
     */
    private static final double MIP_CHAIN_FACTOR = 4.0 / 3.0;

    private NormalMapAtlasReloadListener() {
    }

    /**
     * Builds a normal-map atlas matching the block atlas's UV layout. Publication is owned by
     * {@link LabPbrAtlasPair} after the material lane has also built successfully.
     *
     * @param preparations    the block atlas stitch result (sprites + dimensions)
     * @param resourceManager the client resource manager, used to locate {@code _n} sidecar PNGs
     */
    @Nullable
    public static NormalMapAtlas build(SpriteLoader.Preparations preparations,
                                       ResourceManager resourceManager) {
        return build(TextureAtlas.LOCATION_BLOCKS, preparations, resourceManager);
    }

    /** Builds, but does not publish, the mirrored normal atlas for one exact atlas owner. */
    @Nullable
    public static NormalMapAtlas build(Identifier atlasLocation,
                                       SpriteLoader.Preparations preparations,
                                       ResourceManager resourceManager) {
        long start = System.nanoTime();
        GpuDevice device = RenderSystem.tryGetDevice();

        if (device == null) {
            // No GPU device (should not happen at atlas-upload time); skip rather than crash.
            FornaxMod.LOGGER.warn("[LabPBR] Skipping normal map atlas build: no GPU device available");
            return null;
        }

        // Sidecars are stored at half their OWN resolution, which is not the same thing as half the
        // block atlas's -- see PbrSidecarAtlasScale for what that constant used to mean, what it
        // means now, and how the size is capped against the device limit and a VRAM budget.
        List<TextureAtlasSprite> sprites = new ArrayList<>(preparations.regions().values());
        LabPbrSidecarSurvey.Result survey =
                LabPbrSidecarSurvey.survey(sprites, resourceManager, "_n");
        int log2Scale;
        try {
            log2Scale = PbrSidecarAtlasScale.chooseLog2Scale(
                    preparations.width(), preparations.height(), survey.maxRatio(),
                    LabPbrSidecarSurvey.maxTextureDimension(device), MIP_CHAIN_FACTOR,
                    FornaxConfig.get().sidecarMapResolution.log2ScaleOffset(),
                    PbrSidecarAtlasScale.effectiveMaxAtlasBytes(
                            GpuMemoryEstimator.detectedVramBytesFromDevice(device),
                            FornaxConfig.get().sidecarMapResolution.maxAtlasBytes()));
        } catch (IllegalStateException noFittingScale) {
            FornaxMod.LOGGER.warn("[LabPBR] Skipping normal map atlas build: {}", noFittingScale.getMessage());
            return null;
        }

        int atlasWidth = PbrSidecarAtlasScale.atlasDimension(preparations.width(), log2Scale);
        int atlasHeight = PbrSidecarAtlasScale.atlasDimension(preparations.height(), log2Scale);

        if (atlasWidth <= 0 || atlasHeight <= 0) {
            FornaxMod.LOGGER.warn("[LabPBR] Skipping normal map atlas build: invalid atlas size {}x{}", atlasWidth, atlasHeight);
            return null;
        }

        int found = 0;
        int missing = 0;
        List<LabPbrAnimatedSidecar> animations = new ArrayList<>();
        List<LabPbrAnimatedSidecar.Rect> occupiedAnimationRegions = new ArrayList<>();
        boolean animationsTransferred = false;

        // Paged overflow layers: full-resolution sidecar data for every spilled
        // STATIC sprite at its page-local placement, so page-aware terrain sampling reads normals
        // at the same resolution page-0 sprites get. The quarter-scale ghost blit below still
        // happens for every spilled sprite -- non-page-aware consumers and the distance blend's
        // far end read that. Animated ghosts have no overflow copy by design (they always sample
        // their vanilla-animated ghost) and are skipped here.
        BlockAtlasPagedLayout pagedLayout = TextureAtlas.LOCATION_BLOCKS.equals(atlasLocation)
                ? BlockAtlasPagedLayout.current() : null;

        // Skip point: computed before any NativeImage is allocated (the base atlas alone can be a
        // quarter-gigabyte at 8192^2), so a fingerprint match costs nothing beyond the survey this
        // method already had to run. Measured on a 512x pack: this turns a 14.6s rebuild into a
        // no-op on an F8 reload that only edited a shader file, or a cold boot of an unchanged pack.
        // See LabPbrAtlasFingerprint's own doc for what it covers and why neither Preparations nor
        // BlockAtlasPagedLayout's own equals() can be used directly.
        String fingerprint = LabPbrAtlasFingerprint.compute(atlasLocation, preparations, survey, pagedLayout);
        NormalMapAtlas existing = NormalMapAtlas.getInstance(atlasLocation);
        if (existing != null && fingerprint.equals(existing.fingerprint())) {
            FornaxMod.LOGGER.info("[LabPBR] Normal map atlas reuse: fingerprint unchanged, "
                    + "skipping rebuild ({} sprites)", sprites.size());
            return existing;
        }

        int overflowPages = pagedLayout == null ? 0 : pagedLayout.overflowPageCount();

        // Stage B: a cold boot has nothing in memory to skip against (the check above never fires),
        // but may still have this exact fingerprint's level-0 pixels on disk from a previous run --
        // see LabPbrAtlasDiskCache's own doc for why level 0 only, not the full mip chain. A hit
        // still runs the per-sprite loop below (rects, animation construction, height histogram all
        // still needed) but skips blitSidecar's decode+resample and the extrudeEdges pass, using the
        // loaded pixels as-is -- they are already fully composited and extruded.
        LabPbrAtlasDiskCache.Loaded diskCached = LabPbrAtlasDiskCache.tryRead(
                atlasLocation, "normal", fingerprint, atlasWidth, atlasHeight, overflowPages);
        boolean sourcedFromDisk = diskCached != null;

        NativeImage[] layerImages = new NativeImage[overflowPages];
        boolean[][] layerOccupied = new boolean[overflowPages][];
        List<List<SpriteRect>> layerRects = new ArrayList<>();
        for (int i = 0; i < overflowPages; i++) {
            if (sourcedFromDisk) {
                layerImages[i] = diskCached.layers()[i];
            } else {
                layerImages[i] = new NativeImage(NativeImage.Format.RGBA, atlasWidth, atlasHeight, false);
                layerImages[i].fillRect(0, 0, atlasWidth, atlasHeight, NEUTRAL_NORMAL_ARGB);
            }
            layerOccupied[i] = new boolean[atlasWidth * atlasHeight];
            layerRects.add(new ArrayList<>());
        }

        // Assemble the whole atlas on the CPU, pre-filled with the neutral normal so every texel --
        // including inter-sprite padding -- is a defined, no-op value before we blit any sidecars --
        // or, on a disk-cache hit, already fully assembled from a previous build's exact bytes.
        NormalMapAtlas builtAtlas;
        try (NativeImage atlasImage = sourcedFromDisk ? diskCached.base()
                : new NativeImage(NativeImage.Format.RGBA, atlasWidth, atlasHeight, false)) {
            if (!sourcedFromDisk) {
                atlasImage.fillRect(0, 0, atlasWidth, atlasHeight, NEUTRAL_NORMAL_ARGB);
            }
            java.util.List<SpriteHeightRanges.Range> heightRanges = new java.util.ArrayList<>();

            // Tracks which atlas texels hold real, intentional content (either a blitted _n sidecar,
            // or a deliberate neutral fill for a sprite with no sidecar) versus true unclaimed padding
            // space. Used by extrudeEdges() below so it can tell "empty padding, safe to write into"
            // apart from "a neighboring sprite's real pixel, never overwrite this".
            boolean[] occupied = new boolean[atlasWidth * atlasHeight];
            List<SpriteRect> spriteRects = new ArrayList<>();
            int mipLevels = computeMipLevelCount(atlasWidth, atlasHeight);

            for (LabPbrSidecarSurvey.Entry surveyed : survey.entries()) {
                TextureAtlasSprite sprite = surveyed.sprite();
                LabPbrAnimationMetadata.Lookup animationLookup =
                        LabPbrAnimationMetadata.inspect(sprite, resourceManager);
                LabPbrAnimationMetadata animation = animationLookup.metadata();

                // sprite.getX()/getY() is the top-left of the sprite's *reserved* rectangle in the
                // atlas, which vanilla pads on all sides for mipmap/anisotropic-filtering safety
                // (u0 = (x+padding)/atlasWidth, NOT x/atlasWidth). The real image content -- where
                // the rendered UV rectangle (u0/v0, what v_TexCoord actually samples) starts -- is
                // offset from getX()/getY() by that padding, which has no public getter. Recover it
                // from the public getU0()/getV0() instead: u0*atlasWidth = x+padding, exactly the
                // content's true left edge.
                //
                // Recovered at the BLOCK atlas's own (unscaled) resolution, not this (possibly
                // downscaled) sidecar atlas's. Rounding a float UV is safe at full resolution, where
                // vanilla's padding convention is actually authored, but rounding it AFTER multiplying
                // by an already-shrunk atlasWidth can round to a different texel than
                // PbrSidecarAtlasScale.atlasCoordinate's floor/shift arithmetic would for the same
                // coordinate -- off by one whenever the block-space value is odd. Recovering at full
                // resolution first and then scaling through atlasCoordinate, the same floor-based
                // function every other placement calculation in this class uses, keeps this rectangle
                // aligned with where scaleRect and the shader's own UV math expect it.
                int blockContentX = Math.round(sprite.getU0() * preparations.width());
                int blockContentY = Math.round(sprite.getV0() * preparations.height());
                int contentX = PbrSidecarAtlasScale.atlasCoordinate(blockContentX, log2Scale);
                int contentY = PbrSidecarAtlasScale.atlasCoordinate(blockContentY, log2Scale);
                // Extent from contents() for an ordinary sprite -- byte-identical to pre-paging
                // behaviour, no new float rounding in the common case. A paged BlockAtlasGhostSprite
                // reports a quarter-scale UV rect while its contents stay full size, so sizing THAT
                // one from contents() would blit 4x past the ghost cell into the neighboring ghosts'
                // sidecar area; only it needs the UV-span reconstruction the blitter's resample arm
                // then fills.
                int blockContentW = sprite instanceof BlockAtlasGhostSprite
                        ? Math.round((sprite.getU1() - sprite.getU0()) * preparations.width())
                        : sprite.contents().width();
                int blockContentH = sprite instanceof BlockAtlasGhostSprite
                        ? Math.round((sprite.getV1() - sprite.getV0()) * preparations.height())
                        : sprite.contents().height();
                SpriteRect rect = new SpriteRect(contentX, contentY,
                        PbrSidecarAtlasScale.spriteExtent(blockContentW, log2Scale),
                        PbrSidecarAtlasScale.spriteExtent(blockContentH, log2Scale));
                int padding = Math.max(0, contentX
                        - PbrSidecarAtlasScale.atlasCoordinate(sprite.getX(), log2Scale));
                LabPbrAnimatedSidecar.Rect animatedRect = new LabPbrAnimatedSidecar.Rect(
                        rect.x() - padding, rect.y() - padding,
                        rect.width() + padding * 2, rect.height() + padding * 2);
                occupiedAnimationRegions.add(animatedRect);

                LabPbrAnimatedSidecar animated = animation == null ? null
                        : LabPbrAnimatedSidecar.load(
                                surveyed, resourceManager, animation,
                                new LabPbrAnimatedSidecar.Rect(
                                        rect.x(), rect.y(), rect.width(), rect.height()),
                                padding, mipLevels, LabPbrSidecarBlitter.Filter.NORMAL);
                boolean usable = animationLookup.usable() && (animation == null || animated != null);
                int initialFrame = animation == null ? 0 : animation.frames().getFirst().index();
                int frameColumns = animation == null ? 1 : animation.frameColumns();
                if (usable && (sourcedFromDisk || blitSidecar(atlasImage, surveyed, resourceManager, rect,
                        initialFrame, frameColumns, animation != null))) {
                    found++;
                    // The range this sprite actually uses, measured now while the assembled image is
                    // still on the CPU -- the only point where it is cheaply knowable. A pack needs it
                    // to decide whether to trace labPBR's nominal depth literally or rescale to what
                    // the texture really contains.
                    //
                    // Accumulated as a 256-bin HISTOGRAM rather than a running min/max, because the
                    // true extremes are only half of what a pack needs: see SpriteHeightRanges for
                    // why a range two texels define is not a usable rescale. Height is 8-bit, so the
                    // histogram is exact and costs one int[256] and the same single pass the min/max
                    // scan already cost -- percentiles here are counted, never estimated.
                    int[] histogram = new int[256];
                    if (animated != null) {
                        animated.accumulateLevelZeroAlphaHistogram(histogram);
                    } else {
                        for (int py = 0; py < rect.height(); py++) {
                            for (int px = 0; px < rect.width(); px++) {
                                histogram[(atlasImage.getPixel(
                                        contentX + px, contentY + py) >>> 24) & 0xFF]++;
                            }
                        }
                    }
                    long texels = 0L;
                    for (int count : histogram) {
                        texels += count;
                    }
                    int[] robust = robustBounds(histogram, texels);
                    // Published in NORMALISED UV, never in the texels the histogram was just counted
                    // over. The rectangle above is in SIDECAR texels, whose relationship to the
                    // block atlas's is now whatever PbrSidecarAtlasScale chose for this pack -- and
                    // the consumer lays these into a grid over the BLOCK atlas. That the ratio is no
                    // longer even a fixed 1:2 is exactly why the unit must stay normalised; see
                    // SpriteHeightRanges.Range for what handing it texels cost the last time.
                    heightRanges.add(new SpriteHeightRanges.Range(
                            sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1(),
                            trueBound(histogram, true), trueBound(histogram, false),
                            robust[0], robust[1]));
                    if (animated != null) {
                        animations.add(animated);
                    }
                } else {
                    if (animated != null) {
                        animated.close();
                    }
                    // The neutral fill already covers this sprite's rectangle; nothing more to do.
                    missing++;
                }

                // Reserve this sprite's rectangle regardless of hit/miss above: a sprite with no
                // sidecar is *intentionally* flat, and that flatness must be protected from a
                // neighboring sprite's edge extrusion just as much as real sidecar data is.
                markOccupied(occupied, atlasWidth, rect);
                spriteRects.add(rect);

                // Full-resolution overflow-layer copy for a spilled static sprite: page-local rect
                // at this lane's own scale, same blit and same usable-gating as the ghost above; a
                // sprite with no sidecar keeps the layer's neutral fill but still reserves its
                // rectangle against extrusion, mirroring the page-0 semantics exactly.
                if (pagedLayout != null
                        && sprite instanceof BlockAtlasGhostSprite ghostSprite
                        && ghostSprite.hasOverflowCopy()) {
                    int layerIndex = ghostSprite.overflowPage() - 1;
                    SpriteRect layerRect = new SpriteRect(
                            PbrSidecarAtlasScale.atlasCoordinate(
                                    ghostSprite.pageX() + ghostSprite.padding(), log2Scale),
                            PbrSidecarAtlasScale.atlasCoordinate(
                                    ghostSprite.pageY() + ghostSprite.padding(), log2Scale),
                            PbrSidecarAtlasScale.spriteExtent(sprite.contents().width(), log2Scale),
                            PbrSidecarAtlasScale.spriteExtent(sprite.contents().height(), log2Scale));
                    if (usable && !sourcedFromDisk) {
                        blitSidecar(layerImages[layerIndex], surveyed, resourceManager, layerRect,
                                initialFrame, frameColumns, animation != null);
                    }
                    markOccupied(layerOccupied[layerIndex], atlasWidth, layerRect);
                    layerRects.get(layerIndex).add(layerRect);
                }
            }

            // Vanilla's own block atlas bakes in border padding around every sprite to stop
            // bilinear/derivative-based sampling from bleeding a neighboring sprite's texels in at
            // tile edges. This atlas is assembled from scratch via direct pixel copies and has no
            // such protection, so without extrusion a seam of flat neutral normal would show at every
            // sprite boundary. This duplicates each sprite's own edge pixels outward into the
            // surrounding unclaimed space, mirroring vanilla's edge-extrusion technique.
            if (TextureAtlas.LOCATION_BLOCKS.equals(atlasLocation)) {
                SpriteHeightRanges.replaceAll(heightRanges);
                // The terrain bounds grid carries only block-atlas ranges. A mirrored entity or
                // painting atlas must never replace that global terrain lookup.
                dev.icehunter.fornax.pipeline.SpriteBoundsTexture.invalidate();
            }

            if (!sourcedFromDisk) {
                extrudeEdges(atlasImage, occupied, atlasWidth, atlasHeight, spriteRects);
                for (int i = 0; i < overflowPages; i++) {
                    extrudeEdges(layerImages[i], layerOccupied[i], atlasWidth, atlasHeight, layerRects.get(i));
                }
                // Fresh build only -- no point re-writing what was just read from disk. Copies bytes
                // to the heap and returns immediately; the actual file write happens on a background
                // thread and never blocks this one. See LabPbrAtlasDiskCache's own doc.
                LabPbrAtlasDiskCache.writeAsync(atlasLocation, "normal", fingerprint, atlasImage, layerImages);
            }

            builtAtlas = upload(device, atlasImage, spriteRects,
                    animations, occupiedAnimationRegions, layerImages, layerRects, fingerprint);
            animationsTransferred = true;
        } finally {
            if (!animationsTransferred) {
                animations.forEach(LabPbrAnimatedSidecar::close);
            }
            for (NativeImage layerImage : layerImages) {
                if (layerImage != null) {
                    layerImage.close();
                }
            }
        }

        // Dimensions and resident bytes, not just counts. The atlas's size is now a property of the
        // PACK's maps rather than a fixed fraction of the block atlas, so "how big did this get and
        // what did it cost" is the question a user on a 3 GB card needs answered from the log rather
        // than from a crash -- and the scale exponent reports whether the maps got the resolution
        // they asked for or were capped (0 = the historical half-the-albedo sizing, 3 = 8x that).
        FornaxMod.LOGGER.info("[LabPBR] Normal map atlas built: {} sprites with _n maps, {} without;"
                        + " {}x{} at scale 2^{} (pack asked for 2^{}), {} MB resident with mips, in {} ms"
                        + " (level 0 {})",
                found, missing, atlasWidth, atlasHeight, log2Scale,
                PbrSidecarAtlasScale.ceilLog2(survey.maxRatio()),
                Math.round(atlasWidth * (double) atlasHeight * 4.0 * MIP_CHAIN_FACTOR / 1.0e6),
                (System.nanoTime() - start) / 1_000_000L,
                sourcedFromDisk ? "from disk cache" : "freshly composited");
        return builtAtlas;
    }

    /**
     * Locates and blits a sprite's {@code _n} sidecar into its rectangle within {@code atlasImage}.
     * See {@link LabPbrSidecarBlitter} for the three placement cases and why the exact-size one is a
     * copy rather than a 1:1 resample.
     *
     * @param rect the sprite's rectangle -- content origin from {@link #build}'s {@code getU0()}
     *             derivation (NOT {@code sprite.getX()}, the outer edge of the padded reserved
     *             rectangle), sized by {@link PbrSidecarAtlasScale}
     * @return {@code true} if a sidecar was found and blitted; {@code false} if none exists (leaving
     * the pre-filled neutral normal in place).
     */
    static boolean blitSidecar(NativeImage atlasImage, LabPbrSidecarSurvey.Entry entry,
                               ResourceManager resourceManager, SpriteRect rect,
                               int initialFrame, int frameColumns, boolean animated) {
        return LabPbrSidecarBlitter.blit(atlasImage, entry, resourceManager,
                rect.x(), rect.y(), rect.width(), rect.height(), null,
                LabPbrSidecarBlitter.Filter.NORMAL, initialFrame, frameColumns, animated);
    }

    /**
     * The percentile the robust height bounds trim to, in per cent, at each end.
     *
     * <p><b>Derived from five real pack builds, not from a rule of thumb.</b> Measured over
     * every {@code _n} sprite in one labPBR pack at 64x, 128x, 256x, 256x-CTM128 and 512x --
     * 3,460 sprites, 1,730 of them with a non-degenerate height range -- against two competing costs:
     *
     * <pre>
     *   trim     median span   90th-pct    span DISCARDED on sprites using >=50% of
     *            recovered     recovered   the nominal range (median / 90th pct)
     *   p0.1     1.045x        1.25x        0.0% /  6.9%
     *   p0.5     1.105x        1.45x        0.0% / 15.8%
     *   p1       1.154x        1.63x        1.4% / 22.4%     <-- chosen
     *   p2       1.233x        2.00x        2.8% / 55.2%
     *   p5       1.412x        3.25x       20.8% / 84.8%
     * </pre>
     *
     * <p>The knee is unambiguous and it is between 1 and 2. Going from p1 to p2 buys 7% more span
     * (1.154x -> 1.233x) and costs 2.5x more of a well-authored sprite's real range (22.4% -> 55.2%
     * at the 90th percentile); p5 destroys well-authored height maps outright. Going the other way,
     * p0.5 gives up a third of the recovery for a cost that is already near zero at p1.
     *
     * <p>Trimmed at BOTH ends even though the low tail is where the pollution measurably is (median
     * 4.9%-14.1% of the span below p1, versus a median of exactly 0.0% above p99). The top trim is
     * nearly free -- 97% of these sprites reach alpha 255, labPBR's surface datum, and a texel
     * clamped to the top of the range still reads as "at the surface", which is what it was. On the
     * 43%-44% of sprites where p99 is genuinely below the max it recovers real span, and it costs
     * nothing on {@code bricks} or {@code cobblestone}, where p99 and the max are both 255.
     *
     * <p>REJECTED: trimming only the low end. It is the more conservative reading and it is what the
     * asymmetry in the data first suggested, but it leaves the median recovery at 1.097x against
     * 1.179x for both ends, and the argument for sparing the top -- "255 is a datum, not a
     * measurement" -- is exactly the argument for why clamping to it is harmless.
     */
    static final double ROBUST_TRIM_PERCENT = 1.0;

    /**
     * The trimmed height bounds for one sprite, as {@code {low, high}} in 0..255.
     *
     * <p>NEAREST-RANK: the bounds must be real alpha codes a texel actually carries,
     * because they are used to rescale that texel. An interpolated percentile can land between two
     * codes on a sprite whose height field only uses a handful of them -- common here, where a
     * quarter of all sprites are uniform -- and rescaling against a value nothing in the texture
     * holds shifts the whole field by up to half a code for no reason.
     *
     * <p>Degenerate cases are answered honestly rather than papered over. A uniform sprite returns
     * {@code {v, v}}, a one-texel sprite returns its one value twice, and an empty rect returns
     * {@code {0, 0}}. All three give a zero-width span, which is the shader's signal to fall back to
     * the true range and, failing that, to the raw height. Widening a degenerate range here to keep
     * the shader's arithmetic tidy would invent relief a texture does not have.
     */
    static int[] robustBounds(int[] histogram, long texels) {
        if (texels <= 0) {
            return new int[] {0, 0};
        }
        long lowRank = rank(texels, ROBUST_TRIM_PERCENT);
        long highRank = rank(texels, 100.0 - ROBUST_TRIM_PERCENT);
        int low = 0;
        int high = 0;
        long seen = 0;
        boolean haveLow = false;
        for (int value = 0; value < histogram.length; value++) {
            if (histogram[value] == 0) {
                continue;
            }
            seen += histogram[value];
            if (!haveLow && seen >= lowRank) {
                low = value;
                haveLow = true;
            }
            if (seen >= highRank) {
                high = value;
                break;
            }
        }
        // lowRank <= highRank for every texel count, so this can only fire if the histogram does not
        // account for `texels` -- a caller bug rather than a data shape. Ordering it anyway keeps the
        // record's invariant (low <= high) true by construction rather than by argument.
        return new int[] {Math.min(low, high), Math.max(low, high)};
    }

    /** The 1-based rank of the {@code percent}th percentile among {@code texels} samples. */
    private static long rank(long texels, double percent) {
        return Math.max(1, Math.min(texels, (long) Math.ceil(percent / 100.0 * texels)));
    }

    /** The true extreme -- the single deepest ({@code low}) or shallowest texel in the sprite. */
    static int trueBound(int[] histogram, boolean low) {
        for (int i = 0; i < histogram.length; i++) {
            int value = low ? i : histogram.length - 1 - i;
            if (histogram[value] > 0) {
                return value;
            }
        }
        return 0; // an empty rect; the shader reads the zero span as "no range recorded"
    }

    /**
     * A sprite's rectangle within the atlas, in texel coordinates. Package-private (rather than
     * {@code private}) so {@link NormalMapAtlasReloadListenerTest} can exercise {@link #scaleRect}
     * directly -- the one piece of the per-sprite mip-reduction logic that is pure and headlessly
     * testable; see that test class's own doc for what is and is not covered.
     */
    record SpriteRect(int x, int y, int width, int height) {
    }

    /** How many texels each sprite's edge is duplicated outward by. See {@link #extrudeEdges}. */
    private static final int EXTRUSION_PADDING = 1;

    private static void markOccupied(boolean[] occupied, int atlasWidth, SpriteRect rect) {
        for (int row = 0; row < rect.height(); row++) {
            int base = (rect.y() + row) * atlasWidth + rect.x();
            for (int col = 0; col < rect.width(); col++) {
                occupied[base + col] = true;
            }
        }
    }

    /**
     * Duplicates each sprite's own edge pixels outward by {@link #EXTRUSION_PADDING} texels into any
     * still-unclaimed atlas space immediately surrounding it, mirroring vanilla's own block-atlas
     * edge extrusion. Never overwrites a pixel the occupancy mask already marks as real content --
     * whether that's another sprite's sidecar data or another sprite's intentional neutral fill --
     * so this can only fill genuinely empty padding, never corrupt a real neighbor.
     */
    private static void extrudeEdges(NativeImage atlasImage, boolean[] occupied, int atlasWidth, int atlasHeight, List<SpriteRect> rects) {
        for (SpriteRect rect : rects) {
            int left = rect.x();
            int top = rect.y();
            int right = rect.x() + rect.width() - 1;
            int bottom = rect.y() + rect.height() - 1;

            for (int pad = 1; pad <= EXTRUSION_PADDING; pad++) {
                for (int row = 0; row < rect.height(); row++) {
                    int y = top + row;
                    extrudePixel(atlasImage, occupied, atlasWidth, atlasHeight, left - pad, y, left, y);
                    extrudePixel(atlasImage, occupied, atlasWidth, atlasHeight, right + pad, y, right, y);
                }

                for (int col = 0; col < rect.width(); col++) {
                    int x = left + col;
                    extrudePixel(atlasImage, occupied, atlasWidth, atlasHeight, x, top - pad, x, top);
                    extrudePixel(atlasImage, occupied, atlasWidth, atlasHeight, x, bottom + pad, x, bottom);
                }

                extrudePixel(atlasImage, occupied, atlasWidth, atlasHeight, left - pad, top - pad, left, top);
                extrudePixel(atlasImage, occupied, atlasWidth, atlasHeight, right + pad, top - pad, right, top);
                extrudePixel(atlasImage, occupied, atlasWidth, atlasHeight, left - pad, bottom + pad, left, bottom);
                extrudePixel(atlasImage, occupied, atlasWidth, atlasHeight, right + pad, bottom + pad, right, bottom);
            }
        }
    }

    private static void extrudePixel(NativeImage atlasImage, boolean[] occupied, int atlasWidth, int atlasHeight, int dstX, int dstY, int srcX, int srcY) {
        if (dstX < 0 || dstY < 0 || dstX >= atlasWidth || dstY >= atlasHeight) {
            return;
        }

        int index = dstY * atlasWidth + dstX;
        if (occupied[index]) {
            return;
        }

        atlasImage.setPixel(dstX, dstY, atlasImage.getPixel(srcX, srcY));
        occupied[index] = true;
    }

    /**
     * A single-level atlas aliases hard at grazing sun angles / distant terrain: minified sampling
     * with no mip chain means the fragment shader (both Fornax's own fallback bump-lighting shader
     * and, since this atlas is bound as {@code u_NormalTex} ahead of every terrain draw regardless of
     * which shader is active, a pack's {@code terrain.fsh} too) always reads full-resolution
     * normal texel noise, amplified into shimmer under minification. This builds a real chain instead.
     *
     * <p>{@link CommandEncoder} has no scaling blit -- its one texture-to-texture primitive,
     * {@code copyTextureToTexture}, validates the same mip level against both the source and
     * destination texture (see its bounds checks), so it can only copy 1:1 within a single level,
     * never downsample. GPU-side downsampling would need a whole extra {@code RenderPipeline} +
     * shader, the shape {@link dev.icehunter.fornax.pack.graph.MipchainRunner} uses for pack-declared
     * targets -- but that machinery is wired to the pack graph (compiled shader files under the
     * runtime pack namespace, one pass per frame) and doesn't fit a one-time, build-time atlas
     * assembled from CPU-side {@link NativeImage} pixel copies before any GPU texture exists.
     * Instead this follows vanilla's own precedent for atlas mipmaps -- {@code SpriteContents}/{@code
     * MipmapGenerator} generate each mip level as a CPU-side {@link NativeImage} and upload it
     * level-by-level -- with one important refinement forced by the fact that this atlas is
     * reduced from an already-composited image rather than mipped per-sprite before compositing:
     * a naive whole-atlas box filter blends texels across sprite boundaries after the first
     * reduction (the 1-texel {@link #EXTRUSION_PADDING} survives exactly one halving), producing a
     * lighting seam at every block edge under minification. Each sprite is instead reduced
     * <em>independently</em>, reading only its own rectangle at the previous level (see
     * {@link #boxDownsampleRect}) via the exact rectangle {@link #scaleRect} placed it at when that
     * previous level was itself built -- so the read is always self-consistent with what is
     * actually there, never a neighbor's texels. Each level's composited image is then
     * re-extruded (see {@link #extrudeEdges}) so the 1-texel padding margin is restored at every
     * level, not just level 0.
     *
     * <p>One caveat, accepted as standard practice rather than engineered around (Toksvig-style
     * normal-aware filtering is out of scope here): box-averaging a tangent-space normal shortens
     * the averaged vector (it is not renormalized), which subtly flattens specular response at
     * distance -- far preferable to the aliasing it replaces.
     *
     * <p>At the deepest few levels a sprite's own scaled rectangle can shrink to 1x1 while several
     * other sprites' rectangles have also collapsed to the very same atlas texel (this atlas's
     * level count is sized off the full 2048x2048 image, per {@link #computeMipLevelCount}, so the
     * chain runs well past any individual sprite's own useful depth). Whichever sprite is
     * processed last simply overwrites that texel with its own flat, correctly-reduced color --
     * never a blend of neighbors, so no seam -- and this only happens at extreme minification (a
     * handful of texels covering the whole visible terrain), where it is imperceptible.
     */
    private static NormalMapAtlas upload(GpuDevice device, NativeImage atlasImage,
                                         List<SpriteRect> spriteRects,
                                         List<LabPbrAnimatedSidecar> animations,
                                         List<LabPbrAnimatedSidecar.Rect> occupiedAnimationRegions,
                                         NativeImage[] layerImages,
                                         List<List<SpriteRect>> layerRects,
                                         String fingerprint) {
        int width = atlasImage.getWidth();
        int height = atlasImage.getHeight();
        int levelCount = computeMipLevelCount(width, height);

        GpuTexture texture = device.createTexture(
                "Sodium LabPBR Normal Atlas",
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA8_UNORM,
                width,
                height,
                1,          // depthOrLayers
                levelCount  // mipLevels
        );

        CommandEncoder encoder = device.createCommandEncoder();
        encoder.writeToTexture(texture, atlasImage, 0, 0, 0, 0);

        NativeImage previous = atlasImage;
        for (int level = 1; level < levelCount; level++) {
            NativeImage mip = downsampleLevel(previous, width, height, level, spriteRects);
            encoder.writeToTexture(texture, mip, level, 0, 0, 0);
            if (previous != atlasImage) {
                previous.close();
            }
            previous = mip;
        }
        if (previous != atlasImage) {
            previous.close();
        }

        // Overflow layers: same per-sprite mip discipline as the base atlas above, one layer per
        // overflow page, through the array-texture seam (stock creation refuses depthOrLayers > 1
        // -- see ArrayTextures). Unavailable (non-Vulkan) degrades to null: terrain then binds the
        // neutral array and spilled sprites keep their ghost-resolution normals.
        ArrayTextures.Allocation pages = null;
        if (layerImages.length > 0) {
            pages = ArrayTextures.create("Sodium LabPBR Normal Atlas Pages",
                    GpuFormat.RGBA8_UNORM, width, height, layerImages.length, levelCount);
            if (pages == null) {
                FornaxMod.LOGGER.warn("[LabPBR] Normal overflow layers unavailable on this backend;"
                        + " spilled sprites keep ghost-resolution normals");
            } else {
                for (int layer = 0; layer < layerImages.length; layer++) {
                    encoder.writeToTexture(pages.texture(), layerImages[layer], 0, layer, 0, 0);
                    NativeImage previousLayer = layerImages[layer];
                    for (int level = 1; level < levelCount; level++) {
                        NativeImage mip = downsampleLevel(previousLayer, width, height, level,
                                layerRects.get(layer));
                        encoder.writeToTexture(pages.texture(), mip, level, layer, 0, 0);
                        if (previousLayer != layerImages[layer]) {
                            previousLayer.close();
                        }
                        previousLayer = mip;
                    }
                    if (previousLayer != layerImages[layer]) {
                        previousLayer.close();
                    }
                }
            }
        }

        GpuTextureView view = device.createTextureView(texture);
        return new NormalMapAtlas(texture, view,
                new LabPbrAnimationSet(animations, occupiedAnimationRegions), pages, fingerprint);
    }

    /**
     * Builds one mip level by reducing every sprite independently from its own rectangle in
     * {@code previous} (the prior level's already-extruded image), then re-extruding the result so
     * the {@link #EXTRUSION_PADDING} margin survives this level's own bilinear sampling too. See
     * {@link #upload}'s doc for why whole-atlas box filtering (the original, buggy approach) is not
     * used instead.
     *
     * @param previous      the previous level's composited, edge-extruded atlas image
     * @param atlasWidth    level-0 atlas width, used to derive this level's own dimensions
     * @param atlasHeight   level-0 atlas height, used to derive this level's own dimensions
     * @param level         the mip level being built (>= 1)
     * @param spriteRects   every sprite's rectangle at level 0 (original atlas coordinates); each
     *                      one is rescaled to this level, and to level - 1 to locate it within
     *                      {@code previous}, via {@link #scaleRect}
     */
    private static NativeImage downsampleLevel(NativeImage previous, int atlasWidth, int atlasHeight, int level, List<SpriteRect> spriteRects) {
        int levelWidth = Math.max(1, atlasWidth >> level);
        int levelHeight = Math.max(1, atlasHeight >> level);

        NativeImage mip = new NativeImage(NativeImage.Format.RGBA, levelWidth, levelHeight, false);
        mip.fillRect(0, 0, levelWidth, levelHeight, NEUTRAL_NORMAL_ARGB);

        boolean[] occupied = new boolean[levelWidth * levelHeight];
        List<SpriteRect> levelRects = new ArrayList<>(spriteRects.size());

        for (SpriteRect original : spriteRects) {
            // scaleRect(original, level - 1) reproduces the exact rectangle this same sprite was
            // placed at when `previous` was built (level 0 for the first reduction, otherwise this
            // same formula one level up) -- never a neighbor's texels, no realignment drift.
            SpriteRect parentRect = scaleRect(original, level - 1);
            SpriteRect childRect = scaleRect(original, level);
            boxDownsampleRect(previous, parentRect, mip, childRect, occupied, levelWidth);
            levelRects.add(childRect);
        }

        extrudeEdges(mip, occupied, levelWidth, levelHeight, levelRects);
        return mip;
    }

    /**
     * Scales a level-0 sprite rectangle down to {@code level}, matching {@link GpuTexture}'s own
     * unclamped {@code dimension >> level} mip-size convention (see {@link #computeMipLevelCount}'s
     * doc) so a sprite's rectangle at any level lines up with where the atlas image at that level
     * actually places it. Width/height are clamped to a minimum of 1 -- a sprite smaller than
     * {@code 2^level} in a dimension still occupies a single texel there rather than vanishing.
     */
    static SpriteRect scaleRect(SpriteRect rect, int level) {
        return new SpriteRect(
                rect.x() >> level,
                rect.y() >> level,
                Math.max(1, rect.width() >> level),
                Math.max(1, rect.height() >> level)
        );
    }

    /**
     * Always 1: {@code u_NormalTex}/{@code u_NormalPagesTex} are bound with {@code mipmap=false}
     * (see {@code DefaultChunkRendererTextureBindMixin}'s "mipmap=false independently keeps this
     * atlas on its authored base level" -- not an oversight, since averaging unit normal vectors
     * across a mip level produces a non-unit, visually flattened normal). No
     * consumer of this atlas ever reads past level 0, so generating, extruding and GPU-uploading a
     * full chain down to 1x1 was pure waste -- CPU time on every build (fresh or disk-cache-hit
     * alike) and VRAM for texels nothing samples.
     *
     * <p>{@link #downsampleLevel}/{@link #boxDownsampleRect}/{@link #scaleRect} are kept rather than
     * deleted: their math is pinned by {@code NormalMapAtlasReloadListenerTest} and is the documented
     * reference {@link MaterialMapAtlasReloadListener}'s own (actively used) mip-reduction mirrors --
     * see its doc on {@code scaleRect}. Returning 1 here means {@link #upload}'s
     * {@code for (level = 1; level < levelCount; level++)} loops never execute, so none of that code
     * runs; it costs nothing to leave in place, and reinstating a real chain (should mip-blended
     * normals ever become wanted) is a one-line revert of this method, not a rewrite.
     */
    static int computeMipLevelCount(int width, int height) {
        return 1;
    }

    /**
     * Reduces one sprite's own rectangle by averaging each 2x2 block of texels (replicating the
     * rectangle's own edge texel when a dimension is odd, never a neighboring sprite's). Tangent
     * normal X/Y are reconstructed and renormalized; AO and height are reduced as scalar lanes.
     * See {@link #upload}'s doc for why this is scoped to one sprite rather than the whole atlas.
     *
     * <p>All reads are clamped to {@code [0, parentRect.width()/height())}, i.e. relative to
     * {@code parentRect}'s own top-left -- never to the full {@code source} image -- so this can
     * only ever read the sprite's own previously-written texels, the same guarantee
     * {@link #scaleRect}'s doc describes for why {@code parentRect} lines up correctly in the first
     * place.
     *
     * @param source     the previous level's composited image
     * @param parentRect this sprite's rectangle within {@code source}
     * @param dst        this level's image being built
     * @param childRect  this sprite's rectangle within {@code dst}, sized {@code parentRect}
     *                   halved (rounded up via edge replication)
     */
    /**
     * Reduces one sprite's rectangle from {@code source} into {@code dst}, gated by {@code occupied}
     * so a collision with an already-written sprite loses deterministically rather than silently
     * clobbering it.
     *
     * <p>{@link #scaleRect}'s {@code Math.max(1, ...)} floor means two DIFFERENT sprites can end up
     * with the same (or overlapping) {@code childRect} at a deep enough level -- a sprite narrower
     * than {@code 2^level} collapses to a 1-texel rectangle whose position also collapses, and an
     * adjacent sprite doing the same can land on that exact texel. Without a check, whichever sprite
     * this loop reaches last would overwrite the earlier one's texel with its own unrelated content,
     * so a UV that geometrically belongs to one sprite could sample a completely different sprite's
     * mip data. This makes the loop order-independent instead: the first sprite to reach a given
     * texel at this level keeps it, and every later claimant is silently skipped there (its own,
     * non-colliding texels are unaffected).
     */
    private static void boxDownsampleRect(NativeImage source, SpriteRect parentRect, NativeImage dst,
                                          SpriteRect childRect, boolean[] occupied, int dstWidth) {
        int parentRight = parentRect.width() - 1;
        int parentBottom = parentRect.height() - 1;

        for (int row = 0; row < childRect.height(); row++) {
            int sy0 = Math.min(row * 2, parentBottom);
            int sy1 = Math.min(row * 2 + 1, parentBottom);
            int dstY = childRect.y() + row;
            for (int col = 0; col < childRect.width(); col++) {
                int dstX = childRect.x() + col;
                int occupiedIndex = dstY * dstWidth + dstX;
                if (occupied[occupiedIndex]) {
                    continue;
                }
                int sx0 = Math.min(col * 2, parentRight);
                int sx1 = Math.min(col * 2 + 1, parentRight);

                int p00 = source.getPixel(parentRect.x() + sx0, parentRect.y() + sy0);
                int p10 = source.getPixel(parentRect.x() + sx1, parentRect.y() + sy0);
                int p01 = source.getPixel(parentRect.x() + sx0, parentRect.y() + sy1);
                int p11 = source.getPixel(parentRect.x() + sx1, parentRect.y() + sy1);

                dst.setPixel(dstX, dstY, reduceMipTexel(p00, p10, p01, p11));
                occupied[occupiedIndex] = true;
            }
        }
    }

    static int reduceMipTexel(int p0, int p1, int p2, int p3) {
        return LabPbrSidecarBlitter.reduceNormal(p0, p1, p2, p3);
    }
}
