package dev.icehunter.fornax.rt.vulkan;

import com.google.gson.JsonParser;
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
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Higher-count transport reference: fixed saved geometry, actual optics and exact source flux.
 * Two quadrature counts expose sampling error but retain the finite point-kernel bias. */
class GlassSavedRoomReferenceGpuTest {
    private static final Path ROOT=Path.of("../plague/tools/out/glass-runtime/saved-room-reference").toAbsolutePath().normalize();

    @Test void twoQuadratureCountsMeasureSavedSceneReceiverConvergence() throws IOException {
        assumeTrue(Files.isDirectory(ROOT.resolve("524288")),"prepare_saved_room_reference.py fixture absent");
        StringBuilder report=new StringBuilder();
        for(int count:new int[]{262144,524288}) {
            Path directory=ROOT.resolve(Integer.toString(count));
            var fixture=JsonParser.parseString(Files.readString(directory.resolve("reference.json"))).getAsJsonObject();
            for(var entry:fixture.getAsJsonObject("shaders").entrySet()) {
                var hashes=entry.getValue().getAsJsonObject();
                assertEquals(hashes.get("source_sha256").getAsString(),sha256(directory.resolve(entry.getKey()+".comp")));
                assertEquals(hashes.get("spirv_sha256").getAsString(),sha256(directory.resolve(entry.getKey()+".spv")));
            }
            try(HeadlessVulkan vk=HeadlessVulkan.tryCreate()) {
                assumeTrue(vk!=null,"headless Vulkan unavailable");
                report.append("device: ").append(vk.deviceName).append(" paths=").append(count).append('\n');
                trace(vk,directory,count,report);
                try(var probe=new GlassSavedRoomGatherGpuTest.Probe(vk,fixture.get("width").getAsInt(),
                        fixture.get("height").getAsInt(),directory,count)) {
                    for(boolean measured:new boolean[]{false,true}) {
                        String name=measured?"view0-measured-reference":"view0-flat-diffuse-reference";
                        var result=probe.run("view0",measured,false);
                        assertTrue(result.mean()>0,"reference illuminates saved receivers");
                        report.append(name).append(' ').append(result.summary()).append('\n');
                        GlassSavedRoomGatherGpuTest.save(directory,name,result.rgb(),probe.width,probe.height);
                        ByteBuffer rgb=ByteBuffer.allocate(result.rgb().length*4).order(ByteOrder.LITTLE_ENDIAN);
                        rgb.asFloatBuffer().put(result.rgb());Files.write(directory.resolve(name+".rgb.bin"),rgb.array());
                    }
                }
            }
            Files.writeString(ROOT.resolve("results.txt"),report);System.out.print(report);
        }
        report.append("Limits: preserved offline geometry and optics, 262144/524288 local Halton paths with exact pi*A*Le normalization.\n")
                .append("Repeated measured texels and .25-block kernel; no POM, full material maps, sun, client frame or unbiased pointwise reference.\n");
        Files.writeString(ROOT.resolve("results.txt"),report);
    }

    private static void trace(HeadlessVulkan vk,Path directory,int count,StringBuilder report) throws IOException {
        byte[] shader=Files.readAllBytes(directory.resolve("trace.spv"));ByteBuffer spirv=MemoryUtil.memAlloc(shader.length);spirv.put(shader).flip();
        try {
            String[] names={"globals",null,"occupancy","payload","palette","summary","faces",null,null,null,null,"sources","cache",null,null,null};
            int[] types=new int[16];Arrays.fill(types,VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);Object[] buffers=new Object[16];
            for(int binding=0;binding<16;binding++) {
                byte[] bytes=names[binding]==null?null:Files.readAllBytes(directory.resolve(names[binding]+".bin"));
                long size=binding==13||binding==15?(long)count*64:binding==14?(long)count*208:bytes==null?16:bytes.length;
                buffers[binding]=vk.hostBuffer(bytes,size,VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            }
            long setLayout=vk.descriptorSetLayout(types),layout=vk.pipelineLayout(setLayout,0);
            long set=vk.descriptorSet(setLayout,types,buffers),pipeline=vk.computePipeline(vk.shaderModule(spirv),layout);
            var command=vk.begin();VK10.vkCmdBindPipeline(command,VK10.VK_PIPELINE_BIND_POINT_COMPUTE,pipeline);
            VK10.vkCmdBindDescriptorSets(command,VK10.VK_PIPELINE_BIND_POINT_COMPUTE,layout,0,new long[]{set},null);
            VK10.vkCmdDispatch(command,(count+63)/64,1,1);vk.shaderToHost(command);vk.submitAndWait(command);
            String[] outputs={"photons","beams","terminal-photons"};
            for(int binding=13;binding<16;binding++) {
                var buffer=(HeadlessVulkan.Buffer)buffers[binding];byte[] bytes=new byte[(int)buffer.size()];buffer.mapped().duplicate().get(bytes);
                Files.write(directory.resolve(outputs[binding-13]+".bin"),bytes);
            }
            ByteBuffer photons=((HeadlessVulkan.Buffer)buffers[13]).mapped().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            int active=0;double energy=0;
            for(int i=0;i<count;i++)if(photons.getFloat(i*64+12)!=0) {
                active++;for(int c=0;c<3;c++){float value=photons.getFloat(i*64+16+c*4);assertTrue(Float.isFinite(value)&&value>=0);energy+=value;}
            }
            assertTrue(active>0,"reference reaches receivers");
            report.append(String.format(Locale.ROOT,"valid=%d terminalRGBsum=%.9f%n",active,energy));
        } finally {MemoryUtil.memFree(spirv);}
    }
    private static String sha256(Path path)throws IOException {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
        catch(NoSuchAlgorithmException unavailable){throw new AssertionError(unavailable);}
    }
}
