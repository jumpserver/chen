package org.jumpserver.chen.framework.console;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SQLChunkTransferManagerTest {

    @Test
    void assemblesCurrentLunaPayloadWithoutRequestId() {
        String sql = "x".repeat(10_722);

        try (SQLChunkTransferManager manager = new SQLChunkTransferManager()) {
            Optional<String> assembled = sendLegacyChunks(manager, sql);

            assertTrue(assembled.isPresent());
            assertEquals(sql, assembled.orElseThrow());
        }
    }

    @Test
    void acceptsConsecutiveLegacyTransfers() {
        try (SQLChunkTransferManager manager = new SQLChunkTransferManager()) {
            assertEquals("first", sendLegacyChunks(manager, "first").orElseThrow());
            assertEquals("second", sendLegacyChunks(manager, "second").orElseThrow());
        }
    }

    @Test
    void rejectsLegacyCompletionBeforeLastChunkAndRecovers() {
        try (SQLChunkTransferManager manager = new SQLChunkTransferManager()) {
            assertFalse(manager.receiveChunk(legacyChunk("first-", 0, 2)).isPresent());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.receiveComplete(legacyComplete(2))
            );

            assertEquals("recovered", sendLegacyChunks(manager, "recovered").orElseThrow());
        }
    }

    @Test
    void rejectsOutOfOrderLegacyChunksWithoutExecuting() {
        try (SQLChunkTransferManager manager = new SQLChunkTransferManager()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.receiveChunk(legacyChunk("second", 1, 2))
            );
            assertFalse(manager.receiveComplete(legacyComplete(2)).isPresent());

            assertEquals("ordered", sendLegacyChunks(manager, "ordered").orElseThrow());
        }
    }

    @Test
    void clearsRejectedLegacyTransferAtCompletionBoundary() {
        try (SQLChunkTransferManager manager = new SQLChunkTransferManager()) {
            assertFalse(manager.receiveChunk(legacyChunk("first", 0, 2)).isPresent());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.receiveChunk(legacyChunk("different", 0, 2))
            );
            assertFalse(manager.receiveComplete(legacyComplete(2)).isPresent());

            assertEquals("recovered", sendLegacyChunks(manager, "recovered").orElseThrow());
        }
    }

    @Test
    void keepsExplicitRequestIdTransfersIsolated() {
        try (SQLChunkTransferManager manager = new SQLChunkTransferManager()) {
            assertFalse(manager.receiveChunk(chunk("request-a", "a1", 0, 2)).isPresent());
            assertFalse(manager.receiveChunk(chunk("request-b", "b1", 0, 2)).isPresent());
            assertFalse(manager.receiveComplete(complete("request-a", 2)).isPresent());
            assertFalse(manager.receiveComplete(complete("request-b", 2)).isPresent());

            assertEquals("a1a2", manager.receiveChunk(chunk("request-a", "a2", 1, 2)).orElseThrow());
            assertEquals("b1b2", manager.receiveChunk(chunk("request-b", "b2", 1, 2)).orElseThrow());
        }
    }

    @Test
    void rejectsInvalidProvidedRequestId() {
        try (SQLChunkTransferManager manager = new SQLChunkTransferManager()) {
            Map<String, Object> blankRequestId = legacyChunk("sql", 0, 1);
            blankRequestId.put("requestId", " ");
            Map<String, Object> numericRequestId = legacyChunk("sql", 0, 1);
            numericRequestId.put("requestId", 123);

            assertThrows(IllegalArgumentException.class, () -> manager.receiveChunk(blankRequestId));
            assertThrows(IllegalArgumentException.class, () -> manager.receiveChunk(numericRequestId));
        }
    }

    @Test
    void acceptsChunkAtExactSizeLimit() {
        String sql = "x".repeat(SQLChunkTransferManager.MAX_CHUNK_SIZE);

        try (SQLChunkTransferManager manager = new SQLChunkTransferManager()) {
            assertFalse(manager.receiveChunk(legacyChunk(sql, 0, 1)).isPresent());
            assertEquals(sql, manager.receiveComplete(legacyComplete(1)).orElseThrow());
        }
    }

    @Test
    void rejectsChunkAboveSizeLimitAndRecovers() {
        String oversized = "x".repeat(SQLChunkTransferManager.MAX_CHUNK_SIZE + 1);

        try (SQLChunkTransferManager manager = new SQLChunkTransferManager()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.receiveChunk(legacyChunk(oversized, 0, 1))
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.receiveComplete(legacyComplete(1))
            );

            assertEquals("recovered", sendLegacyChunks(manager, "recovered").orElseThrow());
        }
    }

    @Test
    void acceptsMaximumChunkCountAndRejectsValuesOutsideBounds() {
        try (SQLChunkTransferManager manager = new SQLChunkTransferManager()) {
            for (int index = 0; index < SQLChunkTransferManager.MAX_CHUNKS; index++) {
                assertFalse(manager.receiveChunk(
                        legacyChunk("x", index, SQLChunkTransferManager.MAX_CHUNKS)
                ).isPresent());
            }
            assertEquals(
                    "x".repeat(SQLChunkTransferManager.MAX_CHUNKS),
                    manager.receiveComplete(legacyComplete(SQLChunkTransferManager.MAX_CHUNKS)).orElseThrow()
            );

            assertThrows(IllegalArgumentException.class, () -> manager.receiveChunk(legacyChunk("sql", 0, 0)));
            assertThrows(IllegalArgumentException.class, () -> manager.receiveChunk(legacyChunk("sql", 0, -1)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.receiveChunk(legacyChunk("sql", 0, SQLChunkTransferManager.MAX_CHUNKS + 1))
            );
        }
    }

    @Test
    void rejectsIndexesOutsideBounds() {
        try (SQLChunkTransferManager manager = new SQLChunkTransferManager()) {
            assertThrows(IllegalArgumentException.class, () -> manager.receiveChunk(legacyChunk("sql", -1, 1)));
            assertThrows(IllegalArgumentException.class, () -> manager.receiveChunk(legacyChunk("sql", 1, 1)));
        }
    }

    @Test
    void rejectsNonIntegralOrNonFiniteNumericFields() {
        try (SQLChunkTransferManager manager = new SQLChunkTransferManager()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.receiveChunk(chunkWithNumbers("sql", 0.5, 1.0))
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.receiveChunk(chunkWithNumbers("sql", Double.NaN, 1.0))
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.receiveChunk(chunkWithNumbers("sql", 0.0, Double.POSITIVE_INFINITY))
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.receiveChunk(Map.of("chunk", "sql", "index", "0", "total", 1.0))
            );
        }
    }

    @Test
    void rejectsMissingOrInvalidChunkPayload() {
        try (SQLChunkTransferManager manager = new SQLChunkTransferManager()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.receiveChunk(Map.of("index", 0.0, "total", 1.0))
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.receiveChunk(Map.of("chunk", 42, "index", 0.0, "total", 1.0))
            );
            assertThrows(IllegalArgumentException.class, () -> manager.receiveChunk(null));
            assertThrows(IllegalArgumentException.class, () -> manager.receiveChunk("not-a-map"));
        }
    }

    @Test
    void enforcesRequestIdLengthBoundary() {
        String maximumRequestId = "r".repeat(128);
        String oversizedRequestId = "r".repeat(129);

        try (SQLChunkTransferManager manager = new SQLChunkTransferManager()) {
            assertFalse(manager.receiveChunk(chunk(maximumRequestId, "sql", 0, 1)).isPresent());
            assertEquals("sql", manager.receiveComplete(complete(maximumRequestId, 1)).orElseThrow());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.receiveChunk(chunk(oversizedRequestId, "sql", 0, 1))
            );
        }
    }

    @Test
    void explicitRequestIdSupportsOutOfOrderChunksAndEarlyComplete() {
        try (SQLChunkTransferManager manager = new SQLChunkTransferManager()) {
            assertFalse(manager.receiveChunk(chunk("request", "third", 2, 3)).isPresent());
            assertFalse(manager.receiveComplete(complete("request", 3)).isPresent());
            assertFalse(manager.receiveChunk(chunk("request", "first-", 0, 3)).isPresent());

            assertEquals(
                    "first-second-third",
                    manager.receiveChunk(chunk("request", "second-", 1, 3)).orElseThrow()
            );
        }
    }

    @Test
    void explicitRequestIdIgnoresMatchingDuplicateAndRejectsConflictingDuplicate() {
        try (SQLChunkTransferManager manager = new SQLChunkTransferManager()) {
            assertFalse(manager.receiveChunk(chunk("request", "first", 0, 2)).isPresent());
            assertFalse(manager.receiveChunk(chunk("request", "first", 0, 2)).isPresent());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.receiveChunk(chunk("request", "different", 0, 2))
            );
            assertFalse(manager.receiveComplete(complete("request", 2)).isPresent());
        }
    }

    @Test
    void rejectsLegacyTotalMismatchAndRecoversAtCompletionBoundary() {
        try (SQLChunkTransferManager manager = new SQLChunkTransferManager()) {
            assertFalse(manager.receiveChunk(legacyChunk("first", 0, 2)).isPresent());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.receiveChunk(legacyChunk("second", 1, 3))
            );
            assertFalse(manager.receiveComplete(legacyComplete(2)).isPresent());

            assertEquals("recovered", sendLegacyChunks(manager, "recovered").orElseThrow());
        }
    }

    @Test
    void rejectsLegacyCompletionTotalMismatchAndRecoversImmediately() {
        try (SQLChunkTransferManager manager = new SQLChunkTransferManager()) {
            assertFalse(manager.receiveChunk(legacyChunk("first", 0, 2)).isPresent());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.receiveComplete(legacyComplete(3))
            );

            assertEquals("recovered", sendLegacyChunks(manager, "recovered").orElseThrow());
        }
    }

    @Test
    void cancelAllDiscardsAnActiveLegacyTransfer() {
        try (SQLChunkTransferManager manager = new SQLChunkTransferManager()) {
            assertFalse(manager.receiveChunk(legacyChunk("stale", 0, 2)).isPresent());
            manager.cancelAll();

            assertEquals("fresh", sendLegacyChunks(manager, "fresh").orElseThrow());
        }
    }

    @Test
    void closeRejectsFurtherPackets() {
        SQLChunkTransferManager manager = new SQLChunkTransferManager();
        manager.close();

        assertThrows(
                IllegalStateException.class,
                () -> manager.receiveChunk(legacyChunk("sql", 0, 1))
        );
    }

    @Test
    void timeoutDiscardsAnIncompleteLegacyTransfer() {
        ManualScheduler scheduler = new ManualScheduler();
        try (SQLChunkTransferManager manager = new SQLChunkTransferManager(scheduler, Duration.ofSeconds(30))) {
            assertFalse(manager.receiveChunk(legacyChunk("stale", 0, 2)).isPresent());
            scheduler.runScheduled();

            assertEquals("fresh", sendLegacyChunks(manager, "fresh").orElseThrow());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void limitsConcurrentActiveTransfers() {
        try (SQLChunkTransferManager manager = new SQLChunkTransferManager()) {
            assertFalse(manager.receiveChunk(chunk("request-a", "a", 0, 2)).isPresent());
            assertFalse(manager.receiveChunk(chunk("request-b", "b", 0, 2)).isPresent());

            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.receiveChunk(chunk("request-c", "c", 0, 2))
            );
        }
    }

    @Test
    void preservesSurrogatePairSplitAtChunkBoundary() {
        String sql = "x".repeat(SQLChunkTransferManager.MAX_CHUNK_SIZE - 1) + "😀" + "tail";

        try (SQLChunkTransferManager manager = new SQLChunkTransferManager()) {
            assertEquals(sql, sendLegacyChunks(manager, sql).orElseThrow());
        }
    }

    private static Optional<String> sendLegacyChunks(SQLChunkTransferManager manager, String sql) {
        int chunkSize = SQLChunkTransferManager.MAX_CHUNK_SIZE;
        int total = Math.max(1, (sql.length() + chunkSize - 1) / chunkSize);
        for (int index = 0; index < total; index++) {
            String chunk = sql.substring(index * chunkSize, Math.min(sql.length(), (index + 1) * chunkSize));
            assertFalse(manager.receiveChunk(legacyChunk(chunk, index, total)).isPresent());
        }
        return manager.receiveComplete(legacyComplete(total));
    }

    private static Map<String, Object> legacyChunk(String chunk, int index, int total) {
        Map<String, Object> data = new HashMap<>();
        data.put("chunk", chunk);
        data.put("index", (double) index);
        data.put("total", (double) total);
        return data;
    }

    private static Map<String, Object> legacyComplete(int total) {
        Map<String, Object> data = new HashMap<>();
        data.put("total", (double) total);
        return data;
    }

    private static Map<String, Object> chunkWithNumbers(String chunk, Object index, Object total) {
        Map<String, Object> data = new HashMap<>();
        data.put("chunk", chunk);
        data.put("index", index);
        data.put("total", total);
        return data;
    }

    private static Map<String, Object> chunk(String requestId, String chunk, int index, int total) {
        Map<String, Object> data = legacyChunk(chunk, index, total);
        data.put("requestId", requestId);
        return data;
    }

    private static Map<String, Object> complete(String requestId, int total) {
        Map<String, Object> data = legacyComplete(total);
        data.put("requestId", requestId);
        return data;
    }

    private static final class ManualScheduler extends ScheduledThreadPoolExecutor {
        private ManualScheduledFuture scheduled;

        private ManualScheduler() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            this.scheduled = new ManualScheduledFuture(command);
            return this.scheduled;
        }

        private void runScheduled() {
            if (this.scheduled == null) {
                throw new IllegalStateException("no task scheduled");
            }
            this.scheduled.run();
        }
    }

    private static final class ManualScheduledFuture extends FutureTask<Void> implements ScheduledFuture<Void> {
        private ManualScheduledFuture(Runnable runnable) {
            super(runnable, null);
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(java.util.concurrent.Delayed ignored) {
            return 0;
        }
    }
}
