package dev.icehunter.fornax.metalfx.rt;

import dev.icehunter.fornax.metalfx.objc.Objc;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Real Metal output checks. They do not establish Vulkan upload lifetime or client frame time. */
class MeshShadowTracerTest {
    private static final String TYPE = "dev.icehunter.fornax.metalfx.rt.MeshShadowTracer";
    // Identity light projection: the clip near/far planes are z=0 and z=1, so plane z is depth.
    private static final float[] IDENTITY = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    // Rotate the light 180 degrees around Y: world z=1 is near and z=0 is far. This
    // self-inverse rigid projection also flips X, so asymmetric atlas controls swap screen sides.
    private static final float[] REVERSE_LIGHT = {-1,0,0,0, 0,1,0,0, 0,0,-1,0, 0,0,1,1};


    @Test
    void uploadedWindingKeepsBothFacesAndPreservesNearestDepth() throws Exception {
        try (Fixture f = new Fixture()) {
            long front = f.quad(0.25f, false);
            long back = f.quad(0.125f, true);
            Object first = f.mesh(0, 1, front, 0, 0, 0);
            Object reverse = f.mesh(1, 1, back, 0, 0, 0);
            f.trace(List.of(first, reverse));
            f.assertPixel(1, 1, 0.125f); // Nearest opaque face wins regardless of its winding.
            f.assertPixel(0, 0, 1f); // Quad spans [-.75,.75], corner ray at -.875 misses.
            f.trace(List.of(reverse));
            f.assertPixel(3, 3, 0.125f);
        }
    }

    @Test
    void twoSidedRasterContractOpaqueQuadOccludesFromEitherLightDirection() throws Exception {
        try (Fixture f = new Fixture()) {
            Object quad=f.mesh(0,1,f.quad(.25f,false),0,0,0,false);
            f.trace(List.of(quad));
            float forward=f.pixels[(3*8+3)*4], forwardValid=f.pixels[(3*8+3)*4+3];
            f.traceWithRadius(List.of(quad),REVERSE_LIGHT,REVERSE_LIGHT,2f);
            float reverse=f.pixels[(3*8+3)*4], reverseValid=f.pixels[(3*8+3)*4+3];
            // Raster's registered shadow pipeline uses cull(false): the opaque plane is a
            // blocker from either side. Forward depth is z; reverse depth is 1-z.
            System.out.println("two-sided opaque: forward="+forward+" reverse="+reverse
                    +" validity="+forwardValid+","+reverseValid);
            assertAll(
                    ()->assertEquals(.25f,forward,1e-5f,"front-facing opaque depth"),
                    ()->assertEquals(.75f,reverse,1e-5f,"reverse-facing opaque depth"),
                    ()->assertEquals(1f,forwardValid,0f),
                    ()->assertEquals(1f,reverseValid,0f));
        }
    }

    @Test
    void twoSidedRasterContractCutoutRetainsItsAlphaMaskFromEitherLightDirection() throws Exception {
        try (Fixture f = new Fixture()) {
            // Raster discards alpha below .1: the left texel rejects and the right accepts.
            f.alpha(.05f,.2f);
            Object quad=f.mesh(0,1,f.quad(.25f,false),0,0,0,true);
            f.trace(List.of(quad));
            float forwardRejected=f.pixels[(3*8+1)*4], forwardAccepted=f.pixels[(3*8+6)*4];
            f.traceWithRadius(List.of(quad),REVERSE_LIGHT,REVERSE_LIGHT,2f);
            float reverseAccepted=f.pixels[(3*8+1)*4], reverseRejected=f.pixels[(3*8+6)*4];
            System.out.println("two-sided cutout: forward rejected="+forwardRejected+" accepted="+forwardAccepted
                    +" reverse accepted="+reverseAccepted+" rejected="+reverseRejected);
            assertAll(
                    ()->assertEquals(1f,forwardRejected,0f,"front transparent texel"),
                    ()->assertEquals(.25f,forwardAccepted,1e-5f,"front opaque texel"),
                    ()->assertEquals(.75f,reverseAccepted,1e-5f,"reverse opaque texel"),
                    ()->assertEquals(1f,reverseRejected,0f,"reverse transparent texel"));
        }
    }

