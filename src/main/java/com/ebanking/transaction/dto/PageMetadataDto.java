package com.ebanking.transaction.dto;

public record PageMetadataDto(
        int page,
        int size,
        long totalElements,
        int totalPages) {}
