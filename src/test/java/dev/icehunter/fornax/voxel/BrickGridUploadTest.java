package dev.icehunter.fornax.voxel;

import dev.icehunter.fornax.pack.GraphSpec;
import dev.icehunter.fornax.pack.graph.TargetRegistry;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrickGridUploadTest {
    @Test
    void payloadBytesPerSlotAccountsForOccupancyAndIndices() {
        // 4096 voxels, 1 byte palette-index each, plus a 512-byte (4096-bit) occupancy mask.
        long expected = 4096 + 512;
        assertEquals(expected, BrickGridUpload.PAYLOAD_BYTES_PER_SLOT);
    }

    @Test
    void indexGridSizeScalesWithDiameterCubed() {
        int diameter = 5;
        long expected = (long) diameter * diameter * diameter * Integer.BYTES;
        assertEquals(expected, BrickGridUpload.indexGridSizeBytes(diameter));
    }

    @Test
    void paletteBytesPerSlotIsFixedStrideOfSixteenWordEntries() {
        // Entry stride derives from PALETTE_ENTRY_WORDS (16 since the emitter milestone added the
        // word-15 emission word) -- asserted via the constants themselves, never a re-hardcoded
        // literal, so this test can never silently pass against a drifted stride again.
        assertEquals(BrickGridUpload.PALETTE_ENTRY_WORDS * Integer.BYTES, BrickGridUpload.PALETTE_ENTRY_BYTES);
        assertEquals(16, BrickGridUpload.PALETTE_ENTRY_WORDS,
                "the emitter milestone grew the entry 15 -> 16 words (word 15 = emission)");
        assertEquals((long) SectionHarvester.MAX_PALETTE_ENTRIES * BrickGridUpload.PALETTE_ENTRY_BYTES,
                BrickGridUpload.PALETTE_BYTES_PER_SLOT);
    }

    @Test
    void packEmissionWordQuantizesStrengthAndPacksAuthoredColor() {
        // Bits 0-7: round(clamp(emissiveStrength, 0, 1) * 255). Bits 8-31: authored 0x00RRGGBB color,
        // shifted left 8 (R lands at bits 24-31, G at 16-23, B at 8-15). 0 color = no authored hue.
        assertEquals(0, BrickGridUpload.packEmissionWord(0.0, 0));
        assertEquals(255, BrickGridUpload.packEmissionWord(1.0, 0));
        assertEquals(255, BrickGridUpload.packEmissionWord(2.5, 0), "over-1 strength clamps");
        assertEquals(Math.round(0.8f * 255.0f), BrickGridUpload.packEmissionWord(0.8, 0));
        assertEquals(0xFF0000 << 8, BrickGridUpload.packEmissionWord(0.0, 0xFF0000), "pure red shifted to bits 24-31");
        assertEquals((0xFF0000 << 8) | 255, BrickGridUpload.packEmissionWord(1.0, 0xFF0000),
                "full-strength red: intensity in bits 0-7, color in bits 8-31");
        assertEquals((0x40E0E0 << 8) | 128, BrickGridUpload.packEmissionWord(128.0 / 255.0, 0x40E0E0),
                "arbitrary RGB round-trips through the shift");
    }

    @Test
    void packPaletteEntriesWritesEmissionAsWordFifteen() {
        int[] faces = {0xFFFF8800, 0xFFFF8800, 0xFFFF8800, 0xFFFF8800, 0xFFFF8800, 0xFFFF8800};
        SectionPalette.Entry glowing = new SectionPalette.Entry(
                VoxelShapeKind.FULL, List.of(), faces, 1.0, false, 0);
        SectionPalette.Entry coloredEmitter = new SectionPalette.Entry(
                VoxelShapeKind.FULL, List.of(), faces, 0.5, false, 0xFF3018);

        byte[] bytes = BrickGridUpload.packPaletteEntries(List.of(glowing, coloredEmitter));
        assertEquals(2 * BrickGridUpload.PALETTE_ENTRY_BYTES, bytes.length);

        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(255, buf.getInt(15 * 4), "entry 0 word 15: full-strength emission, no authored color");
        assertEquals((0xFF3018 << 8) | 128, buf.getInt(BrickGridUpload.PALETTE_ENTRY_BYTES + 15 * 4),
                "entry 1 word 15: half-strength emission with an authored deep-red color");
    }

    @Test
    void packBoxRoundTripsEveryCoordinateIncludingTheFullExtentSixteen() {
        // The whole point of 5-bit (not 4-bit) packing: coordinate 16 (a full-cell extent) must survive.
        VoxelShapeClassifier.PackedBox box = new VoxelShapeClassifier.PackedBox(0, 3, 16, 16, 8, 1);
        int packed = BrickGridUpload.packBox(box);
        assertEquals(0, packed & 0x1F, "minX");
        assertEquals(3, (packed >> 5) & 0x1F, "minY");
        assertEquals(16, (packed >> 10) & 0x1F, "minZ (full extent must not truncate)");
        assertEquals(16, (packed >> 15) & 0x1F, "maxX (full extent must not truncate)");
        assertEquals(8, (packed >> 20) & 0x1F, "maxY");
        assertEquals(1, (packed >> 25) & 0x1F, "maxZ");
    }

    @Test
    void packBoxKeepsAllSixCoordinatesInThirtyBits() {
        // Six 5-bit fields fit in 30 bits, so the top two bits of the word stay clear -- no field bleeds
        // into another and no data lands outside a single uint.
        int packed = BrickGridUpload.packBox(new VoxelShapeClassifier.PackedBox(16, 16, 16, 16, 16, 16));
        assertEquals(0, packed >>> 30, "no bits set at or above bit 30");
    }

    @Test
    void packPaletteEntriesLaysOutBoxCountThenColorsThenBoxes() {
        int[] fullFaces = {0xFF112233, 0xFF445566, 0xFF778899, 0xFFAABBCC, 0xFFDDEEFF, 0xFF010203};
        SectionPalette.Entry full = new SectionPalette.Entry(
                VoxelShapeKind.FULL, List.of(), fullFaces, 0.0, false, 0);

        VoxelShapeClassifier.PackedBox slabBox = new VoxelShapeClassifier.PackedBox(0, 0, 0, 16, 8, 16);
        int[] partialFaces = {1, 2, 3, 4, 5, 6};
        SectionPalette.Entry partial = new SectionPalette.Entry(
                VoxelShapeKind.PARTIAL, List.of(slabBox), partialFaces, 0.0, false, 0);

        byte[] bytes = BrickGridUpload.packPaletteEntries(List.of(full, partial));
        assertEquals(2 * BrickGridUpload.PALETTE_ENTRY_BYTES, bytes.length,
                "two entries at the fixed per-entry stride");

        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        // Entry 0 (FULL): boxCount 0, then the six face colors verbatim, then eight zeroed box words.
        assertEquals(0, buf.getInt(0), "FULL entry has boxCount 0 (shader treats it as a solid cube)");
        for (int f = 0; f < 6; f++) {
            assertEquals(fullFaces[f], buf.getInt((1 + f) * 4), "face color " + f);
        }
        for (int b = 0; b < VoxelShapeClassifier.MAX_BOXES; b++) {
            assertEquals(0, buf.getInt((7 + b) * 4), "unused box word " + b + " must be zero");
        }
        assertEquals(0, buf.getInt(15 * 4), "FULL entry's word 15: zero emission, not transmissive");

        // Entry 1 (PARTIAL): starts one full entry in; boxCount 1, then colors, then the packed slab box.
        int base = BrickGridUpload.PALETTE_ENTRY_BYTES;
        assertEquals(1, buf.getInt(base), "PARTIAL entry stores its real box count");
        assertEquals(BrickGridUpload.packBox(slabBox), buf.getInt(base + 7 * 4),
                "the single box lands in word 7 of the entry");
    }

    @Test
    void lightVolumeLayoutIsFourFieldsOfFiveTwelveCellsAtTheDefaultStandardTier() {
        // No setLightCellDetailTier call in this test -- the class's static default (0) must already
        // be Standard, so this is what every reader gets before GraphRunner.rebuild() ever runs, and
        // what a pack that never declares LIGHT_CELL_DETAIL gets forever.
        assertEquals(8, BrickGridUpload.lightCellsPerSectionAxis(), "default tier is Standard");
        assertEquals(512, BrickGridUpload.lightCellsPerSlot(), "8^3 cells (2x2x2 blocks each)");
        assertEquals(4 * 512, BrickGridUpload.lightWordsPerSlot(),
                "direct source + direct field + indirect field + indirect source");
        assertEquals(8192L, BrickGridUpload.lightVolumeBytesPerSlot());
        // vkCmdUpdateBuffer constraints clearLightSlot relies on: multiple of 4, within the 65536
        // inline limit (spec: dataSize must be <= 65536), and slot*stride offsets 4-aligned.
        assertEquals(0, BrickGridUpload.lightVolumeBytesPerSlot() % 4);
        assertTrue(BrickGridUpload.lightVolumeBytesPerSlot() <= 65536);
    }

    @Test
    void lightVolumeLayoutAtTheHighTierIsOneCellPerBlock() {
        BrickGridUpload.setLightCellDetailTier(1);
        try {
            assertEquals(16, BrickGridUpload.lightCellsPerSectionAxis());
            assertEquals(4096, BrickGridUpload.lightCellsPerSlot(), "16^3 cells (one cell per block)");
            assertEquals(4 * 4096, BrickGridUpload.lightWordsPerSlot(),
                    "direct source + direct field + indirect field + indirect source");
            assertEquals(65536L, BrickGridUpload.lightVolumeBytesPerSlot(), "8x Standard's 8192 B/slot");
            assertEquals(0, BrickGridUpload.lightVolumeBytesPerSlot() % 4);
            // EXACTLY vkCmdUpdateBuffer's spec maximum (dataSize <= 65536) -- legal, but zero
            // headroom: any fifth segment must move clearLightSlot off inline updates first.
            assertTrue(BrickGridUpload.lightVolumeBytesPerSlot() <= 65536);
        } finally {
            BrickGridUpload.setLightCellDetailTier(0); // static state -- must not leak into other tests
        }
    }

    @Test
    void setLightCellDetailTierFallsBackToStandardForAnyValueOtherThanOne() {
        BrickGridUpload.setLightCellDetailTier(2); // malformed pack value
        try {
            assertEquals(8, BrickGridUpload.lightCellsPerSectionAxis(),
                    "an unrecognized tier value falls back to Standard, not garbage buffer sizing");
        } finally {
            BrickGridUpload.setLightCellDetailTier(0);
        }
    }

    // --- Brick-summary-skip milestone: anySolidVoxel / summaryWord (BRICK_SUMMARY_TARGET) ----------

    @Test
    void brickSummaryBytesPerSlotIsOneLittleEndianWord() {
        assertEquals(Integer.BYTES, BrickGridUpload.BRICK_SUMMARY_BYTES_PER_SLOT,
                "one uint/slot -- matches FullscreenPassRunner's R32_UINT buffer-kind-input convention");
    }

    @Test
    void anySolidVoxelFalseWhenEverySectionVoxelIsEmptyShape() {
        SectionPalette.Entry empty = new SectionPalette.Entry(
                VoxelShapeKind.EMPTY, List.of(), new int[6], 0.0, false, 0);
        byte[] paletteIndices = new byte[16 * 16 * 16]; // all index 0 -> the EMPTY entry
        assertEquals(false, BrickGridUpload.anySolidVoxel(paletteIndices, List.of(empty)),
                "an all-EMPTY section (e.g. pure air) must summarize as no solid voxels");
    }

    @Test
    void anySolidVoxelTrueWhenAnyVoxelIsFull() {
        SectionPalette.Entry empty = new SectionPalette.Entry(
                VoxelShapeKind.EMPTY, List.of(), new int[6], 0.0, false, 0);
        SectionPalette.Entry full = new SectionPalette.Entry(
                VoxelShapeKind.FULL, List.of(), new int[6], 0.0, false, 0);
        byte[] paletteIndices = new byte[16 * 16 * 16]; // all index 0 (EMPTY)
        paletteIndices[4095] = 1; // exactly one voxel indexes the FULL entry
        assertEquals(true, BrickGridUpload.anySolidVoxel(paletteIndices, List.of(empty, full)),
                "a single solid voxel anywhere in the section must flip the summary true");
    }

    @Test
    void anySolidVoxelTrueWhenAnyVoxelIsPartial() {
        SectionPalette.Entry empty = new SectionPalette.Entry(
                VoxelShapeKind.EMPTY, List.of(), new int[6], 0.0, false, 0);
        VoxelShapeClassifier.PackedBox slabBox = new VoxelShapeClassifier.PackedBox(0, 0, 0, 16, 8, 16);
        SectionPalette.Entry partial = new SectionPalette.Entry(
                VoxelShapeKind.PARTIAL, List.of(slabBox), new int[6], 0.0, false, 0);
        byte[] paletteIndices = new byte[16 * 16 * 16];
        paletteIndices[0] = 1; // first voxel indexes the PARTIAL (partial-shape) entry
        assertEquals(true, BrickGridUpload.anySolidVoxel(paletteIndices, List.of(empty, partial)),
                "a PARTIAL (partial-shape) voxel counts as solid for the coarse summary, same as FULL");
    }

    @Test
    void summaryWordPacksBothBooleansIntoDistinctBits() {
        assertEquals(0, BrickGridUpload.summaryWord(false, false));
        assertEquals(1, BrickGridUpload.summaryWord(true, false), "bit 0 = any solid voxel");
        assertEquals(BrickGridUpload.SUMMARY_HAS_EMITTER, BrickGridUpload.summaryWord(false, true),
                "bit 1 = any emitter, independent of solidity (e.g. lava, an EMPTY-shape entry)");
        assertEquals(1 | BrickGridUpload.SUMMARY_HAS_EMITTER, BrickGridUpload.summaryWord(true, true),
                "both bits set together must not clobber each other");
    }

    @Test
    void summaryPendingSentinelNeverCollidesWithARealSummaryWord() {
        // SUMMARY_PENDING (livefix, window-edge flicker/light-leak) must be distinguishable from every
        // value summaryWord() can ever legitimately produce (bits 0-1 today, and any future low-bit
        // extension) so a consumer testing the sentinel bit specifically (celestial_shadow.fsh's
        // marchOcclusion, or the emitter-cost-reduction skips) can never mistake a real harvested value
        // for "pending".
        for (boolean solid : new boolean[] {false, true}) {
            for (boolean emitter : new boolean[] {false, true}) {
                assertEquals(0, BrickGridUpload.summaryWord(solid, emitter) & BrickGridUpload.SUMMARY_PENDING,
                        "a real summary word (solid=" + solid + ", emitter=" + emitter
                                + ") must never set the pending sentinel bit");
            }
        }
        assertTrue(BrickGridUpload.SUMMARY_PENDING != 0, "the sentinel itself must be nonzero -- "
                + "otherwise clearOccupancySlots writing it would be indistinguishable from a "
                + "genuinely-empty summaryWord(false, false), reproducing the exact bug this constant fixes");
    }

    // --- Emitter-cost-reduction milestone: anyEmitter (BRICK_SUMMARY_TARGET's SUMMARY_HAS_EMITTER bit) -

    @Test
    void anyEmitterFalseWhenNoEntryHasEmissiveStrength() {
        SectionPalette.Entry dark = new SectionPalette.Entry(
                VoxelShapeKind.FULL, List.of(), new int[6], 0.0, false, 0);
        assertEquals(false, BrickGridUpload.anyEmitter(List.of(dark)),
                "a section with no emissive palette entries must summarize as no emitter");
    }

    @Test
    void anyEmitterTrueWhenAnyEntryHasPositiveEmissiveStrength() {
        SectionPalette.Entry dark = new SectionPalette.Entry(
                VoxelShapeKind.FULL, List.of(), new int[6], 0.0, false, 0);
        SectionPalette.Entry glowing = new SectionPalette.Entry(
                VoxelShapeKind.FULL, List.of(), new int[6], 1.0, false, 0);
        assertEquals(true, BrickGridUpload.anyEmitter(List.of(dark, glowing)),
                "a single emissive palette entry anywhere in the section must flip the summary true");
    }

    @Test
    void anyEmitterIgnoresShapeKindAndOnlyLooksAtEmissiveStrength() {
        // Lava has EMPTY shape (never resolved as occupying geometry) but IS an emitter -- anyEmitter
        // must not require isOccupyingShape the way anySolidVoxel does.
        SectionPalette.Entry lavaLike = new SectionPalette.Entry(
                VoxelShapeKind.EMPTY, List.of(), new int[6], 1.0, false, 0);
        assertEquals(true, BrickGridUpload.anyEmitter(List.of(lavaLike)),
                "an EMPTY-shape but emissive entry (e.g. lava) must still count as an emitter");
    }

    @Test
    void anyEmitterFalseForEmptyEntryList() {
        assertEquals(false, BrickGridUpload.anyEmitter(List.of()));
    }

    @Test
    void anySolidVoxelTrueWhenAnyVoxelIsCross() {
        SectionPalette.Entry empty = new SectionPalette.Entry(
                VoxelShapeKind.EMPTY, List.of(), new int[6], 0.0, false, 0);
        VoxelShapeClassifier.PackedBox bbox = new VoxelShapeClassifier.PackedBox(2, 0, 2, 14, 10, 14);
        SectionPalette.Entry cross = new SectionPalette.Entry(
                VoxelShapeKind.CROSS, List.of(bbox), new int[6], 0.0, false, 0, true, new float[] {0f, 0f, 1f, 1f}, 0f);
        byte[] paletteIndices = new byte[16 * 16 * 16];
        paletteIndices[0] = 1; // first voxel indexes the CROSS entry
        assertEquals(true, BrickGridUpload.anySolidVoxel(paletteIndices, List.of(empty, cross)),
                "a CROSS (cutout/cross-shaped) voxel counts as solid for the coarse summary, same as FULL/PARTIAL -- "
                        + "otherwise the brick-summary-skip would silently make grass/leaves cast no shadow at all");
    }

    // --- Cutout/cross milestone: word-0 flags + UV-rect words 13/14 (no PALETTE_ENTRY_WORDS growth) --

    @Test
    void isOccupyingShapeCoversFullPartialAndCross() {
        assertTrue(BrickGridUpload.isOccupyingShape(VoxelShapeKind.FULL));
        assertTrue(BrickGridUpload.isOccupyingShape(VoxelShapeKind.PARTIAL));
        assertTrue(BrickGridUpload.isOccupyingShape(VoxelShapeKind.CROSS));
        assertEquals(false, BrickGridUpload.isOccupyingShape(VoxelShapeKind.EMPTY));
    }

    @Test
    void packPaletteFlagsWordPacksBoxCountAndBothFlagsWithoutCollision() {
        assertEquals(0, BrickGridUpload.packPaletteFlagsWord(0, false, false, 0.0f));
        assertEquals(1, BrickGridUpload.packPaletteFlagsWord(1, false, false, 0.0f));
        assertEquals(1 << 30, BrickGridUpload.packPaletteFlagsWord(0, true, false, 0.0f));
        assertEquals(1 << 31, BrickGridUpload.packPaletteFlagsWord(0, false, true, 0.0f));
        assertEquals((1 << 30) | (1 << 31) | 1, BrickGridUpload.packPaletteFlagsWord(1, true, true, 0.0f),
                "boxCount, cutout, and cross bits coexist in one word without clobbering each other");
    }

    // --- Volumetric foliage milestone: extinction packed into word 0 bits 4-11 ----------------------

    @Test
    void packPaletteFlagsWordPacksExtinctionWithoutDisturbingBoxCountOrFlags() {
        int word = BrickGridUpload.packPaletteFlagsWord(3, true, true, 1.0f);
        assertEquals(3, word & 0xF, "boxCount survives in bits 0-3");
        assertTrue((word & (1 << 30)) != 0, "cutout flag");
        assertTrue((word & (1 << 31)) != 0, "cross flag");
        // 1.0 / 2.0 * 255 = 127.5 -> 128
        assertEquals(128, (word >>> 4) & 0xFF, "extinction in bits 4-11");
    }

    @Test
    void packPaletteFlagsWordClampsExtinctionToTheScaleCeiling() {
        int word = BrickGridUpload.packPaletteFlagsWord(0, true, false, 99.0f);
        assertEquals(255, (word >>> 4) & 0xFF, "clamped, must not overflow into bit 12");
        assertEquals(0, (word >>> 12) & 0x3FFFF, "bits 12-29 stay clear");
    }

    @Test
    void packPaletteFlagsWordWritesZeroExtinctionForOrdinaryBlocks() {
        int word = BrickGridUpload.packPaletteFlagsWord(2, false, false, 0.0f);
        assertEquals(0, (word >>> 4) & 0xFF);
    }

    // --- Bits 12-29 are free (a SHAPE_TRUNCATED flag briefly lived at bit 12, light-leak fix S2
    // 2026-07-20, removed the same day once VoxelShapeClassifier started merging excess boxes instead
    // of dropping them -- see that class's ClassifiedShape doc for the full story) -----------------

    @Test
    void packPaletteFlagsWordKeepsBitsTwelveThroughTwentyNineClearRegardlessOfOtherFlags() {
        int word = BrickGridUpload.packPaletteFlagsWord(8, true, true, 2.0f);
        assertEquals(0, (word >>> 12) & 0x3FFFF, "bits 12-29 stay clear even with every other flag set");
    }

    @Test
    void packPaletteEntriesRoundTripsTheMeasuredExtinction() {
        // The authoritative acacia measurement (0.326762), through the full pack path.
        SectionPalette.Entry leaves = new SectionPalette.Entry(
                VoxelShapeKind.FULL, List.of(), new int[6], 0.0, false, 0,
                true, new float[] {0.1f, 0.2f, 0.3f, 0.4f}, 0.32676f);
        ByteBuffer buf = ByteBuffer.wrap(BrickGridUpload.packPaletteEntries(List.of(leaves)))
                .order(ByteOrder.LITTLE_ENDIAN);
        int quantized = (buf.getInt(0) >>> 4) & 0xFF;
        assertEquals(Math.round(0.32676f / 2.0f * 255.0f), quantized);
        // And it must survive the round trip within quantisation error.
        assertEquals(0.32676f, quantized / 255.0f * 2.0f, 0.01f);
    }

    @Test
    void packUvWordRoundTripsBothComponentsAsUnorm16() {
        int packed = BrickGridUpload.packUvWord(0.0f, 1.0f);
        assertEquals(0, packed >>> 16, "component a=0.0 -> high 16 bits zero");
        assertEquals(0xFFFF, packed & 0xFFFF, "component b=1.0 -> low 16 bits saturate to 0xFFFF");

        int mid = BrickGridUpload.packUvWord(0.5f, 0.25f);
        assertEquals(Math.round(0.5f * 65535.0f), mid >>> 16);
        assertEquals(Math.round(0.25f * 65535.0f), mid & 0xFFFF);
    }

    @Test
    void packPaletteEntriesReusesBoxSlotsSixAndSevenForCutoutUvRect() {
        // A CROSS entry: boxCount 1 (its own harvested bbox in box[0]/word 7), cutout=true. Words
        // 13/14 (box slots 6/7) must carry the packed UV rect, NOT zeroed/unused box words -- and word
        // 7 must still hold the real bbox untouched.
        VoxelShapeClassifier.PackedBox bbox = new VoxelShapeClassifier.PackedBox(2, 0, 2, 14, 10, 14);
        float[] uvRect = {0.1f, 0.2f, 0.3f, 0.4f};
        SectionPalette.Entry cross = new SectionPalette.Entry(
                VoxelShapeKind.CROSS, List.of(bbox), new int[6], 0.0, false, 0, true, uvRect, 0f);

        byte[] bytes = BrickGridUpload.packPaletteEntries(List.of(cross));
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        int word0 = buf.getInt(0);
        assertEquals(1, word0 & 0xF, "boxCount 1");
        assertTrue((word0 & (1 << 30)) != 0, "cutout flag set");
        assertTrue((word0 & (1 << 31)) != 0, "cross flag set (shapeKind == CROSS)");

        assertEquals(BrickGridUpload.packBox(bbox), buf.getInt(7 * 4), "word 7 (box slot 0) still the real bbox");
        assertEquals(BrickGridUpload.packUvWord(uvRect[0], uvRect[1]), buf.getInt(13 * 4), "word 13: u0,v0");
        assertEquals(BrickGridUpload.packUvWord(uvRect[2], uvRect[3]), buf.getInt(14 * 4), "word 14: u1,v1");
    }

    @Test
    void packPaletteEntriesLeavesBoxSlotsSixAndSevenAloneWhenNotCutout() {
        // A ordinary PARTIAL (non-cutout) entry with 8 real boxes must keep writing box[6]/box[7]
        // exactly as before -- the cutout-only reuse of those two words must never touch a normal
        // PARTIAL entry's real box data.
        List<VoxelShapeClassifier.PackedBox> boxes = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            boxes.add(new VoxelShapeClassifier.PackedBox(i, i, i, i + 1, i + 1, i + 1));
        }
        SectionPalette.Entry partial = new SectionPalette.Entry(
                VoxelShapeKind.PARTIAL, boxes, new int[6], 0.0, false, 0);

        byte[] bytes = BrickGridUpload.packPaletteEntries(List.of(partial));
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(BrickGridUpload.packBox(boxes.get(6)), buf.getInt(13 * 4), "word 13 stays box[6] when not cutout");
        assertEquals(BrickGridUpload.packBox(boxes.get(7)), buf.getInt(14 * 4), "word 14 stays box[7] when not cutout");
    }

    // --- Out-of-bounds write guard (OOB-write fix, 2026-07-21): fitsInBuffer -------------------------
    // The correctness core of the "Colored Light Reach shrink stale-slot" fix: every vkCmdUpdateBuffer
    // call site in BrickGridUpload runs a write's (offset, dataSize) through this pure function against
    // the LIVE buffer size before recording it, so a slot index computed against a since-shrunk window
    // is dropped instead of scribbling past the buffer's current end (Vulkan UB, silent adjacent-VRAM
    // corruption on MoltenVK). See fitsInBuffer's own doc for the full hazard writeup.

    @Test
    void fitsInBufferAcceptsARangeThatFitsExactlyToTheBufferEnd() {
        // offset + dataSize == bufferSize, no slack -- the boundary case, must fit.
        assertTrue(BrickGridUpload.fitsInBuffer(96, 4, 100), "a range ending EXACTLY at the buffer's end fits");
        assertTrue(BrickGridUpload.fitsInBuffer(0, 100, 100), "a range spanning the WHOLE buffer fits");
    }

    @Test
    void fitsInBufferRejectsARangeOneByteOverTheBufferEnd() {
        assertEquals(false, BrickGridUpload.fitsInBuffer(97, 4, 100),
                "offset 97 + size 4 = 101, one byte past a 100-byte buffer -- must not fit");
    }

    @Test
    void fitsInBufferRejectsANegativeOffset() {
        // A negative offset can never be a legitimate slot*stride computation, but the pure function
        // must not trust its caller -- see its own doc.
        assertEquals(false, BrickGridUpload.fitsInBuffer(-1, 4, 100));
        assertEquals(false, BrickGridUpload.fitsInBuffer(Long.MIN_VALUE, 4, 100));
    }

    @Test
    void fitsInBufferAcceptsZeroSizeAnywhereWithinTheBuffer() {
        // Nothing is actually written for a zero-size range, so it trivially "fits" as long as the
        // offset itself is in range -- none of this class's real callers ever pass 0 (every per-slot
        // stride is a fixed positive constant, and the palette path already skips a zero-length upload
        // before calling fitsInBuffer at all), but the pure function's own contract must still hold.
        assertTrue(BrickGridUpload.fitsInBuffer(0, 0, 100));
        assertTrue(BrickGridUpload.fitsInBuffer(100, 0, 100), "offset == bufferSize with zero size still fits");
    }

    @Test
    void fitsInBufferRejectsZeroSizePastTheBufferEnd() {
        assertEquals(false, BrickGridUpload.fitsInBuffer(101, 0, 100),
                "an offset already past the buffer's end never fits, regardless of size");
    }

    @Test
    void fitsInBufferRejectsAHugeOffsetFromAStaleLargerDiameter() {
        // The exact live bug: a slot index computed against an OLD, larger window diameter, written
        // after "Colored Light Reach" shrank the window and TargetRegistry.ensureBufferSize reallocated
        // every brick-grid buffer smaller. E.g. old diameter 25 (radius 12): slot near the far edge of
        // the occupancy grid (25^3 - 1 = 15624) * 512 bytes/slot = 7,999,488, computed while the buffer
        // was still that large; new diameter 9 (radius 4) shrinks occupancy to 9^3 * 512 = 373,248
        // bytes. The stale offset lands far past the new buffer's end.
        long staleOffset = 15624L * 512L;
        long shrunkBufferSize = 9L * 9L * 9L * 512L;
        assertEquals(false, BrickGridUpload.fitsInBuffer(staleOffset, 512, shrunkBufferSize),
                "a slot offset computed against the OLD larger diameter must be rejected against the "
                        + "NEW, already-shrunk buffer size");
    }

    @Test
    void fitsInBufferRejectsWhenDataSizeAloneExceedsTheBuffer() {
        assertEquals(false, BrickGridUpload.fitsInBuffer(0, 200, 100),
                "a write larger than the whole buffer never fits, even at offset 0");
    }

    @Test
    void fitsInBufferRejectsANegativeDataSize() {
        assertEquals(false, BrickGridUpload.fitsInBuffer(0, -1, 100));
    }

    // --- ensureAllocated tier/diameter guard-rail (final-review IMPORTANT 2) -----------------------
    // No live Vulkan device in this headless test JVM, so ensureBufferSize itself no-ops (see
    // TargetRegistryBufferTest's own doc for that contract) -- what IS pure JVM behavior, and what
    // these tests pin, is that ensureAllocated validates diameter against tier BEFORE ever reaching
    // the GPU-touching registry call.

    private static TargetRegistry newRegistry() {
        GraphSpec graph = new GraphSpec(new LinkedHashMap<>(), List.of());
        return TargetRegistry.create(graph, Map.of());
    }

    @Test
    void ensureAllocatedTierZeroAcceptsAnyDiameter() {
        // Pre-cascade behaviour, unchanged: VoxelDebugRaymarchPass legitimately passes a
        // render-distance-derived diameter (3..33) to tier 0, never VoxelCascade.TIER_DIAMETER (17).
        TargetRegistry registry = newRegistry();
        assertDoesNotThrow(() -> BrickGridUpload.ensureAllocated(registry, 9, 0));
        assertDoesNotThrow(() -> BrickGridUpload.ensureAllocated(registry, 33, 0));
    }

    @Test
    void ensureAllocatedCoarseTierRejectsANonSeventeenDiameter() {
        // Every coarse-tier memory figure in the cascade design assumes exactly TIER_DIAMETER (17) --
        // a caller passing anything else (e.g. a render-distance-derived diameter meant for tier 0)
        // must fail loudly, not silently allocate the wrong-sized buffer set.
        TargetRegistry registry = newRegistry();
        assertThrows(IllegalArgumentException.class,
                () -> BrickGridUpload.ensureAllocated(registry, 9, 1));
        assertThrows(IllegalArgumentException.class,
                () -> BrickGridUpload.ensureAllocated(registry, 33, 3));
    }

    @Test
    void ensureAllocatedCoarseTierAcceptsExactlyTierDiameter() {
        TargetRegistry registry = newRegistry();
        assertDoesNotThrow(
                () -> BrickGridUpload.ensureAllocated(registry, VoxelCascade.TIER_DIAMETER, 1));
        assertDoesNotThrow(
                () -> BrickGridUpload.ensureAllocated(registry, VoxelCascade.TIER_DIAMETER, 3));
    }
}
