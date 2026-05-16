package com.pbj.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

@Service
public class AiJobQueueService {

    @Value("${ai.queue.max-concurrency:1}")
    private int maxConcurrency;

    private Semaphore semaphore;

    @PostConstruct
    void init() {
        semaphore = new Semaphore(Math.max(1, maxConcurrency), true);
    }

    public <T> T runQueued(String jobName, Supplier<T> supplier) {
        boolean acquired = false;
        long queuedAt = System.currentTimeMillis();
        long startedAt = 0L;
        try {
            System.out.println("INFO: [AI Queue] Waiting for slot: " + jobName);
            semaphore.acquire();
            acquired = true;
            startedAt = System.currentTimeMillis();
            long waitedMs = startedAt - queuedAt;
            System.out.println("INFO: [AI Queue] Started: " + jobName + " after waiting " + waitedMs + " ms");
            return supplier.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("AI job was interrupted while waiting in queue.", e);
        } finally {
            if (acquired) {
                semaphore.release();
                long durationMs = startedAt == 0L ? 0L : System.currentTimeMillis() - startedAt;
                System.out.println("INFO: [AI Queue] Finished: " + jobName + " in " + durationMs + " ms");
            }
        }
    }
}
