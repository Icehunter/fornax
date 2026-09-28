package dev.icehunter.fornax.rt.vulkan;

import dev.icehunter.fornax.pass.compute.ComputeShaderCompiler;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Executes the optional pack's projection and atomic index. Controlled support bounds test
 * capacity and candidate coverage; this does not establish beam transport or client frame time. */
class GlassBeamIndexGpuTest {
    private static final int PHOTONS=65536;
    private static final Path PACK=Path.of("../plague").toAbsolutePath().normalize();
    private static final String GLOBALS="""
            layout(std430,set=0,binding=2) readonly buffer Globals {
                mat4 u_ProjectionMatrix; mat4 u_ModelViewMatrix; vec4 fixtureCamera; ivec4 fixtureSize;
            };
            #define u_CameraAbs fixtureCamera.xyz
            #define textureSize(T,L) fixtureSize.xy
            """;

    @Test void everyPhotonFitsFourOwnedNodesAndQueriesRetainAllExactKeys() throws IOException {
        assumeTrue(Files.isRegularFile(PACK.resolve("shaders/include/glass_beam_index.glsl")),"optional pack absent");
        try(HeadlessVulkan vk=HeadlessVulkan.tryCreate()) {
            assumeTrue(vk!=null,"no headless Vulkan device");
            String source=Files.readString(PACK.resolve("shaders/compute/glass_photon_bin.comp"))
                    .replace("#moj_import <fornax:globals.glsl>",GLOBALS)
                    .replace("layout(set=0,binding=1) uniform sampler2D u_Depth;","");
            ByteBuffer spirv=ComputeShaderCompiler.compileToSpirv(flatten(source),"glass_beam_index_probe.comp");
            try {
                int usage=VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
                var photons=vk.hostBuffer(null,PHOTONS*64L,usage);
                var beams=vk.hostBuffer(null,PHOTONS*208L,usage);
                var heads=vk.hostBuffer(null,PHOTONS*4L,usage);
                var links=vk.hostBuffer(null,PHOTONS*64L,usage);
                var globals=vk.hostBuffer(null,160,usage);
                var cache=vk.hostBuffer(null,64,usage);
                var dummy=vk.hostBuffer(null,16,usage);
                int[] types=new int[7];Arrays.fill(types,VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
                long setLayout=vk.descriptorSetLayout(types),layout=vk.pipelineLayout(setLayout,0);
                long set=vk.descriptorSet(setLayout,types,new Object[]{photons,dummy,globals,cache,beams,heads,links});
                long pipeline=vk.computePipeline(vk.shaderModule(spirv),layout);
                ByteBuffer p=photons.mapped().order(ByteOrder.LITTLE_ENDIAN),b=beams.mapped().order(ByteOrder.LITTLE_ENDIAN);
                ByteBuffer g=globals.mapped().order(ByteOrder.LITTLE_ENDIAN);
                matrix(g,0,new Matrix4f());matrix(g,64,new Matrix4f());
                g.putInt(144,64).putInt(148,64);
                // The centre straddles X32/Y32; outward pixel-footprint rounding touches four
                // base tiles even for a support narrower than a pixel.
                for(int i=0;i<PHOTONS;i++) photon(p,b,i,0,0,.5f,.005f,.005f,false);
                run(vk,pipeline,layout,set);
                ByteBuffer h=heads.mapped().order(ByteOrder.LITTLE_ENDIAN),l=links.mapped().order(ByteOrder.LITTLE_ENDIAN);
                Set<Integer> linked=new HashSet<>();
                for(int bucket=0;bucket<PHOTONS;bucket++) {
                    int node=h.getInt(bucket*4),visits=0;
                    while(node!=0) {
                        assertTrue(node>0 && node<=PHOTONS*4,"node ownership range");
                        assertTrue(linked.add(node),"one node cannot belong to multiple lists or a cycle");
                        node=l.getInt((node-1)*16);
                        assertTrue(++visits<=PHOTONS*4,"all allocated nodes bound traversal");
                    }
                }
                assertEquals(PHOTONS*4,linked.size(),"all four nodes for every photon must be published");
                for(int[] pixel:new int[][]{{31,31},{32,31},{31,32},{32,32}}) {
                    Set<Integer> candidates=query(h,l,pixel[0],pixel[1],64,64);
                    assertEquals(PHOTONS,candidates.size(),"every overlapping photon survives full capacity");
                }
                assertEquals(0,query(h,l,0,0,64,64).size());

                MemoryUtil.memSet(p,0);MemoryUtil.memSet(b,0);MemoryUtil.memSet(h,0);
                matrix(g,0,new Matrix4f().perspective((float)Math.toRadians(70),3440f/1369,.05f,100f,true));
                g.putInt(144,3440).putInt(148,1369);
                photon(p,b,0,0,0,-3,.5f,.25f,false);
                photon(p,b,1,0,0,-3,100,100,false);
                photon(p,b,2,0,0,-.05f,.25f,.25f,false);
                // Turn this support plane vertical so it crosses the eye plane.
                p.putFloat(2*64+52,1).putFloat(2*64+56,0);
                volume(b,2,-.25f,-.001f,-.30f,.25f,.001f,.20f);
                photon(p,b,3,0,0,3,.25f,.25f,false);
                photon(p,b,4,100,0,-3,.25f,.25f,false);
                photon(p,b,5,0,0,-3,0,0,true);
                // The optical reference lies offscreen but its transported volume enters view.
                photon(p,b,6,100,0,-3,.1f,.1f,false);
                volume(b,6,-.1f,-.1f,-4,100.1f,.1f,-2);
                run(vk,pipeline,layout,set);
                Set<Integer> centre=query(h,l,1720,684,3440,1369);
                assertEquals(Set.of(0,1,2,5,6),centre,"finite/point/global and offscreen-reference candidates coexist without behind-eye/offscreen volumes");
                // The wide transported volume selects the global level; support tests in gather
                // reject its corner candidates, just as they reject the two global controls.
                assertEquals(Set.of(1,2,6),query(h,l,0,0,3440,1369));
                assertEquals(Set.of(1,2,6),query(h,l,3439,1368,3440,1369));
                Files.writeString(PACK.resolve("tools/out/glass-runtime/gpu-beam-index-results.txt"),
                        "device: "+vk.deviceName+"\n65536 photons publish all262144 nodes; every overlapping pixel finds65536 unique candidates.\n"
                                +"Odd3440x1369 projection, finite support, point residual, near-eye global, behind-eye and offscreen cases pass.\n"
                                +"Limits: actual bin shader and GPU atomic lists; CPU exact-key traversal; controlled bounds, not beam transport/client cost.\n");
            } finally {MemoryUtil.memFree(spirv);}
        }
    }

    private static void photon(ByteBuffer p,ByteBuffer b,int index,float x,float y,float z,float ex,float ey,boolean point) {
        int at=index*64;p.putFloat(at,x).putFloat(at+4,y).putFloat(at+8,z).putFloat(at+12,1);
        p.putFloat(at+56,1);b.putFloat(index*208+8,ex).putFloat(index*208+12,ey);
        b.putFloat(index*208+5*16+12,point?-1:1);
        volume(b,index,x-ex,y-ey,z-.001f,x+ex,y+ey,z+.001f);
    }
    private static void volume(ByteBuffer b,int index,float lx,float ly,float lz,float hx,float hy,float hz) {
        b.putFloat(index*208+9*16,lx).putFloat(index*208+9*16+4,ly).putFloat(index*208+9*16+8,lz);
        b.putFloat(index*208+10*16,hx).putFloat(index*208+10*16+4,hy).putFloat(index*208+10*16+8,hz);
    }
    private static void matrix(ByteBuffer out,int at,Matrix4f matrix) {
        float[] values=new float[16];matrix.get(values);
        for(int i=0;i<16;i++)out.putFloat(at+i*4,values[i]);
    }
    private static void run(HeadlessVulkan vk,long pipeline,long layout,long set) {
        var command=vk.begin();
        VK10.vkCmdBindPipeline(command,VK10.VK_PIPELINE_BIND_POINT_COMPUTE,pipeline);
        VK10.vkCmdBindDescriptorSets(command,VK10.VK_PIPELINE_BIND_POINT_COMPUTE,layout,0,new long[]{set},null);
        VK10.vkCmdDispatch(command,256,1,1);vk.shaderToHost(command);vk.submitAndWait(command);
    }
    private static Set<Integer> query(ByteBuffer heads,ByteBuffer links,int x,int y,int width,int height) {
        Set<Integer> result=new HashSet<>();int side=16,top=0;
        while(side<Math.max(width,height)){side<<=1;top++;}
        for(int level=0;level<=top;level++) {
            int cx=x/(16<<level),cy=y/(16<<level);
            int node=heads.getInt(bucket(cx,cy,level)*4),visits=0;
            while(node!=0) {
                assertTrue(node>0&&node<=PHOTONS*4);
                int at=(node-1)*16;
                if(links.getInt(at+4)==cx&&links.getInt(at+8)==cy&&links.getInt(at+12)==level)
                    assertTrue(result.add((node-1)/4),"one photon must occur once per pixel query");
                node=links.getInt(at);assertTrue(++visits<=PHOTONS*4);
            }
        }
        return result;
    }
    private static int hash(int state) {
        state=(state^61)^(state>>>16);state*=9;state^=state>>>4;state*=0x27d4eb2d;return state^(state>>>15);
    }
    private static int bucket(int x,int y,int level){return hash(hash(hash(x)^y)^level)&65535;}
    private static String flatten(String source)throws IOException {
        Matcher matcher=Pattern.compile("#moj_import <fornax_runtime:([^>]+)>").matcher(source);
        StringBuffer result=new StringBuffer();
        while(matcher.find())matcher.appendReplacement(result,Matcher.quoteReplacement(flatten(
                Files.readString(PACK.resolve("shaders/include/"+matcher.group(1))))));
        matcher.appendTail(result);return result.toString();
    }
}

