package com.example.voltahome.demo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.example.voltahome.itsme.ItsmeConfirmationResult;
import org.springframework.stereotype.Component;

/**
 * DEMO ONLY. Stands in for the application's business operation and its audit trail: a real application executes the
 * operation and keeps the ItsmeConfirmationResult (who, what, when, at which level) as evidence of the approval.
 * Kept in memory: lost on restart, not shared between instances.
 */
@Component
public class DemoConfirmedOperations {

    private final Map<String, List<ItsmeConfirmationResult>> byCustomer = new ConcurrentHashMap<>();

    void record(ItsmeConfirmationResult result) {
        byCustomer.computeIfAbsent(result.customerId(), id -> new CopyOnWriteArrayList<>()).add(result);
    }

    public List<ItsmeConfirmationResult> forCustomer(String customerId) {
        return List.copyOf(byCustomer.getOrDefault(customerId, List.of()));
    }

    public void clear() {
        byCustomer.clear();
    }
}
