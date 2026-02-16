// src/pages/NotificationsPage.jsx
import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { toast } from "react-hot-toast";
import { useAuth } from "../state/auth.jsx";
import api from "../lib/api";
import "./notificationspage.css";

export default function NotificationsPage() {
    const { userId } = useAuth();
    const navigate = useNavigate();
    const [notifications, setNotifications] = useState([]);
    const [loading, setLoading] = useState(true);
    const [latestChat, setLatestChat] = useState(null);
    const seenIdsRef = useRef(new Set());
    const esRef = useRef(null);
    const [page, setPage] = useState(0);
    const [totalPages, setTotalPages] = useState(1);
    const [pageSize] = useState(50);

    // Fetch chat threads to have a fallback for chat notifications
    useEffect(() => {
        if (!userId) return;
        let alive = true;

        (async () => {
            try {
                const res = await api.get("/chat/threads");
                if (!alive) return;

                const threads = Array.isArray(res.data) ? res.data : [];
                if (threads.length > 0) {
                    threads.sort((a, b) => new Date(b.lastTimestamp) - new Date(a.lastTimestamp));
                    setLatestChat(threads[0]);
                } else {
                    setLatestChat(null);
                }
            } catch (err) {
                console.error("[NotificationsPage] Failed to fetch chat threads:", err);
            }
        })();

        return () => { alive = false; };
    }, [userId]);

    // Fetch notifications for current page
    const fetchNotifications = async () => {
        if (!userId) return;
        setLoading(true);
        try {
            const res = await api.get("/notifications/unread", {
                params: { page, size: pageSize },
            });
            const list = res.data.content || [];
            setNotifications(list);
            setTotalPages(res.data.totalPages || 1);
            seenIdsRef.current = new Set(list.map(n => n.id));
        } catch (err) {
            toast("Failed to fetch notifications");
            console.error(err);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchNotifications();
    }, [userId, page]);

    // Fetch initial unread notifications + SSE for live updates
    useEffect(() => {
        if (!userId) return;

        let alive = true;
        // const fetchNotifications = async () => {
        //     try {
        //         const res = await api.get("/notifications/unread", {
        //             params: { page, size: pageSize },
        //         });
        //         const list = res.data.content || [];
        //         if (!alive) return;
        //         setNotifications(list);
        //         setTotalPages(res.data.totalPages || 1);
        //         seenIdsRef.current = new Set(list.map(n => n.id));
        //     } catch (err) {
        //         toast("Failed to fetch notifications");
        //         console.error(err);
        //     } finally {
        //         setLoading(false);
        //     }
        // };
        //
        // fetchNotifications();

        const base = (api.defaults?.baseURL || "").replace(/\/+$/, "");
        const url = `${base}/notifications/stream?userId=${encodeURIComponent(String(userId))}`;
        esRef.current = new EventSource(url, { withCredentials: false });

        esRef.current.addEventListener("notification", (e) => {
            try {
                const n = JSON.parse(e.data);
                if (!n?.id || seenIdsRef.current.has(n.id)) return;
                seenIdsRef.current.add(n.id);
                setNotifications(prev => [n, ...prev]);
            } catch (err) {
                console.error("[Notifications SSE] Failed to parse:", err);
            }
        });

        return () => {
            alive = false;
            try { esRef.current?.close(); } catch {}
        };
    }, [userId]);

    function renderPageNumbers(page, totalPages, setPage) {
        const pageButtons = [];
        const maxPagesToShow = 7;
        const ellipsis = <span key="ellipsis" className="tp-ellipsis">…</span>;

        const createBtn = (p) => (
            <button
                key={p}
                onClick={() => setPage(p)}
                className={`tp-pagebtn ${p === page ? "active" : ""}`}
            >
                {p + 1}
            </button>
        );

        if (totalPages <= maxPagesToShow) {
            for (let i = 0; i < totalPages; i++) pageButtons.push(createBtn(i));
        } else {
            pageButtons.push(createBtn(0));
            if (page > 3) pageButtons.push(ellipsis);
            const start = Math.max(1, page - 1);
            const end = Math.min(totalPages - 2, page + 1);
            for (let i = start; i <= end; i++) pageButtons.push(createBtn(i));
            if (page < totalPages - 4) pageButtons.push(ellipsis);
            pageButtons.push(createBtn(totalPages - 1));
        }

        return pageButtons;
    }

    // Mark a notification as read and navigate
    const handleNotificationClick = async (n) => {
        try {
            await api.post(`/notifications/${n.id}/read`);
            setNotifications(prev => prev.map(notif => notif.id === n.id ? { ...notif, read: true } : notif));

            switch (n.relatedType) {
                case "CHAT":
                    if (!n.chatId) {
                        if (!latestChat?.partnerId) return;
                        navigate(`/chat/${latestChat.partnerId}`, {
                            state: { recipientName: latestChat.partnerName || "Admin" }
                        });
                    } else {
                        navigate(`/chat/${n.chatId}`, {
                            state: { recipientName: n.recipientName || latestChat?.partnerName || "Admin" }
                        });
                    }
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
        } catch (err) {
            console.error("Failed to handle notification click", err);
        }
    };



    const markAllAsRead = async () => {
        try {
            await api.post(`/notifications/read-all`);
            setNotifications(prev => prev.map(n => ({ ...n, read: true })));
        } catch (err) {
            console.error("Failed to mark all as read", err);
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

    if (loading) return <div className="notificationspage"><p className="np-muted">Loading...</p></div>;

    return (
        <div className="notificationspage">
            <div className="notificationspage__inner">
                <div className="np-card">
                    <div className="np-card-header">
                        <h2 className="np-title">Notifications</h2>
                        <button className="np-sendbtn" onClick={markAllAsRead}>
                            Mark All as Read
                        </button>
                    </div>

                    <ul className="np-list">
                        {notifications.length === 0 && <li className="np-muted">No notifications yet</li>}
                        {notifications.map((n) => (
                            <li
                                key={n.id}
                                className={`np-item ${!n.read ? "unread" : ""}`}
                                onClick={() => handleNotificationClick(n)}
                            >
                                <div className="np-meta">
                                    <div className="np-title-item">{n.title}</div>
                                    <div className="np-message">{n.message}</div>
                                </div>
                                <div className="np-actions">
                                    <button
                                        className="np-sendbtn"
                                        onClick={(e) => { e.stopPropagation(); deleteNotification(n.id); }}
                                    >
                                        Delete
                                    </button>
                                </div>
                            </li>
                        ))}
                    </ul>
                    <div className="tp-pagination-bar">
                        <button
                            onClick={() => setPage(page - 1)}
                            disabled={page === 0}
                            className="tp-pagebtn tp-backbtn"
                        >
                            ←
                        </button>

                        <div className="tp-pagination-scroll">
                            {renderPageNumbers(page, totalPages, setPage)}
                        </div>

                        <button
                            onClick={() => setPage(page + 1)}
                            disabled={page >= totalPages - 1}
                            className="tp-pagebtn tp-nextbtn"
                        >
                            →
                        </button>
                    </div>
                </div>
            </div>
        </div>
    );
}