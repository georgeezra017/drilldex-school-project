package com.drilldex.drillbackend.checkout;

import com.drilldex.drillbackend.checkout.dto.CheckoutRequest;
import com.drilldex.drillbackend.checkout.dto.CheckoutResult;
import com.drilldex.drillbackend.checkout.dto.CheckoutSession;
import com.drilldex.drillbackend.checkout.dto.ConfirmCheckoutRequest;
import com.drilldex.drillbackend.user.User;
import jakarta.servlet.http.HttpServletRequest;

public interface CheckoutService {
    CheckoutSession start(User user, CheckoutRequest request, HttpServletRequest httpRequest);
    CheckoutResult confirm(User user, ConfirmCheckoutRequest request, String orderId);
}