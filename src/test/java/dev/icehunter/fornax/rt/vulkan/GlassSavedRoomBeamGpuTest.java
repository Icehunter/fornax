package dev.icehunter.fornax.rt.vulkan;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Executes the complete local photon/beam trace on saved block geometry and measured glass.
 * Inputs are offline geometry fixtures, not captured engine buffers. Atlas samples are uniform
 * measured values, emitting full-cube faces have unit white radiance, and the sun is disabled.
 * This measures transport coherence/cold GPU work; it does not measure gather or client FPS. */
class GlassSavedRoomBeamGpuTest {
    private static final Path ROOT=Path.of("../plague/tools/out/glass-runtime/saved-room-beam-probe").toAbsolutePath().normalize();
    @Test void savedRoomGeometryMeasuresFiniteBeamCoverageAndResidualEnergy() throws IOException {
        assumeTrue(Files.isRegularFile(ROOT.resolve("trace.spv")),"prepare_saved_room_beam_probe.py fixture absent");
        try(HeadlessVulkan vk=HeadlessVulkan.tryCreate()) {
            assumeTrue(vk!=null,"headless Vulkan unavailable");
            ByteBuffer spirv=MemoryUtil.memAlloc((int)Files.size(ROOT.resolve("trace.spv")));
            spirv.put(Files.readAllBytes(ROOT.resolve("trace.spv"))).flip();
            long queryPool;
            float timestampPeriod;
            try(MemoryStack stack=MemoryStack.stackPush()) {
                var properties=VkPhysicalDeviceProperties.calloc(stack);
                VK10.vkGetPhysicalDeviceProperties(vk.physical,properties);
                timestampPeriod=properties.limits().timestampPeriod();
                var info=VkQueryPoolCreateInfo.calloc(stack).sType$Default().queryType(VK10.VK_QUERY_TYPE_TIMESTAMP).queryCount(2);
                var out=stack.mallocLong(1);assertEquals(VK10.VK_SUCCESS,VK10.vkCreateQueryPool(vk.device,info,null,out));queryPool=out.get(0);
            }
            try {
                String[] names={"globals",null,"occupancy","payload","palette","summary","faces",null,null,null,null,"sources","cache",null,null,null};
                int[] types=new int[16];Arrays.fill(types,VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
                Object[] resources=new Object[16];
                for(int binding=0;binding<16;binding++) {
                    byte[] data=names[binding]==null?null:Files.readAllBytes(ROOT.resolve(names[binding]+".bin"));
                    long size=binding==13||binding==15?65536L*4*16:binding==14?65536L*13*16:data==null?16:data.length;
                    resources[binding]=vk.hostBuffer(data,size,VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
                }
                long setLayout=vk.descriptorSetLayout(types),layout=vk.pipelineLayout(setLayout,0);
                long pipeline=vk.computePipeline(vk.shaderModule(spirv),layout),set=vk.descriptorSet(setLayout,types,resources);
                var photons=(HeadlessVulkan.Buffer)resources[13];var beams=(HeadlessVulkan.Buffer)resources[14];
                var terminal=(HeadlessVulkan.Buffer)resources[15];
                var cache=((HeadlessVulkan.Buffer)resources[12]).mapped().order(ByteOrder.LITTLE_ENDIAN);
                var globals=((HeadlessVulkan.Buffer)resources[0]).mapped().order(ByteOrder.LITTLE_ENDIAN);
                StringBuilder report=new StringBuilder("device: ").append(vk.deviceName).append('\n');
                double cold=run(vk,pipeline,layout,set,queryPool,timestampPeriod);
                ByteBuffer p=photons.mapped().duplicate().order(ByteOrder.LITTLE_ENDIAN),b=beams.mapped().duplicate().order(ByteOrder.LITTLE_ENDIAN);
                ByteBuffer terminalView=terminal.mapped().duplicate().order(ByteOrder.LITTLE_ENDIAN);
                int active=0,finite=0,clear=0,residual=0;double allFlux=0,finiteFlux=0,residualFlux=0;
                Map<Integer,Integer> rejectionCounts=new TreeMap<>();
                int sources=cache.getInt(8),quota=32768/sources;
                int[] perSource=new int[sources],sourceFinite=new int[sources];double[] sourceFlux=new double[sources],sourceResidual=new double[sources];
                for(int i=0;i<32768;i++) {
                    if(p.getFloat(i*64+12)==0)continue;
                    active++;int source=Math.min(i/quota,sources-1);perSource[source]++;
                    double energy=0;
                    for(int channel=0;channel<3;channel++) {
                        float value=p.getFloat(i*64+16+channel*4);
                        assertTrue(Float.isFinite(value)&&value>=0,"finite nonnegative photon RGB");
                        float received=terminalView.getFloat(i*64+16+channel*4);
                        assertTrue(Float.isFinite(received)&&received>=0,"finite nonnegative terminal RGB");energy+=received;
                    }
                    allFlux+=energy;sourceFlux[source]+=energy;
                    float mode=b.getFloat(i*208+5*16+12);
                    if(mode>=0) {
                        finite++;sourceFinite[source]++;finiteFlux+=energy;if(mode>=1)clear++;
                        for(int word=0;word<13*4;word++)assertTrue(Float.isFinite(b.getFloat(i*208+word*4)),"finite beam word"+word);
                        assertTrue(b.getFloat(i*208+8)>=0&&b.getFloat(i*208+12)>=0,"nonnegative support extent");
                    } else {
                        residual++;residualFlux+=energy;sourceResidual[source]+=energy;
                        int reason=Math.round(b.getFloat(i*208+9*16+12))&65535;
                        rejectionCounts.merge(reason,1,Integer::sum);
                    }
                }
                report.append(String.format(Locale.ROOT,"coldGpuMs=%.4f sources=%d valid=%d finite=%d clear=%d residual=%d finiteCountShare=%.6f finiteTerminalFluxShare=%.6f residualTerminalFluxShare=%.6f terminalRGBsum=%.6f%n",
                        cold,sources,active,finite,clear,residual,(double)finite/Math.max(active,1),finiteFlux/Math.max(allFlux,1e-30),residualFlux/Math.max(allFlux,1e-30),allFlux));
                for(int i=0;i<sources;i++)report.append(String.format(Locale.ROOT,"source%d valid=%d finite=%d flux=%.6f residualFlux=%.6f%n",i,perSource[i],sourceFinite[i],sourceFlux[i],sourceResidual[i]));
                report.append("rejections=").append(rejectionCounts).append('\n');
                byte[] photonBytes=new byte[(int)photons.size()],beamBytes=new byte[(int)beams.size()];p.position(0);p.get(photonBytes);b.position(0);b.get(beamBytes);
                Files.write(ROOT.resolve("photons.bin"),photonBytes);Files.write(ROOT.resolve("beams.bin"),beamBytes);
                byte[] terminalBytes=new byte[(int)terminal.size()];terminal.mapped().duplicate().get(terminalBytes);
                Files.write(ROOT.resolve("terminal-photons.bin"),terminalBytes);
                long beforeP=checksum(photonBytes),beforeB=checksum(beamBytes);
                cache.putInt(4,0);globals.putFloat(16,765.875f).putFloat(20,72.0625f).putFloat(24,-848.125f);
                double warm=run(vk,pipeline,layout,set,queryPool,timestampPeriod);
                double[] steady=new double[16];
                for(int repeat=0;repeat<steady.length;repeat++)steady[repeat]=run(vk,pipeline,layout,set,queryPool,timestampPeriod);
                Arrays.sort(steady);
                p.position(0);p.get(photonBytes);b.position(0);b.get(beamBytes);
                assertEquals(beforeP,checksum(photonBytes),"camera motion keeps every cached photon byte");
                assertEquals(beforeB,checksum(beamBytes),"camera motion keeps every cached beam byte");
                report.append(String.format(Locale.ROOT,"firstWarmGpuMs=%.4f steadyWarmGpuMsMedian=%.4f steadyWarmGpuMsMin=%.4f repeats=%d; photon/beam bytes unchanged after fractional eye movement%n",warm,(steady[7]+steady[8])*.5,steady[0],steady.length));
                report.append("Limits: offline saved block geometry, uniform measured glass maps, unit-radiance full-cube emitters only. Not live harvested buffers, full material maps, gather, postprocessing or client FPS.\n");
                Files.writeString(ROOT.resolve("results.txt"),report);System.out.print(report);
                assertTrue(active>0,"saved room sends light through glass to opaque receivers");
                assertEquals(active,finite+residual,"every valid photon retains either finite support or its residual");
                assertTrue(finite>0,"at least some real saved paths must receive finite support");
            } finally {VK10.vkDestroyQueryPool(vk.device,queryPool,null);MemoryUtil.memFree(spirv);}
        }
    }
    private static long checksum(byte[] data){CRC32 crc=new CRC32();crc.update(data);return crc.getValue();}
    private static double run(HeadlessVulkan vk,long pipeline,long layout,long set,long pool,float period) {
        var command=vk.begin();VK10.vkCmdBindPipeline(command,VK10.VK_PIPELINE_BIND_POINT_COMPUTE,pipeline);
        VK10.vkCmdBindDescriptorSets(command,VK10.VK_PIPELINE_BIND_POINT_COMPUTE,layout,0,new long[]{set},null);
        VK10.vkCmdResetQueryPool(command,pool,0,2);
        VK10.vkCmdWriteTimestamp(command,VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,pool,0);
        VK10.vkCmdDispatch(command,65536/64,1,1);
        VK10.vkCmdWriteTimestamp(command,VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,pool,1);
        vk.shaderToHost(command);vk.submitAndWait(command);
        try(MemoryStack stack=MemoryStack.stackPush()) {
            var values=stack.mallocLong(2);assertEquals(VK10.VK_SUCCESS,VK10.vkGetQueryPoolResults(vk.device,pool,0,2,values,8,VK10.VK_QUERY_RESULT_64_BIT|VK10.VK_QUERY_RESULT_WAIT_BIT));
            return (values.get(1)-values.get(0))*period*1e-6;
        }
    }
}