    @Test
    void twoSidedRasterContractTransparentQuadNeverOccludesEitherLightDirection() throws Exception {
        try (Fixture f = new Fixture()) {
            f.alpha(0f,0f);
            Object quad=f.mesh(0,1,f.quad(.25f,false),0,0,0,true);
            f.trace(List.of(quad));
            f.assertPixel(3,3,1f);
            f.traceWithRadius(List.of(quad),REVERSE_LIGHT,REVERSE_LIGHT,2f);
            f.assertPixel(3,3,1f);
            System.out.println("two-sided transparent: forward=1 reverse=1 validity=1,1");
        }
    }

    @Test
    void twoSidedRasterContractClosedCuboidKeepsItsEntrySurfaceFromEitherLightDirection() throws Exception {
        try (Fixture f = new Fixture()) {
            List<Object> cuboid=f.closedCuboid();
            f.trace(cuboid);
            f.assertPixel(3,3,.25f);
            f.traceWithRadius(cuboid,REVERSE_LIGHT,REVERSE_LIGHT,2f);
            f.assertPixel(3,3,.25f); // The opposite entry face is world z=.75, depth 1-.75.
            System.out.println("two-sided closed cuboid: forward=.25 reverse=.25 validity=1,1");
        }
    }

    @Test
    void alphaUsesTheUploadedAsymmetricUvsAndCurrentAtlas() throws Exception {
        try (Fixture f = new Fixture()) {
            f.alpha(.05f, .2f); // Both sides distinguish raster's .1 threshold from .5 or zero.
            Object front=f.mesh(0, 1, f.quad(0.25f, false), 0, 0, 0, false);
            f.trace(List.of(front)); // SOLID keys must still apply the raster shadow alpha test.
            f.assertPixel(1, 3, 1f);
            f.assertPixel(6, 3, 0.25f);
            long back=f.quad(.5f,false);
            MemorySegment data=MemorySegment.ofAddress(Objc.msgSendId(back,Objc.selector("contents"))).reinterpret(96);
            for(int i=0;i<4;i++)data.set(ValueLayout.JAVA_SHORT,i*24L+8,(short)65535);
            f.trace(List.of(front,f.mesh(1,1,back,0,0,0)));
            f.assertPixel(1,3,.5f); // An alpha-rejected near triangle must not stop traversal.
            f.assertPixel(6,3,.25f);
            f.alpha(1f, 1f);
            f.trace(List.of(f.mesh(0, 1, f.quad(0.25f, false), 0, 0, 0)));
            f.assertPixel(1, 3, 0.25f);
        }
    }

    @Test
    void changedRevisionsMovedInstancesDeletedMeshesAndEmptyFramesReplacePriorDepth() throws Exception {
        try (Fixture f = new Fixture()) {
            long packed = f.quad(0.25f, false);
            f.trace(List.of(f.mesh(0, 1, packed, 0, 0, 0)));
            f.assertPixel(3, 3, 0.25f);
            long originalMesh=f.cachedBlas(),originalScene=f.nativeField("tlas");
            f.trace(List.of(f.mesh(0, 1, packed, 0, 0, 0)));
            assertEquals(originalMesh,f.cachedBlas(),"unchanged uploaded mesh reuses its BLAS");
            assertEquals(originalScene,f.nativeField("tlas"),"unchanged instances reuse their TLAS");
            f.trace(List.of(f.mesh(0, 1, packed, 0, 0, 0.25f)));
            f.assertPixel(3, 3, 0.5f);
            assertEquals(originalMesh,f.cachedBlas(),"moving an origin must not decode/rebuild geometry");
            assertNotEquals(originalScene,f.nativeField("tlas"));
            f.writeQuad(packed, 0.75f, false);
            f.trace(List.of(f.mesh(0, 2, packed, 0, 0, 0)));
            f.assertPixel(3, 3, 0.75f);
            f.trace(List.of());
            for (int y=0;y<8;y++) for (int x=0;x<8;x++) f.assertInvalid(x,y);
        }
    }

