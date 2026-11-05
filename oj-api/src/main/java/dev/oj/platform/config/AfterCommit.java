package dev.oj.platform.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class AfterCommit {

    private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

    private AfterCommit() {
    }

    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            execute(action);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                execute(action);
            }
        });
    }

    private static void execute(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Việc sau-commit thất bại (dữ liệu vẫn đúng): {}", e.toString());
        }
    }
}
