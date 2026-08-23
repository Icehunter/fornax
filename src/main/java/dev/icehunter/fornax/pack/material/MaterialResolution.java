package dev.icehunter.fornax.pack.material;

import dev.icehunter.fornax.FornaxMod;
import dev.icehunter.fornax.pack.PackModel;
import dev.icehunter.fornax.pack.graph.GraphRunner;
import dev.icehunter.fornax.pipeline.BlockClassResolver;
import dev.icehunter.fornax.pipeline.BlockClasses;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Rebuilds {@link BlockMaterials} from the active pack's blocks.toml categories against the live
 * BuiltInRegistries.BLOCK registry/tag surface. Called from {@link GraphRunner#rebuild} once a pack
 * (de)activates and again from {@code FornaxMod}'s {@code CommonLifecycleEvents.TAGS_LOADED}
 * listener -- direct block ids resolve on either pass, but tag ids only resolve once the current
 * world/datapack's tags are actually bound, which can happen after a pack already rebuilt.
 */
public final class MaterialResolution {
    private static final AtomicBoolean loggedTagsUnbound = new AtomicBoolean();

    private MaterialResolution() {}

    public static void refresh() {
        // Block CLASSES are an engine fact about vanilla's own tags rather than pack content, so
        // they are installed ahead of the pack check and survive a pack (de)activation. They belong
        // on this call and not on startup for exactly the reason the pack's own tag references do:
        // block tags are datapack content and are unbound at client init, so the TAGS_LOADED pass is
        // the one that actually resolves them.
        java.util.Map<Block, Integer> classes = BlockClassResolver.resolve(LOOKUP);
        BlockClasses.install(classes);
        // LOGGED AT INFO, and deliberately not only when it fails. This resolution has exactly two
        // outcomes that look identical from inside the game -- "no coal ore is tagged" and "the
        // pack-side gate never fired" -- and telling them apart cost a launch. A count of zero here
        // means the lane is dead and nothing pack-side can rescue it; the expected count (2: coal
        // ore and deepslate coal ore) means the flags DID arrive and any remaining complaint is
        // pack-side. Tags bind after client init, so this runs again on TAGS_LOADED and the second
        // line is the authoritative one.
        FornaxMod.LOGGER.info("[Fornax] Block classes resolved: {} blocks carry a class flag ({} coal)",
                classes.size(),
                classes.values().stream().filter(f -> (f & BlockClasses.COAL) != 0).count());

        PackModel pack = GraphRunner.currentPack();
        if (pack == null) {
            BlockMaterials.clear();
            MaterialScalarsHolder.install(MaterialScalars.build(List.of()));
            return;
        }
        BlockMaterials.install(BlockMaterialResolver.resolve(pack.blocks(), pack.categories(), LOOKUP));
        MaterialScalarsHolder.install(MaterialScalars.build(pack.categories().ordered()));
    }

    private static final BlockMaterialResolver.Lookup LOOKUP = new BlockMaterialResolver.Lookup() {
        @Override
        public Optional<Block> block(String id) {
            Identifier rid = Identifier.tryParse(id);
            if (rid == null) return Optional.empty();
            return BuiltInRegistries.BLOCK.getOptional(rid);
        }

        @Override
        public List<Block> tagMembers(String id) {
            Identifier rid = Identifier.tryParse(id);
            if (rid == null) return List.of();
            TagKey<Block> key = TagKey.create(Registries.BLOCK, rid);
            // Registry.getTag(TagKey) doesn't exist on the 26.2 jar's Registry interface --
            // getTagOrEmpty(TagKey) is the real accessor, returning an empty Iterable rather than
            // Optional.empty() for an absent tag (verified via javap against the client jar).
            return tagMembersTolerant(() -> BuiltInRegistries.BLOCK.getTagOrEmpty(key));
        }
    };

    /**
     * Collects a tag's members, tolerating the pre-bind registry state. On 26.2, MappedRegistry's
     * unbound TagSet throws {@code IllegalStateException("Tags not bound...")} from ANY tag access
     * -- {@code getTagOrEmpty} is only "empty" for a bound-but-absent tag, and the registry exposes
     * no public boundness probe ({@code MappedRegistry$TagSet.isBound()} is package-private behind
     * the private {@code allTags} field; javap-verified). The initial pack load runs at client init,
     * well before the first datapack tag bind, so the throw is expected there: return empty and let
     * {@code FornaxMod}'s TAGS_LOADED listener re-run this resolution (plus a terrain rebuild) once
     * tags actually bind. The catch is narrow -- only the tag access itself -- so any other failure
     * still propagates.
     */
    static List<Block> tagMembersTolerant(Supplier<Iterable<Holder<Block>>> tagAccess) {
        Iterable<Holder<Block>> holders;
        try {
            holders = tagAccess.get();
        } catch (IllegalStateException e) {
            if (loggedTagsUnbound.compareAndSet(false, true)) {
                FornaxMod.LOGGER.debug("[Fornax] tags not bound yet; resolved on tag load");
            }
            return List.of();
        }
        List<Block> members = new ArrayList<>();
        for (Holder<Block> holder : holders) {
            members.add(holder.value());
        }
        return members;
    }
}
