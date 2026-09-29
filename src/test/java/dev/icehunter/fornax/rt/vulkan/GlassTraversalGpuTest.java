package dev.icehunter.fornax.rt.vulkan;

import dev.icehunter.fornax.pass.compute.ComputeShaderCompiler;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs the pack's actual optical traversal on controlled voxel geometry. It does not establish
 * the client's harvested model, receiver gather, camera recovery or visual result. */
class GlassTraversalGpuTest {
    private static final String[] NAMES = {"cube-touching-solid", "cube-gap-solid", "glass-touching-carpet",
            "source-inside-carpet-before-glass", "pane-broad-side", "pane-connected-axis", "two-separated-cubes",
            "two-separated-panes", "saved-blue-pane-three-connections", "saved-magenta-pane-three-connections",
            "grazing-upper-face-TIR", "grazing-lower-face-TIR", "parallel-X-edge-entry", "parallel-Y-edge-entry"};
    @Test void thinOpaqueContactsAndCompoundPanesTerminateAtTheActualReceiver() throws IOException {
        Path pack=Path.of("../plague").toAbsolutePath().normalize();
        assumeTrue(Files.isRegularFile(pack.resolve("shaders/include/glass_scene.glsl")), "optional pack absent");
        try (HeadlessVulkan vk=HeadlessVulkan.tryCreate()) {
            assumeTrue(vk!=null, "headless Vulkan unavailable");
            ByteBuffer spirv=ComputeShaderCompiler.compileToSpirv(kernel(pack), "glass_traversal_probe.comp");
            try {
                int[] types={VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER};
                var output=vk.hostBuffer(null,NAMES.length*48L,VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
                long setLayout=vk.descriptorSetLayout(types),layout=vk.pipelineLayout(setLayout,0);
                long pipeline=vk.computePipeline(vk.shaderModule(spirv),layout);
                long set=vk.descriptorSet(setLayout,types,new Object[]{output});
                var command=vk.begin();
                VK10.vkCmdBindPipeline(command,VK10.VK_PIPELINE_BIND_POINT_COMPUTE,pipeline);
                VK10.vkCmdBindDescriptorSets(command,VK10.VK_PIPELINE_BIND_POINT_COMPUTE,layout,0,new long[]{set},null);
                VK10.vkCmdDispatch(command,NAMES.length,1,1);
                vk.shaderToHost(command);vk.submitAndWait(command);
                ByteBuffer result=output.mapped().duplicate().order(ByteOrder.LITTLE_ENDIAN);
                // Packed result: status/crossed/entry/firstDistance, receiver position, throughput.
                float[][] positions={{8.5f,8.5f,5},{8.5f,8.5f,6},{8.5f,9,4.5f},{8.5f,9.0625f,4.5f},
                        {9,8.5f,4.125f},{8.5f,8.5f,6},{8.5f,8.5f,8},{11,8.5f,4.125f},
                        {8.5f,8.5f,6},{9,8.5f,4.5f}};
                float[] firstDistances={1,1,.5f,-1,.9375f,1,1,.9375f,1.4375f,.9375f};
                StringBuilder report=new StringBuilder("device: ").append(vk.deviceName).append('\n');
                for(int fixture=0;fixture<NAMES.length;fixture++) {
                    int offset=fixture*48;
                    report.append(NAMES[fixture]).append(" status=").append(result.getFloat(offset))
                            .append(" crossed=").append(result.getFloat(offset+4))
                            .append(" entry=").append(result.getFloat(offset+8))
                            .append(" first=").append(result.getFloat(offset+12))
                            .append(" receiver=").append(result.getFloat(offset+16)).append(',')
                            .append(result.getFloat(offset+20)).append(',').append(result.getFloat(offset+24))
                            .append(" throughput=").append(result.getFloat(offset+32)).append('\n');
                }
                report.append("Limits: controlled geometry and uniform material; not a captured client voxel scene or receiver gather.\n");
                Files.writeString(pack.resolve("tools/out/glass-runtime/gpu-traversal-results.txt"),report);
                System.out.print(report);
                for(int fixture=0;fixture<NAMES.length;fixture++) {
                    int offset=fixture*48;
                    assertEquals(1f,result.getFloat(offset),NAMES[fixture]+" receiver status");
                    assertEquals(fixture==3?0f:1f,result.getFloat(offset+4),NAMES[fixture]+" crossed glass");
                    assertEquals(fixture==2||fixture==3?3f:1f,result.getFloat(offset+8),NAMES[fixture]+" receiver material");
                    assertEquals(fixture<10?firstDistances[fixture]:fixture<12?.01f:1f,result.getFloat(offset+12),1e-5,NAMES[fixture]+" first glass boundary");
                    if(fixture<10) {
                        for(int axis=0;axis<3;axis++) assertEquals(positions[fixture][axis],result.getFloat(offset+16+axis*4),
                                1e-5,NAMES[fixture]+" receiver axis "+axis); // Float32 coordinate arithmetic tolerance.
                    } else if(fixture<12) {
                        assertEquals(6f,result.getFloat(offset+24),1e-5,NAMES[fixture]+" wall depth");
                        assertTrue(result.getFloat(offset+20)>8f&&result.getFloat(offset+20)<9f,
                                NAMES[fixture]+" total internal reflection must keep this path below/above the respective surface");
                    } else {
                        float[] expected=fixture==12?new float[]{8,8.5f,6}:new float[]{8.5f,8,6};
                        for(int axis=0;axis<3;axis++) assertEquals(expected[axis],result.getFloat(offset+16+axis*4),
                                1e-5,NAMES[fixture]+" slab normal preserves receiver axis "+axis);
                    }
                }
            } finally {MemoryUtil.memFree(spirv);}
        }
    }

    private static String flatten(Path pack,String name)throws IOException {
        String source=Files.readString(pack.resolve("shaders/include/"+name));
        var matcher=Pattern.compile("(?m)^#moj_import <fornax_runtime:([^>]+)>\\s*$").matcher(source);
        StringBuilder out=new StringBuilder();
        while(matcher.find()) matcher.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(flatten(pack,matcher.group(1))));
        matcher.appendTail(out);return out.toString();
    }
    private static String kernel(Path pack)throws IOException {
        return """
                #version 450
                layout(local_size_x=1) in;
                layout(std430,set=0,binding=0) writeonly buffer Results {vec4 results[];};
                const ivec4 u_VoxelWindow=ivec4(0,0,0,1);
                const vec3 u_CameraAbs=vec3(0.0);
                uint fixture;
                int entryAt(ivec3 p) {
                    if(fixture>=10u){
                        if(p.x!=8)return -1;
                        if(p.y==8&&p.z==4)return 0;
                        if(p.z==6){if(p.y==8)return 1;if(p.y==7||p.y==9)return 6;}
                        return -1;
                    }
                    if(fixture==9u){
                        if(p.y!=8||p.z!=4)return -1;
                        if(p.x==8)return 5;
                        return p.x==9?1:-1;
                    }
                    if(fixture==2u||fixture==3u) {
                        if(p.x!=8||p.z!=4) return -1;
                        if(p.y==9) return 3;
                        if(fixture==2u&&p.y==8) return 0;
                        if(fixture==3u&&p.y==10) return 0;
                        if(p.y==11) return 1;
                        return -1;
                    }
                    if(fixture==4u||fixture==7u) {
                        if(p.y!=8||p.z!=4) return -1;
                        if(p.x==8||(fixture==7u&&p.x==10)) return 2;
                        if(p.x==(fixture==7u?11:9)) return 1;
                        return -1;
                    }
                    if(p.x!=8||p.y!=8) return -1;
                    if(p.z==4) return fixture==5u?2:fixture==8u?4:0;
                    if(fixture==6u&&p.z==6) return 0;
                    if(p.z==(fixture==0u?5:fixture==6u?8:6)) return 1;
                    return -1;
                }
                ivec3 cellFor(int index){return ivec3(index&15,index>>8,(index>>4)&15);}
                uint plagueGlassOccupancyWord(int word){uint bits=0u;for(int i=0;i<32;i++)if(entryAt(cellFor(word*32+i))>=0)bits|=1u<<i;return bits;}
                uint plagueGlassPayloadWord(int word){uint bits=0u;for(int i=0;i<4;i++)bits|=uint(max(entryAt(cellFor(word*4+i)),0))<<(i*8);return bits;}
                uint boxWord(ivec3 lo,ivec3 hi){return uint(lo.x)|(uint(lo.y)<<5)|(uint(lo.z)<<10)|(uint(hi.x)<<15)|(uint(hi.y)<<20)|(uint(hi.z)<<25);}
                uint plagueGlassPaletteWord(int word){
                    int entry=word/16,offset=word%16;
                    if(offset==0)return entry==2?5u:entry==3?1u:entry==4||entry==5?4u:0u;
                    if(offset>=1&&offset<=6)return 0xffffffffu;
                    if(offset>=7&&offset<=11&&entry==2){
                        ivec3 lo[5]=ivec3[5](ivec3(7,0,7),ivec3(7,0,0),ivec3(7,0,9),ivec3(0,0,7),ivec3(9,0,7));
                        ivec3 hi[5]=ivec3[5](ivec3(9,16,9),ivec3(9,16,7),ivec3(9,16,16),ivec3(7,16,9),ivec3(16,16,9));
                        return boxWord(lo[offset-7],hi[offset-7]);
                    }
                    if(offset>=7&&offset<=10&&(entry==4||entry==5)){
                        // Saved blue pane: west/east/south; magenta: north/east/south.
                        ivec3 lo[4]=ivec3[4](ivec3(7,0,7),ivec3(7,0,9),ivec3(9,0,7),entry==4?ivec3(0,0,7):ivec3(7,0,0));
                        ivec3 hi[4]=ivec3[4](ivec3(9,16,9),ivec3(9,16,16),ivec3(16,16,9),entry==4?ivec3(7,16,9):ivec3(9,16,7));
                        return boxWord(lo[offset-7],hi[offset-7]);
                    }
                    if(offset==7&&entry==3)return boxWord(ivec3(0),ivec3(16,1,16));
                    return 0u;
                }
                uint plagueGlassSummaryWord(int word){return 1u;}
                uint plagueGlassFaceWord(int word){int entry=word/42,offset=word%7;
                    if(offset==0)return entry==0||entry==2||entry==4||entry==5?0xe1ffffffu:0u;
                    return floatBitsToUint(offset==1||offset==2?.25:offset==3||offset==6?.125:0.0);
                }
                vec4 plagueGlassAlbedo(vec2 uv){return vec4(1.0);}
                vec4 plagueGlassMaterial(vec2 uv){return vec4(1.0,.04,0.0,1.0);}
                // This controlled scene models the existing 96-entry synthetic descriptor table.
                int plagueGlassPaletteCapacity(){return 96;}
                bool plagueGlassBuffersValid(){return true;}
                """+flatten(pack,"glass_scene.glsl")+"""
                void main(){
                    fixture=gl_GlobalInvocationID.x;
                    vec3 origin=vec3(8.5,8.5,3.0),direction=vec3(0,0,1);
                    if(fixture==2u){origin=vec3(8.5,8.5,4.5);direction=vec3(0,1,0);}
                    if(fixture==3u){origin=vec3(8.5,9.0+2.0*PLAGUE_GLASS_EPSILON,4.5);direction=vec3(0,1,0);}
                    if(fixture==4u||fixture==7u){origin=vec3(7.5,8.5,4.125);direction=vec3(1,0,0);}
                    if(fixture==9u){origin=vec3(7.5,8.5,4.5);direction=vec3(1,0,0);}
                    if(fixture==10u||fixture==11u){
                        // One float32 ULP inside the upper/lower surface; shallow entry direction
                        // refracts at the front, then must reflect internally at the horizontal face.
                        origin=vec3(8.5,fixture==10u?8.999999046325684:8.000000953674316,3.99);
                        direction=normalize(vec3(0,fixture==10u?.00001:-.00001,1));
                    }
                    if(fixture==12u)origin.x=8.0;
                    if(fixture==13u)origin.y=8.0;
                    uint state=1u;vec3 energy=vec3(1);PlagueGlassHit hit;bool crossed;float first;
                    int status=plagueGlassTransport(origin,direction,energy,12.0,false,state,hit,crossed,first);
                    results[fixture*3u]=vec4(float(status),crossed?1.0:0.0,float(hit.entry),first);
                    results[fixture*3u+1u]=vec4(hit.position,0.0);
                    results[fixture*3u+2u]=vec4(energy,0.0);
                }
                """;
    }
}
