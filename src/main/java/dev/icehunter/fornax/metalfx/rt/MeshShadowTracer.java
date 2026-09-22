package dev.icehunter.fornax.metalfx.rt;

import dev.icehunter.fornax.metalfx.objc.Objc;
import dev.icehunter.fornax.rt.RayTier;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Traces accepted uploaded caster meshes for the requested horizontal receiver footprint.
 *
 * <p>The caller owns packed buffers/textures and must order their writes before the supplied event.
 * A revision must change whenever packed contents change, even at the same buffer address. Origins
 * and camera use one stable grid origin. A call commits asynchronous Metal work; the caller must
 * wait for the signalled event before consuming output or overwriting imported inputs. It does not
 * certify that an asynchronous GPU submission has completed successfully on return. A native GPU
 * fault can prevent event signalling and cannot be recovered using synchronous exceptions alone.
 * The output must be a resolution-square RGBA32F texture. Radius is horizontal camera-to-receiver
 * distance in blocks. Eligible rays trace the full light near/far segment, including distant casters.
 * The caller must supply every loaded caster intersecting the unwarped light volume. Filter guard
 * is the maximum per-axis shadow UV offset used by the receiver filter, including texel footprint.
 * RGBA stores forward depth in R (miss 1), validity in A, and zero in G/B. Empty scenes, skipped rays
 * and rays outside the captured unwarped light volume are invalid and require raster fallback.
 *
 * <p>One command queue for this object's lifetime provides decode/build/trace ordering. Metal's
 * retained command buffers keep bound resources alive; indirect BLAS data is explicitly declared
 * resident. Replacements never mutate decoded buffers referenced by an earlier frame. Only close()
 * waits for completion, so normal frames perform no CPU geometry readback or host GPU wait.
 */
public final class MeshShadowTracer implements AutoCloseable {
    public record Key(int x,int y,int z,boolean cutout) {}
    public record Mesh(Key key,long revision,long packedBuffer,int vertexCount,
                       float originX,float originY,float originZ) {
        public Mesh {
            Objects.requireNonNull(key,"mesh key");
            if(vertexCount<0 || vertexCount%4!=0 || (vertexCount>0 && packedBuffer==0))
                throw new IllegalArgumentException("mesh must contain complete uploaded quads");
            if(!Float.isFinite(originX)||!Float.isFinite(originY)||!Float.isFinite(originZ))
                throw new IllegalArgumentException("mesh origin must be finite");
        }
    }

    private record Cached(Mesh source,long vertices,long primitives,long blas) {
        boolean matches(Mesh m) {
            return source.revision()==m.revision() && source.packedBuffer()==m.packedBuffer()
                    && source.vertexCount()==m.vertexCount();
        }
        void release() { MeshShadowTracer.release(blas);MeshShadowTracer.release(vertices);MeshShadowTracer.release(primitives); }
    }
    private record Instance(long blas,float x,float y,float z) {}
    private static final Comparator<Mesh> ORDER=Comparator.comparingInt((Mesh m)->m.key().x())
            .thenComparingInt(m->m.key().y()).thenComparingInt(m->m.key().z()).thenComparing(m->m.key().cutout());
    // Metal ABI: float3 positions have an explicitly packed 12-byte vertex stride; a primitive
    // record is 32 bytes, the surface word and a pad followed by three UV pairs. Word 0 is the
    // surface word every geometry record in this engine starts with, so one decode reaches the
    // face from a mesh record and from an rt_expand voxel record alike; its bit 30 is what says
    // the UVs of this record start at byte 8. Instance descriptors are the existing
    // MetalRtAcceleration 64-byte layout.
    private static final long PRIMITIVE_BYTES=32;
    // 176, not 164: MeshShadowConstants holds float4x4 members, so it carries 16-byte alignment
    // and three words of tail padding after the tier at byte 160.
    private static final long CONSTANT_BYTES=176;
    // Power-of-two workgroups, bounded below Metal's supported group-size limit. Not a look setting.
    private static final int DECODE_THREADS=64;
    private static final int TRACE_SIDE=8;
    private static final long READ_USAGE=1; // MTLResourceUsageRead.
    private Map<Key,Cached> cache=new LinkedHashMap<>();
    private List<Instance> instances=List.of();
    private final Deque<Long> pending=new ArrayDeque<>();
    private double lastGpuMillis=Double.NaN;
    private long queue,device,decodePipeline,tracePipeline,clearPipeline,tlas;
    private boolean closed,failed;

