package com.zenithbank.payment.scheduler;

import com.zenithbank.payment.entity.Payment;
import com.zenithbank.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.logging.Logger;

import java.util.List;

/**
 * Payment Scheduler
 *
 * Runs every day at 08:00 SAST to process:
 *   1. Future-dated payments whose paymentDate has arrived
 *   2. Recurring payments whose nextRunDate has arrived
 *
 * Each payment is processed independently so a single failure
 * does not block the rest of the batch.
 */
@Component
@RequiredArgsConstructor
public class PaymentScheduler {
    private static final Logger log = Logger.getLogger(PaymentScheduler.class.getName());


    private final PaymentService paymentService;

    // Runs daily at 08:00 SAST (UTC+2 = 06:00 UTC)
    @Scheduled(cron = "0 0 6 * * *", zone = "Africa/Johannesburg")
    public void processDuePayments() {
        List<Payment> due = paymentService.getDuePayments();

        if (due.isEmpty()) {
            log.info("Payment scheduler: no payments due today.");
            return;
        }

        log.info("Payment scheduler: processing " + due.size() + " due payment(s).");

        int success = 0, failed = 0;
        for (Payment payment : due) {
            try {
                paymentService.processPayment(payment);
                success++;
            } catch (Exception e) {
                failed++;
                log.severe("Scheduler failed to process payment " + payment.getId() + ": " + e.getMessage());
            }
        }

        log.info("Payment scheduler complete — " + success + " succeeded, " + failed + " failed.");
    }
}
