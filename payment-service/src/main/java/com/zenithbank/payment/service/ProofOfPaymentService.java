package com.zenithbank.payment.service;

import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.*;
import com.itextpdf.layout.properties.HorizontalAlignment;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.zenithbank.payment.entity.Payment;
import com.zenithbank.payment.exception.PaymentException;
import com.zenithbank.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Proof-of-Payment PDF generator using iText 8.
 *
 * Only generates for COMPLETED payments.
 * Returns raw PDF bytes — the controller sets the
 * Content-Disposition and Content-Type headers.
 */
@Service
@RequiredArgsConstructor
public class ProofOfPaymentService {
    private static final Logger log = Logger.getLogger(ProofOfPaymentService.class.getName());


    private final PaymentRepository paymentRepository;

    private static final DeviceRgb NAVY  = new DeviceRgb(10,  22,  40);
    private static final DeviceRgb BLUE  = new DeviceRgb(37,  99,  235);
    private static final DeviceRgb GREY  = new DeviceRgb(100, 116, 139);
    private static final DeviceRgb LIGHT = new DeviceRgb(241, 245, 249);
    private static final DeviceRgb GREEN = new DeviceRgb(5,   150, 105);

    public byte[] generate(UUID paymentId, Long customerId) {

        Payment payment = paymentRepository.findByIdAndCustomerId(paymentId, customerId)
                .orElseThrow(() -> new PaymentException("Payment not found or access denied."));

        if (!Payment.PaymentStatus.COMPLETED.equals(payment.getStatus())) {
            throw new PaymentException("Proof of payment is only available for completed payments.");
        }

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PdfWriter  writer  = new PdfWriter(baos);
            PdfDocument pdf    = new PdfDocument(writer);
            Document   doc     = new Document(pdf, PageSize.A4);
            doc.setMargins(40, 50, 40, 50);

            PdfFont bold    = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD);
            PdfFont regular = PdfFontFactory.createFont(StandardFonts.HELVETICA);

            // ── Header ──────────────────────────────────────
            Table header = new Table(UnitValue.createPercentArray(new float[]{1, 1}))
                    .setWidth(UnitValue.createPercentValue(100));

            Cell logoCell = new Cell().setBorder(Border.NO_BORDER);
            logoCell.add(new Paragraph("ZenithBank")
                    .setFont(bold).setFontSize(22).setFontColor(NAVY));
            logoCell.add(new Paragraph("Proof of Payment")
                    .setFont(regular).setFontSize(11).setFontColor(GREY));
            header.addCell(logoCell);

            Cell statusCell = new Cell().setBorder(Border.NO_BORDER)
                    .setTextAlignment(TextAlignment.RIGHT);
            statusCell.add(new Paragraph("PAYMENT SUCCESSFUL")
                    .setFont(bold).setFontSize(11).setFontColor(GREEN));
            statusCell.add(new Paragraph("Reference: " + payment.getId().toString().substring(0, 8).toUpperCase())
                    .setFont(regular).setFontSize(9).setFontColor(GREY));
            header.addCell(statusCell);
            doc.add(header);

            // ── Divider ──────────────────────────────────────
            doc.add(new LineSeparator(new com.itextpdf.kernel.pdf.canvas.draw.SolidLine(2f))
                    .setMarginTop(8).setMarginBottom(20));

            // ── Amount block ─────────────────────────────────
            Table amountBlock = new Table(UnitValue.createPercentArray(new float[]{1}))
                    .setWidth(UnitValue.createPercentValue(100))
                    .setBackgroundColor(LIGHT);

            // iText 8 uses setBackgroundColor directly
            Paragraph amountLabel = new Paragraph("Amount Transferred")
                    .setFont(regular).setFontSize(10).setFontColor(GREY)
                    .setTextAlignment(TextAlignment.CENTER).setMarginBottom(2);
            Paragraph amountValue = new Paragraph("R " + payment.getAmount().toPlainString())
                    .setFont(bold).setFontSize(32).setFontColor(NAVY)
                    .setTextAlignment(TextAlignment.CENTER).setMarginBottom(0);
            Paragraph dateValue = new Paragraph("Processed on " +
                    payment.getProcessedAt().format(DateTimeFormatter.ofPattern("dd MMMM yyyy 'at' HH:mm")))
                    .setFont(regular).setFontSize(9).setFontColor(GREY)
                    .setTextAlignment(TextAlignment.CENTER);