    @Test
    void stableGridTranslationAndRadialProjectionKeepTheSameDepth() throws Exception {
        try (Fixture f = new Fixture()) {
            long packed=f.quad(0.25f,false);
            MemorySegment data=MemorySegment.ofAddress(Objc.msgSendId(packed,Objc.selector("contents"))).reinterpret(96);
            // Narrow the quad to x in [-.25,.25]: pixel x=5 misses without radial distortion.
            for(int i=0;i<4;i++)data.set(ValueLayout.JAVA_SHORT,i*24L,(short)Math.round(((i<2?-.25f:.25f)+8)*2048));
            Object mesh = f.mesh(0, 1, packed, 3, 4, 5);
            f.trace(List.of(mesh), 3, 4, 5, 0);
            f.assertPixel(5, 3, 1f);
            f.trace(List.of(mesh), 3, 4, 5, 0.5f);
            f.assertPixel(3, 3, 0.25f);
            f.assertPixel(5, 3, 0.25f);
        }
    }

    @Test
    void receiverRadiusUsesHorizontalDistanceAndDoesNotClipTheCaster() throws Exception {
        try(Fixture f=new Fixture()) {
            Object mesh=f.mesh(0,1,f.quad(.75f,false),0,0,0);
            // Pixel (3,3)'s full light segment reaches x=-.125,z=0 regardless of y.
            f.traceWithRadius(List.of(mesh),IDENTITY,IDENTITY,.124f);
            f.assertInvalid(3,3);
            long originalMesh=f.cachedBlas();
            f.traceWithRadius(List.of(mesh),IDENTITY,IDENTITY,.126f);
            f.assertPixel(3,3,.75f); // The caster itself is outside the receiver cylinder.
            assertEquals(originalMesh,f.cachedBlas(),"changing receiver distance must not rebuild meshes");
        }
    }

    @Test
    void distantBlockersAffectNearReceiversAtSixteenAndSixtyFourBlocksUnderLowSun() throws Exception {
        try(Fixture f=new Fixture()) {
            // Low-sun rays run from z=-100,y=10 to z=100,y=-10. Pixels 3 and 4 have x=0 and 32.
            float[] inverse={128,0,0,0, 0,1,0,0, 0,-20,200,0, 16,10,-100,1};
            float[] light={1f/128,0,0,0, 0,1,0,0, 0,.1f,1f/200,0, -.125f,0,.5f,1};
            List<Object> meshes=List.of(f.mesh(0,1,f.quad(0,false),0,8,-80),
                    f.mesh(1,1,f.quad(0,false),32,8,-80),
                    f.mesh(2,1,f.quad(0,false),0,0,0));
            f.traceWithRadius(meshes,inverse,light,16);
            f.assertPixel(3,3,.1f); // The first blocker is 80 blocks away; receiver ray reaches x=z=0.
            f.assertInvalid(4,3);
            f.traceWithRadius(meshes,inverse,light,64);
            f.assertPixel(3,3,.1f);
            f.assertPixel(4,3,.1f); // This receiver ray reaches horizontal distance 32.
            f.assertInvalid(6,3); // Full segment remains 96 blocks from the camera.
        }
    }

    @Test
    void aSharedEventOrdersUploadedWritesBeforeDecodeAndSignalsAfterTrace() throws Exception {
        try(Fixture f=new Fixture()) {
            long packed=f.quad(.25f,false);
            long event=f.own(Objc.msgSendId(f.device,Objc.selector("newSharedEvent")));
            f.invokeTrace(List.of(f.mesh(0,1,packed,0,0,0)),IDENTITY,IDENTITY,0,0,0,2,0,event,1,2);
            // A second queue supplies the upload only after the consumer has been committed.
            long producer=f.own(Objc.msgSendId(f.device,Objc.selector("newCommandQueue")));
            long cb=Objc.msgSendId(producer,Objc.selector("commandBuffer"));
            long enc=Objc.msgSendId(cb,Objc.selector("computeCommandEncoder"));
            Objc.msgSendVoid(enc,Objc.selector("setComputePipelineState:"),f.uploadPipeline);
            Objc.msgSendVoidIdLongLong(enc,Objc.selector("setBuffer:offset:atIndex:"),packed,0,0);
            Objc.dispatchThreadgroups(enc,1,1,1,4,1,1);
            Objc.msgSendVoid(enc,Objc.selector("endEncoding"));
            Objc.msgSendVoidIdLong(cb,Objc.selector("encodeSignalEvent:value:"),event,1);
            Objc.msgSendVoid(cb,Objc.selector("commit"));
            f.readOutput();
            f.assertPixel(3,3,.75f);
            assertEquals(2,Objc.msgSendLong(event,Objc.selector("signaledValue")));
        }
    }

