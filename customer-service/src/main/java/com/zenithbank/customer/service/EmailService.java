package com.zenithbank.customer.service;

import com.zenithbank.customer.entity.Customer;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.util.logging.Logger;

@Service
@RequiredArgsConstructor
public class EmailService {
    private static final Logger log = Logger.getLogger(EmailService.class.getName());


    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}") private String fromEmail;
    @Value("${app.admin.email:admin@zenithbank.co.za}") private String adminEmail;

    @Async
    public void sendOtpEmail(Customer customer, String otpCode) {
        send(customer.getEmail(), "ZenithBank — Your Verification Code", """
            <div style="font-family:Arial,sans-serif;max-width:600px;margin:auto;padding:30px;border:1px solid #e0e0e0;border-radius:8px;">
              <h1 style="color:#1a56db;">ZenithBank</h1>
              <h2>Hi %s, verify your email</h2>
              <p>Your 6-digit verification code is below. It expires in <strong>10 minutes</strong>.</p>
              <div style="text-align:center;margin:30px 0;">
                <span style="font-size:36px;font-weight:bold;letter-spacing:8px;color:#1a56db;background:#f0f5ff;padding:16px 32px;border-radius:8px;">%s</span>
              </div>
              <p style="color:#888;font-size:12px;">Never share this code. ZenithBank will never ask for it.</p>
            </div>""".formatted(customer.getFirstName(), otpCode));
    }

    @Async
    public void sendAdminNotification(Customer customer) {
        send(adminEmail, "ZenithBank Admin — New Registration Awaiting Approval",
            "<h2>New Registration</h2><p><b>Name:</b> %s<br><b>Email:</b> %s<br><b>ID:</b> %s</p>"
            .formatted(customer.getFullName(), customer.getEmail(), customer.getIdNumber()));
    }

    @Async
    public void sendApprovalEmail(Customer customer) {
        send(customer.getEmail(), "ZenithBank — Your Account is Approved!",
            "<h2>Welcome, %s!</h2><p>Your account has been <strong>approved</strong>. You can now log in.</p>"
            .formatted(customer.getFirstName()));
    }

    @Async
    public void sendRejectionEmail(Customer customer, String reason) {
        send(customer.getEmail(), "ZenithBank — Application Update",
            "<h2>Dear %s,</h2><p>We could not approve your application at this time.%s</p>"
            .formatted(customer.getFirstName(), reason != null ? " Reason: " + reason : ""));
    }

    private void send(String to, String subject, String html) {
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(msg);
            log.info("Email sent → " + to + " | " + subject);
        } catch (MessagingException e) {
            log.severe("Email failed → " + to + ": " + e.getMessage());
        }
    }
}
