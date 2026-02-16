// src/pages/AccountPage.jsx
import {useState, useEffect} from "react";
import {FaUserCircle} from "react-icons/fa";
import "./AccountPage.css";
import {toast} from "react-hot-toast";
import { useNavigate } from "react-router-dom";
import api from "../lib/api";
import {useCart} from "../state/cart";
import {useSearchParams} from "react-router-dom";


export default function AccountPage() {
    const [active, setActive] = useState("profile");
    const [currentPlan, setCurrentPlan] = useState("free"); // default to Free
    const [billingCycle, setBillingCycle] = useState("monthly"); // default toggle
    const [bio, setBio] = useState("");
    const [instagram, setInstagram] = useState("");
    const [twitter, setTwitter] = useState("");
    const [youtube, setYoutube] = useState("");
    const [facebook, setFacebook] = useState("");
    const [soundcloud, setSoundcloud] = useState("");
    const [tiktok, setTiktok] = useState("");
    const [avatar, setAvatar] = useState(null);
    const [banner, setBanner] = useState(null);
    const [avatarFile, setAvatarFile] = useState(null);
    const [bannerFile, setBannerFile] = useState(null);
    const {add, clear} = useCart();
    const [email, setEmail] = useState("user@example.com");
    const [emailPw, setEmailPw] = useState("");
    const [currentPw, setCurrentPw] = useState("");
    const [newPw, setNewPw] = useState("");
    const [confirmPw, setConfirmPw] = useState("");
    const [searchParams, setSearchParams] = useSearchParams();

    const PLAN_LEVELS = { free: 0, growth: 1, pro: 2 };
    const currentLevel = PLAN_LEVELS[currentPlan?.toLowerCase() || "free"];

    const navigate = useNavigate();

    useEffect(() => {
        const tab = searchParams.get("tab");
        if (tab) setActive(tab);
    }, [searchParams]);

    const CDN_BASE = import.meta.env.VITE_S3_PUBLIC_BASE?.replace(/\/+$/, "") || "";
    const toFileUrl = (p) => {
        if (!p) return null;
        if (/^https?:\/\//i.test(p)) return p;                       // already a URL
        const clean = String(p).replace(/^\/?uploads\/?/, "").replace(/^\/+/, "");
        return CDN_BASE ? `${CDN_BASE}/${clean}` : `/uploads/${clean}`;
    };

    const PLAN_META = {
        free: {name: "Free"},
        growth: {name: "Growth"},
        pro: {name: "Pro"},
    };

    function addPlanToCart(planId, billingCycle = "monthly", opts = {}) {
        const trialDays = Number.isFinite(opts.trialDays) ? opts.trialDays : 0;

        // you can show a price in the cart, but let backend calculate for real
        const DISPLAY_PRICE = {
            growth: {monthly: 5.99, yearly: 59.0},
            pro: {monthly: 14.99, yearly: 149.0},
        };

        const nice = PLAN_META[planId]?.name || planId;
        const cycleLabel = billingCycle === "yearly" ? "Yearly" : "Monthly";

        add({
            id: `sub-${planId}-${billingCycle}${trialDays ? `-trial${trialDays}` : ""}`,
            type: "subscription",
            planId,
            planName: PLAN_META[planId]?.name || planId, // ✅ Add this line
            billingCycle,
            trialDays,
            title: `${nice} — ${cycleLabel}${trialDays ? ` (Trial ${trialDays}d)` : ""}`,
            img: "/logo.png",
            qty: 1,
            price: DISPLAY_PRICE[planId]?.[billingCycle] ?? 0,
        });

        window.location.href = "/checkout";
    }


    const removeAvatar = async () => {
        const t = toast.loading("Removing avatar…");
        try {
            let removed = false;

            // Preferred: dedicated endpoint
            try {
                await api.delete("/me/avatar");
                removed = true;
            } catch (err) {
                const code = err?.response?.status;
                // If endpoint doesn’t exist, fall back to combined profile route
                if (!(code === 404 || code === 405)) throw err;
            }

            // Fallback: combined route with a flag your backend understands
            if (!removed) {
                const fd = new FormData();
                fd.append("removeAvatar", "true");
                await api.post("/me/profile", fd, {
                    headers: {"Content-Type": "multipart/form-data"},
                });
            }

            setAvatar(null);
            setAvatarFile?.(null); // if you also track the File separately
            toast.success("Avatar removed.", {id: t});
        } catch (e) {
            console.error(e);
            toast.error("Couldn't remove avatar.", {id: t});
        }
    };


    useEffect(() => {
        let alive = true;
        (async () => {
            try {
                const {data} = await api.get("/me");
                if (!alive || !data) return;

                setUsername(data.username || "");
                setInstagram(data.instagram || "");
                setTwitter(data.twitter || "");
                setYoutube(data.youtube || "");
                setFacebook(data.facebook || "");
                setSoundcloud(data.soundcloud || "");
                setTiktok(data.tiktok || "");

                setBio(
                    typeof data.bio === "string" ? data.bio
                        : typeof data?.profile?.bio === "string" ? data.profile.bio
                            : ""
                );

                const av =
                    data.avatarUrl ||
                    data.profilePicturePath ||
                    data.avatar ||
                    data?.profile?.avatarUrl ||
                    null;
                if (av) setAvatar(toFileUrl(av));

                const bn =
                    data.bannerUrl ||
                    data.bannerImagePath ||
                    data?.profile?.bannerUrl ||
                    null;
                if (bn) setBanner(toFileUrl(bn));

                if (data.subscription?.planId) {
                    setCurrentPlan(data.subscription.planId); // e.g. "pro", "growth"
                } else {
                    setCurrentPlan("free"); // default to Free
                }

            } catch {
            }
        })();
        return () => {
            alive = false;
        };
    }, []);

    const handleSaveSocials = async (e) => {
        e.preventDefault();
        const t = toast.loading("Updating socials…");
        try {
            await api.patch("/me/profile", {
                instagram: normalizeHandleOrUrl(instagram, "instagram"),
                twitter: normalizeHandleOrUrl(twitter, "twitter"),
                youtube: normalizeHandleOrUrl(youtube, "youtube"),
                facebook: normalizeHandleOrUrl(facebook, "facebook"),
                soundcloud: normalizeHandleOrUrl(soundcloud, "soundcloud"),
                tiktok: normalizeHandleOrUrl(tiktok, "tiktok"),
            });
            toast.success("Socials updated.", {id: t});
        } catch (err) {
            console.error(err);
            toast.error("Failed to update socials.", {id: t});
        }
    };

    const saveProfile = async (e) => {
        e.preventDefault();

        const t = toast.loading("Saving profile…");
        try {
            // Try single combined endpoint first
            let combinedWorked = false;
            try {
                const fd = new FormData();
                if (avatarFile) fd.append("avatar", avatarFile);
                if (bannerFile) fd.append("banner", bannerFile); // optional
                fd.append("bio", bio ?? "");

                const {data} = await api.post("/me/profile", fd, {
                    headers: {"Content-Type": "multipart/form-data"},
                });
                combinedWorked = true;

                if (data?.avatarUrl) setAvatar(toFileUrl(data.avatarUrl));
                if (data?.bannerUrl) setBanner(toFileUrl(data.bannerUrl));
                if (typeof data?.bio === "string") setBio(data.bio);
            } catch (err) {
                const code = err?.response?.status;
                if (!(code === 404 || code === 405 || code === 400)) throw err; // real error
            }

            // Fallback to per-field endpoints if combined isn’t available
            if (!combinedWorked) {
                if (avatarFile) {
                    const f1 = new FormData();
                    f1.append("avatar", avatarFile);
                    const {data: ares} = await api.post("/me/avatar", f1, {
                        headers: {"Content-Type": "multipart/form-data"},
                    });
                    if (ares?.avatarUrl) setAvatar(toFileUrl(ares.avatarUrl));
                }

                if (bannerFile) {
                    const f2 = new FormData();
                    f2.append("banner", bannerFile);
                    await api.post("/me/banner", f2, {
                        headers: {"Content-Type": "multipart/form-data"},
                    });
                }

                await api.patch("/me/profile", {bio: bio ?? ""});
            }

            setAvatarFile(null);
            setBannerFile(null);

            toast.success("Profile updated.", {id: t});
        } catch (err) {
            console.error("Save profile failed:", err);
            const msg =
                err?.response?.data?.error ||
                err?.response?.data?.message ||
                "Couldn't save profile.";
            toast.error(msg, {id: t});
        }
    };

    const onAvatarChange = (e) => {
        const file = e.target.files?.[0];
        if (!file) return;
        setAvatar(URL.createObjectURL(file)); // preview
        setAvatarFile(file);                  // real file to upload
    };

    const onBannerChange = (e) => {
        const file = e.target.files?.[0];
        if (!file) return;
        setBanner(URL.createObjectURL(file)); // preview
        setBannerFile(file);                  // real file to upload
    };

    const removeBio = async () => {
        const t = toast.loading("Removing bio…");
        try {
            // Preferred: JSON PATCH
            try {
                await api.patch("/me/profile", {bio: ""});
            } catch (err) {
                // Fallback: multipart form if your endpoint expects it
                const fd = new FormData();
                fd.append("bio", "");
                await api.post("/me/profile", fd, {
                    headers: {"Content-Type": "multipart/form-data"},
                });
            }

            setBio("");
            toast.success("Bio removed.", {id: t});
        } catch (e) {
            console.error(e);
            toast.error("Couldn't remove bio.", {id: t});
        }
    };

    const saveEmail = async (e) => {
        e.preventDefault();
        const t = toast.loading("Updating email…");
        try {
            await api.patch("/me/email", {
                email,
                password: emailPw
            });
            toast.success("Email updated.", {id: t});
        } catch (err) {
            console.error(err);
            const msg = err?.response?.data?.error || err?.response?.data?.message || "Couldn't update email.";
            toast.error(msg, {id: t});
        }
    };

    const savePassword = async (e) => {
        e.preventDefault();
        if (newPw !== confirmPw) return alert("New passwords do not match.");
        const t = toast.loading("Updating password…");
        try {
            await api.patch("/me/password", {
                currentPassword: currentPw,
                newPassword: newPw
            });
            toast.success("Password updated.", {id: t});
            setCurrentPw("");
            setNewPw("");
            setConfirmPw("");
        } catch (err) {
            console.error(err);
            const msg = err?.response?.data?.error || err?.response?.data?.message || "Couldn't update password.";
            toast.error(msg, {id: t});
        }
    };


    const removeBanner = async () => {
        const t = toast.loading("Removing banner…");
        try {
            let removed = false;

            // Preferred: dedicated endpoint
            try {
                await api.delete("/me/banner");
                removed = true;
            } catch (err) {
                const code = err?.response?.status;
                // If endpoint doesn’t exist, fall back below
                if (!(code === 404 || code === 405)) throw err;
            }

            // Fallback: combined /me/profile with a flag your backend can read
            if (!removed) {
                const fd = new FormData();
                fd.append("removeBanner", "true"); // backend should interpret this and clear the banner
                await api.post("/me/profile", fd, {
                    headers: {"Content-Type": "multipart/form-data"},
                });
            }

            setBanner(null);
            setBannerFile?.(null); // if you track the file separately
            toast.success("Banner removed.", {id: t});
        } catch (e) {
            console.error(e);
            toast.error("Couldn't remove banner.", {id: t});
        }
    };

    const [username, setUsername] = useState("");

    const handleUsernameUpdate = async (e) => {
        e.preventDefault();
        const t = toast.loading("Updating username…");
        try {
            await api.patch("/me/profile", {username});
            toast.success("Username updated.", {id: t});
        } catch (err) {
            console.error(err);
            toast.error("Failed to update username.", {id: t});
        }
    };


    function normalizeHandleOrUrl(input, platform) {
        if (!input) return "";

        // Already full URL
        if (input.startsWith("http://") || input.startsWith("https://")) return input;

        // Remove leading @ if present
        const clean = input.trim().replace(/^@/, "");

        switch (platform) {
            case "instagram":
                return `https://instagram.com/${clean}`;
            case "twitter":
                return `https://x.com/${clean}`;
            case "youtube":
                return `https://youtube.com/@${clean}`;
            case "facebook":
                return `https://facebook.com/${clean}`;
            case "soundcloud":
                return `https://soundcloud.com/${clean}`;
            case "tiktok":
                return `https://tiktok.com/@${clean}`;
            default:
                return input;
        }
    }


    return (
        <main className="account">
            <header className="account__hero">
                <h1 className="account__title">Account</h1>
                <p className="account__sub">Manage your profile, security, and plan.</p>
            </header>

            {/* Tabs */}
            <div className="account__tabs" role="tablist" aria-label="Account sections">
                {[
                    {id: "profile", label: "Profile"},
                    {id: "username", label: "Username"},
                    {id: "email", label: "Email"},
                    {id: "password", label: "Password"},
                    {id: "socials", label: "Socials"},
                    {id: "plans", label: "Plans"},

                ].filter(Boolean)
                    .map((t) => (
                        <button
                            key={t.id}
                            role="tab"
                            aria-selected={active === t.id}
                            className={`account__tab ${active === t.id ? "is-active" : ""}`}
                            onClick={() => setActive(t.id)}
                        >
                            {t.label}
                        </button>
                    ))}
            </div>

            {/* Panels */}
            <section style={{
                borderLeftWidth: 0,
                borderTopWidth: 0,
                borderRightWidth: 0,
                borderBottomWidth: 0,
                paddingLeft: 0,
                paddingTop: 0,
                paddingRight: 0,
                paddingBottom: 0,
            }} className="account__panel account__panel--profile">

                {active === "profile" && (
                    <form className="account__panel account__panel--profile" onSubmit={saveProfile}>
                        <h2 className="account__h2">Profile</h2>

                        {/* Avatar + Bio side-by-side (moved above banner) */}
                        <div className="account__twoCol">
                            {/* Avatar */}
                            <div className="account__col">
                                <div className="field field--avatar">
                                    <label className="field__label">Profile picture</label>
                                    <div className="field__row">
                                        {avatar ? (
                                            <img className="avatar" src={avatar} alt="Avatar preview"/>
                                        ) : (
                                            <FaUserCircle className="avatar avatar--placeholder" size={80}/>
                                        )}
                                        <label className="btn btn--ghost">
                                            Upload avatar
                                            <input type="file" hidden accept="image/*" onChange={onAvatarChange}/>
                                        </label>
                                    </div>
                                    {avatar && (
                                        <button
                                            type="button"
                                            className="btn btn--danger mt-12"
                                            onClick={removeAvatar}
                                        >
                                            Remove avatar
                                        </button>
                                    )}
                                </div>
                            </div>

                            {/* Bio */}
                            <div className="account__col">
                                <div className="field">
                                    <label className="field__label" htmlFor="bio">Bio</label>
                                    <textarea
                                        id="bio"
                                        className="input input--textarea"
                                        placeholder="No bio yet. Tell people about your drill style, typical BPM range, and collab info."
                                        value={bio}
                                        onChange={(e) => setBio(e.target.value)}
                                        rows={8}
                                        maxLength={600}
                                    />
                                    <div className="field__help">{bio?.length ?? 0}/600</div>
                                    {bio?.trim()?.length > 0 && (
                                        <div className="field__row" style={{marginTop: 8}}>
                                            <button
                                                type="button"
                                                className="btn btn--danger"
                                                onClick={removeBio}
                                                title="Clear bio"
                                            >
                                                Remove bio
                                            </button>
                                        </div>
                                    )}
                                </div>
                            </div>
                        </div>

                        {/* Banner (moved below) */}
                        <div className="field">
                            <label className="field__label">Banner</label>
                            <div className="bannerWrap">
                                {banner ? (
                                    <img className="banner" src={banner} alt="Banner"/>
                                ) : (
                                    <div className="banner banner--empty">No banner</div>
                                )}
                                <div className="field__row">
                                    <label className="btn btn--ghost">
                                        Upload banner
                                        <input type="file" hidden accept="image/*" onChange={onBannerChange}/>
                                    </label>
                                </div>
                                {banner && (
                                    <button
                                        type="button"
                                        className="btn btn--danger mt-12"
                                        onClick={removeBanner}
                                    >
                                        Remove banner
                                    </button>
                                )}
                            </div>
                        </div>

                        <div className="mt-8">
                            <button className="btn btn--primary" type="submit">
                                Save Profile
                            </button>
                        </div>
                    </form>
                )}

                {active === "username" && (
                    <form className="account__panel" onSubmit={handleUsernameUpdate}>
                        <h2 className="account__h2">Change Username</h2>

                        <div className="field">
                            <label className="field__label">New username</label>
                            <input
                                type="text"
                                className="input"
                                value={username}
                                onChange={(e) => setUsername(e.target.value)}
                                placeholder="Your new username"
                                required
                            />
                        </div>

                        <div className="mt-8">
                            <button className="btn btn--primary" type="submit">
                                Save Username
                            </button>
                        </div>
                    </form>
                )}

                {active === "email" && (
                    <form className="account__panel" onSubmit={saveEmail}>
                        <h2 className="account__h2">Update Email</h2>

                        <div className="field">
                            <label className="field__label">New email</label>
                            <input
                                type="email"
                                className="input"
                                value={email}
                                onChange={(e) => setEmail(e.target.value)}
                                placeholder="you@domain.com"
                                required
                            />
                        </div>

                        <div className="field">
                            <label className="field__label">Current password</label>
                            <input
                                type="password"
                                className="input"
                                value={emailPw}
                                onChange={(e) => setEmailPw(e.target.value)}
                                placeholder="••••••••"
                                required
                            />
                        </div>

                        <div className="mt-8">
                            <button className="btn btn--primary" type="submit">
                                Save Email
                            </button>
                        </div>
                    </form>
                )}

                {active === "password" && (
                    <form className="account__panel" onSubmit={savePassword}>
                        <h2 className="account__h2">Change Password</h2>

                        {/* Current password */}
                        <div className="field">
                            <label className="field__label">Current password</label>
                            <input
                                type="password"
                                className="input"
                                value={currentPw}
                                onChange={(e) => setCurrentPw(e.target.value)}
                                placeholder="••••••••"
                                required
                            />
                        </div>

                        {/* New password */}
                        <div className="field">
                            <label className="field__label">New password</label>
                            <input
                                type="password"
                                className="input"
                                value={newPw}
                                onChange={(e) => setNewPw(e.target.value)}
                                placeholder="At least 8 characters"
                                required
                            />
                        </div>

                        {/* Confirm new password */}
                        <div className="field">
                            <label className="field__label">Confirm new password</label>
                            <input
                                type="password"
                                className="input"
                                value={confirmPw}
                                onChange={(e) => setConfirmPw(e.target.value)}
                                placeholder="Repeat password"
                                required
                            />
                            {confirmPw && confirmPw !== newPw && (
                                <span className="muted" style={{color: "red", fontSize: "0.85rem"}}>
          Passwords do not match
        </span>
                            )}
                        </div>

                        <div className="mt-8">
                            <button
                                className="btn btn--primary"
                                type="submit"
                                disabled={confirmPw && confirmPw !== newPw}
                            >
                                Save Password
                            </button>
                        </div>
                    </form>
                )}

                {active === "socials" && (
                    <form className="account__panel" onSubmit={handleSaveSocials}>
                        <h2 className="account__h2">Social Links</h2>

                        {/* Instagram */}
                        <div className="field">
                            <label className="field__label">Instagram</label>
                            <div className="field__row">
                                <input
                                    type="text"
                                    className="input"
                                    placeholder="@yourhandle"
                                    value={instagram}
                                    onChange={(e) => setInstagram(e.target.value)}
                                />
                                {instagram && (
                                    <button
                                        type="button"
                                        className="btn btn--danger ml-8"
                                        onClick={() => setInstagram("")}
                                    >
                                        Remove
                                    </button>
                                )}
                            </div>
                        </div>

                        {/* Twitter */}
                        <div className="field">
                            <label className="field__label">Twitter (X)</label>
                            <div className="field__row">
                                <input
                                    type="text"
                                    className="input"
                                    placeholder="@yourhandle"
                                    value={twitter}
                                    onChange={(e) => setTwitter(e.target.value)}
                                />
                                {twitter && (
                                    <button
                                        type="button"
                                        className="btn btn--danger ml-8"
                                        onClick={() => setTwitter("")}
                                    >
                                        Remove
                                    </button>
                                )}
                            </div>
                        </div>

                        {/* YouTube */}
                        <div className="field">
                            <label className="field__label">YouTube</label>
                            <div className="field__row">
                                <input
                                    type="text"
                                    className="input"
                                    placeholder="@channel or link"
                                    value={youtube}
                                    onChange={(e) => setYoutube(e.target.value)}
                                />
                                {youtube && (
                                    <button
                                        type="button"
                                        className="btn btn--danger ml-8"
                                        onClick={() => setYoutube("")}
                                    >
                                        Remove
                                    </button>
                                )}
                            </div>
                        </div>

                        {/* Facebook */}
                        <div className="field">
                            <label className="field__label">Facebook</label>
                            <div className="field__row">
                                <input
                                    type="text"
                                    className="input"
                                    placeholder="Page or profile link"
                                    value={facebook}
                                    onChange={(e) => setFacebook(e.target.value)}
                                />
                                {facebook && (
                                    <button
                                        type="button"
                                        className="btn btn--danger ml-8"
                                        onClick={() => setFacebook("")}
                                    >
                                        Remove
                                    </button>
                                )}
                            </div>
                        </div>

                        {/* SoundCloud */}
                        <div className="field">
                            <label className="field__label">SoundCloud</label>
                            <div className="field__row">
                                <input
                                    type="text"
                                    className="input"
                                    placeholder="@yourhandle"
                                    value={soundcloud}
                                    onChange={(e) => setSoundcloud(e.target.value)}
                                />
                                {soundcloud && (
                                    <button
                                        type="button"
                                        className="btn btn--danger ml-8"
                                        onClick={() => setSoundcloud("")}
                                    >
                                        Remove
                                    </button>
                                )}
                            </div>
                        </div>

                        {/* TikTok */}
                        <div className="field">
                            <label className="field__label">TikTok</label>
                            <div className="field__row">
                                <input
                                    type="text"
                                    className="input"
                                    placeholder="@yourhandle"
                                    value={tiktok}
                                    onChange={(e) => setTiktok(e.target.value)}
                                />
                                {tiktok && (
                                    <button
                                        type="button"
                                        className="btn btn--danger ml-8"
                                        onClick={() => setTiktok("")}
                                    >
                                        Remove
                                    </button>
                                )}
                            </div>
                        </div>

                        <div className="mt-8">
                            <button className="btn btn--primary" type="submit">
                                Save Socials
                            </button>
                        </div>
                    </form>
                )}


                {active === "plans" && (
                    <div className="account__panel plansTable">
                        <h2 className="account__h2">Plans</h2>
                        {/*<p className="muted ">Pick a plan that fits. Upgrade or downgrade anytime.</p>*/}

                        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 16 }}>
                            <p className="muted">Pick a plan that fits. Upgrade or downgrade anytime.</p>

                            <div className="muted billing-toggle">
                                <label className="switch">
                                    <input
                                        type="checkbox"
                                        checked={billingCycle === "yearly"}
                                        onChange={() => setBillingCycle(prev => prev === "monthly" ? "yearly" : "monthly")}
                                    />
                                    <span className="slider" />
                                </label>
                                <span style={{ marginLeft: 8 }}>{billingCycle === "monthly" ? "Monthly" : "Yearly"}</span>
                            </div>
                        </div>

                        <div className="plansTable__head">
                            <div className="plansTable__headCell" />

                            {[
                                { name: "Free", monthly: "$0", yearly: "$0", planId: "free" },
                                { name: "Growth", monthly: "$5.99", yearly: "$59", planId: "growth" },
                                { name: "Pro", monthly: "$14.99", yearly: "$149", planId: "pro" }
                            ].map((plan) => {
                                const planName = plan.name;
                                const isSubscribed = plan.name.toLowerCase() === currentPlan?.toLowerCase();

                                const lowerPlanName = planName.toLowerCase();
                                const current = currentPlan?.toLowerCase();

                                // Header glow logic
                                let showGlow = false;
                                let showRecommended = false;

                                if (current === "free" && lowerPlanName === "growth") {
                                    // User is free → Growth column gets glow + recommended
                                    showGlow = true;
                                    showRecommended = true;
                                } else if (current === "growth" && lowerPlanName === "growth") {
                                    // User on Growth → only glow on Growth
                                    showGlow = true;
                                } else if (current === "pro" && lowerPlanName === "pro") {
                                    // User on Pro → only glow on Pro
                                    showGlow = true;
                                }
                                return (
                                    <div
                                        key={plan.name}
                                        className={`plansTable__headCell
        ${showGlow ? "plansTable__headCell--current" : ""}
        ${showRecommended ? "plansTable__headCell--recommended" : ""}
      `}
                                    >
                                        <div className="plansTable__plan">{plan.name}</div>
                                        <div className="plansTable__price">
                                            {billingCycle === "monthly" ? plan.monthly : plan.yearly}
                                            <span>{billingCycle === "monthly" ? "/mo" : "/yr"}</span>
                                        </div>
                                        <div className="plansTable__priceAlt">
                                            {billingCycle === "monthly" ? plan.yearly : plan.monthly}
                                            <span>{billingCycle === "monthly" ? "/yr" : "/mo"}</span>
                                        </div>
                                        <div className="plansTable__cta">
                                            {/* Invisible button for layout */}
                                            <button
                                                className="btn btn--primary"
                                                style={{ visibility: "hidden", pointerEvents: "none" }}
                                                aria-hidden="true"
                                                tabIndex={-1}
                                                onClick={() => addPlanToCart("growth", "monthly", { trialDays: 7 })}
                                            >
                                                Start 7-day trial
                                            </button>

                                            {/* Main action */}
                                            {isSubscribed ? (
                                                <button
                                                    className="btn btn--ghost"
                                                    onClick={() => navigate("/library", { state: { tab: "services" } })}
                                                >
                                                    Subscribed
                                                </button>
                                            ) : currentPlan === "pro" && plan.name === "Growth" ? (
                                                <button
                                                    className="btn btn--ghost"
                                                    onClick={() => {
                                                        toast("To switch to Growth, cancel your current subscription and buy a new one.");
                                                        navigate("/library", { state: { tab: "services" } });
                                                    }}
                                                >
                                                    Subscribe monthly
                                                </button>
                                            ) : (
                                                <button
                                                    className="btn btn--primary"
                                                    onClick={() => addPlanToCart(plan.planId, billingCycle)}
                                                >
                                                    Subscribe {billingCycle === "monthly" ? "monthly" : "yearly"}
                                                </button>
                                            )}
                                        </div>
                                    </div>
                                );
                            })}
                        </div>

                        {/* Feature Matrix */}
                        {[
                            // --- Core ---
                            {label: "Commission", values: ["0%", "0%", "0%"]},
                            {label: "Uploads", values: ["3 beats / 1 pack / 1 kit", "Unlimited", "Unlimited"]},
                            {
                                label: "Promotion for Unsold Items",
                                values: [
                                    "No extra promotion",
                                    "If unsold, you'll get a  rerun and $10 promo credits",
                                    "If unsold, you'll get a rerun and $25 promo credits"
                                ]
                            },
                            // --- Marketing & Promotion ---
                            {
                                label: "Promo Credits",
                                values: [
                                    "$5 per referral",                                           // Free plan: none
                                    "$75 first-time credits + $10 per referral",      // Growth plan: first-time $30, $5 per referral
                                    "$150 first-time credits + $20 per referral"     // Pro plan: first-time $50, $10 per referral
                                ]
                            },
                            {
                                label: "Featured Placement", values: [
                                    "Pay-per-slot",
                                    "Pay-per-slot (10% off)",
                                    "Pay-per-slot (20% off)"
                                ]
                            },
                            {
                                label: "Marketing & Social Promotion",
                                values: [
                                    false,
                                    "Included in Drilldex social media",
                                    "Included in Drilldex social media + marketing campaigns"
                                ]
                            },
                            {label: "Discount Codes & Coupons", values: [false, true, true]},
                            {
                                label: "Payout Frequency",
                                values: [
                                    "Weekly only",
                                    "Weekly or on-demand",
                                    "Weekly or on-demand"
                                ]
                            },

                            // --- Store & Branding ---
                            {label: "Verified Badge", values: [false, true, true]},

                            // --- Community & Support ---
                            {label: "Review Priority", values: ["Standard", "Fast", "Priority"]},
                            {
                                label: "Support", values: [
                                    "DM the Admin (best effort)",
                                    "DM the Admin (priority)",
                                    "DM the Admin (priority) + onboarding"
                                ]
                            },

                            // --- Coming Soon / Future Features ---
                            {label: "Collaborations & Profit Sharing", values: ["Coming soon", "Coming soon", "Coming soon"]},
                            {label: "Collab Split Metadata", values: ["Coming soon", "Coming soon", "Coming soon"]},
                            {
                                label: "Beat Uploads with Co-Producers",
                                values: ["Coming soon", "Coming soon", "Coming soon"]
                            }
                        ].map((row) => (
                            <div className="plansTable__row" key={row.label}>
                                <div className="plansTable__feature">{row.label}</div>
                                {row.values.map((v, idx) => {
                                    // const planName = planNames[idx];
                                    const planNames = ["Free", "Growth", "Pro"];
                                    const planName = planNames[idx].toLowerCase();
                                    const current = currentPlan?.toLowerCase();

                                    let cellGlow = false;

// Match the rules from header
                                    if (current === "free" && planName === "growth") {
                                        cellGlow = true;
                                    } else if ((current === "growth" && planName === "growth") || (current === "pro" && planName === "pro")) {
                                        cellGlow = true;
                                    }
                                    return (
                                        <div
                                            key={`${row.label}-${idx}`}
                                            className={`plansTable__cell ${cellGlow ? "is-current" : ""}`}
                                            data-plan={planNames[idx]}
                                            aria-label={`${row.label} – ${typeof v === "boolean" ? (v ? "Included" : "Not included") : v}`}
                                        >
                                            {typeof v === "boolean"
                                                ? (v ? <span className="plansTable__check" aria-hidden>✓</span> :
                                                    <span className="plansTable__dash">—</span>)
                                                : <span className="plansTable__text">{v}</span>}
                                        </div>
                                    );
                                })}
                            </div>
                        ))}
                    </div>
                )}

            </section>
        </main>
    );
}