    public MeshShadowTracer() {}

    /** The instance structure this tracer last built, or 0 before the first successful build.
     * No production caller: device tests still use it, written against {@link #trace}. */
    public synchronized long instanceStructure() { return tlas; }

    /**
     * Appends every native handle the instance structure refers to. A compute encoder cannot see
     * through an acceleration structure to the buffers and structures beneath it, so a dispatch
     * that binds this one has to make all of them resident or it reads unresident memory and every
     * ray comes back a miss.
     */
    public synchronized void appendResidentResources(List<Long> out) {
        for (Cached item : cache.values()) {
            out.add(item.blas());
            out.add(item.vertices());
            out.add(item.primitives());
        }
    }

    /** No production caller: {@link MeshMetalProvider} builds and traces through {@link
     * #encodeBuild} and {@link #encodeVisibilityTrace} instead. Device tests below still use
     * this combined form; port a test before changing this method. */
    public synchronized void trace(long commandQueue,List<Mesh> meshes,long atlasTexture,long outputTexture,
            int resolution,float[] inverseLightVp,float[] lightVp,float cameraX,float cameraY,float cameraZ,
            float radiusBlocks,float bias,float filterGuardUv,long waitEvent,long waitValue,long signalValue) {
        if(closed || failed)throw new IllegalStateException("mesh shadow tracer is closed or failed");
        if(commandQueue==0 || atlasTexture==0 || outputTexture==0 || resolution<=0)
            throw new IllegalArgumentException("mesh trace requires queue, atlas, output and positive resolution");
        validateMatrix(inverseLightVp);validateMatrix(lightVp);
        if(!Float.isFinite(cameraX)||!Float.isFinite(cameraY)||!Float.isFinite(cameraZ)
                ||!Float.isFinite(radiusBlocks)||radiusBlocks<0||!Float.isFinite(bias)||bias<0||bias>=1
                ||!Float.isFinite(filterGuardUv)||filterGuardUv<0)
            throw new IllegalArgumentException("mesh trace camera/radius/bias/guard must be finite; radius and guard >= 0; bias in [0,1)");
        if(waitEvent!=0 && (waitValue<0 || signalValue<=waitValue))
            throw new IllegalArgumentException("output event value must follow input event value");
        List<Mesh> ordered=new ArrayList<>(List.copyOf(meshes));ordered.sort(ORDER);
        for(int i=1;i<ordered.size();i++) if(ordered.get(i-1).key().equals(ordered.get(i).key()))
            throw new IllegalArgumentException("duplicate mesh key "+ordered.get(i).key());
        long pool=Objc.autoreleasePoolPush();
        List<Cached> created=new ArrayList<>();
        List<Long> temporary=new ArrayList<>();
        long nextTlas=0;
        boolean newTlas=false,committed=false;
        try {
            initialize(commandQueue);retireCompleted();
            long cb=require(Objc.msgSendId(queue,Objc.selector("commandBuffer")),"command buffer");
            if(waitEvent!=0) Objc.msgSendVoidIdLong(cb,Objc.selector("encodeWaitForEvent:value:"),waitEvent,waitValue);
            Map<Key,Cached> next=new LinkedHashMap<>();
            List<Instance> nextInstances=new ArrayList<>();
            for(Mesh mesh:ordered) {
                if(mesh.vertexCount()==0)continue;
                Cached item=cache.get(mesh.key());
                if(item==null || !item.matches(mesh)) {
                    item=decodeAndBuild(cb,mesh,temporary);created.add(item);
                }
                next.put(mesh.key(),item);
                nextInstances.add(new Instance(item.blas(),mesh.originX(),mesh.originY(),mesh.originZ()));
            }
            newTlas=!nextInstances.equals(instances);
            nextTlas=newTlas?buildInstances(cb,nextInstances,temporary):tlas;
            encodeTrace(cb,next.values().stream().toList(),nextTlas,atlasTexture,outputTexture,resolution,
                    inverseLightVp,lightVp,cameraX,cameraY,cameraZ,radiusBlocks,bias,filterGuardUv);
            if(waitEvent!=0)Objc.msgSendVoidIdLong(cb,Objc.selector("encodeSignalEvent:value:"),waitEvent,signalValue);
            Objc.msgSendVoid(cb,Objc.selector("commit"));committed=true;
            pending.addLast(Objc.msgSendId(cb,Objc.selector("retain")));
            for(var entry:cache.entrySet())if(next.get(entry.getKey())!=entry.getValue())entry.getValue().release();
            if(newTlas) { release(tlas);tlas=nextTlas; }
            cache=next;instances=List.copyOf(nextInstances);
        } finally {
            // All encoded resources are retained by the command buffer, including scratch and
            // instance descriptors. If not committed, autorelease destroys that buffer below.
            for(long resource:temporary)release(resource);
            if(!committed) { for(Cached item:created)item.release();if(newTlas)release(nextTlas); }
            Objc.autoreleasePoolPop(pool);
        }
    }

