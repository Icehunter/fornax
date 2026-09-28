package dev.icehunter.fornax.rt.vulkan;

import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs the current bin, gather, BRDF and last-leg visibility over the saved-room trace output.
 * Offline first-hit depth and repeated measured material texels do not reproduce the client
 * texture neighbourhood, POM, entities, presentation or full-frame timing. */
class GlassSavedRoomGatherGpuTest {
    private static final Path ROOT=Path.of("../plague/tools/out/glass-runtime/saved-room-beam-probe").toAbsolutePath().normalize();

    @Test void actualSavedPathsAreReconstructedWithCurrentMaterialsAndVisibility() throws IOException {
        assumeTrue(Files.isRegularFile(ROOT.resolve("gather-current.spv")),"prepare_saved_room_gather_probe.py fixture absent");
        assumeTrue(Files.isRegularFile(ROOT.resolve("photons.bin")),"saved-room trace GPU readback absent");
        var hashes=JsonParser.parseString(Files.readString(ROOT.resolve("gather-source-hashes.json"))).getAsJsonObject();
        for(var entry:hashes.entrySet())assertEquals(entry.getValue().getAsString(),sha256(
                Path.of("../plague").resolve(entry.getKey())),"prepared shader must match live source: "+entry.getKey());
        var fixture=JsonParser.parseString(Files.readString(ROOT.resolve("gather-fixture.json"))).getAsJsonObject();
        int width=fixture.get("width").getAsInt(),height=fixture.get("height").getAsInt();
        try(HeadlessVulkan vk=HeadlessVulkan.tryCreate()) {
            assumeTrue(vk!=null,"headless Vulkan unavailable");
            try(Probe probe=new Probe(vk,width,height)) {
                StringBuilder report=new StringBuilder("device: ").append(vk.deviceName).append('\n');
                for(String view:new String[]{"view0","view1"}) {
                    for(boolean measured:new boolean[]{false,true}) {
                        for(boolean point:new boolean[]{true,false}) {
                            String name=view+(measured?"-measured":"-flat-diffuse")+(point?"-point-residual":"-finite");
                            Result result=probe.run(view,measured,point);
                            report.append(name).append(' ').append(result.summary()).append('\n');
                            save(ROOT,name,result.rgb,width,height);
                            Files.writeString(ROOT.resolve("gather-results.txt"),report);
                            assertTrue(result.mean>0,"actual optical records must light some visible receivers: "+name);
                        }
                    }
                }
                report.append("Limits: actual production trace records from an offline saved-block fixture; full current bin/gather/BRDF/visibility.\n")
                        .append("320x184 analytic opaque first-hit depth; repeated sixteen measured texels per block; no POM, client Gbuffer or post stack.\n")
                        .append("Point comparison keeps the same photon flux and last-leg tests, replacing finite support only. GPU times include counters and exclude transport/client frame costs.\n");
                Files.writeString(ROOT.resolve("gather-results.txt"),report);System.out.print(report);
            }
        }
    }

    record Result(float[] rgb,double mean,double maximum,double p99,double dark,double candidates,
                          double finite,double residual,double specular,double binMs,double gatherMs) {
        String summary(){return String.format(Locale.ROOT,"mean=%.8f max=%.5f p99=%.6f dark=%.5f visits/pixel=%.2f finite/pixel=%.2f residual/pixel=%.2f specularQueries/pixel=%.2f binGpuMs=%.4f gatherGpuMs=%.4f",
                mean,maximum,p99,dark,candidates,finite,residual,specular,binMs,gatherMs);}
    }

    static final class Probe implements AutoCloseable {
        final HeadlessVulkan vk;final int width,height,pathCount;final Path directory;
        final HeadlessVulkan.Buffer globals,photons,beams,heads,links,cache,dummy;
        final HeadlessVulkan.Image depth,normal,albedo,material,local,sun,stats;
        final long binLayout,binSet,binPipeline,gatherLayout,gatherSet,gatherPipeline,queryPool;
        final ByteBuffer binSpirv,gatherSpirv;final float period;final byte[] originalBeams,originalPhotons,terminalPhotons;

