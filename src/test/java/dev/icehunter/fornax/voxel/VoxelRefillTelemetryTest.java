package dev.icehunter.fornax.voxel;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

class VoxelRefillTelemetryTest {
    @Test void completedTotalsSurviveIdleSnapshotsAndLightRemainsPartOfReaderTime() {
        var telemetry = new VoxelRefillTelemetry();
        var clock = new AtomicLong(100);
        try (var job = telemetry.begin(80, clock::get)) {
            assertEquals(1, telemetry.snapshot().activeJobs());
            long read = VoxelRefillTelemetry.start();
            clock.addAndGet(3);
            long light = VoxelRefillTelemetry.start();
            clock.addAndGet(4);
            VoxelRefillTelemetry.finish(VoxelRefillTelemetry.Phase.LIGHT, light);
            clock.addAndGet(3);
            VoxelRefillTelemetry.finish(VoxelRefillTelemetry.Phase.READ, read);
            VoxelRefillTelemetry.count(VoxelRefillTelemetry.Count.READ);
            VoxelRefillTelemetry.count(VoxelRefillTelemetry.Count.NULL_READ);
            job.complete();
        }
        var first = telemetry.snapshot();
        assertEquals(0, first.activeJobs());
        assertEquals(1, first.jobs());
        assertEquals(0, first.failedJobs());
        assertEquals(20, first.queueNanos());
        assertEquals(10, first.workNanos());
        assertEquals(10, first.nanos(VoxelRefillTelemetry.Phase.READ));
        assertEquals(4, first.nanos(VoxelRefillTelemetry.Phase.LIGHT));
        assertEquals(1, first.count(VoxelRefillTelemetry.Count.NULL_READ));
        VoxelRefillTelemetry.count(VoxelRefillTelemetry.Count.READ);
        assertEquals(0, VoxelRefillTelemetry.start(), "normal meshing has no active refill timer");
        assertEquals(1, telemetry.snapshot().count(VoxelRefillTelemetry.Count.READ));
        assertEquals(first.workNanos(), telemetry.snapshot().workNanos());
    }

    @Test void failedTransferWaitIsAccountedWithoutChangingItsPendingLifecycle() {
        var telemetry = new VoxelRefillTelemetry();
        var clock = new AtomicLong(100);
        var backend = new SynchronousTransfer.Backend() {
            public void reset() { clock.addAndGet(2); }
            public void submit() { clock.addAndGet(3); }
            public void await() { clock.addAndGet(7); throw new IllegalStateException("wait failed"); }
            public void close() { fail("must not free pending transfer"); }
        };
        var transfer = new SynchronousTransfer(backend);
        assertThrows(IllegalStateException.class, () -> {
            try (var job = telemetry.begin(100, clock::get)) {
                transfer.execute(() -> clock.addAndGet(5));
                job.complete();
            }
        });
        var stats = telemetry.snapshot();
        assertEquals(1, stats.failedJobs());
        assertEquals(2, stats.nanos(VoxelRefillTelemetry.Phase.RESET));
        assertEquals(5, stats.nanos(VoxelRefillTelemetry.Phase.RECORD));
        assertEquals(3, stats.nanos(VoxelRefillTelemetry.Phase.SUBMIT));
        assertEquals(7, stats.nanos(VoxelRefillTelemetry.Phase.WAIT));
        assertEquals(17, stats.workNanos());
        assertThrows(IllegalStateException.class, transfer::close);
        assertEquals(7, telemetry.snapshot().nanos(VoxelRefillTelemetry.Phase.WAIT), "out-of-job retry is excluded");
    }

    @Test void reentrantHarvestRestoresTheOuterJobAfterInnerFailure() {
        var telemetry = new VoxelRefillTelemetry();
        var clock = new AtomicLong(100);
        try (var outer = telemetry.begin(100, clock::get)) {
            long read = VoxelRefillTelemetry.start();
            clock.addAndGet(3);
            assertThrows(IllegalStateException.class, () -> {
                try (var inner = telemetry.begin(103, clock::get)) {
                    assertEquals(2, telemetry.snapshot().activeJobs());
                    VoxelRefillTelemetry.count(VoxelRefillTelemetry.Count.NULL_READ);
                    clock.addAndGet(4);
                    throw new IllegalStateException("inner read failed");
                }
            });
            assertEquals(1, telemetry.snapshot().activeJobs());
            clock.addAndGet(3);
            VoxelRefillTelemetry.finish(VoxelRefillTelemetry.Phase.READ, read);
            VoxelRefillTelemetry.count(VoxelRefillTelemetry.Count.READ_OK);
            outer.complete();
        }
        var stats = telemetry.snapshot();
        assertEquals(2, stats.jobs());
        assertEquals(1, stats.failedJobs());
        assertEquals(0, stats.activeJobs());
        // Outer span 3+4+3 contains the inner span 4, so summed job time is 14.
        assertEquals(14, stats.workNanos());
        assertEquals(10, stats.maxJobNanos());
        assertEquals(10, stats.nanos(VoxelRefillTelemetry.Phase.READ));
        assertEquals(1, stats.count(VoxelRefillTelemetry.Count.READ_OK));
        assertEquals(1, stats.count(VoxelRefillTelemetry.Count.NULL_READ));
        assertEquals(0, VoxelRefillTelemetry.start());
    }

    @Test void workerJobsMergeAndSnapshotsCannotMutateRetainedTotals() throws Exception {
        var telemetry = new VoxelRefillTelemetry();
        Runnable run = () -> {
            try (var job = telemetry.begin(System.nanoTime(), System::nanoTime)) {
                for (int i = 0; i < 100; i++) VoxelRefillTelemetry.count(VoxelRefillTelemetry.Count.READ);
                job.complete();
            }
        };
        Thread one = new Thread(run), two = new Thread(run);
        one.start(); two.start(); one.join(); two.join();
        var snapshot = telemetry.snapshot();
        assertEquals(2, snapshot.jobs());
        assertEquals(200, snapshot.count(VoxelRefillTelemetry.Count.READ));
        snapshot.counts()[VoxelRefillTelemetry.Count.READ.ordinal()] = -1;
        assertEquals(200, telemetry.snapshot().count(VoxelRefillTelemetry.Count.READ));
    }
}
