package com.ebanking.transaction.api.advice;

import com.ebanking.transaction.service.fx.FxRateUnavailableException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

/**
 * Maps exceptions to RFC 7807 {@code application/problem+json} responses.
 * Spring 6 {@link ProblemDetail} implements the RFC natively — no custom type needed.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String PROBLEMS_BASE = "https://api.example-bank.com/problems/";

    @ExceptionHandler(FxRateUnavailableException.class)
    public ProblemDetail handleFxUnavailable(FxRateUnavailableException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
        pd.setType(URI.create(PROBLEMS_BASE + "fx-unavailable"));
        pd.setTitle("Exchange rate provider unavailable");
        return pd;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatus(ResponseStatusException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.valueOf(ex.getStatusCode().value()), ex.getReason());
        pd.setType(URI.create(PROBLEMS_BASE + ex.getStatusCode().value()));
        return pd;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleValidation(ConstraintViolationException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setType(URI.create(PROBLEMS_BASE + "validation"));
        pd.setTitle("Invalid request parameter");
        return pd;
    }
}
