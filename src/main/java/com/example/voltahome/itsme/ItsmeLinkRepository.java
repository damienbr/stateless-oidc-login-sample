package com.example.voltahome.itsme;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * README: "Customer mapping". Links between a customer and an itsme account ("sub"), one per customer and vice versa.
 * Read-only for the login: links are created outside it (README: "Authentication, not identification").
 */
@Repository
public class ItsmeLinkRepository {

    private final JdbcClient jdbc;

    public ItsmeLinkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> findCustomerBySub(String sub) {
        return jdbc.sql("SELECT customer_id FROM itsme_link WHERE itsme_sub = :sub")
                .param("sub", sub).query(String.class).optional();
    }

    public void touch(String customerId, Instant now) {
        jdbc.sql("UPDATE itsme_link SET last_login_at = :now WHERE customer_id = :id")
                .param("id", customerId).param("now", Timestamp.from(now)).update();
    }
}
