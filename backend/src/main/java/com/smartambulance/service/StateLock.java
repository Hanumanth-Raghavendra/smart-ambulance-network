package com.smartambulance.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * One lock serialises every change to trip state (API calls and the simulation tick), so a police approval
 * can never interleave with an ambulance movement step. Re-entrant, so services may call each other.
 */
@Component
public class StateLock {
    private final ReentrantLock lock = new ReentrantLock();

    public <T> T run(Supplier<T> body) {
        lock.lock();
        try {
            return body.get();
        } finally {
            lock.unlock();
        }
    }

    public void run(Runnable body) {
        run(() -> {
            body.run();
            return null;
        });
    }
}
