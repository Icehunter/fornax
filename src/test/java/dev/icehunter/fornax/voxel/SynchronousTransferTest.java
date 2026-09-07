package dev.icehunter.fornax.voxel;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SynchronousTransferTest {
    private static final class Backend implements SynchronousTransfer.Backend {
        final List<String> calls = new ArrayList<>();
        boolean failWait, failSubmit;
        public void reset() { calls.add("reset"); }
        public void submit() {
            calls.add("submit");
            if (failSubmit) throw new IllegalStateException("submit");
        }
        public void await() {
            calls.add("wait");
            if (failWait) throw new IllegalStateException("wait");
        }
        public void close() { calls.add("close"); }
    }

    @Test void everyReuseWaitsForThePreviousSubmission() {
        Backend backend = new Backend();
        var transfer = new SynchronousTransfer(backend);
        for (int i = 0; i < 2; i++) transfer.execute(() -> backend.calls.add("record"));
        transfer.close(); transfer.close();
        assertEquals(List.of("reset", "record", "submit", "wait", "reset", "record", "submit", "wait", "close"), backend.calls);
        assertThrows(IllegalStateException.class, () -> transfer.execute(() -> {}));
    }

    @Test void aFailedCompletionBlocksResetAndDestructionUntilItActuallyCompletes() {
        Backend backend = new Backend(); backend.failWait = true;
        var transfer = new SynchronousTransfer(backend);
        assertThrows(IllegalStateException.class, () -> transfer.execute(() -> backend.calls.add("record")));
        assertThrows(IllegalStateException.class, () -> transfer.execute(() -> backend.calls.add("unsafe record")));
        assertThrows(IllegalStateException.class, transfer::close);
        assertEquals(List.of("reset", "record", "submit", "wait", "wait", "wait"), backend.calls);
        backend.failWait = false;
        transfer.close();
        assertEquals(List.of("reset", "record", "submit", "wait", "wait", "wait", "wait", "close"), backend.calls);
    }

    @Test void aFailedSubmissionIsNeverWaitedOn() {
        Backend backend = new Backend(); backend.failSubmit = true;
        var transfer = new SynchronousTransfer(backend);
        assertThrows(IllegalStateException.class, () -> transfer.execute(() -> backend.calls.add("record")));
        transfer.close();
        assertEquals(List.of("reset", "record", "submit", "close"), backend.calls);
    }

    @Test void aRecordingFailureCanResetWithoutWaitingForAnUnsubmittedFence() {
        Backend backend = new Backend();
        var transfer = new SynchronousTransfer(backend);
        assertThrows(IllegalArgumentException.class, () -> transfer.execute(() -> { throw new IllegalArgumentException(); }));
        transfer.execute(() -> backend.calls.add("record"));
        transfer.close();
        assertEquals(List.of("reset", "reset", "record", "submit", "wait", "close"), backend.calls);
    }
}
