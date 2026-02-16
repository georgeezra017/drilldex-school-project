import React, { useRef, useEffect, useState } from "react";
import { FiCopy, FiX } from "react-icons/fi";
import toast from "react-hot-toast";
import api from "../lib/api";
import "./referralmodal.css";

export default function ReferralModal({ onClose }) {
    const modalRef = useRef(null);
    const [data, setData] = useState(null);
    const [loading, setLoading] = useState(true);

    // Fetch referral info when modal opens
    useEffect(() => {
        const fetchReferralInfo = async () => {
            try {
                const res = await api.get("/me/referral"); // adjust endpoint if needed
                setData(res.data);
            } catch (err) {
                console.error("Failed to fetch referral info:", err);
                toast.error("Failed to load referral info");
            } finally {
                setLoading(false);
            }
        };
        fetchReferralInfo();
    }, []);

    const copyLink = () => {
        if (data?.referralLink) {
            navigator.clipboard.writeText(data.referralLink);
            toast.success("Referral link copied!");
        }
    };

    // Close modal when clicking outside
    useEffect(() => {
        const handleClickOutside = (event) => {
            if (modalRef.current && !modalRef.current.contains(event.target)) {
                onClose();
            }
        };
        document.addEventListener("mousedown", handleClickOutside);
        return () => {
            document.removeEventListener("mousedown", handleClickOutside);
        };
    }, [onClose]);

    return (
        <div className="referralModal__backdrop">
            <div className="referralModal" ref={modalRef}>
                <div className="referralModal__header">
                    <h2 className="referralModal__title">Your Referral Link</h2>
                    <button className="referralModal__close" onClick={onClose}><FiX /></button>
                </div>

                <p className="referralModal__desc">
                    Invite friends to Drilldex and earn promo credits!
                </p>

                <div className="referralModal__row">
                    <input
                        type="text"
                        readOnly
                        value={data?.referralLink || ""}
                        placeholder={loading ? "Loading..." : ""}
                    />
                    <button onClick={copyLink} disabled={loading || !data?.referralLink}>
                        <FiCopy /> Copy link
                    </button>
                </div>

                <div className="referralModal__stats">
                    {loading ? (
                        <p>Loading referral stats...</p>
                    ) : (
                        <>
                            <p>Referral Credits Earned: ${data?.referralCredits ?? 0}</p>
                            <p>Number of Referrals: {data?.referralCount ?? 0}</p>
                        </>
                    )}
                </div>
            </div>
        </div>
    );
}