    @Test
    void replacingTheCacheKeepsResourcesAliveForAnEarlierUnfinishedTrace() throws Exception {
        try(Fixture f=new Fixture()) {
            long event=f.own(Objc.msgSendId(f.device,Objc.selector("newSharedEvent")));
            long secondOutput=f.texture(8,8);
            f.invokeTrace(List.of(f.mesh(0,1,f.quad(.25f,false),0,0,0)),IDENTITY,IDENTITY,0,0,0,2,0,event,1,2);
            // The first trace cannot have read its resources: the input event is still unsignalled.
            // Replace the same cache key and release the old references while that command is live.
            f.invokeTraceTo(List.of(f.mesh(0,2,f.quad(.75f,false),0,0,0)),IDENTITY,IDENTITY,
                    0,0,0,2,0,event,2,3,secondOutput);
            Objc.msgSendVoidLong(event,Objc.selector("setSignaledValue:"),1);
            f.readOutput();f.assertPixel(3,3,.25f);
            f.readOutput(secondOutput);f.assertPixel(3,3,.75f);
            assertEquals(3,Objc.msgSendLong(event,Objc.selector("signaledValue")));
            f.trace(List.of()); // Also checks both completed command statuses before cache eviction.
        }
    }

    @Test
    void verticalLightRaysUseTheirConstantHorizontalDistanceRegardlessOfCasterHeight() throws Exception {
        try(Fixture f=new Fixture()) {
            float[] inverse={1,0,0,0, 0,0,1,0, 0,-200,0,0, 0,100,0,1};
            float[] light={1,0,0,0, 0,0,-.005f,0, 0,1,0,0, 0,0,.5f,1};
            Object mesh=f.mesh(0,1,f.horizontalQuad(),0,80,0);
            f.traceWithRadius(List.of(mesh),inverse,light,.2f);
            f.assertPixel(3,3,.1f); // Horizontal distance sqrt(.125²+.125²), caster height 80.
            f.assertInvalid(2,3);
        }
    }

    @Test
    void filterGuardIncludesNeighborRaysOutsideTheReceiverCylinderAfterRadialWarp() throws Exception {
        try(Fixture f=new Fixture()) {
            // Each inverse projection column must contribute: swap the wide horizontal axis.
            for(boolean wideX:new boolean[]{false,true}) {
                float sx=wideX?64:1,sz=wideX?1:64;
                float[] inverse={sx,0,0,0, 0,0,sz,0, 0,-200,0,0, 0,100,0,1};
                float[] light={1f/sx,0,0,0, 0,0,-.005f,0, 0,1f/sz,0,0, 0,0,.5f,1};
                float center=.125f*.5f/(1f-.5f*(float)Math.sqrt(.125*.125*2));
                Object mesh=f.mesh(0,1,f.horizontalQuad(),center*sx,80,-center*sz);
                f.traceGuarded(List.of(mesh),inverse,light,3.8f,.5f,0);
                f.assertInvalid(4,3); // Center ray is at horizontal distance about 4.4.
                long blas=f.cachedBlas(),tlas=f.nativeField("tlas");
                f.traceGuarded(List.of(mesh),inverse,light,3.8f,.5f,.01f);
                f.assertPixel(4,3,.1f); // A receiver tap within .01 UV lies inside distance 3.8.
                assertEquals(blas,f.cachedBlas());assertEquals(tlas,f.nativeField("tlas"));
            }
        }
    }

