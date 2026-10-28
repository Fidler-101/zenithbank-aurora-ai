package com.zenithbank.txnstatus.controller;

import com.zenithbank.txnstatus.dto.ApiResponse;
import com.zenithbank.txnstatus.dto.TransactionStatusResponse;
import com.zenithbank.txnstatus.exception.StatusException;
import com.zenithbank.txnstatus.service.TransactionStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.logging.Logger;

import java.util.UUID;

/**
 * Transaction Status Controller
 *
 * Single endpoint: GET /api/transactions/{id}/status
 *
 * Resolves the status of a transaction or payment by ID.
 * The ID can belong to either banking-service or payment-service —
 * this service queries both transparently.
 */
@RestController
@RequiredArgsConstructor
public class TransactionStatusController {
    private static final Logger log = Logger.getLogger(TransactionStatusController.class.getName());


    private final TransactionStatusService statusService;

    /**
     * GET /{id}/status
     *
     * Returns the current status of any transaction or payment.
     * Possible statuses: PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED.
     */
    @GetMapping("/{id}/status")
    public ResponseEntity<ApiResponse<TransactionStatusResponse>> getStatus(
            @PathVariable UUID id,
            @RequestHeader("X-Customer-Id") Long customerId
    ) {
        TransactionStatusResponse response = statusService.getStatus(id, customerId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @ExceptionHandler(StatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleStatusException(StatusException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
    }
}
