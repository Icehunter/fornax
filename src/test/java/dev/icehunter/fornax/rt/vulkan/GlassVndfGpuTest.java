package dev.icehunter.fornax.rt.vulkan;

import com.google.gson.JsonParser;
import dev.icehunter.fornax.pass.compute.ComputeShaderCompiler;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Executes the live cap helper against a double-precision oracle whose moments and bins are
 * checked separately against the analytic GGX VNDF. This tests sampling, not image quality. */
class GlassVndfGpuTest {
    private static final Path PACK=Path.of("../plague").toAbsolutePath().normalize();
    private static final Path ROOT=PACK.resolve("tools/out/glass-runtime/vndf-probe");

    @Test void liveCapMapMatchesTheValidatedDistributionAndHasContinuousFixedSamples()throws IOException {
        assumeTrue(Files.isRegularFile(ROOT.resolve("fixture.json")),"verify_glass_vndf.py --write-gpu fixture absent");
        var fixture=JsonParser.parseString(Files.readString(ROOT.resolve("fixture.json"))).getAsJsonObject();
        byte[] source=Files.readAllBytes(PACK.resolve("shaders/include/glass_optics.glsl"));
        try {assertEquals(fixture.get("source_sha256").getAsString(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source)),"oracle must describe the current helper");}
        catch(NoSuchAlgorithmException unavailable){throw new AssertionError(unavailable);}
        int count=fixture.get("count").getAsInt();
        byte[] inputs=Files.readAllBytes(ROOT.resolve("inputs.bin"));
        var expected=ByteBuffer.wrap(Files.readAllBytes(ROOT.resolve("expected.bin"))).order(ByteOrder.LITTLE_ENDIAN);
        var values=ByteBuffer.wrap(inputs).order(ByteOrder.LITTLE_ENDIAN);
        String shader="#version 450\n"+new String(source,java.nio.charset.StandardCharsets.UTF_8)+"\n"+"""
                layout(local_size_x=64) in;
                layout(std430,binding=0) readonly buffer Inputs { vec4 inputWords[]; };
                layout(std430,binding=1) writeonly buffer Outputs { vec4 outputWords[]; };
                void main(){uint index=gl_GlobalInvocationID.x;if(index>=uint(outputWords.length()))return;
                    vec4 view=inputWords[index*2u];vec2 draw=inputWords[index*2u+1u].xy;
                    vec3 normal=plagueGlassCapNormal(view.xyz,view.w,draw);
                    outputWords[index]=vec4(normal,dot(normal,view.xyz));}
                """;
        try(HeadlessVulkan vk=HeadlessVulkan.tryCreate()) {
            assumeTrue(vk!=null,"headless Vulkan unavailable");
            ByteBuffer spirv=ComputeShaderCompiler.compileToSpirv(shader,"glass_vndf_probe.comp");
            try {
                int usage=VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
                var input=vk.hostBuffer(inputs,inputs.length,usage);var output=vk.hostBuffer(null,count*16L,usage);
                int[] types={VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER};
                long setLayout=vk.descriptorSetLayout(types),layout=vk.pipelineLayout(setLayout,0);
                long set=vk.descriptorSet(setLayout,types,new Object[]{input,output}),pipeline=vk.computePipeline(vk.shaderModule(spirv),layout);
                var command=vk.begin();VK10.vkCmdBindPipeline(command,VK10.VK_PIPELINE_BIND_POINT_COMPUTE,pipeline);
                VK10.vkCmdBindDescriptorSets(command,VK10.VK_PIPELINE_BIND_POINT_COMPUTE,layout,0,new long[]{set},null);
                VK10.vkCmdDispatch(command,(count+63)/64,1,1);vk.shaderToHost(command);vk.submitAndWait(command);
                var result=output.mapped().order(ByteOrder.LITTLE_ENDIAN);
                double maxError=0,maxEndpointError=0,minVisibility=1,maxLengthError=0;
                int oracleCount=fixture.get("oracle_count").getAsInt();
                // Regular draws use a32-ulp arithmetic budget scaled by the smallest roughness.
                // Near-antipodal RNG endpoints have ill-conditioned normalization; those retain
                // the unit/visibility requirements and report vector error separately.
                double errorBound=32*Math.ulp(1f)/fixture.get("minimum_alpha").getAsDouble();
                for(int i=0;i<count;i++) {
                    double square=0,error=0,visible=0;
                    for(int c=0;c<3;c++) {
                        float value=result.getFloat(i*16+c*4);assertTrue(Float.isFinite(value),"finite sample"+i);
                        square+=value*value;visible+=value*values.getFloat(i*32+c*4);
                        double difference=value-expected.getFloat(i*16+c*4);error+=difference*difference;
                    }
                    if(i<oracleCount)maxError=Math.max(maxError,Math.sqrt(error));
                    else maxEndpointError=Math.max(maxEndpointError,Math.sqrt(error));
                    minVisibility=Math.min(minVisibility,visible);
                    maxLengthError=Math.max(maxLengthError,Math.abs(Math.sqrt(square)-1));
                    assertTrue(result.getFloat(i*16+8)>=0,"upper-hemisphere normal"+i);
                }
                double maxJump=0;
                for(var entry:fixture.getAsJsonArray("continuity_starts")) {
                    int first=entry.getAsInt();double square=0;
                    for(int c=0;c<3;c++){double difference=result.getFloat(first*16+c*4)-result.getFloat((first+2)*16+c*4);square+=difference*difference;}
                    maxJump=Math.max(maxJump,Math.sqrt(square));
                }
                String report="device: "+vk.deviceName+"\nsamples="+count+" maxVectorError="+maxError+" arithmeticTolerance="+errorBound+" maxEndpointVectorError="+maxEndpointError+" minVisibility="+minVisibility+" maxLengthError="+maxLengthError+" fixedSampleNormalCrossing="+maxJump+"\nLimits: live GPU helper vs analytic-distribution-validated double oracle; endpoint vector agreement is diagnostic because of ill-conditioned normalization; no traversal or image quality.\n";
                Files.writeString(ROOT.resolve("gpu-results.txt"),report);System.out.print(report);
                assertTrue(minVisibility>=-8*Math.ulp(1f),"visible normals including exact24-bit RNG endpoints");
                assertTrue(maxLengthError<=8*Math.ulp(1f),"unit normals");
                assertTrue(maxError<=errorBound,"cap map agrees with validated oracle");
                assertTrue(maxJump<1e-5,"fixed samples remain continuous across normal incidence");
            }finally{MemoryUtil.memFree(spirv);}
        }
    }
}