    /**
     * A structure that is built but not yet live. Holds its handle and the Metal objects (BLAS,
     * vertex and primitive buffers) its instances need. The caller only passes it to {@link
     * #promote}, {@link #discardBuild} or {@link #encodeVisibilityTrace}.
     */
    public record Built(long structure, Map<Key,Cached> residency, List<Instance> instances, long buildCommandBuffer) {}

    /**
     * Compares meshes against the live structure only, never against a build still pending.
     * Calling this again before {@link #promote} replaces whatever the first call built; discard
     * that one with {@link #discardBuild}. Records the decode and the acceleration structure
     * build into one command buffer. That buffer waits for {@code event}/{@code waitValue}, this
     * frame's mesh upload. It signals {@code event} at {@code signalValue} when the build
     * finishes, not when a trace does. An unchanged mesh keeps its cached BLAS shared with live.
     * This call adds one reference, so releasing live does not free memory the returned
     * {@link Built} still needs.
     */
    public synchronized Built encodeBuild(long commandQueue,List<Mesh> meshes,
            long event,long waitValue,long signalValue) {
        if(closed || failed)throw new IllegalStateException("mesh shadow tracer is closed or failed");
        if(commandQueue==0)throw new IllegalArgumentException("mesh build requires a command queue");
        if(event!=0 && (waitValue<0 || signalValue<=waitValue))
            throw new IllegalArgumentException("build signal value must follow its wait value");
        List<Mesh> ordered=new ArrayList<>(List.copyOf(meshes));ordered.sort(ORDER);
        for(int i=1;i<ordered.size();i++) if(ordered.get(i-1).key().equals(ordered.get(i).key()))
            throw new IllegalArgumentException("duplicate mesh key "+ordered.get(i).key());
        long pool=Objc.autoreleasePoolPush();
        List<Cached> created=new ArrayList<>();
        // Reused entries got an extra reference here (see retainCached). On failure that
        // reference must be released too, or each reused mesh leaks one reference.
        List<Cached> reused=new ArrayList<>();
        List<Long> temporary=new ArrayList<>();
        long nextTlas=0; boolean newTlas=false, ownsTlas=false, committed=false;
        try {
            initialize(commandQueue);retireCompleted();
            long cb=require(Objc.msgSendId(queue,Objc.selector("commandBuffer")),"build command buffer");
            if(event!=0)Objc.msgSendVoidIdLong(cb,Objc.selector("encodeWaitForEvent:value:"),event,waitValue);
            Map<Key,Cached> next=new LinkedHashMap<>();
            List<Instance> nextInstances=new ArrayList<>();
            for(Mesh mesh:ordered) {
                if(mesh.vertexCount()==0)continue;
                Cached item=cache.get(mesh.key());
                if(item!=null && item.matches(mesh)) {
                    retainCached(item);reused.add(item);
                } else {
                    item=decodeAndBuild(cb,mesh,temporary);created.add(item);
                }
                next.put(mesh.key(),item);
                nextInstances.add(new Instance(item.blas(),mesh.originX(),mesh.originY(),mesh.originZ()));
            }
            newTlas=!nextInstances.equals(instances);
            if(newTlas) { nextTlas=buildInstances(cb,nextInstances,temporary);ownsTlas=true; }
            else if((nextTlas=tlas)!=0) { Objc.msgSendId(nextTlas,Objc.selector("retain"));ownsTlas=true; }
            if(event!=0)Objc.msgSendVoidIdLong(cb,Objc.selector("encodeSignalEvent:value:"),event,signalValue);
            Objc.msgSendVoid(cb,Objc.selector("commit"));committed=true;
            pending.addLast(Objc.msgSendId(cb,Objc.selector("retain")));
            // A second, independent reference. The one above is this tracer's own bookkeeping;
            // retireCompleted() releases it. This one belongs to the caller, released by
            // promote() or discardBuild() once isBuildComplete() stops needing it.
            long handle=Objc.msgSendId(cb,Objc.selector("retain"));
            return new Built(nextTlas,Map.copyOf(next),List.copyOf(nextInstances),handle);
        } finally {
            for(long resource:temporary)release(resource);
            if(!committed) {
                for(Cached item:created)item.release();
                for(Cached item:reused)item.release();
                if(ownsTlas)release(nextTlas);
            }
            Objc.autoreleasePoolPop(pool);
        }
    }

