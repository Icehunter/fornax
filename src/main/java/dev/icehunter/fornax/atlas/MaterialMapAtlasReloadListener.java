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
import dev.icehunter.fornax.util.GpuFatalException;
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
 * Builds the LabPBR {@link MaterialMapAtlas} from vanilla's freshly-stitched block atlas. Mirrors
 * {@link NormalMapAtlasReloadListener}'s structure exactly -- see that class's own javadoc for the
 * reload-mixin hook point and the content-rect derivation, {@link LabPbrSidecarLocator} for
 * Continuity's CTM path recovery, and {@link PbrSidecarAtlasScale} for how the atlas is sized.
 *
 * <p>The two atlases are sized INDEPENDENTLY and deliberately so: a pack may ship high-resolution
 * normals beside ordinary specular, and there is no reason the cheaper map should pay for the
 * expensive one's resolution.
 */
public final class MaterialMapAtlasReloadListener {
    /**
     * Missing-map sentinel: zero smoothness/F0/porosity, LabPBR emission alpha 255 (ignored).
     * Alpha 0 is an authored, meaningful "no emission" value and therefore cannot also identify a
     * sprite with no {@code _s} sidecar. The shader uses this sentinel to apply its block-category
     * fallback only when emission data is absent/ignored, never over an authored zero.
     */
    private static final int MISSING_MATERIAL_ARGB = 0xFF_00_00_00;

    /**
     * Resident-size multiplier for a full mip chain: the levels below the base sum to 1/3 of it.
     * Now matches {@link NormalMapAtlasReloadListener}'s, which this atlas shares a chain shape
     * with. Feeding it to {@link PbrSidecarAtlasScale#chooseLog2Scale} is what stops the chain
     * pushing this atlas over the device's budget and forcing a HALF-RESOLUTION base -- which would
     * be self-defeating, because halving the base resamples every {@code _s} sidecar, and a
     * resampler averaging labPBR's green is the exact defect this chain exists to avoid. On the
     * user's pack the chooser answers 2^0 either way (the normal atlas already carries a chain at
     * the same 8192x8192 base), so this changes the budget arithmetic, not the layout.
     */
    private static final double MIP_CHAIN_FACTOR = 4.0 / 3.0;

    private MaterialMapAtlasReloadListener() {
    }

    /**
     * Builds a material-map atlas matching the block atlas's UV layout. Publication is owned by
     * {@link LabPbrAtlasPair} after the normal lane has also built successfully.
     *
     * @param preparations    the block atlas stitch result (sprites + dimensions)
     * @param resourceManager the client resource manager, used to locate {@code _s} sidecar PNGs
     */
    @Nullable
    public static MaterialMapAtlas build(SpriteLoader.Preparations preparations,
                                         ResourceManager resourceManager) {
        return build(TextureAtlas.LOCATION_BLOCKS, preparations, resourceManager);
    }

