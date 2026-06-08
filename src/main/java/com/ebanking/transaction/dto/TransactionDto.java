package com.ebanking.transaction.dto;

/** Wire representation of a single transaction (matches the OpenAPI {@code Transaction} schema). */
public record TransactionDto(
        String id,
        MoneyDto money,
        String iban,
        String valueDate,      // ISO 8601 date string, e.g. "2020-10-01"
        String description) {}
