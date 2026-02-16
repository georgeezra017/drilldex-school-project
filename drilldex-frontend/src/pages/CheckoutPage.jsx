// src/pages/CheckoutPage.jsx
import React, {useEffect, useState} from "react";
import {useCart} from "../state/cart";
import "./CheckoutPage.css";
import api from "../lib/api";
import {useNavigate} from "react-router-dom";
import AuthModal from "../components/AuthModal.jsx";
import { useAuth } from "../state/auth.jsx";
import toast from "react-hot-toast";

export default function CheckoutPage() {
    const [showAuthModal, setShowAuthModal] = useState(false);
    const isLoggedIn = () => {
        try {
            const auth = localStorage.getItem("auth");
            if (!auth) return false;
            const parsed = JSON.parse(auth);
            return parsed && parsed.token && parsed.userId; // ensure both exist
        } catch {
            return false;
        }
    };
    const {items, remove} = useCart();
    const subtotal = items.reduce((sum, i) => {
        const price = Number(i.license?.price ?? i.price ?? 0);
        const qty = Number(i.qty ?? 1);
        return sum + price * qty;
    }, 0);
    const tax = 0;
    const total = subtotal + tax;
    const navigate = useNavigate();
    const [isProcessing, setIsProcessing] = useState(false);

    const getItemUnitPrice = (item) => {
        if (item.type === "promotion") return Number(item.price ?? 0);
        return Number(item.license?.price ?? item.price ?? 0);
    };

    const handleSimulatedPayment = async () => {
        if (!items.length) {
            toast.error("Your cart is empty");
            return;
        }

        if (!isLoggedIn()) {
            setShowAuthModal(true);
            return;
        }

        try {
            setIsProcessing(true);
            toast.loading("Processing simulated payment…", {id: "checkout-loading"});

            const payload = {items, method: "test", subtotal, tax, total};
            const { data: session } = await api.post("/checkout/start", payload);

            const sessionId = session?.sessionId || session?.orderId || `local_${Date.now()}`;
            navigate(`/payment/confirm?provider=test&sessionId=${encodeURIComponent(sessionId)}`);
        } catch (e) {
            console.error(e);
            toast.error("Payment failed.");
        } finally {
            setIsProcessing(false);
            toast.dismiss("checkout-loading");
        }
    };


    useEffect(() => {
        window.scrollTo({top: 0, left: 0, behavior: "auto"});
    }, [location.pathname]);

    return (
        <>
        <div className="checkout-container">
            <div className="checkout-card">
                {/* LEFT: Order Summary */}
                <div className="checkout-section">
                    <h2 className="checkout-title">Order Summary</h2>

                    <div className="checkout-items">
                        {items.length === 0 && <p className="checkout-empty">Your cart is empty.</p>}

                        {items.map((item) => (
                            <div key={item.id} className="checkout-item">
                                <button className="remove-btn" onClick={() => remove(item.id)} aria-label="Remove item">
                                    ✕
                                </button>

                                <img
                                    src={item.img || "/placeholder-cover.jpg"}
                                    alt={item.title || "Item"}
                                    className="checkout-item-img"
                                />

                                <div className="checkout-item-info">
                                    <p className="checkout-item-name">{item.title || item.name}</p>
                                    <p className="checkout-item-sub">
                                        {item.type === "promotion"
                                            ? "Promotion"
                                            : item.type === "subscription"
                                                ? "Subscription"
                                                : item.type === "kit"
                                                    ? "Kit"
                                                    : item.license?.name || "Beat License"}
                                    </p>
                                </div>

                                <p className="checkout-item-price">
                                    ${(item.qty * (item.license?.price || item.price)).toFixed(2)}
                                    {item.qty > 1 && (
                                        <span className="checkout-item-unit">
      {" "}({item.qty} × ${(item.license?.price || item.price).toFixed(2)})
    </span>
                                    )}
                                </p>
                            </div>
                        ))}
                    </div>

                    {/* Totals */}
                    <div className="checkout-totals">
                        <div className="checkout-total-row">
                            <span>Subtotal</span>
                            <span>${subtotal.toFixed(2)}</span>
                        </div>
                        <div className="checkout-total-row">
                            <span>Tax</span>
                            <span>${tax.toFixed(2)}</span>
                        </div>
                        <div className="checkout-total-row total-strong">
                            <span>Total</span>
                            <span className="checkout-total-price">${total.toFixed(2)}</span>
                        </div>
                    </div>
                </div>

                {/* RIGHT: Payment */}
                <div className="checkout-section">
                    <h2 className="checkout-title">Payment</h2>

                    <div className="alt-payments">
                        <button
                            className="checkout-button"
                            disabled={isProcessing || !items.length}
                            onClick={handleSimulatedPayment}
                        >
                            {isProcessing ? "Processing…" : `Simulate payment ($${total.toFixed(2)})`}
                        </button>
                        <p className="checkout-secure">
                            Local-only payment simulation for grading (no external services required).
                        </p>
                    </div>


                </div>
            </div>
        </div>
            <AuthModal
                isOpen={showAuthModal}
                onClose={() => setShowAuthModal(false)}
            />
        </>
    );
}
