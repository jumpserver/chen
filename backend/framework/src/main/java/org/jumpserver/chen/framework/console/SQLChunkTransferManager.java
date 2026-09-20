package org.jumpserver.chen.framework.console;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class SQLChunkTransferManager implements AutoCloseable {

    static final int MAX_CHUNK_SIZE = 4096;
    static final int MAX_CHUNKS = 1024;
    // Luna's current chunk protocol has no requestId. A manager belongs to one QueryConsole,
    // whose packets are processed serially, so one reserved key safely represents that transfer.
    private static final TransferKey LEGACY_TRANSFER_KEY = new TransferKey(null);
    private static final int MAX_ACTIVE_TRANSFERS = 2;
    private static final int MAX_TRACKED_TRANSFERS = 64;
    private static final int MAX_REQUEST_ID_LENGTH = 128;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final ScheduledThreadPoolExecutor TIMEOUT_EXECUTOR = createTimeoutExecutor();

    private final Object lock = new Object();
    private final Map<TransferKey, ChunkTransfer> transfers = new HashMap<>();
    private final ScheduledExecutorService scheduler;
    private final Duration timeout;
    private boolean closed;

    SQLChunkTransferManager() {
        this(TIMEOUT_EXECUTOR, DEFAULT_TIMEOUT);
    }

    SQLChunkTransferManager(ScheduledExecutorService scheduler, Duration timeout) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    Optional<String> receiveChunk(Object rawData) {
        Map<?, ?> data = requireMap(rawData);
        TransferKey transferKey = requireTransferKey(data);
        int total;
        int index;
        String chunk;
        try {
            total = requireTotal(data);
            index = requireInt(data, "index");
            if (index < 0 || index >= total) {
                throw new IllegalArgumentException("index is outside the transfer range");
            }
            chunk = requireChunk(data);
        } catch (IllegalArgumentException e) {
            reject(transferKey);
            throw e;
        }

        synchronized (lock) {
            ensureOpen();
            ChunkTransfer transfer = getOrCreate(transferKey, total);
            if (transfer.terminal) {
                return Optional.empty();
            }
            if (transfer.total != total) {
                rejectLocked(transfer);
                throw new IllegalArgumentException("total does not match the existing transfer");
            }
            if (transferKey.isLegacy() && index != transfer.receivedChunks) {
                rejectLocked(transfer);
                throw new IllegalArgumentException("legacy chunks must arrive in order");
            }

            String existing = transfer.chunks[index];
            if (existing != null) {
                if (!existing.equals(chunk)) {
                    rejectLocked(transfer);
                    throw new IllegalArgumentException("duplicate chunk has different content");
                }
                return Optional.empty();
            }

            transfer.chunks[index] = chunk;
            transfer.receivedChunks += 1;
            return assembleIfReady(transferKey, transfer);
        }
    }

    Optional<String> receiveComplete(Object rawData) {
        Map<?, ?> data = requireMap(rawData);
        TransferKey transferKey = requireTransferKey(data);
        int total;
        try {
            total = requireTotal(data);
        } catch (IllegalArgumentException e) {
            rejectAndForgetLegacy(transferKey);
            throw e;
        }

        synchronized (lock) {
            ensureOpen();
            ChunkTransfer transfer = getOrCreate(transferKey, total);
            if (transfer.terminal) {
                forgetLegacyTransferLocked(transferKey, transfer);
                return Optional.empty();
            }
            if (transfer.total != total) {
                rejectLocked(transfer);
                forgetLegacyTransferLocked(transferKey, transfer);
                throw new IllegalArgumentException("total does not match the existing transfer");
            }
            if (transferKey.isLegacy() && transfer.receivedChunks != transfer.total) {
                rejectLocked(transfer);
                forgetLegacyTransferLocked(transferKey, transfer);
                throw new IllegalArgumentException("legacy transfer completed before all chunks arrived");
            }

            transfer.completeReceived = true;
            return assembleIfReady(transferKey, transfer);
        }
    }

    void cancelAll() {
        synchronized (lock) {
            clearTransfersLocked();
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            clearTransfersLocked();
        }
    }

    private ChunkTransfer getOrCreate(TransferKey transferKey, int total) {
        ChunkTransfer existing = transfers.get(transferKey);
        if (existing != null) {
            return existing;
        }
        if (transfers.size() >= MAX_TRACKED_TRANSFERS) {
            throw new IllegalArgumentException("too many tracked chunk transfers");
        }
        long activeTransfers = transfers.values().stream()
                .filter(transfer -> !transfer.terminal)
                .count();
        if (activeTransfers >= MAX_ACTIVE_TRANSFERS) {
            throw new IllegalArgumentException("too many active chunk transfers");
        }

        ChunkTransfer transfer = new ChunkTransfer(total);
        transfers.put(transferKey, transfer);
        try {
            transfer.timeoutFuture = scheduler.schedule(
                    () -> expire(transferKey, transfer),
                    timeout.toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (RuntimeException e) {
            transfers.remove(transferKey, transfer);
            throw e;
        }
        return transfer;
    }

    private Optional<String> assembleIfReady(TransferKey transferKey, ChunkTransfer transfer) {
        if (!transfer.completeReceived || transfer.receivedChunks != transfer.total) {
            return Optional.empty();
        }

        StringBuilder sql = new StringBuilder();
        for (String chunk : transfer.chunks) {
            sql.append(chunk);
        }
        String assembled = sql.toString();
        rejectLocked(transfer);
        forgetLegacyTransferLocked(transferKey, transfer);
        return Optional.of(assembled);
    }

    private void reject(TransferKey transferKey) {
        synchronized (lock) {
            ChunkTransfer transfer = transfers.get(transferKey);
            if (transfer != null) {
                rejectLocked(transfer);
            }
        }
    }

    private void rejectAndForgetLegacy(TransferKey transferKey) {
        synchronized (lock) {
            ChunkTransfer transfer = transfers.get(transferKey);
            if (transfer != null) {
                rejectLocked(transfer);
                forgetLegacyTransferLocked(transferKey, transfer);
            }
        }
    }

    private void rejectLocked(ChunkTransfer transfer) {
        if (transfer.terminal) {
            return;
        }
        transfer.terminal = true;
        transfer.completeReceived = false;
        transfer.receivedChunks = 0;
        Arrays.fill(transfer.chunks, null);
    }

    private void expire(TransferKey transferKey, ChunkTransfer expectedTransfer) {
        synchronized (lock) {
            if (transfers.remove(transferKey, expectedTransfer)) {
                rejectLocked(expectedTransfer);
            }
        }
    }

    private void forgetLegacyTransferLocked(TransferKey transferKey, ChunkTransfer transfer) {
        if (!transferKey.isLegacy() || !transfers.remove(transferKey, transfer)) {
            return;
        }
        if (transfer.timeoutFuture != null) {
            transfer.timeoutFuture.cancel(false);
        }
    }

    private void clearTransfersLocked() {
        for (ChunkTransfer transfer : transfers.values()) {
            rejectLocked(transfer);
            if (transfer.timeoutFuture != null) {
                transfer.timeoutFuture.cancel(false);
            }
        }
        transfers.clear();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("chunk transfer manager is closed");
        }
    }

    private static Map<?, ?> requireMap(Object data) {
        if (!(data instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("chunk data must be an object");
        }
        return map;
    }

    private static TransferKey requireTransferKey(Map<?, ?> data) {
        Object value = data.get("requestId");
        if (value == null) {
            return LEGACY_TRANSFER_KEY;
        }
        if (!(value instanceof String requestId)
                || requestId.isBlank()
                || requestId.length() > MAX_REQUEST_ID_LENGTH) {
            throw new IllegalArgumentException("requestId is invalid");
        }
        return new TransferKey(requestId);
    }

    private static int requireTotal(Map<?, ?> data) {
        int total = requireInt(data, "total");
        if (total <= 0 || total > MAX_CHUNKS) {
            throw new IllegalArgumentException("total is outside the allowed range");
        }
        return total;
    }

    private static int requireInt(Map<?, ?> data, String key) {
        Object value = data.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(key + " must be a number");
        }
        try {
            return new BigDecimal(number.toString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a finite int", e);
        }
    }

    private static String requireChunk(Map<?, ?> data) {
        Object value = data.get("chunk");
        if (!(value instanceof String chunk)) {
            throw new IllegalArgumentException("chunk must be a string");
        }
        if (chunk.length() > MAX_CHUNK_SIZE) {
            throw new IllegalArgumentException("chunk exceeds the allowed size");
        }
        return chunk;
    }

    private static ScheduledThreadPoolExecutor createTimeoutExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "query-console-chunk-timeout");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static final class ChunkTransfer {
        private final int total;
        private final String[] chunks;
        private int receivedChunks;
        private boolean completeReceived;
        private boolean terminal;
        private ScheduledFuture<?> timeoutFuture;

        private ChunkTransfer(int total) {
            this.total = total;
            this.chunks = new String[total];
        }
    }

    private record TransferKey(String requestId) {
        private boolean isLegacy() {
            return requestId == null;
        }
    }
}
