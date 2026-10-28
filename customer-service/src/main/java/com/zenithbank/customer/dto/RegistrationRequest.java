package com.zenithbank.customer.dto;

import jakarta.validation.constraints.*;
import lombok.*;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RegistrationRequest {

    @NotBlank(message = "First name is required")
    @Size(min = 2, max = 80)
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(min = 2, max = 80)
    private String lastName;

    @NotBlank(message = "Email is required")
    @Email(message = "Please enter a valid email address")
    private String email;

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^(\\+27|0)[6-8][0-9]{8}$", message = "Please enter a valid SA phone number")
    private String phoneNumber;

    @NotBlank(message = "ID number is required")
    @Pattern(regexp = "^\\d{13}$", message = "ID number must be exactly 13 digits")
    private String idNumber;

    @NotNull(message = "Date of birth is required")
    @Past(message = "Date of birth must be in the past")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate dateOfBirth;

    @NotBlank(message = "Address is required")
    private String addressLine1;

    private String addressLine2;

    @NotBlank(message = "City is required")
    private String city;

    @NotBlank(message = "Province is required")
    private String province;

    @NotBlank(message = "Postal code is required")
    @Pattern(regexp = "^\\d{4}$", message = "Postal code must be 4 digits")
    private String postalCode;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    @Pattern(
        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{8,}$",
        message = "Password must contain uppercase, lowercase, number and special character"
    )
    private String password;

    @NotBlank(message = "Please confirm your password")
    private String confirmPassword;
}