    /**
     * Makes {@code built} the live structure. Releases every entry the old live structure held,
     * even ones {@code built} reuses. {@link #encodeBuild} already took an extra reference on
     * anything reused, so this drops only live's own reference, leaving {@code built}'s intact.
     * Safe immediately, with no wait needed. Every command buffer that could still read the old
     * structure was already committed before this call. Metal keeps an object alive for any
     * command buffer that used it, no matter this object's own release count.
     */
    public synchronized void promote(Built built) {
        for(Cached item:cache.values())item.release();
        release(tlas);
        release(built.buildCommandBuffer());
        cache=built.residency();
        instances=built.instances();
        tlas=built.structure();
    }

    /** Releases a built structure that will never go live. A newer build replaced it, or the
     * caller chose not to use it. Safe immediately, for the same reason {@link #promote} is. */
    public synchronized void discardBuild(Built built) {
        for(Cached item:built.residency().values())item.release();
        release(built.structure());
        release(built.buildCommandBuffer());
    }

    /**
     * Whether the build command buffer behind {@code built} has finished. This checks that one
     * specific command buffer, never a value on a shared event. The caller's Vulkan encoder
     * signals that same event every frame from another queue. Nothing keeps its signal behind
     * this command buffer. This never waits; it reads Metal's own status. If the build failed,
     * this marks the tracer failed and throws; {@link #retireCompleted} does the same for any
     * other command.
     */
    public synchronized boolean isBuildComplete(Built built) {
        if(closed || failed)throw new IllegalStateException("mesh shadow tracer is closed or failed");
        long status=Objc.msgSendLong(built.buildCommandBuffer(),Objc.selector("status"));
        if(status==5) { failed=true;throw new IllegalStateException("mesh shadow build command failed"); }
        return status>=4;
    }

