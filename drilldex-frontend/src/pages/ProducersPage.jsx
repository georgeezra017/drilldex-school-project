// src/pages/ProducersPage.jsx
import { useEffect, useMemo, useState } from "react";
import { IoMailOutline } from "react-icons/io5";
import { FaUserCircle } from "react-icons/fa";
import "./producers.css";
import { Link } from "react-router-dom";
import api from "../lib/api";
import { useNavigate } from "react-router-dom";
import UploaderLink from "../components/UploaderLink.jsx";

/* ---------- helpers ---------- */
const CDN_BASE = import.meta.env.VITE_S3_PUBLIC_BASE?.replace(/\/+$/, "") || "";
const toFileUrl = (p) => {
    if (!p) return "";
    if (/^https?:\/\//i.test(p)) return p;
    if (p.startsWith("/uploads/https://") || p.startsWith("/uploads/http://")) {
        return p.replace(/^\/uploads\//, "");
    }
    if (p.startsWith("/uploads/")) return p;
    const clean = p.replace(/^\/+/, "");
    return CDN_BASE ? `${CDN_BASE}/${clean}` : `/uploads/${clean}`;
};

// tolerant mapper for different backend shapes
const mapProducer = (u) => {
    const rawAvatar = u.avatarUrl || u.photoUrl || u.profileImageUrl || u.imageUrl;
    return {
        id: u.id ?? u.userId,
        name: u.displayName || u.name || u.username || u.email || "Producer",
        avatar: rawAvatar ? toFileUrl(rawAvatar) : null,
        beats: u.beatsCount ?? u.beatCount ?? u.numBeats ?? u.stats?.beats ?? 0,
        followers: u.followersCount ?? u.followerCount ?? u.stats?.followers ?? 0,
    };
};

const fmtK = (n) =>
    n >= 1000 ? `${(n / 1000).toFixed(n % 1000 === 0 ? 0 : 1)}k` : `${n}`;

export default function ProducersPage() {
    const [q, setQ] = useState("");
    const [loading, setLoading] = useState(true);
    const [err, setErr] = useState("");
    const [producers, setProducers] = useState([]);
    const navigate = useNavigate();
    const [page, setPage] = useState(0);
    const [totalPages, setTotalPages] = useState(1);
    const [limit, setLimit] = useState(100); // default page size


        const onMessage = (p) => {
                navigate(`/chat/${p.id}`, {
                        state: { recipientName: p.name }  // pass display name to ChatPage
                });
            };

    useEffect(() => {
        let alive = true;
        (async () => {
            setLoading(true);
            setErr("");
            try {
                const res = await api.get("/users/producers", {
                    params: {
                        sort: "popular",
                        limit,
                        page
                    }
                });

                const rows = Array.isArray(res.data?.items)
                    ? res.data.items.map(mapProducer)
                    : [];

                if (alive) {
                    setProducers(rows);
                    setTotalPages(res.data?.totalPages ?? 1); // get total pages from backend
                }
            } catch (e) {
                if (alive) setErr("Failed to load users.");
            } finally {
                if (alive) setLoading(false);
            }
        })();
        return () => { alive = false; };
    }, [page, limit]);

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

    const list = useMemo(() => {
        const term = q.trim().toLowerCase();
        if (!term) return producers;
        return producers.filter((p) => p.name.toLowerCase().includes(term));
    }, [q, producers]);



    return (
        <div className="producers">
            <header className="producers__hero">
                <div>
                    <h1 className="producers__title">Drilldex’ Users</h1>
                    <div className="search">
                        <input
                            className="search__input"
                            placeholder="Search users…"
                            value={q}
                            onChange={(e) => setQ(e.target.value)}
                        />
                        <svg className="search__icon" viewBox="0 0 24 24" fill="none" stroke="currentColor">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="m21 21-4.35-4.35M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z" />
                        </svg>
                    </div>
                </div>
            </header>



            {loading && <div className="list__empty">Loading users…</div>}
            {!loading && err && <div className="list__empty">{err}</div>}

            {!loading && !err && (
                <>
                    {list.length === 0 ? (
                        <div className="list__empty">No producers yet.</div>
                    ) : (
                        <ul className="producers__grid">
                            {list.map((p) => (
                                <li className="producerCard" key={p.id}>
                                    <div className="producerCard__avatar">
                                        {p.avatar ? (
                                            <img src={p.avatar} alt={p.name} />
                                        ) : (
                                            <FaUserCircle className="producerCard__avatar--placeholder" />
                                        )}
                                    </div>
                                    <div className="producerCard__name">
                                        <UploaderLink userId={p.id} noUnderline>
                                            {p.name || "Producer"}
                                        </UploaderLink>
                                    </div>

                                    <div className="producerCard__stats">
                                        <span>{p.beats} beats</span>
                                        <span>· {fmtK(p.followers)} followers</span>
                                    </div>

                                    <div className="producerCard__actions">
                                        <Link to={`/profile/${p.id}`} style={{ textDecoration: "none" }}>
                                            <button className="btn btn--primary">Show profile</button>
                                        </Link>
                                        <button
                                            className="iconbtn"
                                            aria-label={`Message ${p.name}`}
                                            onClick={() => onMessage(p)}
                                            title="Message"
                                        >
                                            <IoMailOutline />
                                        </button>
                                    </div>
                                </li>
                            ))}
                        </ul>
                    )}
                </>
            )}
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
                    disabled={page >= totalPages - 1}   // <-- key change
                    className="tp-pagebtn tp-nextbtn"
                >
                    →
                </button>
            </div>
        </div>
    );
}
