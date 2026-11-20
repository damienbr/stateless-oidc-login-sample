package com.example.voltahome.itsme;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.domain.Persistable;

/**
 * README: "Hand-over". One row of itsme_handover, one per itsme flow: the browser code challenge stored at the
 * start, then the one-time code and the result (JSON) set at the callback.
 * A row is inserted once: isNew() is always true, so that save() is an INSERT and never replaces an existing row
 * (see StoredAuthorizationRequest). The code and the result are set by a conditional update query
 * (StoredHandoverRepository.complete), never by saving this object again.
 */
@Entity
@Table(name = "itsme_handover")
public class StoredHandover implements Persistable<String> {

    @Id
    private String state;
    @Enumerated(EnumType.STRING)
    private ItsmeHandover.Flow flow;
    private String browserCodeChallenge;
    private String code;
    private String result;
    private Instant expiresAt;

    protected StoredHandover() {
        // for JPA
    }

    StoredHandover(String state, ItsmeHandover.Flow flow, String browserCodeChallenge, Instant expiresAt) {
        this.state = state;
        this.flow = flow;
        this.browserCodeChallenge = browserCodeChallenge;
        this.expiresAt = expiresAt;
    }

    @Override
    public String getId() {
        return state;
    }

    @Override
    public boolean isNew() {
        return true;
    }

    boolean isValidAt(Instant now) {
        return expiresAt.isAfter(now);
    }

    ItsmeHandover.Flow flow() {
        return flow;
    }

    String browserCodeChallenge() {
        return browserCodeChallenge;
    }

    String result() {
        return result;
    }
}