    /**
     * Traces the light-space visibility image against {@code built}, normally the live
     * structure. Never diffs or rebuilds anything. Records its own command buffer, waiting for
     * {@code event}/{@code waitValue}, the copy of this frame's atlas and depth target. It
     * signals {@code event} at {@code signalValue} when the trace is done. {@code
     * built.structure() == 0}, an empty scene, still writes the clear image, the same as
     * {@link #trace}.
     */
    public synchronized void encodeVisibilityTrace(long commandQueue,Built built,long atlasTexture,long outputTexture,
            int resolution,float[] inverseLightVp,float[] lightVp,float cameraX,float cameraY,float cameraZ,
            float radiusBlocks,float bias,float filterGuardUv,long event,long waitValue,long signalValue) {
        if(closed || failed)throw new IllegalStateException("mesh shadow tracer is closed or failed");
        if(commandQueue==0 || atlasTexture==0 || outputTexture==0 || resolution<=0)
            throw new IllegalArgumentException("mesh trace requires queue, atlas, output and positive resolution");
        validateMatrix(inverseLightVp);validateMatrix(lightVp);
        if(!Float.isFinite(cameraX)||!Float.isFinite(cameraY)||!Float.isFinite(cameraZ)
                ||!Float.isFinite(radiusBlocks)||radiusBlocks<0||!Float.isFinite(bias)||bias<0||bias>=1
                ||!Float.isFinite(filterGuardUv)||filterGuardUv<0)
            throw new IllegalArgumentException("mesh trace camera/radius/bias/guard must be finite; radius and guard >= 0; bias in [0,1)");
        if(event!=0 && (waitValue<0 || signalValue<=waitValue))
            throw new IllegalArgumentException("output event value must follow input event value");
        long pool=Objc.autoreleasePoolPush();
        try {
            initialize(commandQueue);retireCompleted();
            long cb=require(Objc.msgSendId(queue,Objc.selector("commandBuffer")),"trace command buffer");
            if(event!=0)Objc.msgSendVoidIdLong(cb,Objc.selector("encodeWaitForEvent:value:"),event,waitValue);
            encodeTrace(cb,built.residency().values().stream().toList(),built.structure(),atlasTexture,outputTexture,
                    resolution,inverseLightVp,lightVp,cameraX,cameraY,cameraZ,radiusBlocks,bias,filterGuardUv);
            if(event!=0)Objc.msgSendVoidIdLong(cb,Objc.selector("encodeSignalEvent:value:"),event,signalValue);
            Objc.msgSendVoid(cb,Objc.selector("commit"));
            pending.addLast(Objc.msgSendId(cb,Objc.selector("retain")));
        } finally { Objc.autoreleasePoolPop(pool); }
    }

    private void initialize(long requestedQueue) {
        if(queue!=0) {
            if(queue!=requestedQueue)throw new IllegalArgumentException("mesh tracer requires one ordered Metal queue");
            return;
        }
        device=require(Objc.msgSendId(requestedQueue,Objc.selector("device")),"device");
        String path="/assets/fornax/shaders_engine/rt_mesh_shadow.metal";
        String source;
        try(InputStream in=MeshShadowTracer.class.getResourceAsStream(path)) {
            if(in==null)throw new IllegalStateException("missing "+path);
            source=new String(in.readAllBytes(),StandardCharsets.UTF_8);
        } catch(IOException e) { throw new IllegalStateException("cannot read "+path,e); }
        long library=MetalRtShaders.compileSource(device,path,source);
        try {
            decodePipeline=pipeline(library,"mesh_shadow_decode");
            tracePipeline=pipeline(library,"mesh_shadow_trace");
            clearPipeline=pipeline(library,"mesh_shadow_clear");
            queue=Objc.msgSendId(requestedQueue,Objc.selector("retain"));
        } catch(RuntimeException|Error e) {
            release(decodePipeline);release(tracePipeline);release(clearPipeline);
            decodePipeline=tracePipeline=clearPipeline=0;throw e;
        } finally { release(library); }
    }

