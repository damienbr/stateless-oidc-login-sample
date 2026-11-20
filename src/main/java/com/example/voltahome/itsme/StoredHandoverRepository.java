package com.example.voltahome.itsme;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/**
 * README: "Database access". The table itsme_handover. The update and the deletes are single statements that return
 * the number of rows changed: that number is what makes a code single use.
 */
@Transactional
interface StoredHandoverRepository extends JpaRepository<StoredHandover, String> {

    Optional<StoredHandover> findByCode(String code);

    /** Sets the code and the result of a flow that was begun, has not expired and was not completed yet. */
    @Modifying
    @Query("""
            update StoredHandover h set h.code = :code, h.result = :result, h.expiresAt = :expiresAt
            where h.state = :state and h.flow = :flow and h.code is null and h.expiresAt > :now
            """)
    int complete(String state, ItsmeHandover.Flow flow, String code, String result, Instant expiresAt, Instant now);

    @Modifying
    @Query("delete from StoredHandover h where h.code = :code")
    int deleteByCode(String code);

    @Modifying
    @Query("delete from StoredHandover h where h.expiresAt < :now")
    int deleteExpired(Instant now);
}
