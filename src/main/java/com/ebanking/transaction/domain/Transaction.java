package com.ebanking.transaction.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * JPA entity for the CQRS read-model projection of the Kafka transactions topic.
 *
 * The composite index {@code (customer_id, value_date DESC)} makes the primary
 * query pattern — filter by customer + month range, order by date — an efficient
 * indexed range scan regardless of total table size.
 *
 * {@code amount} is signed: positive = credit, negative = debit.
 * Always use {@link BigDecimal} — never double — for monetary values.
 */
@Entity
@Table(
    name = "transaction",
    indexes = {
        @Index(
            name = "idx_transaction_customer_date",
            columnList = "customer_id, value_date"
        )
    }
)
public class Transaction {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "customer_id", length = 50, nullable = false, updatable = false)
    private String customerId;

    /** Signed decimal: positive = credit, negative = debit. */
    @Column(name = "amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "iban", length = 34, nullable = false)
    private String iban;

    @Column(name = "value_date", nullable = false)
    private LocalDate valueDate;

    @Column(name = "description", length = 255, nullable = false)
    private String description;

    @Column(name = "ingested_at", nullable = false, updatable = false)
    private LocalDateTime ingestedAt;

    protected Transaction() {}

    public Transaction(String id, String customerId, BigDecimal amount,
                       String currency, String iban,
                       LocalDate valueDate, String description) {
        this.id = id;
        this.customerId = customerId;
        this.amount = amount;
        this.currency = currency;
        this.iban = iban;
        this.valueDate = valueDate;
        this.description = description;
        this.ingestedAt = LocalDateTime.now();
    }

    public String getId()              { return id; }
    public String getCustomerId()      { return customerId; }
    public BigDecimal getAmount()      { return amount; }
    public String getCurrency()        { return currency; }
    public String getIban()            { return iban; }
    public LocalDate getValueDate()    { return valueDate; }
    public String getDescription()     { return description; }
    public LocalDateTime getIngestedAt() { return ingestedAt; }
}