    /** Builds, but does not publish, the mirrored material atlas for one exact atlas owner. */
    @Nullable
    public static MaterialMapAtlas build(Identifier atlasLocation,
                                         SpriteLoader.Preparations preparations,
                                         ResourceManager resourceManager) {
        long start = System.nanoTime();
        GpuDevice device = RenderSystem.tryGetDevice();

        if (device == null) {
            FornaxMod.LOGGER.warn("[LabPBR] Skipping material map atlas build: no GPU device available");
            return null;
        }

        // Sized from the _s maps' OWN resolution, independently of the normal atlas: a pack is free
        // to ship 512px normals beside 64px specular, and each atlas should cost what its own maps
        // are worth. See PbrSidecarAtlasScale.
        List<TextureAtlasSprite> sprites = new ArrayList<>(preparations.regions().values());
        LabPbrSidecarSurvey.Result survey = LabPbrSidecarSurvey.survey(sprites, resourceManager, "_s");

        // Read before choosing the scale, not after -- see the normal lane's matching comment.
        BlockAtlasPagedLayout pagedLayout = TextureAtlas.LOCATION_BLOCKS.equals(atlasLocation)
                ? BlockAtlasPagedLayout.current() : null;
        int overflowPages = pagedLayout == null ? 0 : pagedLayout.overflowPageCount();

        int log2Scale;
        try {
            log2Scale = PbrSidecarAtlasScale.chooseLog2Scale(
                    preparations.width(), preparations.height(), survey.maxRatio(),
                    LabPbrSidecarSurvey.maxTextureDimension(device), MIP_CHAIN_FACTOR, overflowPages,
                    FornaxConfig.get().sidecarMapResolution.log2ScaleOffset(),
                    PbrSidecarAtlasScale.effectiveMaxAtlasBytes(
                            GpuMemoryEstimator.detectedVramBytesFromDevice(device),
                            FornaxConfig.get().sidecarMapResolution.maxAtlasBytes()));
        } catch (IllegalStateException noFittingScale) {
            FornaxMod.LOGGER.warn("[LabPBR] Skipping material map atlas build: {}", noFittingScale.getMessage());
            return null;
        }

        int atlasWidth = PbrSidecarAtlasScale.atlasDimension(preparations.width(), log2Scale);
        int atlasHeight = PbrSidecarAtlasScale.atlasDimension(preparations.height(), log2Scale);

        if (atlasWidth <= 0 || atlasHeight <= 0) {
            FornaxMod.LOGGER.warn("[LabPBR] Skipping material map atlas build: invalid atlas size {}x{}", atlasWidth, atlasHeight);
            return null;
        }

        int found = 0;
        int missing = 0;
        int authoredEmission = 0;
        List<LabPbrAnimatedSidecar> animations = new ArrayList<>();
        List<LabPbrAnimatedSidecar.Rect> occupiedAnimationRegions = new ArrayList<>();
        boolean animationsTransferred = false;

        // Paged overflow layers (pagedLayout/overflowPages, computed above) -- same shape as the
        // normal lane's, with this lane's own neutral fill and class-aware mip reduction. See the
        // normal listener's matching block for the full rationale.

        // Skip point -- see the normal lane's matching block for the full rationale.
        String fingerprint = LabPbrAtlasFingerprint.compute(atlasLocation, preparations, survey, pagedLayout);
        MaterialMapAtlas existing = MaterialMapAtlas.getInstance(atlasLocation);
        if (existing != null && fingerprint.equals(existing.fingerprint())) {
            FornaxMod.LOGGER.info("[LabPBR] Material map atlas reuse: fingerprint unchanged, "
                    + "skipping rebuild ({} sprites)", sprites.size());
            return existing;
        }

        // Stage B: see the normal lane's matching block for the full rationale.
        LabPbrAtlasDiskCache.Loaded diskCached = LabPbrAtlasDiskCache.tryRead(
                atlasLocation, "material", fingerprint, atlasWidth, atlasHeight, overflowPages);
        boolean sourcedFromDisk = diskCached != null;

        NativeImage[] layerImages = new NativeImage[overflowPages];
        boolean[][] layerOccupied = new boolean[overflowPages][];
        List<List<SpriteRect>> layerRects = new ArrayList<>();
        for (int i = 0; i < overflowPages; i++) {
            if (sourcedFromDisk) {
                layerImages[i] = diskCached.layers()[i];
            } else {
                layerImages[i] = new NativeImage(NativeImage.Format.RGBA, atlasWidth, atlasHeight, false);
                layerImages[i].fillRect(0, 0, atlasWidth, atlasHeight, MISSING_MATERIAL_ARGB);
            }
            layerOccupied[i] = new boolean[atlasWidth * atlasHeight];
            layerRects.add(new ArrayList<>());
        }

        MaterialMapAtlas builtAtlas;
        try (NativeImage atlasImage = sourcedFromDisk ? diskCached.base()
                : new NativeImage(NativeImage.Format.RGBA, atlasWidth, atlasHeight, false)) {
            if (!sourcedFromDisk) {
                atlasImage.fillRect(0, 0, atlasWidth, atlasHeight, MISSING_MATERIAL_ARGB);
            }

            boolean[] occupied = new boolean[atlasWidth * atlasHeight];
            List<SpriteRect> spriteRects = new ArrayList<>();
            int mipLevels = computeMipLevelCount(atlasWidth, atlasHeight);

            for (LabPbrSidecarSurvey.Entry surveyed : survey.entries()) {
                TextureAtlasSprite sprite = surveyed.sprite();
                LabPbrAnimationMetadata.Lookup animationLookup =
                        LabPbrAnimationMetadata.inspect(sprite, resourceManager);
                LabPbrAnimationMetadata animation = animationLookup.metadata();

                // Recovered at the BLOCK atlas's own (unscaled) resolution, then scaled through
                // atlasCoordinate -- not rounded directly against this (possibly downscaled) sidecar
                // atlas's width/height. Rounding a float UV after multiplying by an already-shrunk
                // atlasWidth can round to a different texel than atlasCoordinate's floor/shift
                // arithmetic would for the same coordinate (off by one whenever the block-space value
                // is odd), which is exactly what put this lane's page-0 rect one sidecar texel away
                // from the normal lane's for the same sprite. See the normal lane's matching block for
                // the full derivation.
                int blockContentX = Math.round(sprite.getU0() * preparations.width());
                int blockContentY = Math.round(sprite.getV0() * preparations.height());
                int contentX = PbrSidecarAtlasScale.atlasCoordinate(blockContentX, log2Scale);
                int contentY = PbrSidecarAtlasScale.atlasCoordinate(blockContentY, log2Scale);
                // Extent from contents() for an ordinary sprite, same reasoning and same guard as
                // the normal lane's rect derivation: only a paged BlockAtlasGhostSprite (quarter-scale
                // UV rect, full-size contents) needs the UV-span reconstruction -- sizing an ordinary
                // sprite from it added a new rounding path with no behavioural need.
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
                occupiedAnimationRegions.add(new LabPbrAnimatedSidecar.Rect(
                        rect.x() - padding, rect.y() - padding,
                        rect.width() + padding * 2, rect.height() + padding * 2));

                // The sentinel resolves on the SOURCE, inside the blit, before the resample -- see
                // resolveEmissionSentinel. The array is how a lambda reports back; the alternative
                // was threading a return value through a hook whose other caller has no use for it.
                boolean[] authored = new boolean[1];
                LabPbrAnimatedSidecar animated = animation == null ? null
                        : LabPbrAnimatedSidecar.load(
                                surveyed, resourceManager, animation,
                                new LabPbrAnimatedSidecar.Rect(
                                        rect.x(), rect.y(), rect.width(), rect.height()),
                                padding, mipLevels, LabPbrSidecarBlitter.Filter.MATERIAL);
                boolean usable = animationLookup.usable() && (animation == null || animated != null);
                int initialFrame = animation == null ? 0 : animation.frames().getFirst().index();
                int frameColumns = animation == null ? 1 : animation.frameColumns();
                if (usable && (sourcedFromDisk || blitSidecar(atlasImage, surveyed, resourceManager, rect,
                        (source, frameHeight) ->
                                authored[0] = resolveEmissionSentinel(source, frameHeight),
                        initialFrame, frameColumns, animation != null))) {
                    found++;
                    if (authored[0]) {
                        authoredEmission++;
                    }
                    if (animated != null) {
                        animations.add(animated);
                    }
                } else {
                    if (animated != null) {
                        animated.close();
                    }
                    missing++;
                }

                markOccupied(occupied, atlasWidth, rect);
                spriteRects.add(rect);

                // Full-resolution overflow-layer copy for a spilled static sprite -- page-local
                // rect at this lane's scale, same blit (including the emission-sentinel source
                // transform) and same usable-gating as the ghost above; see the normal lane's
                // matching block.
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
                                (source, frameHeight) ->
                                        resolveEmissionSentinel(source, frameHeight),
                                initialFrame, frameColumns, animation != null);
                    }
                    markOccupied(layerOccupied[layerIndex], atlasWidth, layerRect);
                    layerRects.get(layerIndex).add(layerRect);
                }
            }

            if (!sourcedFromDisk) {
                extrudeEdges(atlasImage, occupied, atlasWidth, atlasHeight, spriteRects);
                for (int i = 0; i < overflowPages; i++) {
                    extrudeEdges(layerImages[i], layerOccupied[i], atlasWidth, atlasHeight, layerRects.get(i));
                }
                LabPbrAtlasDiskCache.writeAsync(atlasLocation, "material", fingerprint, atlasImage, layerImages);
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

        FornaxMod.LOGGER.info("[LabPBR] Material map atlas built: {} sprites with _s maps ({} carrying"
                        + " authored emission), {} without;"
                        + " {}x{} at scale 2^{} (pack asked for 2^{}), {} MB resident with mips and {}"
                        + " overflow layer(s), in {} ms (level 0 {})",
                found, authoredEmission, missing, atlasWidth, atlasHeight, log2Scale,
                PbrSidecarAtlasScale.ceilLog2(survey.maxRatio()),
                Math.round(atlasWidth * (double) atlasHeight * 4.0 * MIP_CHAIN_FACTOR
                        * (1 + overflowPages) / 1.0e6),
                overflowPages,
                (System.nanoTime() - start) / 1_000_000L,
                sourcedFromDisk ? "from disk cache" : "freshly composited");
        return builtAtlas;
    }

    record SpriteRect(int x, int y, int width, int height) {
    }

    static boolean blitSidecar(NativeImage atlasImage, LabPbrSidecarSurvey.Entry entry,
                               ResourceManager resourceManager, SpriteRect rect,
                               LabPbrSidecarBlitter.SourceTransform sourceTransform,
                               int initialFrame, int frameColumns, boolean animated) {
        return LabPbrSidecarBlitter.blit(atlasImage, entry, resourceManager,
                rect.x(), rect.y(), rect.width(), rect.height(), sourceTransform,
                LabPbrSidecarBlitter.Filter.MATERIAL, initialFrame, frameColumns, animated);
    }

    private static final int EXTRUSION_PADDING = 1;

    /** Reports authored emission for diagnostics without rewriting the pack's source bytes. */
    private static boolean resolveEmissionSentinel(NativeImage source, int frameHeight) {
        int width = source.getWidth();
        for (int row = 0; row < frameHeight; row++) {
            for (int col = 0; col < width; col++) {
                if ((source.getPixel(col, row) >>> 24) != LabPbrEmissionSentinel.UNAUTHORED) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void markOccupied(boolean[] occupied, int atlasWidth, SpriteRect rect) {
        for (int row = 0; row < rect.height(); row++) {
            int base = (rect.y() + row) * atlasWidth + rect.x();
            for (int col = 0; col < rect.width(); col++) {
                occupied[base + col] = true;
            }
        }
    }

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
     * Uploads the base level and a full mip chain built by {@link LabPbrMaterialReduction}.
     *
     * <p>Structurally identical to {@link NormalMapAtlasReloadListener#upload} -- see that method's
     * doc for why the chain is generated CPU-side (no scaling blit exists on {@link CommandEncoder})
     * and why each sprite is reduced from its OWN rectangle at the previous level rather than by a
     * whole-atlas box filter (a filter that reads across sprite boundaries puts a seam on every
     * block edge, and the 1-texel extrusion margin survives exactly one halving). The one difference
     * is the filter itself: normals are directions and box-average, {@code _s} texels are four
     * independent labPBR lanes and do not. See {@link LabPbrMaterialReduction}.
     *
     * <p><b>Why this atlas gets a chain at all.</b> Bound with a single level, minification samples
     * full-resolution texels no matter how far away the surface is, and on the user's pack that is
     * both reported symptoms at once. A 256x sidecar is MAGNIFIED only within three blocks of the
     * camera (256 texels across a face that subtends 771/distance pixels at 1080p and a 70 degree
     * FOV), so the NEAREST magnification filter this atlas is bound with essentially never engages
     * -- past three blocks every ore face is minified, the LINEAR minification filter blends a metal
     * code with the stone matrix, and 2.9%..5.7% of the face carries an invented F0 averaging 0.44
     * against the matrix's 0.039. Further out a screen pixel covers dozens of texels while a 2x2 tap
     * samples four, so the class landed on at random and the specks scintillated.
     *
     * <p><b>Leak safety.</b> {@code texture} and {@code pages} are hoisted to nulled locals and
     * freed in an outer catch if anything after their allocation throws -- this lane's mip chain is
     * real (unlike the normal lane's), so the loop that can throw mid-sequence runs on every build,
     * not just in theory. See the normal lane's matching method for why the two allocation calls
     * are wrapped to raise {@link GpuFatalException} on any failure.
     */
    private static MaterialMapAtlas upload(GpuDevice device, NativeImage atlasImage,
                                           List<SpriteRect> spriteRects,
                                           List<LabPbrAnimatedSidecar> animations,
                                           List<LabPbrAnimatedSidecar.Rect> occupiedAnimationRegions,
                                           NativeImage[] layerImages,
                                           List<List<SpriteRect>> layerRects,
                                           String fingerprint) {
        int width = atlasImage.getWidth();
        int height = atlasImage.getHeight();
        int levelCount = computeMipLevelCount(width, height);

        GpuTexture texture = null;
        ArrayTextures.Allocation pages = null;
        NativeImage previous = atlasImage;
        NativeImage previousLayer = null;
        try {
            try {
                texture = device.createTexture(
                        "Sodium LabPBR Material Atlas",
                        GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                        GpuFormat.RGBA8_UNORM,
                        width,
                        height,
                        1,          // depthOrLayers
                        levelCount  // mipLevels
                );
            } catch (RuntimeException e) {
                throw new GpuFatalException(
                        "Material map atlas base texture allocation failed: " + e.getMessage());
            }

            CommandEncoder encoder = device.createCommandEncoder();
            encoder.writeToTexture(texture, atlasImage, 0, 0, 0, 0);

            for (int level = 1; level < levelCount; level++) {
                NativeImage mip = downsampleLevel(previous, width, height, level, spriteRects);
                try {
                    encoder.writeToTexture(texture, mip, level, 0, 0, 0);
                } catch (RuntimeException e) {
                    mip.close();
                    throw e;
                }
                if (previous != atlasImage) {
                    previous.close();
                }
                previous = mip;
            }
            if (previous != atlasImage) {
                previous.close();
                previous = atlasImage;
            }

            // Overflow layers: same per-sprite (class-aware) mip discipline as the base atlas above,
            // one layer per overflow page, through the array-texture seam. See the normal lane's
            // matching block.
            if (layerImages.length > 0) {
                try {
                    pages = ArrayTextures.create("Sodium LabPBR Material Atlas Pages",
                            GpuFormat.RGBA8_UNORM, width, height, layerImages.length, levelCount);
                } catch (RuntimeException e) {
                    throw new GpuFatalException(
                            "Material map atlas overflow pages allocation failed: " + e.getMessage());
                }
                if (pages == null) {
                    FornaxMod.LOGGER.warn("[LabPBR] Material overflow layers unavailable on this backend;"
                            + " spilled sprites keep ghost-resolution material data");
                } else {
                    for (int layer = 0; layer < layerImages.length; layer++) {
                        encoder.writeToTexture(pages.texture(), layerImages[layer], 0, layer, 0, 0);
                        // layerBase is the caller's own image (layerImages[layer]; the outer
                        // finally in build() always closes it regardless of what happens here), so
                        // the method-scope previousLayer the outer catch below closes must only
                        // ever point at a mip THIS loop produced, never at layerBase itself --
                        // otherwise a throw before the first reassignment would double-close it.
                        NativeImage layerBase = layerImages[layer];
                        NativeImage current = layerBase;
                        previousLayer = null;
                        for (int level = 1; level < levelCount; level++) {
                            NativeImage mip = downsampleLevel(current, width, height, level,
                                    layerRects.get(layer));
                            try {
                                encoder.writeToTexture(pages.texture(), mip, level, layer, 0, 0);
                            } catch (RuntimeException e) {
                                mip.close();
                                throw e;
                            }
                            if (current != layerBase) {
                                current.close();
                            }
                            current = mip;
                            previousLayer = current;
                        }
                        if (current != layerBase) {
                            current.close();
                        }
                        previousLayer = null;
                    }
                }
            }

            GpuTextureView view = device.createTextureView(texture);
            return new MaterialMapAtlas(texture, view,
                    new LabPbrAnimationSet(animations, occupiedAnimationRegions), pages, fingerprint);
        } catch (RuntimeException e) {
            if (previousLayer != null) {
                previousLayer.close();
            }
            if (previous != atlasImage) {
                previous.close();
            }
            if (pages != null) {
                pages.close();
            }
            if (texture != null) {
                texture.close();
            }
            throw e;
        }
    }

    /**
     * Builds one mip level by reducing every sprite independently from its own rectangle in
     * {@code previous}, then re-extruding so the {@link #EXTRUSION_PADDING} margin exists at this
     * level too. Mirrors {@link NormalMapAtlasReloadListener}'s method of the same name.
     */
    private static NativeImage downsampleLevel(NativeImage previous, int atlasWidth, int atlasHeight, int level, List<SpriteRect> spriteRects) {
        int levelWidth = Math.max(1, atlasWidth >> level);
        int levelHeight = Math.max(1, atlasHeight >> level);

        NativeImage mip = new NativeImage(NativeImage.Format.RGBA, levelWidth, levelHeight, false);
        mip.fillRect(0, 0, levelWidth, levelHeight, MISSING_MATERIAL_ARGB);

        boolean[] occupied = new boolean[levelWidth * levelHeight];
        List<SpriteRect> levelRects = new ArrayList<>(spriteRects.size());

        for (SpriteRect original : spriteRects) {
            SpriteRect parentRect = scaleRect(original, level - 1);
            SpriteRect childRect = scaleRect(original, level);
            reduceRect(previous, parentRect, mip, childRect, occupied, levelWidth);
            levelRects.add(childRect);
        }

        extrudeEdges(mip, occupied, levelWidth, levelHeight, levelRects);
        return mip;
    }

    /**
     * Reduces one sprite's rectangle by half in each dimension, four texels at a time.
     *
     * <p>Gated on {@code occupied} exactly like {@link NormalMapAtlasReloadListener#boxDownsampleRect}
     * -- two sprites can scale down to the SAME texel at a deep enough mip level (their {@code
     * childRect}s collide once both shrink past the point where they were ever distinct), and without
     * this check whichever sprite iterates last in {@code spriteRects} silently overwrote the first
     * one's reduction. First-writer-wins, matching the normal lane, rather than last-writer-wins.
     */
    private static void reduceRect(NativeImage source, SpriteRect parentRect, NativeImage dst,
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

                int reduced = LabPbrMaterialReduction.reduce(
                        source.getPixel(parentRect.x() + sx0, parentRect.y() + sy0),
                        source.getPixel(parentRect.x() + sx1, parentRect.y() + sy0),
                        source.getPixel(parentRect.x() + sx0, parentRect.y() + sy1),
                        source.getPixel(parentRect.x() + sx1, parentRect.y() + sy1));

                dst.setPixel(dstX, dstY, reduced);
                occupied[occupiedIndex] = true;
            }
        }
    }

    /**
     * Scales a level-0 sprite rectangle down to {@code level}, matching {@link GpuTexture}'s own
     * unclamped {@code dimension >> level} mip-size convention. See
     * {@link NormalMapAtlasReloadListener#scaleRect}, which this mirrors.
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
     * Mip levels for a full chain down to 1x1, based on the SMALLER dimension so both stay >= 1 at
     * the final level -- {@link GpuTexture#getWidth(int)} does a raw unclamped shift. See
     * {@link NormalMapAtlasReloadListener#computeMipLevelCount} for the full reasoning.
     */
    static int computeMipLevelCount(int width, int height) {
        int minDim = Math.max(1, Math.min(width, height));
        return 1 + (31 - Integer.numberOfLeadingZeros(minDim)); // 1 + floor(log2(minDim))
    }
}
