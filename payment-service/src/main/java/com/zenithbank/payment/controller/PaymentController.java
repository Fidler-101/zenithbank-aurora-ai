package com.zenithbank.payment.controller;

import com.zenithbank.payment.dto.ApiResponse;
import com.zenithbank.payment.dto.PaymentRequest;
import com.zenithbank.payment.dto.PaymentResponse;
import com.zenithbank.payment.exception.PaymentException;
import com.zenithbank.payment.service.PaymentService;
import com.zenithbank.payment.service.ProofOfPaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.logging.Logger;

import java.util.List;
import java.util.UUID;

/**
 * Payment Controller
 *
 * All endpoints read customerId from X-Customer-Id header,
 * injected by the API Gateway after JWT validation.
 *
 * Base path: /api/payments/** (prefix stripped by gateway)
 */
@RestController
@RequiredArgsConstructor
public class PaymentController {
    private static final Logger log = Logger.getLogger(PaymentController.class.getName());


    private final PaymentService       paymentService;
    private final ProofOfPaymentService proofService;

    // ── POST /create — Submit a new payment ──────────────────

    @PostMapping("/create")
    public ResponseEntity<ApiResponse<PaymentResponse>> createPayment(
            @Valid @RequestBody PaymentRequest request,
            @RequestHeader("X-Customer-Id") Long customerId
    ) {
        PaymentResponse response = paymentService.createPayment(request, customerId);

        String message = response.getPaymentDate().isAfter(java.time.LocalDate.now())
                ? "Payment scheduled for " + response.getPaymentDate() + "."
                : "Payment processed successfully.";

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(message, response));
    }

    // ── GET /{id} — Get a single payment ─────────────────────

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<PaymentResponse>> getPayment(
            @PathVariable UUID id,
            @RequestHeader("X-Customer-Id") Long customerId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(paymentService.getPayment(id, customerId)));
    }

    // ── GET /scheduled — List future-dated + recurring ───────

    @GetMapping("/scheduled")
    public ResponseEntity<ApiResponse<List<PaymentResponse>>> getScheduled(
            @RequestHeader("X-Customer-Id") Long customerId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(paymentService.getScheduledPayments(customerId)));
    }

    // ── DELETE /{id}/cancel — Cancel a scheduled payment ─────

    @DeleteMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<PaymentResponse>> cancelPayment(
            @PathVariable UUID id,
            @RequestHeader("X-Customer-Id") Long customerId
    ) {
        PaymentResponse response = paymentService.cancelPayment(id, customerId);
        return ResponseEntity.ok(ApiResponse.ok("Payment cancelled successfully.", response));
    }

    // ── GET /{id}/proof — Download proof of payment PDF ──────

    @GetMapping("/{id}/proof")
    public ResponseEntity<byte[]> downloadProof(
            @PathVariable UUID id,
            @RequestHeader("X-Customer-Id") Long customerId
    ) {
        byte[] pdf = proofService.generate(id, customerId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData(
            "attachment",
            "ZenithBank_ProofOfPayment_" + id.toString().substring(0, 8).toUpperCase() + ".pdf"
        );
        headers.setContentLength(pdf.length);

        return ResponseEntity.ok().headers(headers).body(pdf);
    }

    // ── Exception handler ─────────────────────────────────────

    @ExceptionHandler(PaymentException.class)
    public ResponseEntity<ApiResponse<Void>> handlePaymentException(PaymentException e) {
        log.warning("Payment exception: " + e.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
    }
}
