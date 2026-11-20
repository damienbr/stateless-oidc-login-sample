package com.example.voltahome.demo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.example.voltahome.itsme.ItsmeConfirmationResult;
import org.springframework.stereotype.Component;

/**
 * DEMO ONLY. Stands in for the application's audit trail: once the operation is executed (DemoOrderConfirmation,
 * DemoOfferAcceptance), a real application keeps the ItsmeConfirmationResult (who, what, when, at which level) as
 * evidence of the approval, with the reference of the record it created or changed.
 * Kept in memory: lost on restart, not shared between instances.
 */
@Component
public class DemoConfirmedOperations {

    /** One executed operation: the record it created or changed, and the evidence of the approval. */
    public record Executed(String reference, ItsmeConfirmationResult evidence) {}

    private final Map<String, List<Executed>> byCustomer = new ConcurrentHashMap<>();

    void record(String reference, ItsmeConfirmationResult evidence) {
        byCustomer.computeIfAbsent(evidence.customerId(), id -> new CopyOnWriteArrayList<>())
                .add(new Executed(reference, evidence));
    }

    public List<Executed> forCustomer(String customerId) {
        return List.copyOf(byCustomer.getOrDefault(customerId, List.of()));
    }

    public void clear() {
        byCustomer.clear();
    }
}