    private long pipeline(long library,String name) {
        long function=require(Objc.msgSendId(library,Objc.selector("newFunctionWithName:"),Objc.nsString(name)),name);
        try {
            Objc.Result result=Objc.msgSendIdIdErr(device,Objc.selector("newComputePipelineStateWithFunction:error:"),function);
            if(result.id()==0)throw new IllegalStateException(name+": "+result.error());
            return result.id();
        } finally { release(function); }
    }

    private Cached decodeAndBuild(long cb,Mesh mesh,List<Long> temporary) {
        int triangles=mesh.vertexCount()/2;
        if(Objc.msgSendLong(mesh.packedBuffer(),Objc.selector("length"))<mesh.vertexCount()*24L)
            throw new IllegalArgumentException("packed mesh buffer is shorter than its vertex count");
        long vertices=0,primitives=0,blas=0;
        try {
            vertices=MetalRtAcceleration.createBuffer(device,triangles*36L);
            primitives=MetalRtAcceleration.createBuffer(device,triangles*PRIMITIVE_BYTES);
            long enc=require(Objc.msgSendId(cb,Objc.selector("computeCommandEncoder")),"decode encoder");
            try(Arena arena=Arena.ofConfined()) {
                Objc.msgSendVoid(enc,Objc.selector("setComputePipelineState:"),decodePipeline);
                buffer(enc,mesh.packedBuffer(),0);buffer(enc,vertices,1);buffer(enc,primitives,2);
                MemorySegment count=arena.allocate(ValueLayout.JAVA_INT);count.set(ValueLayout.JAVA_INT,0,triangles);
                Objc.msgSendVoidIdLongLong(enc,Objc.selector("setBytes:length:atIndex:"),count.address(),4,3);
                Objc.dispatchThreadgroups(enc,(triangles+(long)DECODE_THREADS-1)/DECODE_THREADS,1,1,DECODE_THREADS,1,1);
            } finally { Objc.msgSendVoid(enc,Objc.selector("endEncoding")); }
            long geometry=MetalRtAcceleration.triangleGeometry(vertices,primitives,triangles,PRIMITIVE_BYTES);
            blas=build(cb,MetalRtAcceleration.primitiveDescriptor(List.of(geometry)),temporary);
            return new Cached(mesh,vertices,primitives,blas);
        } catch(RuntimeException|Error e) { release(blas);release(vertices);release(primitives);throw e; }
    }

    private long build(long cb,long descriptor,List<Long> temporary) {
        Objc.AccelerationStructureSizes sizes=Objc.accelerationStructureSizes(device,descriptor);
        long acceleration=require(Objc.msgSendId(device,Objc.selector("newAccelerationStructureWithSize:"),sizes.accelerationStructureSize()),"acceleration structure");
        try {
            long scratch=MetalRtAcceleration.createBuffer(device,Math.max(sizes.buildScratchBufferSize(),4));temporary.add(scratch);
            long enc=require(Objc.msgSendId(cb,Objc.selector("accelerationStructureCommandEncoder")),"acceleration encoder");
            try {
                Objc.msgSendVoidIdIdIdLong(enc,Objc.selector("buildAccelerationStructure:descriptor:scratchBuffer:scratchBufferOffset:"),
                        acceleration,descriptor,scratch,0);
            } finally { Objc.msgSendVoid(enc,Objc.selector("endEncoding")); }
            return acceleration;
        } catch(RuntimeException|Error e) { release(acceleration);throw e; }
    }

