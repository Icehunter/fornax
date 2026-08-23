package dev.icehunter.fornax.mixin.vanilla;

import dev.icehunter.fornax.pack.layout.RuntimeShaderPack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.world.flag.FeatureFlagSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Registers {@link RuntimeShaderPack}'s single long-lived instance as a real, always-present
 * {@code RepositorySource} in the client's {@code PackRepository} -- the wiring {@link
 * RuntimeShaderPack}'s own javadoc documents (fabric-api 0.152.1+26.2's only
 * public "programmatic resource pack" entry points are hard-wired to file-backed
 * {@code ModNioPackResources}; there is no public API for a non-file-backed {@code PackResources}).
 *
 * <p>{@code javap}-confirmed against the real, unobfuscated MC 26.2 client jar: {@code
 * Minecraft}'s sole constructor builds a {@code RepositorySource[]} inline (a {@code
 * ClientPackSource}, the downloaded-pack source, and a folder source) and passes it straight to
 * {@code new PackRepository(RepositorySource...)} -- the client's only real {@code PackRepository}
 * construction site. Widening that array by one entry here (the same {@code @ModifyArg}-on-a-
 * constructor-argument shape {@code UniformBufferManagerMixin} already uses elsewhere in this mod)
 * lands Fornax's synthetic pack in the repository from the very first {@code
 * PackRepository.reload()} onward, positioned {@code TOP} and {@code required} so it's always
 * selected and can never be dragged below/disabled from the resource-pack screen -- there is
 * nothing for a player to configure about it.
 */
@Mixin(net.minecraft.client.Minecraft.class)
public class MinecraftPackRepositoryMixin {
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/packs/repository/PackRepository;<init>([Lnet/minecraft/server/packs/repository/RepositorySource;)V"))
    private RepositorySource[] fornax$appendRuntimeShaderPackSource(RepositorySource[] original) {
        RepositorySource[] widened = Arrays.copyOf(original, original.length + 1);
        widened[original.length] = consumer -> consumer.accept(buildPack());
        return widened;
    }

    /**
     * Constructs the {@code Pack} entry directly with explicit {@link Pack.Metadata} rather than via
     * {@code Pack.readMetaAndCreate}: that factory reads the pack's {@code pack.mcmeta} metadata
     * section, and {@link RuntimeShaderPack#getMetadataSection} (a synthetic, in-memory pack with no
     * mcmeta) returns null for every section -- so {@code readMetaAndCreate} logged "Missing metadata
     * in pack fornax_runtime", returned null, and the null {@code Pack} handed to the {@code
     * RepositorySource} consumer crashed {@code PackRepository.discoverAvailable} at {@code
     * Pack.getId()}. There is nothing to read: this pack's metadata is a compile-time constant
     * ({@code COMPATIBLE}, no feature flags, no overlays), so it's supplied inline and the null path
     * ceases to exist.
     */
    private static Pack buildPack() {
        PackLocationInfo location = new PackLocationInfo(RuntimeShaderPack.NAMESPACE,
                Component.literal("Fornax Runtime Shaders"), PackSource.BUILT_IN, Optional.empty());
        Pack.ResourcesSupplier supplier = new Pack.ResourcesSupplier() {
            @Override
            public PackResources openPrimary(PackLocationInfo location) {
                return RuntimeShaderPack.getInstance();
            }

            @Override
            public PackResources openFull(PackLocationInfo location, Pack.Metadata metadata) {
                return RuntimeShaderPack.getInstance();
            }
        };
        Pack.Metadata metadata = new Pack.Metadata(Component.literal("Fornax shader pipeline (managed automatically)"),
                PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of());
        Pack pack = new Pack(location, supplier, metadata,
                new PackSelectionConfig(true, Pack.Position.TOP, true));

        // Hide it from the resource-pack selection UI entirely: fabric-resource-loader's
        // PackSelectionModelMixin drops any pack whose FabricPack.fabric$isHidden() is true from
        // both the Available and Selected lists, and isHidden() is defined as "has a non-default
        // parents predicate" (javap-verified against fabric-resource-loader-v1 2.0.13). An
        // always-true predicate marks this pack hidden while keeping it unconditionally enabled --
        // exactly right for an internal pipeline pack users must never manage by hand. Vanilla's
        // own required+fixedPosition flags (above) still force-select it regardless. Guarded
        // instanceof: if a future fabric-api drops this internal interface, the pack merely becomes
        // visible again (with the honest description above) instead of crashing.
        if (pack instanceof net.fabricmc.fabric.impl.resource.pack.FabricPack fabricPack) {
            fabricPack.fabric$setParentsPredicate(parents -> true);
        }
        return pack;
    }
}
