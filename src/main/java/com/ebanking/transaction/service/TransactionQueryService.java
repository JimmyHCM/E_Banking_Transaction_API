package com.ebanking.transaction.service;

import com.ebanking.transaction.domain.Transaction;
import com.ebanking.transaction.domain.TransactionRepository;
import com.ebanking.transaction.dto.*;
import com.ebanking.transaction.service.fx.FxRateService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class TransactionQueryService {

    private final TransactionRepository repository;
    private final FxRateService fxRateService;

    public TransactionQueryService(TransactionRepository repository, FxRateService fxRateService) {
        this.repository = repository;
        this.fxRateService = fxRateService;
    }

    /** Returns a page of transactions for the given customer + calendar month. */
    public TransactionPageResponse getTransactions(
            String customerId, int year, int month,
            String targetCurrency, int page, int size) {

        YearMonth ym    = YearMonth.of(year, month);
        LocalDate start = ym.atDay(1);
        LocalDate end   = ym.atEndOfMonth();

        Page<Transaction> txPage = repository
                .findByCustomerIdAndValueDateBetweenOrderByValueDateDesc(
                        customerId, start, end, PageRequest.of(page, size));

        LocalDate rateDate = LocalDate.now();
        Map<String, BigDecimal> rates = buildRateMap(txPage.getContent(), targetCurrency, rateDate);
        BigDecimal[] totals = computeTotals(txPage.getContent(), targetCurrency, rates);

        return new TransactionPageResponse(
                txPage.getContent().stream().map(this::toDto).toList(),
                toTotalsDto(totals[0], totals[1], targetCurrency, rateDate, rates),
                toPageMeta(txPage)
        );
    }

    /** Returns a single transaction, enforcing ownership via the repository query. */
    public TransactionDto getTransactionById(String customerId, String transactionId) {
        return repository.findByIdAndCustomerId(transactionId, customerId)
                .map(this::toDto)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Transaction not found"));
    }

    // ── private helpers ───────────────────────────────────────────────────

    /**
     * Fetches one rate per distinct currency on this page (cache collapses duplicates).
     * Skips same-currency pairs (no conversion needed).
     */
    private Map<String, BigDecimal> buildRateMap(
            List<Transaction> txns, String targetCurrency, LocalDate rateDate) {

        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        txns.stream()
                .map(Transaction::getCurrency)
                .filter(ccy -> !ccy.equals(targetCurrency))
                .distinct()
                .forEach(ccy -> rates.put(
                        ccy + "/" + targetCurrency,
                        fxRateService.getRate(ccy, targetCurrency, rateDate)));
        return rates;
    }

    /**
     * Returns [totalCredit, totalDebit] in {@code targetCurrency}.
     * Uses {@link BigDecimal} throughout — never double — for monetary arithmetic.
     */
    private BigDecimal[] computeTotals(
            List<Transaction> txns, String targetCurrency, Map<String, BigDecimal> rates) {

        BigDecimal credit = BigDecimal.ZERO;
        BigDecimal debit  = BigDecimal.ZERO;

        for (Transaction tx : txns) {
            BigDecimal abs;
            if (tx.getCurrency().equals(targetCurrency)) {
                abs = tx.getAmount().abs();
            } else {
                BigDecimal rate = rates.get(tx.getCurrency() + "/" + targetCurrency);
                abs = tx.getAmount().abs().multiply(rate).setScale(2, RoundingMode.HALF_UP);
            }
            if (tx.getAmount().signum() >= 0) {
                credit = credit.add(abs);
            } else {
                debit = debit.add(abs);
            }
        }
        return new BigDecimal[]{credit, debit};
    }

    private TransactionDto toDto(Transaction tx) {
        return new TransactionDto(
                tx.getId(),
                new MoneyDto(tx.getAmount().toPlainString(), tx.getCurrency()),
                tx.getIban(),
                tx.getValueDate().toString(),
                tx.getDescription()
        );
    }

    private PageTotalsDto toTotalsDto(BigDecimal credit, BigDecimal debit,
                                      String currency, LocalDate rateDate,
                                      Map<String, BigDecimal> rates) {
        Map<String, String> ratesStr = new LinkedHashMap<>();
        rates.forEach((k, v) -> ratesStr.put(k, v.toPlainString()));
        return new PageTotalsDto(
                currency,
                rateDate.toString(),
                credit.toPlainString(),
                debit.toPlainString(),
                ratesStr
        );
    }

    private PageMetadataDto toPageMeta(Page<Transaction> p) {
        return new PageMetadataDto(p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }
}
