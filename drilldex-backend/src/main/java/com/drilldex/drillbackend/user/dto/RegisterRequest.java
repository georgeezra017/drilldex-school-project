package com.drilldex.drillbackend.user.dto;

import com.drilldex.drillbackend.user.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank @Email
    public String email;

    @NotBlank
    @Size(min = 8, max = 128)
    public String password;

    @NotBlank @Size(min = 2, max = 32)
    public String displayName;
    public Role role;
    private String referralCode;

    public RegisterRequest(String email, String password, String displayName, Role role, String referralCode) {
        this.email = email;
        this.password = password;
        this.displayName = displayName;
        this.role = role;
        this.referralCode = referralCode;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }
}