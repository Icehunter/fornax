package dev.icehunter.fornax.mixin.vulkan;

import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level and annotation-level: a mixin cannot be applied in a unit test. Pinned is the
 * injection point, which the whole mechanism rests on: {@code createDevice} receives the same live
 * extension set and feature set its caller built and iterates both into {@code VkDeviceCreateInfo}
 * inside the method, so a HEAD inject that mutates them lands before {@code vkCreateDevice} and
 * before LWJGL loads the device's per-extension entry points. A different target, or a different
 * point, silently enables nothing.
 */
class VulkanDeviceRayTracingMixinContractTest {

    private static final String CREATE_DEVICE =
            "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;"
                    + "Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;";

    private static Inject theInject() {
        return Arrays.stream(VulkanDeviceRayTracingMixin.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(Inject.class))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected one @Inject handler on the mixin"));
    }

    @Test
    void injectsAtTheHeadOfThePrivateStaticCreateDeviceOverload() {
        Inject inject = theInject();
        assertEquals(1, inject.method().length);
        assertEquals(CREATE_DEVICE, inject.method()[0],
                "must target the (Collection, VulkanPhysicalDevice, Set) overload, the one that receives the live sets");
        assertEquals(1, inject.at().length);
        At at = inject.at()[0];
        assertEquals("HEAD", at.value(), "the sets are consumed inside the method; only HEAD precedes that");
    }

    @Test
    void theHandlerIsStaticAndFornaxPrefixedLikeTheMetalOne() {
        Method handler = Arrays.stream(VulkanDeviceRayTracingMixin.class.getDeclaredMethods())
                .filter(method -> method.getAnnotation(Inject.class) != null)
                .findFirst().orElseThrow();
        assertTrue(java.lang.reflect.Modifier.isStatic(handler.getModifiers()),
                "a static target needs a static handler");
        assertTrue(handler.getName().startsWith("fornax$"), handler.getName());
    }

    @Test
    void isRegisteredInTheMixinConfig() throws IOException {
        String config = Files.readString(Path.of("src/main/resources/fornax.mixins.json"));
        assertTrue(config.contains("\"vulkan.VulkanDeviceRayTracingMixin\""),
                "an unregistered mixin is never applied and nothing reports it");
    }

    @Test
    void thePlatformGuardPrecedesEveryDeviceQuery() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/dev/icehunter/fornax/mixin/vulkan/VulkanDeviceRayTracingMixin.java"));
        int guard = source.indexOf("os.contains(\"mac\")");
        int extensions = source.indexOf("getMissingExtensions(");
        int features = source.indexOf("vkGetPhysicalDeviceFeatures2(");
        assertTrue(guard > 0, "the mixin must consult the platform");
        assertTrue(extensions > guard && features > guard,
                "on macOS Metal owns ray tracing; no Vulkan query may run there");
    }

    @Test
    void everyVerdictIsRecordedWhetherEnabledOrNot() throws IOException {
        // A refusal that is not recorded reads as "no Vulkan device has been created", which sends
        // a reader to the backend rather than the driver.
        String source = Files.readString(
                Path.of("src/main/java/dev/icehunter/fornax/mixin/vulkan/VulkanDeviceRayTracingMixin.java"));
        int decide = source.indexOf("VulkanRtRequirements.decide(");
        int record = source.indexOf("VulkanRtSupport.recordDeviceVerdict(");
        assertTrue(decide > 0 && record > decide, "the verdict is decided, then recorded");
    }
}
