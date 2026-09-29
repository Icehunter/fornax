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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Executes live last-leg visibility helpers on controlled geometry; not a client voxel capture. */
class GlassBeamVisibilityGpuTest {
    private static final String[] NAMES={"clear", "thin-carpet-off-centre", "solid-blocker", "missing-exit",
            "compound-exit-hole", "intervening-glass", "unknown-section", "over-work-budget", "moved-camera",
            "degenerate-swept-line", "opposite-exit-face", "exit-footprint-outside-shape",
            "inside-equal-glass-seams", "source-contact-inside-glass", "source-contact-opaque-blocker",
            "inside-differing-ior", "source-contact-missing-source", "source-in-air", "source-wrong-kind"};

    @Test void fullSweptVolumeAndPerAngularQueryRespectOccludersAndExitSupport() throws IOException {
        Path pack=Path.of("../plague").toAbsolutePath().normalize();
        assumeTrue(Files.isRegularFile(pack.resolve("shaders/include/glass_beam_visibility.glsl")), "optional pack absent");
        try(HeadlessVulkan vk=HeadlessVulkan.tryCreate()) {
            assumeTrue(vk!=null,"headless Vulkan unavailable");
            ByteBuffer spirv=ComputeShaderCompiler.compileToSpirv(kernel(pack),"glass_beam_visibility_probe.comp");
            try {
                int[] types={VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER};
                var output=vk.hostBuffer(null,NAMES.length*32L,VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
                long setLayout=vk.descriptorSetLayout(types),layout=vk.pipelineLayout(setLayout,0);
                long pipeline=vk.computePipeline(vk.shaderModule(spirv),layout);
                long set=vk.descriptorSet(setLayout,types,new Object[]{output});
                var command=vk.begin();
                VK10.vkCmdBindPipeline(command,VK10.VK_PIPELINE_BIND_POINT_COMPUTE,pipeline);
                VK10.vkCmdBindDescriptorSets(command,VK10.VK_PIPELINE_BIND_POINT_COMPUTE,layout,0,new long[]{set},null);
                VK10.vkCmdDispatch(command,NAMES.length,1,1);
                vk.shaderToHost(command);vk.submitAndWait(command);
                ByteBuffer result=output.mapped().duplicate().order(ByteOrder.LITTLE_ENDIAN);
                // Certificate, centre visibility, interior query visibility, all four corners visible.
                float[][] expected={{1,1,1,1},{0,1,0,1},{0,0,0,0},{0,0,0,0},{0,1,0,1},{0,0,0,0},
                        {0,0,0,0},{0,1,1,1},{1,1,1,1},{0,1,0,1},{1,1,1,1},{1,1,1,1},
                        {0,1,1,1},{0,1,1,1},{0,0,0,0},{0,0,0,0},{0,0,0,0},{1,1,1,1},{0,0,0,0}};
                StringBuilder report=new StringBuilder("device: ").append(vk.deviceName).append('\n');
                for(int fixture=0;fixture<NAMES.length;fixture++) {
                    report.append(NAMES[fixture]);
                    for(int channel=0;channel<8;channel++) report.append(' ').append(result.getFloat(fixture*32+channel*4));
                    report.append('\n');
                }
                report.append("Columns: volume certificate, centre/interior/corners visibility; centre/outside support; centre/interior hybrid.\n")
                        .append("Limits: controlled model boxes/uniform material; no client scene, beam fit, gather energy or performance proof.\n");
                Files.writeString(pack.resolve("tools/out/glass-runtime/gpu-beam-visibility-results.txt"),report);
                System.out.print(report);
                for(int fixture=0;fixture<NAMES.length;fixture++) for(int channel=0;channel<4;channel++)
                    assertEquals(expected[fixture][channel],result.getFloat(fixture*32+channel*4),
                            NAMES[fixture]+" channel "+channel);
                for(int fixture=0;fixture<NAMES.length;fixture++) {
                    assertEquals(1f,result.getFloat(fixture*32+16),NAMES[fixture]+" supported centre");
                    assertEquals(0f,result.getFloat(fixture*32+20),NAMES[fixture]+" outside-exit domain rejects");
                    assertEquals(expected[fixture][1],result.getFloat(fixture*32+24),NAMES[fixture]+" hybrid centre");
                    assertEquals(expected[fixture][2],result.getFloat(fixture*32+28),NAMES[fixture]+" hybrid interior");
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
                vec3 u_CameraAbs;
                uint fixture;
                int entryAt(ivec3 p){
                    if(p.x!=8||p.y!=8)return -1;
                    if(fixture>=12u){
                        if(p.z==9)return 1;
                        if(p.z==4)return fixture==12u?0:fixture==16u?-1:1;
                        if(p.z>=5&&p.z<=8&&fixture<=16u){
                            if(p.z==6&&fixture==14u)return 1;
                            if(p.z==6&&fixture==15u)return 4;
                            return 0;
                        }
                        return -1;
                    }
                    if(fixture==10u){if(p.z==9)return 0;if(p.z==4)return 1;return -1;}
                    if(p.z==4)return fixture==3u?-1:fixture==4u?3:0;
                    if(p.z==9)return 1;
                    if(p.z==6){
                        if(fixture==1u||fixture==9u)return 2;
                        if(fixture==2u)return 1;
                        if(fixture==5u)return 0;
                    }
                    return -1;
                }
                ivec3 cellFor(int index){return ivec3(index&15,index>>8,(index>>4)&15);}
                uint plagueGlassOccupancyWord(int word){uint bits=0u;for(int i=0;i<32;i++)if(entryAt(cellFor(word*32+i))>=0)bits|=1u<<i;return bits;}
                uint plagueGlassPayloadWord(int word){uint bits=0u;for(int i=0;i<4;i++)bits|=uint(max(entryAt(cellFor(word*4+i)),0))<<(i*8);return bits;}
                uint boxWord(ivec3 lo,ivec3 hi){return uint(lo.x)|(uint(lo.y)<<5)|(uint(lo.z)<<10)|(uint(hi.x)<<15)|(uint(hi.y)<<20)|(uint(hi.z)<<25);}
                uint plagueGlassPaletteWord(int word){
                    int entry=word/16,offset=word%16;
                    if(offset==0)return entry==2?1u:entry==3?2u:0u;
                    if(offset>=1&&offset<=6)return 0xffffffffu;
                    // Carpet crosses only the interior of the swept beam; centre/corner rays miss it.
                    if(offset==7&&entry==2)return boxWord(ivec3(0,8,0),ivec3(16,9,16));
                    if(offset==7&&entry==3)return boxWord(ivec3(0),ivec3(16,7,16));
                    if(offset==8&&entry==3)return boxWord(ivec3(0,9,0),ivec3(16));
                    return 0u;
                }
                uint plagueGlassSummaryWord(int word){return fixture==6u?0x80000001u:1u;}
                uint plagueGlassFaceWord(int word){int entry=word/42,offset=word%7;
                    if(offset==0)return entry==0||entry==3||entry==4?0xe1ffffffu:0u;
                    return floatBitsToUint(offset==1&&entry==4?.75:offset==1||offset==2?.25:offset==3||offset==6?.125:0.0);
                }
                vec4 plagueGlassAlbedo(vec2 uv){return vec4(1.0);}
                vec4 plagueGlassMaterial(vec2 uv){return vec4(1.0,uv.x>.5?.09:.04,0.0,1.0);}
                // This controlled scene models the existing 96-entry synthetic descriptor table.
                int plagueGlassPaletteCapacity(){return 96;}
                bool plagueGlassBuffersValid(){return true;}
                """+flatten(pack,"glass_beam_visibility.glsl")+"""
                bool query(vec2 xy,vec4 plane,vec3 lo,vec3 hi,bool supportOnly){
                    bool reverse=fixture==10u;
                    vec3 normal=vec3(0,0,reverse?1:-1),incoming=normal;
                    vec3 point=vec3(xy,reverse?5:9)-u_CameraAbs;
                    vec3 start=plagueGlassBoundaryOffset(point,incoming,normal);
                    bool source=fixture>=13u&&fixture<=17u;
                    return supportOnly?plagueGlassBeamLastLegSupported(start,incoming,plane,lo,hi,-u_CameraAbs,source)
                        :plagueGlassBeamLastLegVisible(start,incoming,plane,lo,hi,-u_CameraAbs,source);
                }
                void main(){
                    fixture=gl_GlobalInvocationID.x;
                    u_CameraAbs=fixture==8u?vec3(1.375,-2.625,.875):vec3(0);
                    bool reverse=fixture==10u;
                    vec3 lo=vec3(8,8,reverse?9:4),hi=lo+vec3(1);
                    vec4 plane=reverse?vec4(0,0,-1,-9):vec4(0,0,1,5);
                    vec3 sweptLo=vec3(8.1,8.1,5),sweptHi=vec3(8.9,8.9,9);
                    vec3 receiver=vec3(8.5,8.5,reverse?5:9),receiverNormal=vec3(0,0,reverse?1:-1);
                    if(fixture==7u){sweptHi.z=200;receiver.z=200;}
                    if(fixture==9u){sweptLo.xy=vec2(8.5,8.53);sweptHi.xy=sweptLo.xy;}
                    if(fixture==11u){sweptLo.x=7.9;sweptHi.x=9.1;}
                    vec4 scenePlane=vec4(plane.xyz,plane.w-dot(plane.xyz,u_CameraAbs));
                    bool clear=plagueGlassBeamCertifyLastLeg(sweptLo-u_CameraAbs,sweptHi-u_CameraAbs,
                        scenePlane,lo-u_CameraAbs,hi-u_CameraAbs,receiver-u_CameraAbs,receiverNormal,
                        fixture>=13u&&fixture<=17u);
                    bool centre=query(vec2(8.5,8.2),plane,lo,hi,false),interior=query(vec2(8.5,8.53),plane,lo,hi,false);
                    bool corners=true;
                    for(int i=0;i<4;i++)corners=corners&&query(vec2((i&1)==0?8.1:8.9,i<2?8.1:8.9),plane,lo,hi,false);
                    results[fixture*2u]=vec4(clear?1:0,centre?1:0,interior?1:0,corners?1:0);
                    bool supported=query(vec2(8.5,8.2),plane,lo,hi,true),outside=query(vec2(7.9,8.2),plane,lo,hi,true);
                    bool hybridCentre=clear?supported:centre;
                    bool hybridInterior=clear?query(vec2(8.5,8.53),plane,lo,hi,true):interior;
                    results[fixture*2u+1u]=vec4(supported?1:0,outside?1:0,hybridCentre?1:0,hybridInterior?1:0);
                }
                """;
    }
}