    private long buildInstances(long cb,List<Instance> next,List<Long> temporary) {
        if(next.isEmpty())return 0;
        long descriptors=MetalRtAcceleration.createBuffer(device,next.size()*64L);temporary.add(descriptors);
        MemorySegment bytes=MemorySegment.ofAddress(Objc.msgSendId(descriptors,Objc.selector("contents"))).reinterpret(next.size()*64L);
        try(Arena arena=Arena.ofConfined()) {
            MemorySegment ids=arena.allocate(next.size()*8L);
            for(int i=0;i<next.size();i++) {
                Instance instance=next.get(i);
                MetalRtAcceleration.writeInstanceDescriptor(bytes,i,instance.x(),instance.y(),instance.z(),i);
                ids.setAtIndex(ValueLayout.JAVA_LONG,i,instance.blas());
            }
            long array=Objc.msgSendIdPtrLong(Objc.getClass("NSArray"),Objc.selector("arrayWithObjects:count:"),ids.address(),next.size());
            long descriptor=Objc.msgSendId(Objc.getClass("MTLInstanceAccelerationStructureDescriptor"),Objc.selector("descriptor"));
            Objc.msgSendVoid(descriptor,Objc.selector("setInstanceDescriptorBuffer:"),descriptors);
            Objc.msgSendVoidLong(descriptor,Objc.selector("setInstanceCount:"),next.size());
            Objc.msgSendVoid(descriptor,Objc.selector("setInstancedAccelerationStructures:"),array);
            return build(cb,descriptor,temporary);
        }
    }

    private void encodeTrace(long cb,List<Cached> next,long scene,long atlas,long output,int resolution,
            float[] inverse,float[] light,float x,float y,float z,float radius,float bias,float filterGuardUv) {
        long enc=require(Objc.msgSendId(cb,Objc.selector("computeCommandEncoder")),"trace encoder");
        try(Arena arena=Arena.ofConfined()) {
            Objc.msgSendVoid(enc,Objc.selector("setComputePipelineState:"),scene==0?clearPipeline:tracePipeline);
            if(scene==0)Objc.msgSendVoidIdLong(enc,Objc.selector("setTexture:atIndex:"),output,0);
            else {
                MemorySegment constants=arena.allocate(CONSTANT_BYTES);constants.fill((byte)0);
                for(int i=0;i<16;i++) {
                    constants.set(ValueLayout.JAVA_FLOAT,i*4L,inverse[i]);
                    constants.set(ValueLayout.JAVA_FLOAT,64+i*4L,light[i]);
                }
                constants.set(ValueLayout.JAVA_FLOAT,128,x);constants.set(ValueLayout.JAVA_FLOAT,132,y);
                constants.set(ValueLayout.JAVA_FLOAT,136,z);constants.set(ValueLayout.JAVA_FLOAT,144,radius);
                constants.set(ValueLayout.JAVA_FLOAT,148,bias);constants.set(ValueLayout.JAVA_INT,152,resolution);
                constants.set(ValueLayout.JAVA_FLOAT,156,filterGuardUv);
                // The kernel copies this into G on every traced texel; it is what tells a reader
                // this answer came from uploaded meshes rather than from a voxel approximation.
                constants.set(ValueLayout.JAVA_INT,160,RayTier.HARDWARE_MESH.ordinal());
                Objc.msgSendVoidIdLongLong(enc,Objc.selector("setBytes:length:atIndex:"),constants.address(),CONSTANT_BYTES,0);
                Objc.msgSendVoidIdLong(enc,Objc.selector("setAccelerationStructure:atBufferIndex:"),scene,1);
                Objc.msgSendVoidIdLong(enc,Objc.selector("setTexture:atIndex:"),atlas,0);
                Objc.msgSendVoidIdLong(enc,Objc.selector("setTexture:atIndex:"),output,1);
                for(Cached item:next) { resident(enc,item.blas());resident(enc,item.vertices());resident(enc,item.primitives()); }
                resident(enc,scene);
            }
            Objc.dispatchThreadgroups(enc,(resolution+(long)TRACE_SIDE-1)/TRACE_SIDE,
                    (resolution+(long)TRACE_SIDE-1)/TRACE_SIDE,1,TRACE_SIDE,TRACE_SIDE,1);
        } finally { Objc.msgSendVoid(enc,Objc.selector("endEncoding")); }
    }

