// src/components/ConfirmCancelModal.jsx
import React from "react";
import "./ConfirmCancelModal.css";

export default function ConfirmCancelModal({ open, onClose, onConfirm, type = "promotion" }) {
    if (!open) return null;

    return (
        <div className="ccmodal-backdrop">
            <div className="ccmodal-box">
                <h2 className="ccmodal-title">
                    Cancel {type === "subscription" ? "Subscription" : "Promotion"}?
                </h2>
                <p className="ccmodal-desc">
                    Are you sure you want to cancel this {type}? This action cannot be undone.
                </p>
                <div className="ccmodal-actions">
                    <button className="ccmodal-btn ccmodal-btn--danger" onClick={onClose}>
                        No, go back
                    </button>
                    <button className="ccmodal-btn " onClick={onConfirm}>
                        Yes, cancel
                    </button>
                </div>
            </div>
        </div>
    );
}