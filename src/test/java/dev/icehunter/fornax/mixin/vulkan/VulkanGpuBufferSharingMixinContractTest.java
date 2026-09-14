package dev.icehunter.fornax.mixin.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Pins the real dependency allocator and the registered hook; does not apply mixins in a client. */
final class VulkanGpuBufferSharingMixinContractTest {
    private static final String DIRECT = "com/mojang/blaze3d/vulkan/VulkanGpuBuffer$Direct";
    private static final String DEVICE = "com/mojang/blaze3d/vulkan/VulkanDevice";
    private static final String CTOR = "(L" + DEVICE + ";Ljava/util/function/Supplier;IJZ)V";
    private static final String VMA_CALL = "Lorg/lwjgl/util/vma/Vma;vmaCreateBuffer(JLorg/lwjgl/vulkan/VkBufferCreateInfo;"
            + "Lorg/lwjgl/util/vma/VmaAllocationCreateInfo;Ljava/nio/LongBuffer;Lorg/lwjgl/PointerBuffer;"
            + "Lorg/lwjgl/util/vma/VmaAllocationInfo;)I";
    private static final Path MIXIN = Path.of("src/main/java/dev/icehunter/fornax/mixin/vulkan/VulkanGpuBufferSharingMixin.java");

    @Test
    void actualDeviceAllocationUsesDirectAndThatAllocatorStartsExclusiveBeforeSuperInitialization() throws IOException {
        MethodNode create = method(DEVICE, "createBuffer", "(Ljava/util/function/Supplier;IJ)Lcom/mojang/blaze3d/vulkan/VulkanGpuBuffer;");
        assertEquals(1, calls(create, DIRECT, "<init>").stream().filter(call -> call.desc.equals(CTOR)).count());
        MethodNode ctor = method(DIRECT, "<init>", CTOR);
        MethodInsnNode allocation = onlyCall(ctor, "org/lwjgl/util/vma/Vma", "vmaCreateBuffer");
        assertEquals(VMA_CALL, "L" + allocation.owner + ";" + allocation.name + allocation.desc);
        MethodInsnNode sharing = onlyCall(ctor, "org/lwjgl/vulkan/VkBufferCreateInfo", "sharingMode");
        MethodInsnNode families = onlyCall(ctor, "org/lwjgl/vulkan/VkBufferCreateInfo", "pQueueFamilyIndices");
        assertEquals(Opcodes.ICONST_0, sharing.getPrevious().getOpcode());
        assertEquals(Opcodes.ACONST_NULL, families.getPrevious().getOpcode());
        int infoSlot = assertInstanceOf(VarInsnNode.class, sharing.getPrevious().getPrevious()).var;
        assertEquals(infoSlot, assertInstanceOf(VarInsnNode.class, families.getPrevious().getPrevious()).var);
        // VMA arguments after info are allocationInfo, buffer, allocation, and optional allocation details.
        AbstractInsnNode actualInfo = allocation.getPrevious();
        for (int i = 0; i < 4; i++) actualInfo = actualInfo.getPrevious();
        assertEquals(Opcodes.ALOAD, actualInfo.getOpcode());
        assertEquals(infoSlot, assertInstanceOf(VarInsnNode.class, actualInfo).var);
        assertTrue(ctor.instructions.indexOf(sharing) < ctor.instructions.indexOf(allocation));
        assertTrue(ctor.instructions.indexOf(families) < ctor.instructions.indexOf(allocation));
        MethodInsnNode superInit = onlyCall(ctor, "com/mojang/blaze3d/vulkan/VulkanGpuBuffer", "<init>");
        assertTrue(ctor.instructions.indexOf(allocation) < ctor.instructions.indexOf(superInit),
                "the allocation hook runs before super: accessing this in its handler is invalid");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo original = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .usage(VK13.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT)
                    .sharingMode(sharing.getPrevious().getOpcode() - Opcodes.ICONST_0)
                    .pQueueFamilyIndices(null);
            assertEquals(VK13.VK_SHARING_MODE_EXCLUSIVE, original.sharingMode(),
                    "the dependency alone provides no cross-family uniform ownership");
            assertEquals(0, original.queueFamilyIndexCount());
        }
    }

    @Test
    void registeredStaticAllocationHookConnectsBothDeviceFamiliesToTheNativePolicy() throws IOException {
        assertTrue(Files.exists(MIXIN), "Direct allocations need a uniform-buffer sharing hook");
        String source = Files.readString(MIXIN);
        String registration = Files.readString(Path.of("src/main/resources/fornax.mixins.json"));
        assertTrue(connected(source, registration), "the real Direct allocator must invoke the sharing policy before VMA allocation");
    }

    @Test
    void sourceContractRejectsAnUnregisteredDisconnectedOrInstanceHook() throws IOException {
        assertTrue(Files.exists(MIXIN), "Direct allocations need a uniform-buffer sharing hook");
        String source = Files.readString(MIXIN);
        String registration = Files.readString(Path.of("src/main/resources/fornax.mixins.json"));
        assertTrue(connected(source, registration));
        assertFalse(connected(source, registration.replace("vulkan.VulkanGpuBufferSharingMixin", "")));
        assertFalse(connected(source.replace("device.computeQueue()", "device.graphicsQueue()"), registration));
        assertFalse(connected(source.replace("private static VkBufferCreateInfo", "private VkBufferCreateInfo"), registration));
        assertFalse(connected(source.replace("index = 1", "index = 0"), registration));
        assertFalse(connected(source.replace("return VulkanBufferSharing.configure(info, graphicsFamily, computeFamily);", "return info;"), registration));
        assertFalse(connected(source.replace("@Local(argsOnly = true)", "@Local"), registration));
    }

    private static boolean connected(String source, String registration) {
        String code = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\r\\n]*", "").replaceAll("\\s+", " ");
        return registration.contains("\"vulkan.VulkanGpuBufferSharingMixin\"")
                && code.contains("@Mixin(VulkanGpuBuffer.Direct.class)")
                && code.contains("@ModifyArg(method = \"<init>\", at = @At(value = \"INVOKE\",")
                && code.contains("target = \"" + VMA_CALL + "\",")
                && code.contains("remap = false), index = 1)")
                && code.contains("private static VkBufferCreateInfo fornax$shareUniformBufferAcrossQueues(")
                && code.contains("VkBufferCreateInfo info, @Local(argsOnly = true) VulkanDevice device)")
                && code.contains("int graphicsFamily = device.graphicsQueue().queueFamilyIndex();")
                && code.contains("int computeFamily = device.computeQueue().queueFamilyIndex();")
                && code.contains("return VulkanBufferSharing.configure(info, graphicsFamily, computeFamily);")
                && !code.contains("this") && !code.contains("isActive()");
    }

    private static MethodNode method(String owner, String name, String descriptor) throws IOException {
        try (InputStream input = VulkanGpuBufferSharingMixinContractTest.class.getClassLoader().getResourceAsStream(owner + ".class")) {
            assertNotNull(input, "actual dependency class must be on the test classpath: " + owner);
            ClassNode type = new ClassNode();
            new ClassReader(input).accept(type, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return type.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                    .findFirst().orElseThrow(() -> new AssertionError("missing actual allocation API: " + owner + name + descriptor));
        }
    }

    private static List<MethodInsnNode> calls(MethodNode method, String owner, String name) {
        return java.util.Arrays.stream(method.instructions.toArray()).filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast).filter(call -> call.owner.equals(owner) && call.name.equals(name)).toList();
    }

    private static MethodInsnNode onlyCall(MethodNode method, String owner, String name) {
        List<MethodInsnNode> calls = calls(method, owner, name);
        assertEquals(1, calls.size(), "allocation contract changed: " + owner + "." + name);
        return calls.getFirst();
    }
}
