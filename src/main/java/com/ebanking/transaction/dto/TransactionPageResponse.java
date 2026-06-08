package com.ebanking.transaction.dto;

import java.util.List;

/** Top-level response envelope for GET /api/v1/transactions (matches OpenAPI {@code TransactionPage}). */
public record TransactionPageResponse(
        List<TransactionDto> content,
        PageTotalsDto totals,
        PageMetadataDto page) {}
