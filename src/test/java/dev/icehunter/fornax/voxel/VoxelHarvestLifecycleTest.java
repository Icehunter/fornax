package dev.icehunter.fornax.voxel;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelHarvestLifecycleTest {
    @Test
    void imageRetirementWaitsUntilTheLastLeasedTexelReadFinishes() throws Exception {
        var gate = new VoxelHarvestLifecycle.Gate();
        var entered = new CountDownLatch(1);
        var finishRead = new CountDownLatch(1);
        var retiring = new CountDownLatch(1);
        var imageClosed = new CountDownLatch(1);
        try (NativeImage image = new NativeImage(NativeImage.Format.RGBA, 1, 1, false)) {
            image.setPixel(0, 0, 0xff123456);
            CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> {
                try (var lease = gate.tryAcquire(gate.generation())) {
                    assertNotNull(lease);
                    entered.countDown();
                    await(finishRead);
                    assertEquals(0xff123456, image.getPixel(0, 0));
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Void> closer = CompletableFuture.runAsync(() -> {
                retiring.countDown();
                gate.retireAndDrain();
                image.close();
                imageClosed.countDown();
            });
            try {
                assertTrue(retiring.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (gate.isAvailable() && System.nanoTime() < deadline) Thread.onSpinWait();
                assertFalse(gate.isAvailable(), "retirement must enter before checking the held lease");
                // This is an ordering assertion: the held lease forbids close at any time.
                assertFalse(imageClosed.await(100, TimeUnit.MILLISECONDS),
                        "native pixels must remain allocated while a harvest lease is active");
            } finally {
                finishRead.countDown();
            }
            reader.get(5, TimeUnit.SECONDS);
            closer.get(5, TimeUnit.SECONDS);
            assertEquals(0, imageClosed.getCount());
        }
    }

    @Test
    void retiredAndQueuedOldGenerationsCannotReadAfterModelPublication() {
        var gate = new VoxelHarvestLifecycle.Gate();
        long queuedGeneration = gate.generation();
        gate.retireAndDrain();
        assertFalse(gate.isAvailable());
        assertNull(gate.tryAcquire(queuedGeneration));
        assertNull(gate.tryAcquire(gate.generation()));
        assertFalse(gate.isCurrent(queuedGeneration));

        gate.modelsPublished();
        assertTrue(gate.isAvailable());
        assertNotEquals(queuedGeneration, gate.generation());
        assertNull(gate.tryAcquire(queuedGeneration), "queued old-world work must remain cancelled");
        try (var current = gate.tryAcquire(gate.generation())) {
            assertNotNull(current);
            assertTrue(gate.isCurrent(current.generation()));
        }
    }

    @Test
    void anExceptionStillReleasesTheReaderBeforeRetirement() throws Exception {
        var gate = new VoxelHarvestLifecycle.Gate();
        try (var ignored = gate.tryAcquire(gate.generation())) {
            throw new IllegalArgumentException("fixture leaves the harvest early");
        } catch (IllegalArgumentException expected) {
            // The try-with-resources boundary is the same one used by SectionHarvester.
        }
        CompletableFuture.runAsync(gate::retireAndDrain).get(5, TimeUnit.SECONDS);
    }

    @Test
    void nestedHarvestCancelsWithoutBlockingTheOuterLeaseDrain() throws Exception {
        var gate = new VoxelHarvestLifecycle.Gate();
        CompletableFuture<Void> retirement;
        try (var outer = gate.tryAcquire(gate.generation())) {
            assertNotNull(outer);
            retirement = CompletableFuture.runAsync(gate::retireAndDrain);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (gate.isAvailable() && System.nanoTime() < deadline) Thread.onSpinWait();
            assertFalse(gate.isAvailable());
            assertNull(gate.tryAcquire(gate.generation()), "inner harvest must cancel while the outer read drains");
            assertFalse(retirement.isDone(), "the outer lease still owns the native pixels");
        }
        retirement.get(5, TimeUnit.SECONDS);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
