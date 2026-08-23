package dev.icehunter.fornax.atlas;

import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * A content-derived key for "would rebuilding this labPBR sidecar atlas produce different bytes
 * than what is already installed" -- covers every input {@link NormalMapAtlasReloadListener}/
 * {@link MaterialMapAtlasReloadListener} actually read to composite their output, so
 * {@link LabPbrAtlasPair#rebuild} can skip the expensive blit/extrude/mip-chain work (measured at
 * ~24s combined on the owner's 512x pack) whenever nothing relevant changed since the last build --
 * which is every F8 press that only edited a shader file, and every cold boot of an unchanged pack.
 *
 * <p>Deliberately NOT {@code Preparations.equals()} or {@code BlockAtlasPagedLayout.equals()}: both
 * fail across two reloads of an identical pack, because {@code TextureAtlasSprite}/
 * {@code BlockAtlasGhostSprite} have no value {@code equals()} (identity only), so a fresh stitch's
 * sprite map or ghost list compares unequal to an old one even when every field is the same. This
 * projects only the scalar fields that actually determine composited bytes, in an explicit
 * name-sorted order (a {@code Map}'s iteration order is not a stable contract to hash against).
 *
 * <p>Includes each sidecar's own content hash ({@link LabPbrSidecarSurvey.Entry#contentHash()}),
 * which is the one thing nothing else in this codebase can see: no mtime, no size, no digest is
 * tracked anywhere else for a labPBR sidecar file, so without it a texture re-exported in place at
 * the same resolution would be silently missed by every other signal available.
 */
public final class LabPbrAtlasFingerprint {
    private LabPbrAtlasFingerprint() {
    }

    /**
     * @param atlasLocation the vanilla atlas this sidecar mirrors (block atlas, banner patterns, ...)
     * @param preparations  vanilla's own stitch result -- only {@code width()}/{@code height()} and
     *                      the sprite set are read; {@code mipLevel()} is not, matching what the
     *                      builders themselves consume
     * @param survey        this lane's own {@code _n}/{@code _s} survey result
     * @param pagedLayout   the current paged-atlas layout, or {@code null} when the pack fits one
     *                      page -- {@link BlockAtlasPagedLayout#current()}
     */
    public static String compute(Identifier atlasLocation, SpriteLoader.Preparations preparations,
                                 LabPbrSidecarSurvey.Result survey,
                                 @Nullable BlockAtlasPagedLayout pagedLayout) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 unavailable", e);
        }

        put(digest, atlasLocation);
        put(digest, preparations.width());
        put(digest, preparations.height());

        // Name-sorted: preparations.regions()'s (and therefore survey.entries()'s) iteration order
        // is whatever the backing Map happens to give, not a contract -- two builds of the identical
        // sprite set must fingerprint identically regardless of that order.
        List<LabPbrSidecarSurvey.Entry> sorted = new ArrayList<>(survey.entries());
        sorted.sort(Comparator.comparing(entry -> entry.sprite().contents().name().toString()));

        for (LabPbrSidecarSurvey.Entry entry : sorted) {
            TextureAtlasSprite sprite = entry.sprite();
            put(digest, sprite.contents().name());
            put(digest, sprite.getX());
            put(digest, sprite.getY());
            put(digest, sprite.getU0());
            put(digest, sprite.getV0());
            put(digest, sprite.getU1());
            put(digest, sprite.getV1());
            put(digest, sprite.contents().width());
            put(digest, sprite.contents().height());
            if (sprite instanceof BlockAtlasGhostSprite ghost) {
                put(digest, "ghost");
                put(digest, ghost.overflowPage());
                put(digest, ghost.pageX());
                put(digest, ghost.pageY());
                put(digest, ghost.padding());
                put(digest, ghost.hasOverflowCopy());
            } else {
                put(digest, "plain");
            }
            put(digest, entry.width());
            put(digest, entry.height());
            byte[] hash = entry.contentHash();
            digest.update(hash != null ? hash : NO_SIDECAR_MARKER);
            digest.update((byte) 0);
        }

        if (pagedLayout != null) {
            put(digest, pagedLayout.canvasSize());
            put(digest, pagedLayout.mipLevel());
            put(digest, pagedLayout.overflowPageCount());
        } else {
            put(digest, "unpaged");
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    // A single reserved byte, distinct from any real 32-byte SHA-256 output, marking "this sprite
    // has no sidecar" -- keeps the digest input length-prefixed in effect (see the trailing
    // separator byte in the caller) so this can never collide with a genuine content hash.
    private static final byte[] NO_SIDECAR_MARKER = {0};

    // String.valueOf(...) plus a NUL separator for every field: simple, and the separator is what
    // stops adjacent fields from hashing ambiguously (e.g. int 12 then 3 must not equal int 1 then
    // 23).
    private static void put(MessageDigest digest, Object value) {
        digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
