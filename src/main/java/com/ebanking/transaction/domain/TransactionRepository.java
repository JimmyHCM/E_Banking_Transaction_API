package com.ebanking.transaction.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, String> {

    /**
     * Indexed range scan on {@code (customer_id, value_date DESC)}.
     *
     * Uses {@code BETWEEN :startDate AND :endDate} (not {@code YEAR()}/{@code MONTH()}
     * functions) so the query planner can use the composite index and avoid a full
     * table scan — critical at the expected data volumes.
     *
     * Spring Data derives the SQL automatically from the method name:
     * {@code WHERE customer_id = ? AND value_date BETWEEN ? AND ? ORDER BY value_date DESC}
     */
    Page<Transaction> findByCustomerIdAndValueDateBetweenOrderByValueDateDesc(
            String customerId,
            LocalDate startDate,
            LocalDate endDate,
            Pageable pageable);

    /**
     * Used by the single-transaction endpoint.
     * Enforces ownership: only returns the row if it belongs to {@code customerId},
     * preventing IDOR without a separate authorization check.
     */
    Optional<Transaction> findByIdAndCustomerId(String id, String customerId);
}
