package com.ebanking.transaction.kafka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * Tolerant reader for the Kafka topic JSON payload.
 *
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} is the key schema-evolution
 * safeguard: events spanning 10 years will have drifted; we silently ignore fields we
 * don't recognise rather than failing deserialization.  New required fields should be
 * added as {@code Optional} or with a default value.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransactionEvent(
        String id,
        String customerId,
        BigDecimal amount,       // signed: positive = credit, negative = debit
        String currency,         // ISO 4217, e.g. "GBP"
        String iban,
        String valueDate,        // yyyy-MM-dd string from Kafka payload
        String description) {}
