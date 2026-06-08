package com.ebanking.transaction.api;

import com.ebanking.transaction.dto.TransactionDto;
import com.ebanking.transaction.dto.TransactionPageResponse;
import com.ebanking.transaction.service.TransactionQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
@Validated
public class TransactionController {

    private final TransactionQueryService queryService;

    public TransactionController(TransactionQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * GET /api/v1/transactions?year=&month=&currency=&page=&size=
     *
     * The customer identity is extracted exclusively from the validated JWT {@code sub}
     * claim — it is NEVER accepted as a request parameter (prevents IDOR).
     */
    @GetMapping
    public ResponseEntity<TransactionPageResponse> getTransactionsForMonth(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @Min(1900) @Max(2100) int year,
            @RequestParam @Min(1)    @Max(12)   int month,
            @RequestParam(required = false, defaultValue = "EUR")
                @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter ISO 4217 code")
                String currency,
            @RequestParam(required = false, defaultValue = "0")  @Min(0)   int page,
            @RequestParam(required = false, defaultValue = "50") @Min(1) @Max(200) int size) {

        String customerId = jwt.getSubject();

        TransactionPageResponse response =
                queryService.getTransactions(customerId, year, month, currency, page, size);

        return ResponseEntity.ok()
                .header("X-Correlation-Id", correlationId(jwt))
                .body(response);
    }

    /**
     * GET /api/v1/transactions/{transactionId}
     *
     * Returns 403 (not 404) when the transaction exists but belongs to a different
     * customer, to avoid leaking the existence of other customers' records.
     */
    @GetMapping("/{transactionId}")
    public ResponseEntity<TransactionDto> getTransactionById(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String transactionId) {

        String customerId = jwt.getSubject();
        TransactionDto dto = queryService.getTransactionById(customerId, transactionId);

        return ResponseEntity.ok()
                .header("X-Correlation-Id", correlationId(jwt))
                .body(dto);
    }

    private static String correlationId(Jwt jwt) {
        return jwt.getId() != null ? jwt.getId() : UUID.randomUUID().toString();
    }
}