            Cell amountCell = new Cell().setBorder(Border.NO_BORDER)
                    .setBackgroundColor(LIGHT).setPadding(20);
            amountCell.add(amountLabel);
            amountCell.add(amountValue);
            amountCell.add(dateValue);

            Table amountTable = new Table(UnitValue.createPercentArray(new float[]{1}))
                    .setWidth(UnitValue.createPercentValue(100))
                    .setBackgroundColor(LIGHT)
                    .setMarginBottom(24);
            amountTable.addCell(amountCell);
            doc.add(amountTable);

            // ── Payment details table ─────────────────────────
            doc.add(new Paragraph("Payment Details")
                    .setFont(bold).setFontSize(12).setFontColor(NAVY).setMarginBottom(10));

            Table details = new Table(UnitValue.createPercentArray(new float[]{1, 1}))
                    .setWidth(UnitValue.createPercentValue(100))
                    .setMarginBottom(24);

            addDetailRow(details, "From Account",    payment.getFromAccountNo(), bold, regular);
            addDetailRow(details, "To Account",      payment.getToAccountNo(),   bold, regular);
            addDetailRow(details, "Branch Code",     payment.getBranchCode(),    bold, regular);
            addDetailRow(details, "Payment Date",
                    payment.getPaymentDate().format(DateTimeFormatter.ofPattern("dd MMMM yyyy")),
                    bold, regular);
            addDetailRow(details, "Reference",
                    payment.getReference() != null ? payment.getReference() : "—", bold, regular);
            addDetailRow(details, "Payment Type",
                    payment.getIsRecurring()
                            ? payment.getFrequency().name() + " (Recurring)"
                            : "Once-off",
                    bold, regular);
            addDetailRow(details, "Status",          "COMPLETED",               bold, regular);
            doc.add(details);

            // ── Footer ───────────────────────────────────────
            doc.add(new LineSeparator(new com.itextpdf.kernel.pdf.canvas.draw.SolidLine(0.5f)).setMarginBottom(12));
            doc.add(new Paragraph(
                    "This is an official proof of payment issued by ZenithBank. " +
                    "Document generated: " +
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss")) +
                    ". ZenithBank is a registered financial services provider.")
                    .setFont(regular).setFontSize(8).setFontColor(GREY)
                    .setTextAlignment(TextAlignment.CENTER));

            doc.close();

            payment.setProofGenerated(true);
            paymentRepository.save(payment);

            log.info("Proof of payment generated for payment " + paymentId);
            return baos.toByteArray();

        } catch (PaymentException e) {
            throw e;
        } catch (Exception e) {
            log.severe("PDF generation failed for payment " + paymentId + ": " + e.getMessage());
            throw new PaymentException("Could not generate proof of payment. Please try again.");
        }
    }

    private void addDetailRow(Table table, String label, String value,
                               PdfFont bold, PdfFont regular) {
        Cell labelCell = new Cell()
                .setBorder(Border.NO_BORDER)
                .setBorderBottom(new SolidBorder(LIGHT, 1))
                .setPaddingTop(8).setPaddingBottom(8);
        labelCell.add(new Paragraph(label).setFont(bold).setFontSize(9).setFontColor(GREY));

        Cell valueCell = new Cell()
                .setBorder(Border.NO_BORDER)
                .setBorderBottom(new SolidBorder(LIGHT, 1))
                .setPaddingTop(8).setPaddingBottom(8)
                .setTextAlignment(TextAlignment.RIGHT);
        valueCell.add(new Paragraph(value).setFont(regular).setFontSize(10).setFontColor(NAVY));

        table.addCell(labelCell);
        table.addCell(valueCell);
    }
}