    private void retireCompleted() {
        while(!pending.isEmpty()) {
            long cb=pending.peekFirst();
            long status=Objc.msgSendLong(cb,Objc.selector("status"));
            // MTLCommandBufferStatusCompleted=4, Error=5. Querying status does not wait/read geometry.
            if(status<4)return;
            if(status==4)lastGpuMillis=gpuMillis(cb);
            pending.removeFirst();release(cb);
            if(status==5) { failed=true;throw new IllegalStateException("previous mesh shadow Metal command failed"); }
        }
    }

    /**
     * How long the GPU spent in the last completed trace, in milliseconds, or NaN before one
     * completes.
     *
     * <ul>
     *   <li>The Vulkan timers see their own queue only, so nothing else measures this dispatch.
     *   <li>Read from a buffer that already reports completed, so it never waits.
     *   <li>Lags by the frames in flight: it answers what a trace costs, not what this frame did.
     * </ul>
     */
    public synchronized double lastGpuMillis() {
        return lastGpuMillis;
    }

    private static double gpuMillis(long cb) {
        double start=Objc.msgSendDouble(cb,Objc.selector("GPUStartTime"));
        double end=Objc.msgSendDouble(cb,Objc.selector("GPUEndTime"));
        // Both read zero on a buffer the driver never scheduled, which is not a zero-cost trace.
        return end>start ? (end-start)*1000.0 : Double.NaN;
    }

    @Override public synchronized void close() {
        if(closed)return;closed=true;
        try {
            if(!pending.isEmpty())Objc.msgSendVoid(pending.peekLast(),Objc.selector("waitUntilCompleted"));
        } finally {
            while(!pending.isEmpty())release(pending.removeFirst());
            // Reassigned here, not cleared with cache.clear(). promote() may have set cache to
            // the immutable map Map.copyOf() returns inside a Built. clear() throws on an
            // immutable map.
            for(Cached item:cache.values())item.release();cache=Map.of();
            release(tlas);tlas=0;instances=List.of();
            release(decodePipeline);release(tracePipeline);release(clearPipeline);release(queue);
            decodePipeline=tracePipeline=clearPipeline=queue=device=0;
        }
    }
    private static void validateMatrix(float[] matrix) {
        if(matrix==null||matrix.length!=16)throw new IllegalArgumentException("light matrix must have 16 values");
        for(float value:matrix)if(!Float.isFinite(value))throw new IllegalArgumentException("light matrix must be finite");
    }
    private static long require(long value,String what) { if(value==0)throw new IllegalStateException("Metal returned nil "+what);return value; }
    private static void buffer(long encoder,long value,long index) { Objc.msgSendVoidIdLongLong(encoder,Objc.selector("setBuffer:offset:atIndex:"),value,0,index); }
    private static void resident(long encoder,long value) { Objc.msgSendVoidIdLong(encoder,Objc.selector("useResource:usage:"),value,READ_USAGE); }
    private static void release(long value) { if(value!=0)Objc.msgSendVoid(value,Objc.selector("release")); }
    /** Takes an extra Obj-C reference on a Cached entry a new structure reuses unchanged. Live's
     * own map keeps its reference, so the entry needs one more owner. Otherwise promote()'s
     * release of live's whole map would free memory the new structure still needs. */
    private static void retainCached(Cached item) {
        Objc.msgSendId(item.blas(),Objc.selector("retain"));
        Objc.msgSendId(item.vertices(),Objc.selector("retain"));
        Objc.msgSendId(item.primitives(),Objc.selector("retain"));
    }
}
