// src/components/Header.jsx
import {Link, NavLink} from "react-router-dom";
import {useState} from "react";
import {useCart} from "../state/cart";
import {useAuth} from "../state/auth.jsx";
import api from "../lib/api.js"
import ReferralModal from "../components/ReferralModal";


import {
    IoCartOutline,
    IoMenu,
    IoClose,
    IoPersonOutline,
    IoSettingsOutline,
    IoRocketOutline,
    IoLogOutOutline,
    IoLibraryOutline,
    IoCardOutline,
    IoChatbubbleOutline,
    IoPeopleOutline
} from "react-icons/io5";
import "../pages/landing.css";
import AuthModal from "../components/AuthModal";
import {FaUserCircle} from "react-icons/fa";
import {useRef, useEffect} from "react";
import {createContext, useContext} from "react";
import {useNavigate} from "react-router-dom";
import {IoNotificationsOutline} from "react-icons/io5";

function useNotifications(userId) {
    const [notifications, setNotifications] = useState([]);
    const seenIdsRef = useRef(new Set());

    useEffect(() => {
        if (!userId) return;

        let alive = true;

        // 1. Fetch initial unread notifications
        (async () => {
            try {
                const res = await api.get("/notifications/unread");
                console.log("[Notifications] Raw backend response:", res.data);

                const list = res.data.content || [];
                console.log("[Notifications] Parsed notifications list:", list);

                if (!alive) return;
                setNotifications(list);
                seenIdsRef.current = new Set(list.map(n => n.id));
            } catch (err) {
                console.error("[Notifications] Failed to fetch initial list:", err);
            }
        })();

        // 2. SSE / live updates
        const base = (api.defaults?.baseURL || "").replace(/\/+$/, "");
        const url = `${base}/notifications/stream?userId=${encodeURIComponent(String(userId))}`;
        console.log("[Notifications] Connecting SSE to:", url);

        let es;

        try {
            es = new EventSource(url, { withCredentials: false });
        } catch (err) {
            console.warn("[Notifications] SSE initialization failed:", err);
        }

        if (es) {
            es.addEventListener("open", () => {
                console.log("[Notifications SSE] connection opened");
            });

            es.addEventListener("notification", (e) => {
                console.log("[Notifications SSE] raw event data:", e.data);
                try {
                    const n = JSON.parse(e.data);
                    console.log("[Notifications SSE] parsed notification DTO:", n);

                    if (!n?.id || seenIdsRef.current.has(n.id)) return;
                    seenIdsRef.current.add(n.id);
                    setNotifications(prev => [n, ...prev]);
                } catch (err) {
                    console.error("[Notifications SSE] Failed to parse:", err);
                }
            });

            es.addEventListener("error", (e) => {
                console.warn("[Notifications SSE] error:", e);
            });
        }

        return () => {
            alive = false;
            try { es?.close(); } catch {}
        };
    }, [userId]);

    return [notifications, setNotifications];
}

