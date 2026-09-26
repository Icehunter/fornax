package dev.icehunter.fornax.rt.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtent3D;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A bare Vulkan device for tests that need a real GPU: one instance, one physical device (the first
 * discrete one, else the first of any kind), one compute-capable queue, one command pool. Prefers a
 * queue family without graphics, the family the engine's compute passes run on when a device offers
 * one. {@link #tryCreate()} returns {@code null} when there is no loader or no device, so a test
 * skips with {@code assumeTrue} rather than failing on a machine without a GPU.
 */
final class HeadlessVulkan implements AutoCloseable {
    final VkInstance instance;
    final VkPhysicalDevice physical;
    final VkDevice device;
    final VkQueue queue;
    final int queueFamily;
    final String deviceName;
    private final long commandPool;
    private final List<Runnable> cleanup = new ArrayList<>();

    record Buffer(long handle, long memory, long size, ByteBuffer mapped) { }

    record Image(long handle, long memory, long view, int width, int height, int format) { }

    private HeadlessVulkan(VkInstance instance, VkPhysicalDevice physical, VkDevice device, VkQueue queue,
                           int queueFamily, String deviceName, long commandPool) {
        this.instance = instance;
        this.physical = physical;
        this.device = device;
        this.queue = queue;
        this.queueFamily = queueFamily;
        this.deviceName = deviceName;
        this.commandPool = commandPool;
    }

    static HeadlessVulkan tryCreate() {
        try {
            return create();
        } catch (Throwable t) {
            System.err.println("[HeadlessVulkan] unavailable: " + t);
            return null;
        }
    }

    private static HeadlessVulkan create() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default()
                    .pApplicationName(stack.UTF8("fornax-headless")).apiVersion(VK12.VK_API_VERSION_1_2);
            VkInstanceCreateInfo ici = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app);
            PointerBuffer pInstance = stack.mallocPointer(1);
            check(VK10.vkCreateInstance(ici, null, pInstance), "vkCreateInstance");
            VkInstance instance = new VkInstance(pInstance.get(0), ici);

            IntBuffer count = stack.mallocInt(1);
            check(VK10.vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices");
            if (count.get(0) == 0) throw new IllegalStateException("no Vulkan physical device");
            PointerBuffer devices = stack.mallocPointer(count.get(0));
            check(VK10.vkEnumeratePhysicalDevices(instance, count, devices), "vkEnumeratePhysicalDevices");
            VkPhysicalDevice chosen = null;
            String chosenName = null;
            for (int i = 0; i < count.get(0); i++) {
                VkPhysicalDevice candidate = new VkPhysicalDevice(devices.get(i), instance);
                VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
                VK10.vkGetPhysicalDeviceProperties(candidate, props);
                if (chosen == null || props.deviceType() == VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
                    chosen = candidate;
                    chosenName = props.deviceNameString();
                    if (props.deviceType() == VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) break;
                }
            }

            VK10.vkGetPhysicalDeviceQueueFamilyProperties(chosen, count, null);
            VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(count.get(0), stack);
            VK10.vkGetPhysicalDeviceQueueFamilyProperties(chosen, count, families);
            int family = -1;
            for (int i = 0; i < families.capacity(); i++) {
                int flags = families.get(i).queueFlags();
                if ((flags & VK10.VK_QUEUE_COMPUTE_BIT) == 0) continue;
                boolean computeOnly = (flags & VK10.VK_QUEUE_GRAPHICS_BIT) == 0;
                if (family < 0 || computeOnly) family = i;
                if (computeOnly) break;
            }
            if (family < 0) throw new IllegalStateException("no compute queue family");

            VkDeviceQueueCreateInfo.Buffer qci = VkDeviceQueueCreateInfo.calloc(1, stack);
            qci.get(0).sType$Default().queueFamilyIndex(family).pQueuePriorities(stack.floats(1f));
            VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(qci);
            PointerBuffer pDevice = stack.mallocPointer(1);
            check(VK10.vkCreateDevice(chosen, dci, null, pDevice), "vkCreateDevice");
            VkDevice device = new VkDevice(pDevice.get(0), chosen, dci);
            PointerBuffer pQueue = stack.mallocPointer(1);
            VK10.vkGetDeviceQueue(device, family, 0, pQueue);
            VkQueue queue = new VkQueue(pQueue.get(0), device);

            VkCommandPoolCreateInfo pci = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(family);
            LongBuffer pPool = stack.mallocLong(1);
            check(VK10.vkCreateCommandPool(device, pci, null, pPool), "vkCreateCommandPool");
            return new HeadlessVulkan(instance, chosen, device, queue, family, chosenName, pPool.get(0));
        }
    }

    static void check(int result, String what) {
        if (result != VK10.VK_SUCCESS) throw new IllegalStateException(what + " failed: VkResult " + result);
    }

    private int memoryType(int typeBits, int required) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryProperties props = VkPhysicalDeviceMemoryProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceMemoryProperties(physical, props);
            for (int i = 0; i < props.memoryTypeCount(); i++) {
                if ((typeBits & (1 << i)) != 0 && (props.memoryTypes(i).propertyFlags() & required) == required) return i;
            }
            throw new IllegalStateException("no memory type for bits " + typeBits + " flags " + required);
        }
    }

    /** A host-visible, host-coherent buffer holding {@code data} (or zeros when null). */
    Buffer hostBuffer(byte[] data, long size, int usage) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bci = VkBufferCreateInfo.calloc(stack).sType$Default().size(size).usage(usage)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = stack.mallocLong(1);
            check(VK10.vkCreateBuffer(device, bci, null, pBuffer), "vkCreateBuffer");
            long buffer = pBuffer.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
            VK10.vkGetBufferMemoryRequirements(device, buffer, req);
            VkMemoryAllocateInfo mai = VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(req.size())
                    .memoryTypeIndex(memoryType(req.memoryTypeBits(),
                            VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
            LongBuffer pMemory = stack.mallocLong(1);
            check(VK10.vkAllocateMemory(device, mai, null, pMemory), "vkAllocateMemory");
            long memory = pMemory.get(0);
            check(VK10.vkBindBufferMemory(device, buffer, memory, 0), "vkBindBufferMemory");
            PointerBuffer pData = stack.mallocPointer(1);
            check(VK10.vkMapMemory(device, memory, 0, size, 0, pData), "vkMapMemory");
            ByteBuffer mapped = MemoryUtil.memByteBuffer(pData.get(0), (int) size);
            MemoryUtil.memSet(mapped, 0);
            if (data != null) mapped.duplicate().put(data, 0, (int) Math.min(data.length, size));
            cleanup.add(() -> {
                VK10.vkDestroyBuffer(device, buffer, null);
                VK10.vkFreeMemory(device, memory, null);
            });
            return new Buffer(buffer, memory, size, mapped);
        }
    }

    /** A device-local 2D storage image that can also be copied out. Layout is UNDEFINED until a barrier. */
    Image storageImage(int width, int height, int format) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo ici = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK10.VK_IMAGE_TYPE_2D).format(format)
                    .extent(VkExtent3D.calloc(stack).set(width, height, 1))
                    .mipLevels(1).arrayLayers(1).samples(VK10.VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
            LongBuffer pImage = stack.mallocLong(1);
            check(VK10.vkCreateImage(device, ici, null, pImage), "vkCreateImage");
            long image = pImage.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
            VK10.vkGetImageMemoryRequirements(device, image, req);
            VkMemoryAllocateInfo mai = VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(req.size())
                    .memoryTypeIndex(memoryType(req.memoryTypeBits(), VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            LongBuffer pMemory = stack.mallocLong(1);
            check(VK10.vkAllocateMemory(device, mai, null, pMemory), "vkAllocateMemory(image)");
            long memory = pMemory.get(0);
            check(VK10.vkBindImageMemory(device, image, memory, 0), "vkBindImageMemory");
            VkImageViewCreateInfo vci = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(image).viewType(VK10.VK_IMAGE_VIEW_TYPE_2D).format(format);
            vci.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);
            LongBuffer pView = stack.mallocLong(1);
            check(VK10.vkCreateImageView(device, vci, null, pView), "vkCreateImageView");
            long view = pView.get(0);
            cleanup.add(() -> {
                VK10.vkDestroyImageView(device, view, null);
                VK10.vkDestroyImage(device, image, null);
                VK10.vkFreeMemory(device, memory, null);
            });
            return new Image(image, memory, view, width, height, format);
        }
    }

    long shaderModule(ByteBuffer spirv) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkShaderModuleCreateInfo smci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
            LongBuffer pModule = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(device, smci, null, pModule), "vkCreateShaderModule");
            long module = pModule.get(0);
            cleanup.add(() -> VK10.vkDestroyShaderModule(device, module, null));
            return module;
        }
    }

    /** Descriptor set layout for {@code types} at bindings 0..n-1, all compute stage. */
    long descriptorSetLayout(int[] types) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(types.length, stack);
            for (int i = 0; i < types.length; i++) {
                bindings.get(i).binding(i).descriptorType(types[i]).descriptorCount(1)
                        .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            VkDescriptorSetLayoutCreateInfo ci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings);
            LongBuffer pLayout = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(device, ci, null, pLayout), "vkCreateDescriptorSetLayout");
            long layout = pLayout.get(0);
            cleanup.add(() -> VK10.vkDestroyDescriptorSetLayout(device, layout, null));
            return layout;
        }
    }

    long pipelineLayout(long setLayout, int pushBytes) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineLayoutCreateInfo ci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(setLayout));
            if (pushBytes > 0) {
                VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack);
                range.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(pushBytes);
                ci.pPushConstantRanges(range);
            }
            LongBuffer pLayout = stack.mallocLong(1);
            check(VK10.vkCreatePipelineLayout(device, ci, null, pLayout), "vkCreatePipelineLayout");
            long layout = pLayout.get(0);
            cleanup.add(() -> VK10.vkDestroyPipelineLayout(device, layout, null));
            return layout;
        }
    }

    long computePipeline(long module, long pipelineLayout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, stack);
            ci.get(0).sType$Default().stage(stage).layout(pipelineLayout);
            LongBuffer pPipeline = stack.mallocLong(1);
            check(VK10.vkCreateComputePipelines(device, 0, ci, null, pPipeline), "vkCreateComputePipelines");
            long pipeline = pPipeline.get(0);
            cleanup.add(() -> VK10.vkDestroyPipeline(device, pipeline, null));
            return pipeline;
        }
    }

    /** One descriptor set over {@code setLayout}; entries are Buffer or Image, in binding order. */
    long descriptorSet(long setLayout, int[] types, Object[] resources) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(types.length, stack);
            for (int i = 0; i < types.length; i++) sizes.get(i).type(types[i]).descriptorCount(1);
            VkDescriptorPoolCreateInfo pci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes);
            LongBuffer pPool = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorPool(device, pci, null, pPool), "vkCreateDescriptorPool");
            long pool = pPool.get(0);
            cleanup.add(() -> VK10.vkDestroyDescriptorPool(device, pool, null));
            VkDescriptorSetAllocateInfo ai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(setLayout));
            LongBuffer pSet = stack.mallocLong(1);
            check(VK10.vkAllocateDescriptorSets(device, ai, pSet), "vkAllocateDescriptorSets");
            long set = pSet.get(0);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(types.length, stack);
            for (int i = 0; i < types.length; i++) {
                VkWriteDescriptorSet w = writes.get(i).sType$Default().dstSet(set).dstBinding(i).dstArrayElement(0)
                        .descriptorType(types[i]).descriptorCount(1);
                if (resources[i] instanceof Buffer b) {
                    w.pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(b.handle()).offset(0).range(b.size()));
                } else if (resources[i] instanceof Image img) {
                    w.pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(img.view())
                            .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL));
                } else {
                    throw new IllegalArgumentException("binding " + i + ": " + resources[i]);
                }
            }
            VK10.vkUpdateDescriptorSets(device, writes, null);
            return set;
        }
    }

    VkCommandBuffer begin() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo ai = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(commandPool).level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            PointerBuffer pCmd = stack.mallocPointer(1);
            check(VK10.vkAllocateCommandBuffers(device, ai, pCmd), "vkAllocateCommandBuffers");
            VkCommandBuffer cmd = new VkCommandBuffer(pCmd.get(0), device);
            VkCommandBufferBeginInfo bi = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(VK10.vkBeginCommandBuffer(cmd, bi), "vkBeginCommandBuffer");
            return cmd;
        }
    }

    void submitAndWait(VkCommandBuffer cmd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            check(VK10.vkEndCommandBuffer(cmd), "vkEndCommandBuffer");
            VkFenceCreateInfo fci = VkFenceCreateInfo.calloc(stack).sType$Default();
            LongBuffer pFence = stack.mallocLong(1);
            check(VK10.vkCreateFence(device, fci, null, pFence), "vkCreateFence");
            long fence = pFence.get(0);
            try {
                VkSubmitInfo si = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(cmd));
                check(VK10.vkQueueSubmit(queue, si, fence), "vkQueueSubmit");
                check(VK10.vkWaitForFences(device, fence, true, 10_000_000_000L), "vkWaitForFences");
            } finally {
                VK10.vkDestroyFence(device, fence, null);
                VK10.vkFreeCommandBuffers(device, commandPool, cmd);
            }
        }
    }

    /** Moves a fresh storage image from UNDEFINED to GENERAL before any dispatch touches it. */
    void toGeneral(VkCommandBuffer cmd, Image image) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
            barrier.get(0).sType$Default().srcAccessMask(0)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(image.handle());
            barrier.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);
            VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, null, null, barrier);
        }
    }

    /** Makes shader writes visible to transfer, copies the whole image into {@code into}, then makes
     * the copy visible to the host. */
    void copyImageToBuffer(VkCommandBuffer cmd, Image image, Buffer into) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer memory = VkMemoryBarrier.calloc(1, stack);
            memory.get(0).sType$Default().srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT);
            VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, memory, null, null);
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
                    .imageExtent(VkExtent3D.calloc(stack).set(image.width(), image.height(), 1));
            region.get(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            VK10.vkCmdCopyImageToBuffer(cmd, image.handle(), VK10.VK_IMAGE_LAYOUT_GENERAL, into.handle(), region);
            VkMemoryBarrier.Buffer host = VkMemoryBarrier.calloc(1, stack);
            host.get(0).sType$Default().srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK10.VK_ACCESS_HOST_READ_BIT);
            VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_PIPELINE_STAGE_HOST_BIT,
                    0, host, null, null);
        }
    }

    /** Makes shader writes visible to host reads, for buffers the kernel wrote. */
    void shaderToHost(VkCommandBuffer cmd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer host = VkMemoryBarrier.calloc(1, stack);
            host.get(0).sType$Default().srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK10.VK_ACCESS_HOST_READ_BIT);
            VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_HOST_BIT,
                    0, host, null, null);
        }
    }

    @Override
    public void close() {
        VK10.vkDeviceWaitIdle(device);
        for (int i = cleanup.size() - 1; i >= 0; i--) cleanup.get(i).run();
        VK10.vkDestroyCommandPool(device, commandPool, null);
        VK10.vkDestroyDevice(device, null);
        VK10.vkDestroyInstance(instance, null);
    }
}
