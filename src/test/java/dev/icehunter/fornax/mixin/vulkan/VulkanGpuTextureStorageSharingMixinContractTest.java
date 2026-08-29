package dev.icehunter.fornax.mixin.vulkan;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the queue-family sharing prerequisite for graph storage-image synchronization. */
final class VulkanGpuTextureStorageSharingMixinContractTest {
    private static final Path MIXINS = Path.of("src/main/resources/fornax.mixins.json");
    private static final Path SOURCE = Path.of("src/main/java/dev/icehunter/fornax/mixin/vulkan/"
            + "VulkanGpuTextureStorageSharingMixin.java");
    private static final Path TARGET_REGISTRY = Path.of(
            "src/main/java/dev/icehunter/fornax/pack/graph/TargetRegistry.java");

    @Test
    void registeredMixinConnectsStorageUsageToBothQueueFamilies() throws IOException {
        String mixins = Files.readString(MIXINS);
        String source = Files.readString(SOURCE);
        String registry = Files.readString(TARGET_REGISTRY);

        assertTrue(validate(mixins, source, registry).isEmpty(),
                () -> String.join("; ", validate(mixins, source, registry)));

        String missingRegistration = mixins.replace(
                "\"vulkan.VulkanGpuTextureStorageSharingMixin\",", "");
        assertFalse(validate(missingRegistration, source, registry).isEmpty(),
                "removing the load-bearing mixin registration must fail the contract");

        String disconnectedComputeFamily = source.replace(
                "ints(graphicsFamily, computeFamily)", "ints(graphicsFamily, graphicsFamily)");
        assertFalse(validate(mixins, disconnectedComputeFamily, registry).isEmpty(),
                "disconnecting the compute-family index must fail the contract");

        String disconnectedStorageUsage = registry.replace(
                "if (storage) usage |= FornaxTextureUsage.STORAGE;",
                "if (storage) usage |= 0;");
        assertFalse(validate(mixins, source, disconnectedStorageUsage).isEmpty(),
                "disconnecting TargetRegistry's STORAGE bit must fail the contract");
    }

    private static List<String> validate(String mixins, String source, String registry) {
        List<String> errors = new ArrayList<>();
        require(errors, mixins.contains("\"vulkan.VulkanGpuTextureStorageSharingMixin\""),
                "storage-sharing mixin is not registered");
        require(errors, source.contains("@Mixin(VulkanGpuTexture.class)"),
                "mixin no longer targets VulkanGpuTexture");
        require(errors, Pattern.compile(
                "@ModifyArg\\s*\\(\\s*method\\s*=\\s*\\\"<init>\\\"\\s*,\\s*"
                        + "at\\s*=\\s*@At\\s*\\(\\s*value\\s*=\\s*\\\"INVOKE\\\"\\s*,.*?"
                        + "target\\s*=\\s*\\\"Lorg/lwjgl/util/vma/Vma;vmaCreateImage\\("
                        + "JLorg/lwjgl/vulkan/VkImageCreateInfo;"
                        + "Lorg/lwjgl/util/vma/VmaAllocationCreateInfo;"
                        + "Ljava/nio/LongBuffer;Lorg/lwjgl/PointerBuffer;"
                        + "Lorg/lwjgl/util/vma/VmaAllocationInfo;\\)I\\\".*?"
                        + "remap\\s*=\\s*false\\s*\\)\\s*,?\\s*index\\s*=\\s*1\\s*\\)",
                Pattern.DOTALL).matcher(source).find(),
                "constructor injection no longer modifies vmaCreateImage's VkImageCreateInfo argument");

        String body = functionBody(source, "fornax$shareStorageImageAcrossQueues");
        require(errors, body != null, "storage-sharing handler body is missing");
        if (body == null) {
            return errors;
        }
        require(errors, Pattern.compile(
                "if\\s*\\(.*?usage\\(\\)\\s*&\\s*FornaxTextureUsage\\.STORAGE.*?==\\s*0\\s*\\)"
                        + "\\s*\\{\\s*return\\s+info\\s*;\\s*\\}", Pattern.DOTALL)
                .matcher(body).find(), "non-storage images are not excluded by the STORAGE usage bit");
        require(errors, body.contains("int graphicsFamily = device.graphicsQueue().queueFamilyIndex();"),
                "graphics family is not read from the owning VulkanDevice");
        require(errors, body.contains("int computeFamily = device.computeQueue().queueFamilyIndex();"),
                "compute family is not read from the owning VulkanDevice");
        require(errors, Pattern.compile(
                "if\\s*\\(\\s*graphicsFamily\\s*!=\\s*computeFamily\\s*\\)\\s*\\{"
                        + ".*?info\\.sharingMode\\(VK13\\.VK_SHARING_MODE_CONCURRENT\\)"
                        + "\\s*\\.pQueueFamilyIndices\\(MemoryStack\\.stackGet\\(\\)"
                        + "\\.ints\\(graphicsFamily,\\s*computeFamily\\)\\)\\s*;.*?\\}",
                Pattern.DOTALL).matcher(body).find(),
                "distinct families are not connected to CONCURRENT sharing with both indices");

        String reconcile = functionBody(registry, "private void reconcile");
        require(errors, reconcile != null, "TargetRegistry.reconcile body is missing");
        if (reconcile != null) {
            require(errors, Pattern.compile(
                    "int\\s+usage\\s*=.*?;\\s*if\\s*\\(\\s*storage\\s*\\)\\s*"
                            + "usage\\s*\\|=\\s*FornaxTextureUsage\\.STORAGE\\s*;.*?"
                            + "device\\.createTexture\\([^;]*?usage\\s*,",
                    Pattern.DOTALL).matcher(reconcile).find(),
                    "TargetRegistry storage declarations do not propagate STORAGE into createTexture");
        }
        return errors;
    }

    private static String functionBody(String source, String functionName) {
        int name = source.indexOf(functionName + "(");
        if (name < 0) return null;
        int open = source.indexOf('{', name);
        if (open < 0) return null;
        int depth = 1;
        for (int i = open + 1; i < source.length(); i++) {
            if (source.charAt(i) == '{') depth++;
            if (source.charAt(i) == '}' && --depth == 0) return source.substring(open + 1, i);
        }
        return null;
    }

    private static void require(List<String> errors, boolean condition, String message) {
        if (!condition) errors.add(message);
    }
}
