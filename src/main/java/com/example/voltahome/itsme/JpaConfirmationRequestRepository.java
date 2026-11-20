package com.example.voltahome.itsme;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * README: "Confirmation of an operation". Stores the confirmation in progress in the database, like
 * JpaAuthorizationRequestRepository does for the login, plus who started it and what is to be approved: the operation
 * type and reference (the id of the pending operation the application persisted), never
 * read from the callback (README: "Several operations, one callback"), and the text.
 * Read + checked delete makes each stored confirmation single-use, without database-specific SQL.
 */
@Component
public class JpaConfirmationRequestRepository {

    static final Duration TTL = Duration.ofMinutes(5);

    /** A confirmation in progress. nonce is the raw value; Spring hashes it to compare with the ID token. */
    public record PendingConfirmation(String state, String customerId, ItsmeOperation operation,
                               String nonce, String codeVerifier) {}

    private final StoredConfirmationRequestRepository requests;
    private final Clock clock;

    public JpaConfirmationRequestRepository(StoredConfirmationRequestRepository requests, Clock clock) {
        this.requests = requests;
        this.clock = clock;
    }

    public void save(PendingConfirmation pending) {
        Instant now = clock.instant();
        requests.save(new StoredConfirmationRequest(
                pending.state(),
                pending.customerId(),
                pending.operation().type(),
                pending.operation().reference(),
                pending.operation().text().value(),
                pending.nonce(),
                pending.codeVerifier(),
                now,
                now.plus(TTL)));
    }

    /** The confirmation started with this state, if it exists, has not expired and was not used yet. */
    @Transactional
    public Optional<PendingConfirmation> remove(String state) {
        if (state == null) {
            return Optional.empty();
        }
        Optional<PendingConfirmation> found = find(state);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        int deleted = requests.deleteByState(state);
        return deleted == 1 ? found : Optional.empty();   // 0 = already consumed by a concurrent callback
    }

    /** Removes abandoned confirmations. Idempotent: safe to run on several instances. */
    @Scheduled(fixedDelayString = "PT15M")
    public void purgeExpired() {
        requests.deleteExpired(clock.instant());
    }

    private Optional<PendingConfirmation> find(String state) {
        return requests.findById(state)
                .filter(stored -> stored.isValidAt(clock.instant()))
                .map(stored -> new PendingConfirmation(
                        stored.state(),
                        stored.customerId(),
                        new ItsmeOperation(stored.operationType(), stored.operationReference(),
                                ItsmeFreeText.stored(stored.approvalText())),
                        stored.nonce(),
                        stored.codeVerifier()));
    }
}
