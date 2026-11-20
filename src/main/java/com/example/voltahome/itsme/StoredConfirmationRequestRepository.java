package com.example.voltahome.itsme;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/**
 * README: "Database access". The table itsme_confirmation_request. Saving and reading by state are the methods
 * JpaRepository already has (save, findById). Only the deletes are written here: each is one DELETE statement that
 * returns the number of rows deleted, which deleteById does not.
 */
@Transactional
interface StoredConfirmationRequestRepository extends JpaRepository<StoredConfirmationRequest, String> {

    @Modifying
    @Query("delete from StoredConfirmationRequest r where r.state = :state")
    int deleteByState(String state);

    @Modifying
    @Query("delete from StoredConfirmationRequest r where r.expiresAt < :now")
    int deleteExpired(Instant now);
}