export default function Header() {
    const {items} = useCart();
    const { token, displayName, logout, avatarUrl, userId } = useAuth();
    const [open, setOpen] = useState(false);
    const [isAuthOpen, setAuthOpen] = useState(false);
    const [profileOpen, setProfileOpen] = useState(false);       // dropdown
    const navigate = useNavigate();
    const [query, setQuery] = useState("");
    const openAuth = () => {
        setOpen(false);
        setAuthOpen(true);
    };
    const [referralOpen, setReferralOpen] = useState(false);
    const [latestChat, setLatestChat] = useState(null);
    const wrapRef = useRef(null);
    const count = items.reduce((n, i) => n + (i.qty ?? 1), 0);
    const notifRef = useRef(null); // ref for the notification dropdown
    const [notifications, setNotifications] = useNotifications(userId);
    const [notificationsOpen, setNotificationsOpen] = useState(false);
    const unreadCount = (notifications || []).filter(n => !n.read).length;
    const [referralData, setReferralData] = useState(null);

    const openReferralModal = async () => {
        try {
            const res = await api.get("/me/referral"); // call your endpoint
            setReferralData(res.data);
            setReferralOpen(true);
        } catch (err) {
            console.error("Failed to fetch referral info", err);
        }
    };

// Helper to mark a notification read
    const markAsRead = async (id) => {
        try {
            await api.post(`/notifications/${id}/read`);
            setNotifications(prev => prev.map(n => n.id === id ? { ...n, read: true } : n));
        } catch (err) {
            console.error("Failed to mark notification as read", err);
        }
    };

    const deleteNotification = async (id) => {
        try {
            await api.delete(`/notifications/${id}`);
            setNotifications(prev => prev.filter(n => n.id !== id));
        } catch (err) {
            console.error("Failed to delete notification", err);
        }
    };

    useEffect(() => {
        if (!userId) return;
        let alive = true;

        (async () => {
            try {
                const res = await api.get("/chat/threads", { params: { userId } });
                if (!alive) return;

                const threads = Array.isArray(res.data) ? res.data : [];
                if (threads.length > 0) {
                    // Sort descending by lastTimestamp
                    threads.sort((a, b) => new Date(b.lastTimestamp) - new Date(a.lastTimestamp));
                    setLatestChat(threads[0]);
                } else {
                    setLatestChat(null);
                }
            } catch (err) {
                console.error("[Header] Failed to fetch latest chat:", err);
            }
        })();

        return () => { alive = false; };
    }, [userId]);

    const handleNotificationClick = async (n) => {
        try {
            await api.post(`/notifications/${n.id}/read`);
            setNotifications(prev => prev.map(notif => notif.id === n.id ? { ...notif, read: true } : notif));

            switch (n.relatedType) {
                case "CHAT":
                    if (!n.chatId) return;
                    navigate(`/chat/${n.chatId}`, { state: { recipientName: n.recipientName || "Admin" } });
                    break;
                case "BEAT":
                case "PACK":
                case "KIT":
                    if (!n.slug) return;
                    navigate(`/track/${n.slug}`);
                    break;
                case "SUBSCRIPTION":
                case "PROMOTION":
                    navigate("/library", { state: { tab: "services" } });
                    break;
                case "USER":
                    if (!n.profileId) return;
                    navigate(`/profile/${n.profileId}`);
                    break;
                case "SYSTEM":
                default:
                    navigate("/notifications");
            }

            setNotificationsOpen(false);
        } catch (err) {
            console.error("Failed to handle notification click", err);
        }
    };


    useEffect(() => {
        const handleOutside = (e) => {
            if (notificationsOpen && notifRef.current && !notifRef.current.contains(e.target)) {
                setNotificationsOpen(false);
            }
        };
        const handleEsc = (e) => {
            if (e.key === "Escape") setNotificationsOpen(false);
        };

        document.addEventListener("mousedown", handleOutside);
        document.addEventListener("touchstart", handleOutside, { passive: true });
        document.addEventListener("keydown", handleEsc);

        return () => {
            document.removeEventListener("mousedown", handleOutside);
            document.removeEventListener("touchstart", handleOutside);
            document.removeEventListener("keydown", handleEsc);
        };
    }, [notificationsOpen]);

    useEffect(() => {
        const handleOutside = (e) => {
            if (profileOpen && wrapRef.current && !wrapRef.current.contains(e.target)) {
                setProfileOpen(false);
            }
        };
        const handleEsc = (e) => {
            if (e.key === "Escape") setProfileOpen(false);
        };

        document.addEventListener("mousedown", handleOutside);
        document.addEventListener("touchstart", handleOutside, {passive: true});
        document.addEventListener("keydown", handleEsc);

        return () => {
            document.removeEventListener("mousedown", handleOutside);
            document.removeEventListener("touchstart", handleOutside);
            document.removeEventListener("keydown", handleEsc);
        };
    }, [profileOpen]);

    // Count of unread notifications


    return (
        <header className="header">
            <div className="header__inner">
                <Link to="/" className="brand" onClick={() => setOpen(false)}>
                    <div className="brand__logo"><img src="/logo.png" alt="Drilldex Logo"/></div>
                    <div className="brand__name">Drilldex</div>
                </Link>

                {/* Desktop nav */}
                <nav className="nav">
                    <NavLink to="/" className="nav__link">Home</NavLink>
                    <NavLink to="/browse" className="nav__link">Browse</NavLink>
                    <NavLink to="/charts" className="nav__link">Charts</NavLink>
                    <NavLink to="/producers" className="nav__link">Users</NavLink>
                    <NavLink to="/sell" className="nav__link">Sell Beats</NavLink>
                    <NavLink to="/kits" className="nav__link">Sell Kits</NavLink>
                    <NavLink to="/about" className="nav__link">About</NavLink>
                    <NavLink to="/checkout" className="nav__link cart-link" onClick={() => setOpen(false)}>
                        <IoCartOutline size={20}/>
                        {count > 0 && <span className="cart__count">{count}</span>}
                    </NavLink>
                </nav>

                {/* Desktop search */}
                <div className="search" role="search">
                    <input
                        className="search__input"
                        placeholder="Search beats, styles, tags…"
                        value={query}
                        onChange={(e) => setQuery(e.target.value)}
                        onKeyDown={(e) => {
                            if (e.key === "Enter") {
                                e.preventDefault();
                                navigate(`/search${query ? `?q=${encodeURIComponent(query)}` : ""}`);
                                setOpen(false);
                            }
                        }}
                        aria-label="Search"
                    />
                    <button
                        type="button"
                        className="search__iconBtn"
                        onClick={() => {
                            navigate(`/search${query ? `?q=${encodeURIComponent(query)}` : ""}`);
                            setOpen(false);
                        }}
                        aria-label="Search"
                        title="Search"
                    >
                        <svg className="search__icon" viewBox="0 0 24 24" fill="none" stroke="currentColor">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2"
                                  d="m21 21-4.35-4.35M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z"/>
                        </svg>
                    </button>
                </div>


                {token && (
                    <div className="header__notifications"  ref={notifRef} style={{position: "relative", marginRight: "0.5rem"}}>
                        <button
                            type="button"
                            className="header__notifBtn"
                            onClick={() => setNotificationsOpen(v => !v)}
                            aria-label="Notifications"
                        >
                            <IoNotificationsOutline size={24}/>
                            {unreadCount > 0 && <span className="notif__badge">{unreadCount}</span>}
                        </button>

                        {notificationsOpen && (
                            <div className="notif__dropdown">
                                <div className="notif__header">
                                    <span>Notifications</span>
                                    <button
                                        onClick={() => {
                                            navigate("/notifications");   // navigate to notifications page
                                            setNotificationsOpen(false);  // close dropdown
                                        }}
                                        className="notif__markAll"
                                    >
                                        View all notifications
                                    </button>
                                </div>
                                <ul className="notif__list">
                                    {notifications.length === 0 && <li className="notif__item">No notifications</li>}
                                    {notifications.map(n => (
                                        <li
                                            key={n.id}
                                            className={`notif__item ${n.read ? "" : "notif__unread"}`}
                                        >
                                            <div
                                                className="notif__content-wrapper"
                                                onClick={() => handleNotificationClick(n)}
                                            >
                                                <strong>{n.title}</strong>
                                                <p>{n.message}</p>
                                            </div>

                                            <button
                                                className="notif__clear"
                                                onClick={(e) => { e.stopPropagation(); deleteNotification(n.id); }}
                                                aria-label="Clear notification"
                                            >
                                                ×
                                            </button>
                                        </li>
                                    ))}
                                </ul>
                            </div>
                        )}
                    </div>
                )}

                {/* Right side: auth */}
                {token ? (
                    <div className="header__profileWrap" ref={wrapRef}>
                        <button
                            type="button"
                            className="header__profileBtn"
                            onClick={() => setProfileOpen(v => !v)}
                            aria-haspopup="menu"
                            aria-expanded={profileOpen}
                            aria-label="Account menu"
                        >
                            {avatarUrl ? (
                                <img
                                    className="header__avatar"
                                    src={avatarUrl}
                                    alt={displayName || "Profile"}
                                />
                            ) : (
                                <FaUserCircle className="header__avatar" size={32}/>
                            )}
                        </button>

                        {profileOpen && (
                            <div className="header__menu" role="menu">
                                <Link
                                    to="/account"
                                    className="header__menuItem"
                                    role="menuitem"
                                    onClick={() => setProfileOpen(false)}
                                >
                                    <IoSettingsOutline size={20} className="mr-2"/>
                                    Account
                                </Link>
                                <Link
                                    to="/profile/me"
                                    className="header__menuItem"
                                    role="menuitem"
                                    onClick={() => setProfileOpen(false)}
                                >
                                    <IoPersonOutline size={20} className="mr-2"/>
                                    Profile
                                </Link>
                                <Link
                                    to="/account?tab=plans"
                                    className="header__menuItem"
                                    role="menuitem"
                                    onClick={() => setProfileOpen(false)}
                                >
                                    <IoCardOutline size={20} className="mr-2"/>
                                    Plans
                                </Link>
                                <Link
                                    to="/promos"
                                    className="header__menuItem"
                                    role="menuitem"
                                    onClick={() => setProfileOpen(false)}
                                >
                                    <IoRocketOutline size={20} className="mr-2"/>
                                    Promotions
                                </Link>


                                <Link
                                    to={latestChat?.partnerId ? `/chat/${latestChat.partnerId}` : "/chat/2"}
                                    className="header__menuItem"
                                    role="menuitem"
                                    onClick={() => setProfileOpen(false)}
                                    state={{ recipientName: latestChat?.partnerName || "Admin" }}
                                >
                                    <IoChatbubbleOutline size={20} className="mr-2"/>
                                    Chat
                                </Link>
                                <Link
                                    className="header__menuItem"
                                    role="menuitem"
                                    onClick={() => { openReferralModal(); setProfileOpen(false); }}
                                >
                                    <IoPeopleOutline size={20} className="mr-2"/>
                                    Referral
                                </Link>
                                <Link
                                    to="/library"
                                    className="header__menuItem"
                                    role="menuitem"
                                    onClick={() => setProfileOpen(false)}
                                >
                                    <IoLibraryOutline size={20} className="mr-2"/>
                                    My Library
                                </Link>

                                <button
                                    className="header__menuItem header__menuItem--danger"
                                    role="menuitem"
                                    onClick={() => {
                                        setProfileOpen(false);
                                        logout();
                                        navigate("/");
                                    }}
                                >
                                    <IoLogOutOutline size={20} className="mr-2"/>
                                    Sign out
                                </button>
                            </div>
                        )}
                    </div>
                ) : (
                    <button
                        type="button"
                        onClick={openAuth}
                        className="btn btn--primary header__signin"
                        aria-haspopup="dialog"
                        aria-controls="auth-modal"
                    >
                        Sign in
                    </button>
                )}

                {/* Mobile menu button */}
                <button
                    className="header__menuBtn"
                    aria-label={open ? "Close menu" : "Open menu"}
                    aria-expanded={open}
                    onClick={() => setOpen(v => !v)}
                >
                    {open ? <IoClose size={22}/> : <IoMenu size={22}/>}
                </button>
            </div>

            {/* Mobile dropdown */}
            <div className={`header__mobile ${open ? "is-open" : ""}`}>
                <nav className="header__mobileNav" onClick={() => setOpen(false)}>
                    <NavLink to="/" className="nav__link">Home</NavLink>
                    <NavLink to="/browse" className="nav__link">Browse</NavLink>
                    <NavLink to="/charts" className="nav__link">Charts</NavLink>
                    <NavLink to="/producers" className="nav__link">Users</NavLink>
                    <NavLink to="/sell" className="nav__link">Sell Beats</NavLink>
                    <NavLink to="/kits" className="nav__link">Sell Kits</NavLink>
                    <NavLink to="/about" className="nav__link">About</NavLink>
                    <NavLink
                        to="/notifications"
                        className="nav__link"
                        onClick={() => setOpen(false)} // close mobile menu
                    >
                        Notifications {unreadCount > 0 && `(${unreadCount})`}
                    </NavLink>
                    <NavLink to="/checkout" className="nav__link">Cart ({items.length})</NavLink>

                    {token ? (
                        <div className="header__mobileAuth">
                            <Link to="/account" className="nav__link">
                                <IoSettingsOutline size={16} className="mr-2"/> Account
                            </Link>
                            <Link to="/account?tab=plans" className="nav__link">
                                <IoCardOutline size={16} className="mr-2"/> Plans
                            </Link>
                            <Link to="/library" className="nav__link">
                                <IoLibraryOutline size={16} className="mr-2"/> My Library
                            </Link>
                            <Link to="/profile/me" className="nav__link">
                                <IoPersonOutline size={16} className="mr-2"/> Profile
                            </Link>
                            <Link
                                to={latestChat?.partnerId ? `/chat/${latestChat.partnerId}` : "/chat/2"}
                                className="nav__link"
                                state={{
                                    recipientName: latestChat?.partnerName || "Admin"
                                }}
                            >
                                <IoChatbubbleOutline size={16} className="mr-2"/> Chat
                            </Link>
                            <Link to="/promos" className="nav__link">
                                <IoRocketOutline size={16} className="mr-2"/> Promotions
                            </Link>
                            <button
                                className="nav__link"
                                onClick={() => { openReferralModal(); setProfileOpen(false); }}
                            >
                                <IoPeopleOutline size={16} className="mr-2"/>
                                Referral
                            </button>

                            <button className="btn btn--ghost" onClick={() => {
                                setOpen(false);
                                logout();
                                navigate("/");
                            }}>
                                Sign out
                            </button>
                        </div>
                    ) : (
                        <button type="button" className="btn btn--primary btn--block" onClick={openAuth}>
                            Sign in
                        </button>
                    )}
                </nav>

            </div>

            <AuthModal isOpen={isAuthOpen} onClose={() => setAuthOpen(false)}/>

            {referralOpen && referralData && (
                <ReferralModal
                    user={referralData}
                    onClose={() => setReferralOpen(false)}
                />
            )}
        </header>

    );

}