    @Test
    void aSingularInverseWarpGuardTracesTheValidCenterRayConservatively() throws Exception {
        try(Fixture f=new Fixture()) {
            Object mesh=f.mesh(0,1,f.quad(.25f,false),0,0,0);
            f.traceGuarded(List.of(mesh),IDENTITY,IDENTITY,.01f,.9f,0);
            f.assertInvalid(7,3);
            f.traceGuarded(List.of(mesh),IDENTITY,IDENTITY,.01f,.9f,.2f);
            f.assertPixel(7,3,.25f); // Guard reaches the warp pole; the center ray is still valid.
        }
    }

    @Test
    void raysOutsideTheCapturedUnwarpedLightVolumeRemainInvalid() throws Exception {
        try(Fixture f=new Fixture()) {
            Object mesh=f.mesh(0,1,f.quad(.25f,false),0,0,0);
            f.traceGuarded(List.of(mesh),IDENTITY,IDENTITY,64,.5f,0);
            f.assertInvalid(7,7); // Positive inverse denominator, but unwarped p exceeds 1.
            f.traceGuarded(List.of(mesh),IDENTITY,IDENTITY,64,.9f,.2f);
            f.assertInvalid(7,7); // Outside the inverse radial warp's finite domain.
        }
    }

    @Test
    void aNonplanarUploadedQuadPreservesItsRasterDiagonal() throws Exception {
        try(Fixture f=new Fixture()) {
            long packed=f.quad(.25f,false);
            MemorySegment data=MemorySegment.ofAddress(Objc.msgSendId(packed,Objc.selector("contents"))).reinterpret(96);
            data.set(ValueLayout.JAVA_SHORT,3*24+4,(short)17920); // Only corner 3 rises to z=.75.
            f.trace(List.of(f.mesh(0,1,packed,0,0,0)));
            f.assertPixel(5,2,.5f); // Raster's 0–2 diagonal gives .5; the other diagonal gives .625.
        }
    }

