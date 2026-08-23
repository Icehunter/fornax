package dev.icehunter.fornax.mixin.vanilla;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.icehunter.fornax.atlas.LabPbrAtlasProvenance;
import net.minecraft.client.renderer.texture.atlas.sources.DirectoryLister;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Map;

/** Records the exact resource files enumerated by a directory source before sprite ids are derived. */
@Mixin(DirectoryLister.class)
public class DirectoryListerLabPbrProvenanceMixin {
    @ModifyVariable(method = "run", at = @At("HEAD"), argsOnly = true)
    private SpriteSource.Output fornax$trackDecodedContents(SpriteSource.Output output) {
        return LabPbrAtlasProvenance.trackingOutput(output);
    }

    @WrapOperation(
            method = "run",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/resources/FileToIdConverter;"
                            + "listMatchingResources(Lnet/minecraft/server/packs/resources/ResourceManager;)"
                            + "Ljava/util/Map;"))
    private Map<Identifier, Resource> fornax$rememberExactSources(
            FileToIdConverter converter,
            ResourceManager resourceManager,
            Operation<Map<Identifier, Resource>> original) {
        Map<Identifier, Resource> resources = original.call(converter, resourceManager);
        resources.forEach((resourceFile, resource) ->
                LabPbrAtlasProvenance.rememberSource(resource, resourceFile));
        return resources;
    }
}
