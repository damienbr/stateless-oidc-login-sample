package com.example.voltahome.fraud;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * DEMO implementation. A real one sends the signals to fraud monitoring.
 * Logs the customer id and the itsme sub (a pseudonym), never token content.
 */
@Service
public class FraudService {

    private static final Logger log = LoggerFactory.getLogger(FraudService.class);
    private static final int MAX_KEPT = 1_000;   // DEMO: last signals kept in memory

    public record RaisedSignal(FraudSignal signal, String customerId, String itsmeSub, Instant at) {}

    private final List<RaisedSignal> raised = new CopyOnWriteArrayList<>();
    private final Clock clock;

    public FraudService(Clock clock) {
        this.clock = clock;
    }

    /** @param customerId null when the itsme account is not linked to a customer */
    public void report(FraudSignal signal, String customerId, String itsmeSub) {
        raised.add(new RaisedSignal(signal, customerId, itsmeSub, clock.instant()));
        while (raised.size() > MAX_KEPT) {
            raised.remove(0);
        }
        log.warn("Fraud signal {} (customer {}, itsme sub {})", signal, customerId, itsmeSub);
    }

    public List<RaisedSignal> raisedSignals() {
        return List.copyOf(raised);
    }

    public void clear() {
        raised.clear();
    }
}
