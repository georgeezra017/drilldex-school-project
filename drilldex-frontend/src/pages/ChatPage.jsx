// src/pages/ChatPage.jsx
import {useEffect, useRef, useState} from "react";
import {useParams, useNavigate, useLocation} from "react-router-dom";
import "./chatpage.css";
import {useAuth} from "../state/auth";
import api from "../lib/api";

export default function ChatPage() {
    const {user, loading: authLoading} = useAuth() || {user: null, loading: false};
    const {recipientId} = useParams();
    const navigate = useNavigate();
    const location = useLocation();

    // ---- ids & names ---------------------------------------------------------
    const toId = Number(recipientId);
    const [myId, setMyId] = useState(null);
    const [recipientName, setRecipientName] = useState("");

    // ---- sidebar threads -----------------------------------------------------
    // Each: { partnerId, partnerName?, lastContent?, lastTimestamp? }
    const [threads, setThreads] = useState([]);
    const [nameCache, setNameCache] = useState({});

    // helper: fetch & cache a user's display name
    const ensureName = async (id) => {
        if (!id || nameCache[id]) return;
        try {
            const r = await api.get(`/users/${id}`).catch(() => api.get(`/users/artist/${id}`));
            const display =
                r?.data?.displayName ||
                r?.data?.username ||
                r?.data?.name ||
                r?.data?.email ||
                `User #${id}`;
            setNameCache(prev => ({...prev, [id]: display}));
        } catch {
            setNameCache(prev => ({...prev, [id]: `User #${id}`}));
        }
    };


    // ---- chat state ----------------------------------------------------------
    const [messages, setMessages] = useState([]);
    const [newMessage, setNewMessage] = useState("");
    const [activeChatId, setActiveChatId] = useState(Number.isFinite(toId) ? toId : 0);

    // ---- refs ----------------------------------------------------------------
    const messagesEndRef = useRef(null);
    const esRef = useRef(null); // EventSource
    const seenIdsRef = useRef(new Set()); // for strict de-dupe by message id

    useEffect(() => {
        setNameCache(prev => ({ ...prev, 2: "Admin" }));
    }, []);

    // === Resolve myId: useAuth -> localStorage -> /auth/me ====================
    useEffect(() => {
        let cancelled = false;

        (async () => {
            // 1) Auth context
            if (user?.id) {
                if (!cancelled) setMyId(Number(user.id));
                return;
            }

            // 2) localStorage (optional)
            try {
                const ls = JSON.parse(localStorage.getItem("me") || "null");
                if (ls?.id && !cancelled) {
                    setMyId(Number(ls.id));
                    return;
                }
            } catch {
            }

            // 3) backend
            try {
                const res = await api.get("/auth/me");
                const id = res?.data?.id;
                if (id && !cancelled) setMyId(Number(id));
            } catch {
                /* not fatal; send disabled if we never resolve */
            }
        })();

        return () => {
            cancelled = true;
        };
    }, [user?.id]);

    // Keep activeChatId synced with URL
    useEffect(() => {
        if (Number.isFinite(toId)) setActiveChatId(toId);
    }, [toId]);

    // Hydrate recipient name immediately if we navigated with it
    useEffect(() => {
        if (location.state?.recipientName) {
            setRecipientName(location.state.recipientName);
        }
    }, [location.state]);

    // === Load sidebar threads =================================================
    useEffect(() => {
        let alive = true;
        if (!Number.isFinite(myId)) return;

        (async () => {
            try {
                // Preferred: server builds your thread list
                const {data} = await api.get("/chat/threads", {params: {userId: myId}});
                if (!alive) return;
                // setThreads(Array.isArray(data) ? data : []);
                //
                //         // Fetch names for all unique partnerIds
                //             const ids = (data || []).map(t => Number(t.partnerId)).filter(Boolean);
                //         await Promise.all(ids.map(async (id) => {
                //                       if (nameCache[id]) return; // already cached
                //            try {
                //                     const r = await api.get(`/users/${id}`).catch(() => api.get(`/users/artist/${id}`));
                //                     const displayName = r?.data?.displayName || r?.data?.username || r?.data?.email || `User #${id}`;
                //                     setNameCache(prev => ({ ...prev, [id]: displayName }));
                //                   } catch {
                //                     setNameCache(prev => ({ ...prev, [id]: `User #${id}` }));
                //                   }
                //             }));
                const list = Array.isArray(data) ? data : [];
                setThreads(list);
                // prefetch names for all thread partners
                const ids = [...new Set(list.map(t => Number(t.partnerId)).filter(Boolean))];
                await Promise.all(ids.map(ensureName));
            } catch {
                // Soft fallback: no threads endpoint
                // We'll at least ensure the current chat appears
                setThreads(prev => {
                    if (!Number.isFinite(toId)) return prev;
                    const exists = prev.some(t => Number(t.partnerId) === toId);
                    return exists ? prev : [{partnerId: toId, partnerName: recipientName || `User #${toId}`}, ...prev];
                });
                if (Number.isFinite(toId)) ensureName(toId);
            }
        })();

        return () => {
            alive = false;
        };
    }, [myId, toId, recipientName]);

    // === Load chat history for current thread =================================
    useEffect(() => {
        let alive = true;

        async function loadHistory() {
            try {
                const res = await api.get("/chat/history", {params: {user1: myId, user2: toId}});
                if (!alive) return;
                const rows = Array.isArray(res.data) ? res.data : [];
                // seed de-dupe set with history IDs
                seenIdsRef.current = new Set(rows.filter(m => m?.id != null).map(m => m.id));
                setMessages(rows);
            } catch (e) {
                console.error("[History] failed:", e);
            }
        }

        if (Number.isFinite(myId) && Number.isFinite(toId)) {
            // reset when switching threads
            setMessages([]);
            seenIdsRef.current = new Set();
            loadHistory();
        }
        return () => {
            alive = false;
        };
    }, [myId, toId]);

    // === Fetch recipient display name (if not provided via navigation) ========
    useEffect(() => {
        if (!Number.isFinite(toId)) return;
        let cancelled = false;

        (async () => {
            try {
                // Adjust to match your backend; includes a fallback
                const r = await api.get(`/users/${toId}`).catch(() => api.get(`/users/artist/${toId}`));
                const name =
                    r?.data?.displayName ||
                    r?.data?.username ||
                    r?.data?.name ||
                    r?.data?.email ||
                    `User #${toId}`;
                if (!cancelled) setRecipientName(name);
            } catch {
                if (!cancelled) setRecipientName(`User #${toId}`);
            }
        })();

        return () => {
            cancelled = true;
        };
    }, [toId]);

    // === Subscribe to live updates (SSE) =====================================
    useEffect(() => {
        if (!Number.isFinite(myId)) return;

        const base = (api.defaults?.baseURL || "").replace(/\/+$/, ""); // e.g. http://localhost:8080/api
        const url = `${base}/chat/stream?userId=${encodeURIComponent(String(myId))}`;
        const es = new EventSource(url, {withCredentials: false});
        esRef.current = es;

        es.addEventListener("message", (e) => {
            try {
                const payload = JSON.parse(e.data); // { id?, senderId, receiverId, content, timestamp }
                const a = Number(payload.senderId);
                const b = Number(payload.receiverId);

                // Update/bump thread preview for whichever partner this is
                const partnerId = a === myId ? b : a;
                setThreads(prev => {
                    const rest = prev.filter(t => Number(t.partnerId) !== partnerId);
                    const existing = prev.find(t => Number(t.partnerId) === partnerId);
                    const item = {
                        // ...(existing || { partnerId, partnerName: existing?.partnerName || `User #${partnerId}` }),
                        ...(existing || {partnerId}),
                        lastContent: payload.content ?? existing?.lastContent ?? "",
                        lastTimestamp: payload.timestamp ?? existing?.lastTimestamp ?? null,
                    };
                    return [item, ...rest];
                });

                // Only push into message list if it's for the active thread
                const forActive = (a === myId && b === toId) || (a === toId && b === myId);
                if (!forActive) return;

                // Hard de-dupe by message id
                if (payload.id != null) {
                    if (seenIdsRef.current.has(payload.id)) return;
                    seenIdsRef.current.add(payload.id);
                }
                setMessages(prev => [...prev, payload]);
            } catch (err) {
                console.warn("[SSE] bad JSON:", e.data, err);
            }
        });

        es.addEventListener("error", (e) => {
            console.warn("[SSE] error:", e);
        });

        return () => {
            try {
                es.close();
            } catch {
            }
            esRef.current = null;
        };
    }, [myId, toId]);

    // === Keep messages scrolled to bottom (container only) ===================
    useEffect(() => {
        const el = messagesEndRef.current;
        if (el && el.parentElement) {
            el.parentElement.scrollTop = el.parentElement.scrollHeight;
        }
    }, [messages]);

    // === Prevent page scroll, only chat list scrolls =========================
    useEffect(() => {
        const prev = document.body.style.overflow;
        document.body.style.overflow = "hidden";
        return () => {
            document.body.style.overflow = prev;
        };
    }, []);

    // === Send a message =======================================================
    const sendMessage = async () => {
        if (!newMessage.trim() || !Number.isFinite(myId) || !Number.isFinite(toId)) return;

        const msg = {
            senderId: myId,
            receiverId: toId,
            content: newMessage.trim(),
        };

        try {
            await api.post("/chat/send", msg);
            setNewMessage(""); // clear input
            // DO NOT append to messages here
        } catch (e) {
            console.error("[Send] failed:", e);
        }
    };

    // === Guards ==============================================================
    if (!Number.isFinite(toId)) {
        return (
            <div className="chatpage">
                <div className="chatpage__content">
                    <div className="chatpage__main">Invalid conversation.</div>
                </div>
            </div>
        );
    }
    if (!myId && authLoading) {
        return (
            <div className="chatpage">
                <div className="chatpage__content">
                    <div className="chatpage__main">Loading…</div>
                </div>
            </div>
        );
    }

    // === UI ==================================================================
    return (
        <div className="chatpage">
            <div className="chatpage__content">
                {/* Sidebar with started chats */}
                <div className="chatpage__sidebar">
                    <h2>Messages</h2>
                    <ul style={{listStyle: "none", padding: 0, margin: 0}}>
                        {threads.map((t) => {
                            const pid = Number(t.partnerId);
                            const active = activeChatId === pid;
                            const display = nameCache[pid] || t.partnerName || `User #${pid}`;
                            return (
                                <li key={pid}>
                                    <div
                                        className={`chatpage__conversation ${active ? "active" : ""}`}
                                        onClick={() => {
                                            setActiveChatId(pid);
                                            // show name instantly in header; router state for hydration on landing
                                            // if (t.partnerName) setRecipientName(t.partnerName);
                                            // navigate(`/chat/${pid}`, { state: { recipientName: t.partnerName } });
                                            setRecipientName(display); // header updates instantly
                                            +navigate(`/chat/${pid}`, {state: {recipientName: display}});
                                        }}
                                    >
                                        {/*<strong>{pid === toId ? (recipientName || `User #${pid}`) : (t.partnerName || `User #${pid}`)}</strong><br />*/}
                                        <strong>{display}</strong><br/>
                                        <span style={{fontSize: "0.85rem", opacity: 0.7}}>
                      {t.lastContent ? t.lastContent : "Open chat"}
                    </span>
                                    </div>
                                </li>
                            );
                        })}
                    </ul>
                </div>

                {/* Main chat */}
                <div className="chatpage__main">
                    <div className="chatpage__header">Chat with {recipientName || `User #${toId}`}</div>

                    <div className="chatpage__messages">
                        {messages.map((m, i) => {
                            const mine = Number(m.senderId) === myId;
                            return (
                                <div
                                    key={m.id || `${m.senderId}-${m.timestamp || i}`}
                                    className={`chatpage__message ${mine ? "chatpage__message--user" : "chatpage__message--other"}`}
                                    title={`from ${m.senderId} to ${m.receiverId}`}
                                >
                                    {m.content ?? m.text ?? ""}
                                </div>
                            );
                        })}
                        <div ref={messagesEndRef}/>
                    </div>

                    <form
                        className="chatpage__inputform"
                        onSubmit={(e) => {
                            e.preventDefault();
                            sendMessage();
                        }}
                    >
                        <input
                            type="text"
                            placeholder="Type a message…"
                            value={newMessage}
                            onChange={(e) => setNewMessage(e.target.value)}
                            disabled={!Number.isFinite(myId)}
                        />
                        <button type="submit" disabled={!Number.isFinite(myId) || !newMessage.trim()}>
                            Send
                        </button>
                    </form>
                </div>
            </div>
        </div>
    );
}