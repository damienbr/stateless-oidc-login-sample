package com.example.voltahome.operation;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/**
 * README: "Database access". The table pending_operation. A pending operation is only ever read for its customer and
 * its type. The deletes are single DELETE statements that return the number of rows deleted.
 */
@Transactional
interface StoredPendingOperationRepository extends JpaRepository<StoredPendingOperation, String> {

    Optional<StoredPendingOperation> findByOperationIdAndCustomerIdAndOperationType(
            String operationId, String customerId, String operationType);

    @Modifying
    @Query("delete from StoredPendingOperation p where p.operationId = :operationId")
    int deleteByOperationId(String operationId);

    @Modifying
    @Query("delete from StoredPendingOperation p where p.expiresAt < :now")
    int deleteExpired(Instant now);
}
