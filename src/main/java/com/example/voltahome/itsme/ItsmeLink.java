package com.example.voltahome.itsme;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * README: "Customer mapping". One row of itsme_link: the itsme account ("sub") of a customer. Never created by the
 * login (README: "Authentication, not identification"): there is no public constructor.
 */
@Entity
@Table(name = "itsme_link")
public class ItsmeLink {

    @Id
    private String customerId;
    private String itsmeSub;
    private Instant linkedAt;
    private Instant lastLoginAt;

    protected ItsmeLink() {
        // for JPA
    }
}
