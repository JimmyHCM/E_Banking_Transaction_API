package com.ebanking.transaction.dto;

/**
 * A monetary amount in a single ISO 4217 currency.
 * {@code amount} is a decimal string (not a JSON number) to preserve
 * {@link java.math.BigDecimal} precision across serialization boundaries.
 */
public record MoneyDto(String amount, String currency) {}
