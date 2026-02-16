package com.drilldex.drillbackend.user.dto;

// user/dto/LoginRequest.java
public class LoginRequest {
    public String email;
    public String password;

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

    public LoginRequest(String password) {
        this.password = password;


    }
}