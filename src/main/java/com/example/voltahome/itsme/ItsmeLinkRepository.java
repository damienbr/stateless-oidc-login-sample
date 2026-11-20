package com.example.voltahome.itsme;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * README: "Customer mapping". Links between a customer and an itsme account ("sub"), one per customer and vice versa.
 * Read-only for the login: links are created outside it (README: "Authentication, not identification"). That is why
 * this interface extends Repository, which has no method, and not JpaRepository: there is no save() to call.
 */
public interface ItsmeLinkRepository extends Repository<ItsmeLink, String> {

    @Query("select l.customerId from ItsmeLink l where l.itsmeSub = :sub")
    Optional<String> findCustomerBySub(String sub);

    /** README: "Confirmation of an operation". The itsme account that may confirm the customer's operations. */
    @Query("select l.itsmeSub from ItsmeLink l where l.customerId = :customerId")
    Optional<String> findSubByCustomer(String customerId);

    @Modifying
    @Transactional
    @Query("update ItsmeLink l set l.lastLoginAt = :now where l.customerId = :customerId")
    void touch(String customerId, Instant now);
}