        Probe(HeadlessVulkan vk,int width,int height)throws IOException {
            this(vk,width,height,ROOT,65536);
        }
        Probe(HeadlessVulkan vk,int width,int height,Path directory,int pathCount)throws IOException {
            this.vk=vk;this.width=width;this.height=height;this.directory=directory;this.pathCount=pathCount;
            int usage=VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
            globals=vk.hostBuffer(null,352,usage);photons=file(vk,"photons",usage);beams=file(vk,"beams",usage);
            originalBeams=Files.readAllBytes(directory.resolve("beams.bin"));cache=file(vk,"cache",usage);
            originalPhotons=Files.readAllBytes(directory.resolve("photons.bin"));
            terminalPhotons=Files.readAllBytes(directory.resolve("terminal-photons.bin"));
            heads=vk.hostBuffer(null,65536L*4,usage|VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            links=vk.hostBuffer(null,(long)pathCount*4*16,usage);dummy=vk.hostBuffer(null,16,usage);
            depth=image(vk,width,height);normal=image(vk,width,height);albedo=image(vk,width,height);material=image(vk,width,height);stats=image(vk,width,height);
            local=vk.storageImage(width,height,VK10.VK_FORMAT_R16G16B16A16_SFLOAT);sun=vk.storageImage(width,height,VK10.VK_FORMAT_R16G16B16A16_SFLOAT);
            var initialize=vk.begin();for(var image:new HeadlessVulkan.Image[]{depth,normal,albedo,material,stats,local,sun})initialize(initialize,image);vk.submitAndWait(initialize);
            int[] types=new int[21];Arrays.fill(types,VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
            Object[] resources=new Object[21];Arrays.fill(resources,dummy);
            resources[0]=globals;resources[5]=photons;resources[6]=heads;resources[7]=links;resources[16]=cache;resources[17]=beams;
            String[] voxel={"occupancy","payload","palette","summary","faces"};
            for(int i=0;i<voxel.length;i++)resources[8+i]=file(vk,voxel[i],usage);
            int[] bindings={2,3,4,15,18,19,20};var images=new HeadlessVulkan.Image[]{depth,normal,albedo,material,local,sun,stats};
            for(int i=0;i<bindings.length;i++){types[bindings[i]]=VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;resources[bindings[i]]=images[i];}
            long setLayout=vk.descriptorSetLayout(types);gatherLayout=vk.pipelineLayout(setLayout,0);gatherSet=vk.descriptorSet(setLayout,types,resources);
            gatherSpirv=spirv("gather-current");gatherPipeline=vk.computePipeline(vk.shaderModule(gatherSpirv),gatherLayout);
            int[] binTypes=new int[7];Arrays.fill(binTypes,VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
            setLayout=vk.descriptorSetLayout(binTypes);binLayout=vk.pipelineLayout(setLayout,0);
            binSet=vk.descriptorSet(setLayout,binTypes,new Object[]{photons,dummy,globals,cache,beams,heads,links});
            binSpirv=spirv("gather-bin");binPipeline=vk.computePipeline(vk.shaderModule(binSpirv),binLayout);
            try(MemoryStack stack=MemoryStack.stackPush()) {
                var properties=VkPhysicalDeviceProperties.calloc(stack);VK10.vkGetPhysicalDeviceProperties(vk.physical,properties);period=properties.limits().timestampPeriod();
                var info=VkQueryPoolCreateInfo.calloc(stack).sType$Default().queryType(VK10.VK_QUERY_TYPE_TIMESTAMP).queryCount(4);
                var out=stack.mallocLong(1);assertEquals(VK10.VK_SUCCESS,VK10.vkCreateQueryPool(vk.device,info,null,out));queryPool=out.get(0);
            }
        }

        Result run(String view,boolean measured,boolean point)throws IOException {
            globals.mapped().duplicate().put(Files.readAllBytes(directory.resolve(view+"-globals.bin")));
            // Finite records reference optical planes. The control must use the separately
            // captured terminal receiver records, rather than splatting reference coordinates.
            photons.mapped().duplicate().put(point?terminalPhotons:originalPhotons);
            ByteBuffer beam=beams.mapped().duplicate().order(ByteOrder.LITTLE_ENDIAN);beam.put(originalBeams);
            if(point)for(int i=0;i<pathCount;i++) {
                int plane=i*208+6*16;float length=0;
                for(int c=0;c<3;c++){float v=beam.getFloat(plane+c*4);length+=v*v;}
                beam.putFloat(i*208+5*16+12,length>0?-2:-1);
            }
            byte[] materialBytes=Files.readAllBytes(directory.resolve(view+"-material.bin"));
            if(!measured) {
                var values=ByteBuffer.wrap(materialBytes).order(ByteOrder.LITTLE_ENDIAN);
                for(int i=0;i<width*height;i++){values.putFloat(i*16,0);values.putFloat(i*16+4,10f/255);values.putFloat(i*16+8,0);}
            }
            var command=vk.begin();barrier(command,VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_ACCESS_SHADER_READ_BIT|VK10.VK_ACCESS_SHADER_WRITE_BIT,VK10.VK_ACCESS_TRANSFER_WRITE_BIT);
            upload(vk,command,depth,Files.readAllBytes(directory.resolve(view+"-depth.bin")));
            upload(vk,command,normal,Files.readAllBytes(directory.resolve(view+(measured?"-normal.bin":"-flat.bin"))));
            upload(vk,command,albedo,Files.readAllBytes(directory.resolve(view+"-albedo.bin")));upload(vk,command,material,materialBytes);
            VK10.vkCmdFillBuffer(command,heads.handle(),0,heads.size(),0);
            barrier(command,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK10.VK_ACCESS_TRANSFER_WRITE_BIT,VK10.VK_ACCESS_SHADER_READ_BIT|VK10.VK_ACCESS_SHADER_WRITE_BIT);
            VK10.vkCmdResetQueryPool(command,queryPool,0,4);VK10.vkCmdWriteTimestamp(command,VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,queryPool,0);
            dispatch(command,binPipeline,binLayout,binSet,(pathCount+255)/256,1);VK10.vkCmdWriteTimestamp(command,VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,queryPool,1);
            barrier(command,VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK10.VK_ACCESS_SHADER_WRITE_BIT,VK10.VK_ACCESS_SHADER_READ_BIT|VK10.VK_ACCESS_SHADER_WRITE_BIT);
            VK10.vkCmdWriteTimestamp(command,VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,queryPool,2);
            dispatch(command,gatherPipeline,gatherLayout,gatherSet,(width+7)/8,(height+7)/8);VK10.vkCmdWriteTimestamp(command,VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,queryPool,3);
            var light=vk.hostBuffer(null,(long)width*height*8,VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            var count=vk.hostBuffer(null,(long)width*height*16,VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            vk.copyImageToBuffer(command,local,light);vk.copyImageToBuffer(command,stats,count);vk.submitAndWait(command);
            double binMs,gatherMs;
            try(MemoryStack stack=MemoryStack.stackPush()) {
                var times=stack.mallocLong(4);assertEquals(VK10.VK_SUCCESS,VK10.vkGetQueryPoolResults(vk.device,queryPool,0,4,times,8,VK10.VK_QUERY_RESULT_64_BIT|VK10.VK_QUERY_RESULT_WAIT_BIT));
                binMs=(times.get(1)-times.get(0))*period*1e-6;gatherMs=(times.get(3)-times.get(2))*period*1e-6;
            }
            var data=light.mapped().order(ByteOrder.LITTLE_ENDIAN);var counters=count.mapped().order(ByteOrder.LITTLE_ENDIAN);
            float[] rgb=new float[width*height*3];double[] values=new double[width*height],sums=new double[4];double sum=0,maximum=0,dark=0;
            for(int i=0;i<values.length;i++) {
                for(int c=0;c<3;c++){float value=Float.float16ToFloat(data.getShort(i*8+c*2));assertTrue(Float.isFinite(value)&&value>=0,"finite nonnegative gather RGB");rgb[i*3+c]=value;values[i]+=value/3;maximum=Math.max(maximum,value);}
                sum+=values[i];if(values[i]==0)dark++;
                for(int c=0;c<4;c++)sums[c]+=counters.getFloat(i*16+c*4);
            }
            Arrays.sort(values);return new Result(rgb,sum/values.length,maximum,values[(int)(values.length*.99)],dark/values.length,
                    sums[0]/values.length,sums[1]/values.length,sums[2]/values.length,sums[3]/values.length,binMs,gatherMs);
        }
        @Override public void close(){VK10.vkDestroyQueryPool(vk.device,queryPool,null);MemoryUtil.memFree(binSpirv);MemoryUtil.memFree(gatherSpirv);}
        private ByteBuffer spirv(String name)throws IOException {byte[] bytes=Files.readAllBytes(directory.resolve(name+".spv"));var result=MemoryUtil.memAlloc(bytes.length);result.put(bytes).flip();return result;}
        private HeadlessVulkan.Buffer file(HeadlessVulkan vk,String name,int usage)throws IOException {byte[] data=Files.readAllBytes(directory.resolve(name+".bin"));return vk.hostBuffer(data,data.length,usage);}
    }

    private static ByteBuffer spirv(String name)throws IOException {byte[] bytes=Files.readAllBytes(ROOT.resolve(name+".spv"));var result=MemoryUtil.memAlloc(bytes.length);result.put(bytes).flip();return result;}
    private static String sha256(Path path)throws IOException {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
        catch(NoSuchAlgorithmException unavailable){throw new AssertionError(unavailable);}
    }
    private static HeadlessVulkan.Buffer file(HeadlessVulkan vk,String name,int usage)throws IOException {byte[] data=Files.readAllBytes(ROOT.resolve(name+".bin"));return vk.hostBuffer(data,data.length,usage);}
    private static HeadlessVulkan.Image image(HeadlessVulkan vk,int w,int h){return vk.storageImage(w,h,VK10.VK_FORMAT_R32G32B32A32_SFLOAT);}
    private static void dispatch(VkCommandBuffer command,long pipeline,long layout,long set,int x,int y) {VK10.vkCmdBindPipeline(command,VK10.VK_PIPELINE_BIND_POINT_COMPUTE,pipeline);VK10.vkCmdBindDescriptorSets(command,VK10.VK_PIPELINE_BIND_POINT_COMPUTE,layout,0,new long[]{set},null);VK10.vkCmdDispatch(command,x,y,1);}
    private static void initialize(VkCommandBuffer command,HeadlessVulkan.Image image) {
        try(MemoryStack stack=MemoryStack.stackPush()) {
            var barrier=VkImageMemoryBarrier.calloc(1,stack);barrier.get(0).sType$Default().oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).image(image.handle()).dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT|VK10.VK_ACCESS_SHADER_WRITE_BIT);
            barrier.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            VK10.vkCmdPipelineBarrier(command,VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT|VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,null,null,barrier);
        }
    }
    private static void upload(HeadlessVulkan vk,VkCommandBuffer command,HeadlessVulkan.Image image,byte[] values) {
        var buffer=vk.hostBuffer(values,values.length,VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        try(MemoryStack stack=MemoryStack.stackPush()) {var region=VkBufferImageCopy.calloc(1,stack);region.get(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);region.get(0).imageExtent().set(image.width(),image.height(),1);VK10.vkCmdCopyBufferToImage(command,buffer.handle(),image.handle(),VK10.VK_IMAGE_LAYOUT_GENERAL,region);}
    }
    private static void barrier(VkCommandBuffer command,int srcStage,int dstStage,int srcAccess,int dstAccess) {
        try(MemoryStack stack=MemoryStack.stackPush()) {var barrier=VkMemoryBarrier.calloc(1,stack);barrier.get(0).sType$Default().srcAccessMask(srcAccess).dstAccessMask(dstAccess);VK10.vkCmdPipelineBarrier(command,srcStage,dstStage,0,barrier,null,null);}
    }
    static void save(Path directory,String name,float[] rgb,int width,int height)throws IOException {
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){int packed=0;for(int c=0;c<3;c++){float value=rgb[(y*width+x)*3+c];int channel=Math.round((float)Math.pow(value/(1+value),1/2.2)*255);packed=(packed<<8)|Math.min(255,channel);}image.setRGB(x,height-1-y,packed);}
        ImageIO.write(image,"png",directory.resolve("gather-"+name+".png").toFile());
    }
}

