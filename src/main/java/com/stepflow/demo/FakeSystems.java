package com.stepflow.demo;

import com.stepflow.definition.NonRetryableException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stand-ins for the inventory, payment and shipping systems an order touches. Each one
 * is idempotent on the run id, which is the pattern real handlers should follow: a step can
 * run twice (retry, crashed worker), and the second call must not charge the card twice.
 */
class FakeSystems {

    private final DemoLedger ledger;
    private final long latencyMillis;
    private final Map<UUID, String> reservations = new ConcurrentHashMap<>();
    private final Map<UUID, String> charges = new ConcurrentHashMap<>();
    private final Map<UUID, String> shipments = new ConcurrentHashMap<>();

    FakeSystems(DemoLedger ledger, long latencyMillis) {
        this.ledger = ledger;
        this.latencyMillis = latencyMillis;
    }

    String reserveStock(UUID runId, String orderId) throws InterruptedException {
        pause();
        String id = reservations.computeIfAbsent(runId, k -> {
            ledger.add("reserved stock for " + orderId);
            return "RSV-" + orderId;
        });
        return id;
    }

    void releaseStock(UUID runId, String orderId) throws InterruptedException {
        pause();
        if (reservations.remove(runId) != null) {
            ledger.add("released stock for " + orderId);
        }
    }

    String charge(UUID runId, String orderId, int amount, String mode, int attempt) throws InterruptedException {
        pause();
        if ("declined".equals(mode)) {
            throw new NonRetryableException("card declined by issuer");
        }
        if ("flaky".equals(mode) && attempt < 3) {
            throw new IllegalStateException("payment gateway timed out (attempt " + attempt + ")");
        }
        return charges.computeIfAbsent(runId, k -> {
            ledger.add("charged " + amount + " for " + orderId);
            return "CH-" + orderId;
        });
    }

    void refund(UUID runId, String orderId, int amount) throws InterruptedException {
        pause();
        if (charges.remove(runId) != null) {
            ledger.add("refunded " + amount + " for " + orderId);
        }
    }

    String ship(UUID runId, String orderId, String mode) throws InterruptedException {
        pause();
        if ("fail".equals(mode)) {
            throw new NonRetryableException("carrier rejected the address");
        }
        return shipments.computeIfAbsent(runId, k -> {
            ledger.add("shipped " + orderId);
            return "TRK-" + orderId;
        });
    }

    private void pause() throws InterruptedException {
        if (latencyMillis > 0) {
            Thread.sleep(latencyMillis);
        }
    }
}
