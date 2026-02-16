import React from "react";
import "./AuthModal.css";
import {useState } from "react";
import { useAuth } from "../state/auth.jsx";
import api from "../lib/api.js";
import toast from "react-hot-toast";
import {useNavigate} from "react-router-dom";


export default function AuthModal({ isOpen, onClose }) {
    const { login } = useAuth();
    const navigate = useNavigate();

    const [email, setEmail] = useState("");
    const [password, setPassword] = useState("");
    const [loading, setLoading] = useState(false);

    if (!isOpen) return null;

    const handleSubmit = async (e) => {
        e.preventDefault();
        if (loading) return;
        setLoading(true);

        const t = toast.loading("Signing you in…");
        try {
            // 1) Login to get tokens
            const { data } = await api.post("/auth/login", { email, password });
            api.defaults.headers.common.Authorization = `Bearer ${data.accessToken}`;
            const token = data?.accessToken;
            const refreshToken = data?.refreshToken;

            // Ensure requests after this use the token (works even if your provider doesn’t set it)
            if (token) {
                api.defaults.headers.common.Authorization = `Bearer ${token}`;
            }

            // 2) Fetch /me so we can store userId, displayName, avatarUrl
            let me = null;
            try {
                const meRes = await api.get("/me");   // change if your endpoint differs
                me = meRes?.data ?? null;
            } catch {
                // it's okay if this fails—we'll still log in with tokens
            }

            // 3) Single call to login with everything we have
            login({
                token,
                refreshToken,
                userId: me?.id ?? me?.userId ?? null,
                displayName: me?.displayName ?? me?.username ?? null,
                avatarUrl: me?.avatarUrl ?? null,
            });

            toast.success("Signed in", { id: t });
            onClose?.();
            navigate("/profile/me");
        } catch (err) {
            const msg = err?.response?.data?.message || err?.response?.data || "Login failed";
            toast.error(String(msg), { id: t });
        } finally {
            setLoading(false);
        }
    };

    return (
        <div className="authModal__backdrop" onClick={onClose}>
            <div className="authModal__content" onClick={(e) => e.stopPropagation()}>
                <h2>Sign In</h2>

                {/* Email + Password form */}
                <form className="authModal__form" onSubmit={handleSubmit}>
                    <div className="authModal__field">
                        <input
                            type="email"
                            className="authModal__input"
                            placeholder="Email"
                            value={email}
                            onChange={(e) => setEmail(e.target.value)}
                            required
                        />
                    </div>
                    <div className="authModal__field">
                        <input
                            type="password"
                            className="authModal__input"
                            placeholder="Password"
                            value={password}
                            onChange={(e) => setPassword(e.target.value)}
                            required
                        />
                    </div>
                    <button type="submit" className="authModal__btn">Sign In</button>
                </form>

                <p className="authModal__alt">
                    No account? <a href="/register">Sign up</a>
                </p>
            </div>
        </div>
    );
}