    @Test
    void malformedInputsAreRejectedBeforeAnyNativeQueueAccess() {
        var key=new MeshShadowTracer.Key(0,0,0,false);
        assertThrows(IllegalArgumentException.class,()->new MeshShadowTracer.Mesh(key,1,1,3,0,0,0));
        assertThrows(IllegalArgumentException.class,()->new MeshShadowTracer.Mesh(key,1,0,4,0,0,0));
        assertThrows(IllegalArgumentException.class,()->new MeshShadowTracer.Mesh(key,1,1,4,Float.NaN,0,0));
        try(var tracer=new MeshShadowTracer()) {
            assertThrows(IllegalArgumentException.class,()->tracer.trace(1,List.of(),1,1,8,new float[15],IDENTITY,0,0,0,2,0,0,0,0,0));
            assertThrows(IllegalArgumentException.class,()->tracer.trace(1,List.of(),1,1,8,IDENTITY,IDENTITY,0,0,0,-1,0,0,0,0,0));
            assertThrows(IllegalArgumentException.class,()->tracer.trace(1,List.of(),1,1,8,IDENTITY,IDENTITY,0,0,0,2,1,0,0,0,0));
            assertThrows(IllegalArgumentException.class,()->tracer.trace(1,List.of(),1,1,8,IDENTITY,IDENTITY,0,0,0,2,0,0,1,2,1));
            assertThrows(IllegalArgumentException.class,()->tracer.trace(1,List.of(),1,1,8,IDENTITY,IDENTITY,0,0,0,2,0,-1,0,0,0));
            assertThrows(IllegalArgumentException.class,()->tracer.trace(1,List.of(),1,1,8,IDENTITY,IDENTITY,0,0,0,2,0,Float.NaN,0,0,0));
            var empty=new MeshShadowTracer.Mesh(key,1,0,0,0,0,0);
            assertThrows(IllegalArgumentException.class,()->tracer.trace(1,List.of(empty,empty),1,1,8,IDENTITY,IDENTITY,0,0,0,2,0,0,0,0,0));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final long pool;
        final Deque<Long> owned = new ArrayDeque<>();
        final long device, queue, atlas, output, seedPipeline,uploadPipeline;
        final Object tracer;
        final Class<?> type, meshType, keyType;
        final float[] pixels = new float[8*8*4];

        Fixture() throws Exception {
            Assumptions.assumeTrue(Objc.isLoaded());
            pool=Objc.autoreleasePoolPush();
            device=own(Objc.createSystemDefaultMetalDevice());
            Assumptions.assumeTrue(device!=0);
            queue=own(Objc.msgSendId(device,Objc.selector("newCommandQueue")));
            type=Class.forName(TYPE);
            meshType=Class.forName(TYPE+"$Mesh");
            keyType=Class.forName(TYPE+"$Key");
            tracer=type.getConstructor().newInstance();
            atlas=texture(2,1);
            output=texture(8,8);
            long library=own(MetalRtShaders.compileSource(device,"mesh_fixture","""
                #include <metal_stdlib>
                using namespace metal;
                kernel void seed(constant float2& a [[buffer(0)]],
                    texture2d<float,access::write> out [[texture(0)]],uint gid [[thread_position_in_grid]]) {
                    if (gid<2) out.write(float4(1,1,1,a[gid]),uint2(gid,0));
                }
                kernel void upload(device ushort* packed [[buffer(0)]],uint gid [[thread_position_in_grid]]) {
                    // Fornax's z=.75 fixed-point position code is (.75+8)*2048=17920.
                    if(gid<4)packed[gid*12+2]=17920;
                }
                """));
            long function=own(Objc.msgSendId(library,Objc.selector("newFunctionWithName:"),Objc.nsString("seed")));
            seedPipeline=own(Objc.msgSendIdIdErr(device,Objc.selector("newComputePipelineStateWithFunction:error:"),function).id());
            long upload=own(Objc.msgSendId(library,Objc.selector("newFunctionWithName:"),Objc.nsString("upload")));
            uploadPipeline=own(Objc.msgSendIdIdErr(device,Objc.selector("newComputePipelineStateWithFunction:error:"),upload).id());
            alpha(1f,1f);
        }

        long own(long value) { assertNotEquals(0,value); owned.push(value); return value; }
        long texture(int width,int height) {
            long desc=Objc.msgSendId(Objc.getClass("MTLTextureDescriptor"),Objc.selector("new"));
            try {
                // MTLTextureType2D=2, RGBA32Float=125, read|write=3, shared storage=0.
                Objc.msgSendVoidLong(desc,Objc.selector("setTextureType:"),2);
                Objc.msgSendVoidLong(desc,Objc.selector("setPixelFormat:"),125);
                Objc.msgSendVoidLong(desc,Objc.selector("setWidth:"),width);
                Objc.msgSendVoidLong(desc,Objc.selector("setHeight:"),height);
                Objc.msgSendVoidLong(desc,Objc.selector("setUsage:"),3);
                Objc.msgSendVoidLong(desc,Objc.selector("setStorageMode:"),0);
                return own(Objc.msgSendId(device,Objc.selector("newTextureWithDescriptor:"),desc));
            } finally { Objc.msgSendVoid(desc,Objc.selector("release")); }
        }
        void alpha(float left,float right) {
            long cb=Objc.msgSendId(queue,Objc.selector("commandBuffer"));
            long enc=Objc.msgSendId(cb,Objc.selector("computeCommandEncoder"));
            Objc.msgSendVoid(enc,Objc.selector("setComputePipelineState:"),seedPipeline);
            Objc.msgSendVoidIdLong(enc,Objc.selector("setTexture:atIndex:"),atlas,0);
            try (Arena arena=Arena.ofConfined()) {
                MemorySegment bytes=arena.allocate(8);
                bytes.set(ValueLayout.JAVA_FLOAT,0,left);bytes.set(ValueLayout.JAVA_FLOAT,4,right);
                Objc.msgSendVoidIdLongLong(enc,Objc.selector("setBytes:length:atIndex:"),bytes.address(),8,0);
                Objc.dispatchThreadgroups(enc,1,1,1,2,1,1);
            }
            Objc.msgSendVoid(enc,Objc.selector("endEncoding"));
            Objc.msgSendVoid(cb,Objc.selector("commit"));
            Objc.msgSendVoid(cb,Objc.selector("waitUntilCompleted"));
        }
        long quad(float z,boolean reversed) { long buffer=own(MetalRtAcceleration.createBuffer(device,96));writeQuad(buffer,z,reversed);return buffer; }
        List<Object> closedCuboid() throws Exception {
            // Six outward-facing quads close x/y in [-.75,.75] and z in [.25,.75].
            // The two opposing light directions each enter a front-facing outer surface.
            float[][][] faces={
                {{-.75f,-.75f,.25f},{-.75f,.75f,.25f},{.75f,.75f,.25f},{.75f,-.75f,.25f}},
                {{.75f,-.75f,.75f},{.75f,.75f,.75f},{-.75f,.75f,.75f},{-.75f,-.75f,.75f}},
                {{.75f,-.75f,.25f},{.75f,.75f,.25f},{.75f,.75f,.75f},{.75f,-.75f,.75f}},
                {{-.75f,-.75f,.75f},{-.75f,.75f,.75f},{-.75f,.75f,.25f},{-.75f,-.75f,.25f}},
                {{-.75f,.75f,.25f},{-.75f,.75f,.75f},{.75f,.75f,.75f},{.75f,.75f,.25f}},
                {{.75f,-.75f,.25f},{.75f,-.75f,.75f},{-.75f,-.75f,.75f},{-.75f,-.75f,.25f}}
            };
            var meshes=new java.util.ArrayList<Object>();
            for(int face=0;face<faces.length;face++) {
                long packed=quad(0f,false);
                MemorySegment data=MemorySegment.ofAddress(Objc.msgSendId(packed,Objc.selector("contents"))).reinterpret(96);
                for(int corner=0;corner<4;corner++) for(int axis=0;axis<3;axis++)
                    data.set(ValueLayout.JAVA_SHORT,corner*24L+axis*2L,
                            (short)Math.round((faces[face][corner][axis]+8)*2048));
                meshes.add(mesh(face,1,packed,0,0,0,false));
            }
            return meshes;
        }
        long horizontalQuad() {
            long packed=quad(0,false);
            MemorySegment data=MemorySegment.ofAddress(Objc.msgSendId(packed,Objc.selector("contents"))).reinterpret(96);
            // Rotate the XY quad into XZ, preserving outward +Y for light rays travelling downward.
            for(int i=0;i<4;i++) {
                short y=data.get(ValueLayout.JAVA_SHORT,i*24L+2);
                data.set(ValueLayout.JAVA_SHORT,i*24L+2,(short)16384);
                data.set(ValueLayout.JAVA_SHORT,i*24L+4,y);
            }
            return packed;
        }
        void writeQuad(long buffer,float z,boolean reversed) {
            MemorySegment data=MemorySegment.ofAddress(Objc.msgSendId(buffer,Objc.selector("contents"))).reinterpret(96);
            data.fill((byte)0);
            // Clockwise viewed from the near plane; outward normal points toward the light (-Z).
            float[][] vertices={{-.75f,-.75f,0,0},{-.75f,.75f,0,1},{.75f,.75f,1,1},{.75f,-.75f,1,0}};
            for(int i=0;i<4;i++) {
                float[] v=vertices[reversed?3-i:i];long p=i*24L;
                data.set(ValueLayout.JAVA_SHORT,p,(short)Math.round((v[0]+8)*2048));
                data.set(ValueLayout.JAVA_SHORT,p+2,(short)Math.round((v[1]+8)*2048));
                data.set(ValueLayout.JAVA_SHORT,p+4,(short)Math.round((z+8)*2048));
                data.set(ValueLayout.JAVA_SHORT,p+8,(short)Math.round(v[2]*65535));
                data.set(ValueLayout.JAVA_SHORT,p+10,(short)Math.round(v[3]*65535));
            }
        }
        Object mesh(int key,long revision,long buffer,float x,float y,float z) throws Exception {
            return mesh(key,revision,buffer,x,y,z,true);
        }
        Object mesh(int key,long revision,long buffer,float x,float y,float z,boolean cutout) throws Exception {
            Object k=keyType.getConstructor(int.class,int.class,int.class,boolean.class).newInstance(key,0,0,cutout);
            return meshType.getConstructor(keyType,long.class,long.class,int.class,float.class,float.class,float.class)
                    .newInstance(k,revision,buffer,4,x,y,z);
        }
        void trace(List<Object> meshes) throws Exception { trace(meshes,0,0,0,0); }
        void trace(List<Object> meshes,float x,float y,float z,float bias) throws Exception {
            trace(meshes,IDENTITY,IDENTITY,x,y,z,2f,bias);
        }
        void traceWithRadius(List<Object> meshes,float[] inverse,float[] light,float radius) throws Exception {
            trace(meshes,inverse,light,0,0,0,radius,0);
        }
        long nativeField(String name) throws Exception {
            var field=type.getDeclaredField(name);field.setAccessible(true);return field.getLong(tracer);
        }
        long cachedBlas() throws Exception {
            var field=type.getDeclaredField("cache");field.setAccessible(true);
            Object entry=((java.util.Map<?,?>)field.get(tracer)).values().iterator().next();
            var blas=entry.getClass().getDeclaredField("blas");blas.setAccessible(true);return blas.getLong(entry);
        }
        void trace(List<Object> meshes,float[] inverse,float[] light,float x,float y,float z,float radius,float bias) throws Exception {
            invokeTrace(meshes,inverse,light,x,y,z,radius,bias,0,0,0);
            readOutput();
        }
        void traceGuarded(List<Object> meshes,float[] inverse,float[] light,float radius,float bias,float guard) throws Exception {
            invokeTraceTo(meshes,inverse,light,0,0,0,radius,bias,guard,0,0,0,output);
            readOutput();
        }
        void invokeTrace(List<Object> meshes,float[] inverse,float[] light,float x,float y,float z,float radius,float bias,
                long event,long waitValue,long signalValue) throws Exception {
            invokeTraceTo(meshes,inverse,light,x,y,z,radius,bias,event,waitValue,signalValue,output);
        }
        void invokeTraceTo(List<Object> meshes,float[] inverse,float[] light,float x,float y,float z,float radius,float bias,
                long event,long waitValue,long signalValue,long target) throws Exception {
            invokeTraceTo(meshes,inverse,light,x,y,z,radius,bias,0,event,waitValue,signalValue,target);
        }
        void invokeTraceTo(List<Object> meshes,float[] inverse,float[] light,float x,float y,float z,float radius,float bias,
                float guard,long event,long waitValue,long signalValue,long target) throws Exception {
            try {
                type.getMethod("trace",long.class,List.class,long.class,long.class,int.class,float[].class,float[].class,
                    float.class,float.class,float.class,float.class,float.class,float.class,long.class,long.class,long.class)
                    .invoke(tracer,queue,meshes,atlas,target,8,inverse,light,x,y,z,radius,bias,guard,event,waitValue,signalValue);
            } catch(InvocationTargetException e) { throw new RuntimeException(e.getCause()); }
        }
        void readOutput() { readOutput(output); }
        void readOutput(long target) {
            // Only the test waits/reads GPU output; the production trace must remain asynchronous.
            long cb=Objc.msgSendId(queue,Objc.selector("commandBuffer"));
            Objc.msgSendVoid(cb,Objc.selector("commit"));Objc.msgSendVoid(cb,Objc.selector("waitUntilCompleted"));
            try(Arena arena=Arena.ofConfined()) {
                MemorySegment bytes=arena.allocate(pixels.length*4L);
                Objc.getBytesFromRegion(target,bytes.address(),8*16L,0,0,0,8,8,1,0);
                for(int i=0;i<pixels.length;i++)pixels[i]=bytes.getAtIndex(ValueLayout.JAVA_FLOAT,i);
            }
        }
        void assertPixel(int x,int y,float depth) {
            int p=(y*8+x)*4;
            assertEquals(depth,pixels[p],1e-5f,"depth at "+x+","+y);
            assertEquals(1f,pixels[p+3],0f,"validity at "+x+","+y);
        }
        void assertInvalid(int x,int y) {
            int p=(y*8+x)*4;
            assertEquals(1f,pixels[p],0f,"fallback depth at "+x+","+y);
            assertEquals(0f,pixels[p+3],0f,"invalid footprint at "+x+","+y);
        }
        @Override public void close() throws Exception {
            try { type.getMethod("close").invoke(tracer); }
            finally { while(!owned.isEmpty())Objc.msgSendVoid(owned.pop(),Objc.selector("release"));Objc.autoreleasePoolPop(pool); }
        }
    }
}
