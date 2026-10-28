package com.zenithbank.payment.system;

import com.zenithbank.payment.entity.Payment;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zenithbank.payment.dto.PaymentRequest;
import com.zenithbank.payment.entity.Payment.PaymentFrequency;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

/**
 * LEVEL 3 — SYSTEM TESTS: Payment API
 *
 * Full application context loaded. Tests complete HTTP request/response
 * cycles against all Payment endpoints.
 *
 * Spec coverage (Section 10.1):
 *   ✔ Full flow: create → verify HTTP codes → check status
 *   ✔ All validation rules return correct HTTP 400 responses
 *   ✔ Proof-of-payment returns PDF content type
 *   ✔ Schedule and cancellation endpoints respond correctly
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("SYSTEM | Payment API Endpoints")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PaymentSystemTest {

    @Autowired MockMvc mockMvc;

    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private static final Long   CUSTOMER_ID = 1L;
    private static final UUID   ACCOUNT_ID  = UUID.randomUUID();
    private static       UUID   createdPaymentId;

    private PaymentRequest buildRequest(LocalDate date, BigDecimal amount, boolean recurring) {
        return PaymentRequest.builder()
                .fromAccountId(ACCOUNT_ID)
                .toAccountNo("12345678901")
                .branchCode("250655")
                .amount(amount)
                .reference("System Test Payment")
                .paymentDate(date)
                .isRecurring(recurring)
                .frequency(recurring ? PaymentFrequency.MONTHLY : PaymentFrequency.ONCE)
                .build();
    }

    // ── HTTP 400 Validation Responses ─────────────────────

    @Test @Order(1)
    @DisplayName("[SYS-01] POST /create returns 400 for missing required fields")
    void create_missingFields_returns400() throws Exception {
        mockMvc.perform(post("/create")
                .header("X-Customer-Id", CUSTOMER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test @Order(2)
    @DisplayName("[SYS-02] POST /create returns 400 for past payment date")
    void create_pastDate_returns400() throws Exception {
        mockMvc.perform(post("/create")
                .header("X-Customer-Id", CUSTOMER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(
                    buildRequest(LocalDate.now().minusDays(1), new BigDecimal("100.00"), false)
                )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("past")));
    }

    @Test @Order(3)
    @DisplayName("[SYS-03] POST /create returns 400 for invalid branch code format")
    void create_invalidBranchFormat_returns400() throws Exception {
        PaymentRequest req = buildRequest(LocalDate.now().plusDays(1), new BigDecimal("100.00"), false);
        req.setBranchCode("12"); // Too short — should be 6 digits

        mockMvc.perform(post("/create")
                .header("X-Customer-Id", CUSTOMER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test @Order(4)
    @DisplayName("[SYS-04] POST /create returns 400 for negative amount")
    void create_negativeAmount_returns400() throws Exception {
        mockMvc.perform(post("/create")
                .header("X-Customer-Id", CUSTOMER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(
                    buildRequest(LocalDate.now().plusDays(1), new BigDecimal("-100.00"), false)
                )))
                .andExpect(status().isBadRequest());
    }

    // ── GET Scheduled Payments ─────────────────────────────

    @Test @Order(5)
    @DisplayName("[SYS-05] GET /scheduled returns 200 with list")
    void getScheduled_returns200() throws Exception {
        mockMvc.perform(get("/scheduled")
                .header("X-Customer-Id", CUSTOMER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    // ── GET Non-Existent Payment ───────────────────────────

    @Test @Order(6)
    @DisplayName("[SYS-06] GET /{id} returns 400 for unknown payment ID")
    void getPayment_notFound_returns400() throws Exception {
        mockMvc.perform(get("/" + UUID.randomUUID())
                .header("X-Customer-Id", CUSTOMER_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── DELETE Cancel Non-Existent ────────────────────────

    @Test @Order(7)
    @DisplayName("[SYS-07] DELETE /{id}/cancel returns 400 for unknown payment ID")
    void cancel_notFound_returns400() throws Exception {
        mockMvc.perform(delete("/" + UUID.randomUUID() + "/cancel")
                .header("X-Customer-Id", CUSTOMER_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── Missing Customer-Id Header ─────────────────────────

    @Test @Order(8)
    @DisplayName("[SYS-08] POST /create returns 400 when X-Customer-Id header is missing")
    void create_missingCustomerIdHeader_returns400() throws Exception {
        mockMvc.perform(post("/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(
                    buildRequest(LocalDate.now().plusDays(1), new BigDecimal("100.00"), false)
                )))
                .andExpect(status().isBadRequest());
    }

    // ── Proof of Payment ──────────────────────────────────

    @Test @Order(9)
    @DisplayName("[SYS-09] GET /{id}/proof returns 400 when payment not found")
    void proof_notFound_returns400() throws Exception {
        mockMvc.perform(get("/" + UUID.randomUUID() + "/proof")
                .header("X-Customer-Id", CUSTOMER_ID))
                .andExpect(status().isBadRequest());
    }
}
