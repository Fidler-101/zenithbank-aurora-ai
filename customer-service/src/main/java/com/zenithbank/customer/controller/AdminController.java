package com.zenithbank.customer.controller;

import com.zenithbank.customer.entity.Customer.CustomerStatus;
import com.zenithbank.customer.exception.CustomerException;
import com.zenithbank.customer.repository.AdminAuditLogRepository;
import com.zenithbank.customer.repository.CustomerRepository;
import com.zenithbank.customer.service.CustomerService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final CustomerService     customerService;
    private final CustomerRepository  customerRepository;
    private final AdminAuditLogRepository auditLogRepository;

    @GetMapping("/customers")
    public String listPending(Model model) {
        model.addAttribute("customers",
            customerRepository.findByStatusOrderByCreatedAtDesc(CustomerStatus.OTP_VERIFIED));
        return "admin/customer-list";
    }

    @GetMapping("/customers/{id}")
    public String viewCustomer(@PathVariable Long id, Model model) {
        customerRepository.findById(id).ifPresent(c -> model.addAttribute("customer", c));
        model.addAttribute("auditLog", auditLogRepository.findByCustomerIdOrderByActionAtDesc(id));
        return "admin/customer-detail";
    }

    @PostMapping("/customers/{id}/approve")
    public String approve(@PathVariable Long id,
                          @AuthenticationPrincipal UserDetails admin,
                          RedirectAttributes ra) {
        try {
            customerService.approveCustomer(id, admin.getUsername());
            ra.addFlashAttribute("success", "Customer approved successfully.");
        } catch (CustomerException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/customers";
    }

    @PostMapping("/customers/{id}/reject")
    public String reject(@PathVariable Long id,
                         @RequestParam(required = false) String reason,
                         @AuthenticationPrincipal UserDetails admin,
                         RedirectAttributes ra) {
        customerService.rejectCustomer(id, admin.getUsername(), reason);
        ra.addFlashAttribute("success", "Customer application rejected.");
        return "redirect:/admin/customers";
    }

    @PostMapping("/customers/{id}/suspend")
    public String suspend(@PathVariable Long id,
                          @RequestParam(required = false) String reason,
                          @AuthenticationPrincipal UserDetails admin,
                          RedirectAttributes ra) {
        try {
            customerService.suspendCustomer(id, admin.getUsername(), reason);
            ra.addFlashAttribute("success", "Customer account suspended.");
        } catch (CustomerException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/customers";
    }
}
