import { useState } from "react";
import { Link } from "react-router-dom";
import { useNavigate, useLocation } from "react-router-dom";
import { useAuth } from "../state/auth";
import "./SignUp.css";
import AuthModal from "../components/AuthModal.jsx";
import toast from "react-hot-toast";
import api from "../lib/api";

export default function SignUpPage() {
    const { login } = useAuth();
    const navigate = useNavigate();
    const [isAuthOpen, setAuthOpen] = useState(false);
    const location = useLocation();
    const queryParams = new URLSearchParams(location.search);
    const referralCode = queryParams.get("ref"); // this will capture ?ref=XXX



    const [form, setForm] = useState({
        email: "",
        password: "",
        confirm: "",
        handle: "",
        agree: false,
        updates: true,
    });

    const [errors, setErrors] = useState({});

    const onChange = (e) => {
        const { name, value, type, checked } = e.target;
        setForm((f) => ({ ...f, [name]: type === "checkbox" ? checked : value }));
    };

    const strength = getStrength(form.password);

    function getStrength(pw) {
        let s = 0;
        if (pw.length >= 8) s++;
        if (/[A-Z]/.test(pw)) s++;
        if (/[0-9]/.test(pw)) s++;
        if (/[^A-Za-z0-9]/.test(pw)) s++;
        return s; // 0–4
    }



    const onSubmit = async (e) => {
        e.preventDefault();

        const next = {};
        if (!form.email) next.email = "Email is required.";
        if (!form.handle) next.handle = "Display name is required.";
        if (form.password.length < 8) next.password = "Use at least 8 characters.";
        if (form.password !== form.confirm) next.confirm = "Passwords don’t match.";
        if (!form.agree) next.agree = "You must accept the Terms.";
        setErrors(next);
        if (Object.keys(next).length > 0) return;

        const t = toast.loading("Creating account...");

        try {
            const res = await api.post("/auth/register", {
                email: form.email,
                password: form.password,
                displayName: form.handle,
                referralCode: referralCode
            });

            const { accessToken: token, refreshToken } = res.data;
            console.log("✅ Tokens:", token, refreshToken);

            if (!token || !refreshToken) throw new Error("No tokens returned from backend");

            api.defaults.headers.common.Authorization = `Bearer ${token}`;

            let me = {
                id: null,
                displayName: form.handle,
                avatarUrl: null,
                oauthUser: false,
            };

            try {
                const meRes = await api.get("/me");
                console.log("✅ /me response:", meRes.data);
                me = meRes.data ?? me;
            } catch (err) {
                console.warn("⚠️ /me request failed:", err);
            }

            const authObj = {
                token,
                refreshToken,
                userId: me.id,
                displayName: me.displayName ?? form.handle,
                avatarUrl: me.avatarUrl ?? null,
                oauthUser: me.oauthUser ?? false,
            };

            localStorage.setItem("auth", JSON.stringify(authObj));

            login(authObj); // make sure login() updates context or global state
            console.log("✅ Login called with:", authObj);

            toast.success("Account created!", { id: t });
            navigate("/profile/me");
        } catch (err) {
            const msg =
                err?.response?.data?.message ||
                err?.response?.data ||
                "Something went wrong. Try again.";
            console.error("❌ Signup error:", err);
            toast.error(String(msg), { id: t });
        }
    };

    return (
        <main className="signup" aria-labelledby="signup-title">
            <section className="signup__card">
                <header className="signup__head">
                    <h1 id="signup-title">Create your account</h1>
                    <p className="signup__sub">One account for beats, kits, videos, and more.</p>
                </header>

                {/* Form */}
                <form className="signup__form" onSubmit={onSubmit} noValidate>
                    <label className="field">
                        <span className="field__label">Email</span>
                        <input
                            className={`field__input ${errors.email ? "is-error" : ""}`}
                            type="email"
                            name="email"
                            placeholder="you@domain.com"
                            value={form.email}
                            onChange={onChange}
                            autoComplete="email"
                        />
                        {errors.email && <span className="field__error">{errors.email}</span>}
                    </label>

                    <label className="field">
                        <span className="field__label">Display name</span>
                        <input
                            className={`field__input ${errors.handle ? "is-error" : ""}`}
                            type="text"
                            name="handle"
                            placeholder="Artist / Producer name"
                            value={form.handle}
                            onChange={onChange}
                            autoComplete="nickname"
                        />
                        {errors.handle && <span className="field__error">{errors.handle}</span>}
                    </label>

                    <label className="field">
                        <span className="field__label">Password</span>
                        <input
                            className={`field__input ${errors.password ? "is-error" : ""}`}
                            type="password"
                            name="password"
                            placeholder="••••••••"
                            value={form.password}
                            onChange={onChange}
                            autoComplete="new-password"
                        />
                        <div className="strength" aria-hidden>
                            <span className={`bar ${strength >= 1 ? "on" : ""}`} />
                            <span className={`bar ${strength >= 2 ? "on" : ""}`} />
                            <span className={`bar ${strength >= 3 ? "on" : ""}`} />
                            <span className={`bar ${strength >= 4 ? "on" : ""}`} />
                        </div>
                        {errors.password && <span className="field__error">{errors.password}</span>}
                    </label>

                    <label className="field">
                        <span className="field__label">Confirm password</span>
                        <input
                            className={`field__input ${errors.confirm ? "is-error" : ""}`}
                            type="password"
                            name="confirm"
                            placeholder="••••••••"
                            value={form.confirm}
                            onChange={onChange}
                            autoComplete="new-password"
                        />
                        {errors.confirm && <span className="field__error">{errors.confirm}</span>}
                    </label>

                    <label className="check">
                        <input
                            type="checkbox"
                            name="agree"
                            checked={form.agree}
                            onChange={onChange}
                        />
                        <span>
              I agree to the{" "}
                            <Link to="/terms">Terms</Link> &amp; <Link to="/privacy">Privacy</Link>.
            </span>
                    </label>
                    {errors.agree && <span className="field__error">{errors.agree}</span>}

                    <label className="check">
                        <input
                            type="checkbox"
                            name="updates"
                            checked={form.updates}
                            onChange={onChange}
                        />
                        <span>Send me release notes and updates (optional).</span>
                    </label>

                    <button className="signup__submit" type="submit">Create account</button>
                </form>

                <p className="signup__alt">
                    Already have an account?{" "}
                    <button
                        type="button"
                        className="signup__altBtn"
                        onClick={() => setAuthOpen(true)} // 👈 opens the modal
                    >
                        Sign in
                    </button>
                </p>
            </section>

            <AuthModal isOpen={isAuthOpen} onClose={() => setAuthOpen(false)} />
        </main>
    );
}
