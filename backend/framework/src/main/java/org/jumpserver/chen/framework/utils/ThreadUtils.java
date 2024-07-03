package org.jumpserver.chen.framework.utils;

import org.jumpserver.chen.framework.session.SessionManager;

import java.util.concurrent.*;

public class ThreadUtils {

    public static class SessionCtxRunnable implements Runnable {
        private final Runnable runnable;
        private final String token;

        public SessionCtxRunnable(Runnable runnable, String sessionToken) {
            this.runnable = runnable;
            this.token = sessionToken;
        }

        @Override
        public void run() {
            SessionManager.setContext(token);
            runnable.run();
        }
    }


    public static void runWithTimeout(SessionCtxRunnable runnable, int timeout) {
        if (timeout < 0) {
            runnable.run();
            return;
        }
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = executorService.submit(runnable);
            future.get(timeout, TimeUnit.SECONDS);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            throw new RuntimeException(e);
        } finally {
            executorService.shutdown();
        }
    }
}
