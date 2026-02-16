// src/components/UploaderLink.jsx
import React, {useEffect, useState} from "react";
import { useNavigate } from "react-router-dom";
import api from "../lib/api";
import { RiVerifiedBadgeFill } from "react-icons/ri";

import "./UploaderLink.css";

export default function UploaderLink({ userId, children, noUnderline = false }) {
    const navigate = useNavigate();
    const [plan, setPlan] = useState(null);

    useEffect(() => {
        if (!userId) return;
        let cancelled = false;

        api.get(`/users/${userId}`) // <-- fetch user details from backend
            .then(res => {
                if (!cancelled && res?.data?.plan) {
                    setPlan(res.data.plan); // e.g., "free", "growth", "pro"
                }
            })
            .catch(() => {
                // optionally handle error
            });

        return () => { cancelled = true; };
    }, [userId]);

    const handleClick = (e) => {
        e.stopPropagation(); // prevent parent card click
        navigate(`/profile/${userId}`);
    };


    const isVerified = plan === "growth" || plan === "pro";
    const badgeColor = plan === "pro" ? "#f2c94c" : "#009e94"; // gold for pro, Drilldex green for growth

    return (
        <span
            role="link"
            onClick={handleClick}
            className={`clickable-username ${noUnderline ? "no-underline" : ""}`}
        >
            {children}
            {isVerified && (
                <RiVerifiedBadgeFill
                    className="ml-1"
                    style={{ color: badgeColor, fontSize: "0.9em", verticalAlign: "middle", marginLeft: "0.25em"}}
                />
            )}
        </span>
    );
}