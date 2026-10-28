package com.zenithbank.payment.dto;

import com.zenithbank.payment.entity.Payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public class PaymentRequest {

    private UUID      fromAccountId;
    private String    toAccountNo;
    private String    branchCode;
    private BigDecimal amount;
    private String    reference;
    private LocalDate paymentDate;
    private Boolean   isRecurring;
    private Payment.PaymentFrequency frequency;

    public PaymentRequest() {}

    public UUID       getFromAccountId() { return fromAccountId; }
    public String     getToAccountNo()   { return toAccountNo; }
    public String     getBranchCode()    { return branchCode; }
    public BigDecimal getAmount()        { return amount; }
    public String     getReference()     { return reference; }
    public LocalDate  getPaymentDate()   { return paymentDate; }
    public Boolean    getIsRecurring()   { return isRecurring; }
    public Payment.PaymentFrequency getFrequency() { return frequency; }

    public void setFromAccountId(UUID v)      { this.fromAccountId = v; }
    public void setToAccountNo(String v)      { this.toAccountNo = v; }
    public void setBranchCode(String v)       { this.branchCode = v; }
    public void setAmount(BigDecimal v)       { this.amount = v; }
    public void setReference(String v)        { this.reference = v; }
    public void setPaymentDate(LocalDate v)   { this.paymentDate = v; }
    public void setIsRecurring(Boolean v)     { this.isRecurring = v; }
    public void setFrequency(Payment.PaymentFrequency v) { this.frequency = v; }

    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private final PaymentRequest r = new PaymentRequest();
        public Builder fromAccountId(java.util.UUID v)       { r.fromAccountId = v; return this; }
        public Builder toAccountNo(String v)                 { r.toAccountNo = v;   return this; }
        public Builder branchCode(String v)                  { r.branchCode = v;    return this; }
        public Builder amount(java.math.BigDecimal v)        { r.amount = v;        return this; }
        public Builder reference(String v)                   { r.reference = v;     return this; }
        public Builder paymentDate(java.time.LocalDate v)    { r.paymentDate = v;   return this; }
        public Builder isRecurring(Boolean v)                { r.isRecurring = v;   return this; }
        public Builder frequency(Payment.PaymentFrequency v) { r.frequency = v; return this; }
        public PaymentRequest build() { return r; }
    }

}
