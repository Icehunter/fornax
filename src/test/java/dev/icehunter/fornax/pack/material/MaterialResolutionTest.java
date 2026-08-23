package dev.icehunter.fornax.pack.material;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pre-bind tag tolerance seam: MC 26.2's MappedRegistry throws IllegalStateException on ANY tag
 * access before datapack tags bind (getTagOrEmpty is only "empty" for a bound-but-absent tag), and
 * the initial pack load runs at client init, before that bind. The lookup must swallow exactly that
 * state -- empty members, no crash -- and resolve normally once the access stops throwing (the
 * TAGS_LOADED re-refresh).
 */
class MaterialResolutionTest {
    @BeforeAll static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void unboundTagAccessResolvesEmptyInsteadOfCrashing() {
        List<Block> members = assertDoesNotThrow(() -> MaterialResolution.tagMembersTolerant(() -> {
            throw new IllegalStateException("Tags not bound, trying to access TagKey[minecraft:block / c:storage_blocks/iron]");
        }));
        assertTrue(members.isEmpty());

        // Repeat access in the unbound state stays tolerant (the one-time log is noise control,
        // not a latch that changes behavior).
        assertTrue(MaterialResolution.tagMembersTolerant(() -> {
            throw new IllegalStateException("Tags not bound");
        }).isEmpty());
    }

    @Test
    void boundTagAccessResolvesMembersAfterEarlierUnboundPass() {
        // Same lookup, unbound first...
        assertTrue(MaterialResolution.tagMembersTolerant(() -> {
            throw new IllegalStateException("Tags not bound");
        }).isEmpty());

        // ...then bound (TAGS_LOADED fired): members come through, nothing sticky about the
        // earlier tolerated state.
        List<Block> members = MaterialResolution.tagMembersTolerant(
                () -> List.of(Holder.direct(Blocks.IRON_BLOCK), Holder.direct(Blocks.GOLD_BLOCK)));
        assertEquals(List.of(Blocks.IRON_BLOCK, Blocks.GOLD_BLOCK), members);
    }

    @Test
    void onlyIllegalStateIsTolerated() {
        // The catch is narrow by design -- any other failure mode must still propagate.
        assertThrows(RuntimeException.class, () -> MaterialResolution.tagMembersTolerant(() -> {
            throw new RuntimeException("not the unbound state");
        }));
    }
}
