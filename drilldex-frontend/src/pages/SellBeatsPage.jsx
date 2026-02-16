import { useMemo, useState } from "react";
import {
    IoAddOutline, IoSearch, IoSwapVertical, IoFilter, IoPencil,
    IoShareOutline, IoTrashOutline, IoOpenOutline, IoTimeOutline, IoStatsChartOutline,
} from "react-icons/io5";
import "./SellBeats.css";
import api from "../lib/api";

import { useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../state/auth.jsx";
import AuthModal from "../components/AuthModal";
import UploadModal from "../components/UploadModal";
import { toast } from "react-hot-toast";
import ShareModal from "../components/ShareModal";
import EditItemModal from "../components/EditItemModal";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";



const fmtDate = (d) =>
    new Date(d).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });

const fmtCurrency = (n) =>
    n.toLocaleString(undefined, { style: "currency", currency: "USD", maximumFractionDigits: 2 });

const API_ORIGIN = new URL(api.defaults.baseURL).origin;
function toFileUrl(input) {
    if (!input) return "";
    if (/^https?:\/\//i.test(input)) return input;

    let path = String(input).replace(/\\/g, "/").trim();

    // Normalize bare "uploads/..." to "/uploads/..."
    if (/^uploads\//i.test(path)) path = `/${path}`;

    // Always keep the last /uploads/... segment if it exists
    const i = path.lastIndexOf("/uploads/");
    if (i >= 0) path = path.slice(i);

    // If still not /uploads/, accept covers|audio|packs (with or without leading "uploads/")
    if (!path.startsWith("/uploads/")) {
        if (/^(?:uploads\/)?(covers|audio|packs)\//i.test(path)) {
            path = path.replace(/^uploads\//i, ""); // drop leading "uploads/" if present
            path = `/uploads/${path}`;
        } else {
            return ""; // unknown / not public
        }
    }

    // Collapse accidental double slashes
    path = path.replace(/\/{2,}/g, "/");

    return `${API_ORIGIN}${path}`;
}

export default function SellBeatsPage() {
    const [q, setQ] = useState("");
    const [status, setStatus] = useState("all");
    const [sort, setSort] = useState("updated");
    const [open, setOpen] = useState(false);
    const [items, setItems]   = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError]     = useState("");
    const { token } = useAuth();
    const [deleteModalOpen, setDeleteModalOpen] = useState(false);
    const [itemToDelete, setItemToDelete] = useState(null);
    const navigate = useNavigate();
    const [forceAuthOpen, setForceAuthOpen] = useState(false);
    const [shareOpen, setShareOpen] = useState(false);
    const [shareData, setShareData] = useState(null);
    const [editOpen, setEditOpen] = useState(false);
    const [editItem, setEditItem] = useState(null);
    const FEE_RATE = 0;
    const earningsOf = (b) => b.price * b.sales * (1 - FEE_RATE); // NEW
    const [page, setPage] = useState(0);
    const [totalPages, setTotalPages] = useState(1);
    const beatSeg = (b) => b?.slug || String(b?.id || "");
    const packSeg = (p) => p?.slug || "";

    function buildShareData(row, slug) {
          const path = row.kind === "Pack" ? `/pack/${slug}` : `/track/${slug}`;
          return {
                type: row.kind,            // "Beat" | "Pack"
                id: row.id,
                title: row.title,
                coverUrl: row.cover,
                canonicalUrl: `${window.location.origin}${path}`,
              };
        }

    const onOpen = async (row) => {
        if (!row) return;

        if (row.kind === "Pack") {
            let slug = row.slug;
            if (!slug) {
                const t = toast.loading("Resolving pack…");
                slug = await fetchPackSlugById(row.id);
                toast.dismiss(t);

                if (!slug) {
                    toast.error("Couldn't resolve this pack's slug.");
                    return;
                }

                // cache into state
                setItems((prev) => prev.map((it) => (it.id === row.id ? { ...it, slug } : it)));
            }
            navigate(`/pack/${slug}`);
            return;
        }

        // Beat
        let slug = row.slug;
        if (!slug) {
            const t = toast.loading("Resolving track…");
            slug = await fetchBeatSlugById(row.id);
            toast.dismiss(t);

            if (!slug) {
                toast.error("Couldn't resolve this track's slug.");
                return;
            }

            // cache into state
            setItems((prev) => prev.map((it) => (it.id === row.id ? { ...it, slug } : it)));
        }
        navigate(`/track/${slug}`);
    };

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
            pageButtons.push(createBtn(0)); // First page

            if (page > 3) pageButtons.push(ellipsis);

            const start = Math.max(1, page - 1);
            const end = Math.min(totalPages - 2, page + 1);
            for (let i = start; i <= end; i++) {
                pageButtons.push(createBtn(i));
            }

            if (page < totalPages - 4) pageButtons.push(ellipsis);

            pageButtons.push(createBtn(totalPages - 1)); // Last page
        }

        return pageButtons;
    }

    async function fetchBeatSlugById(id) {
        try {
            const { data } = await api.get(`/beats/${id}`); // expects BeatDto with .slug
            return data?.slug || null;
        } catch {
            return null;
        }
    }

    async function fetchPackSlugById(id) {
        try {
            const { data } = await api.get(`/packs/${id}`); // expects { ..., slug }
            return data?.slug || null;
        } catch {
            return null;
        }
    }

    const onEdit = (row) => {
        setEditItem(row);
        setEditOpen(true);
    };

    const onShare = (row) => {
        if (!row) return;

        const base = row.kind === "Pack" ? "pack" : "track";

        setShareData({
            id: row.id,
            slug: row.slug || row.id,
            kind: row.kind,
            title: row.title,
            artistName: row.artistName || row.ownerName || "Unknown Artist",
            albumCoverUrl: row.albumCoverUrl || row.cover,
            cover: row.cover,
            canonicalUrl: `${window.location.origin}/${base}/${row.slug || row.id}`,
        });

        setShareOpen(true);
    };

    const onDelete = (item) => {
        setItemToDelete(item);
        setDeleteModalOpen(true);
    };

    const handleConfirmDelete = async (item) => {
        setDeleteModalOpen(false);
        const prev = items;
        setItems((rows) => rows.filter((x) => x.id !== item.id));

        try {
            const path = item.kind === "Pack" ? `/me/packs/${item.id}` : `/me/beats/${item.id}`;
            await api.delete(path);
            toast.success(`${item.kind} deleted successfully.`);
        } catch (e) {
            console.error(e);
            toast.error("Delete failed.");
            setItems(prev); // revert UI
        }
    };

    useEffect(() => {
        if (!token) { setForceAuthOpen(true); return; }

        let alive = true;

        const mapBeat = (x) => ({
            id: x.id,
            slug: x.slug || null,
            kind: "Beat",
            title: x.title ?? "(untitled)",
            artistName: x.ownerName || x.artistName || "Unknown Artist",
            albumCoverUrl: toFileUrl(x.coverUrl || x.albumCoverUrl || x.coverImagePath),
            cover: toFileUrl(x.coverUrl || x.albumCoverUrl || x.coverImagePath),
            bpm: x.bpm ?? 0,
            key: x.keySignature || x.key || "",
            genre: x.genre || "",
            price: Number(x.price ?? 0),
            plays: Number(x.plays ?? 0),
            likes: Number(x.likes ?? 0),
            sales: Number(x.sales ?? 0),
            status: x.status || "published",
            updatedAt: x.updatedAt || x.modifiedAt || x.createdAt || Date.now(),
        });

        const mapPack = (x) => ({
            id: x.id,
            slug: x.slug || null,
            kind: "Pack",
            title: x.title ?? "(untitled)",
            artistName: x.ownerName || x.artistName || "Unknown Artist",
            albumCoverUrl: toFileUrl(x.coverUrl || x.albumCoverUrl || x.coverImagePath),
            cover: toFileUrl(x.coverUrl || x.albumCoverUrl || x.coverImagePath),
            genre: x.genre || "",
            price: Number(x.price ?? 0),
            plays: Number(x.plays ?? 0),
            likes: Number(x.likes ?? 0),
            sales: Number(x.sales ?? 0),
            status: x.status || "published",
            updatedAt: x.updatedAt || x.modifiedAt || x.createdAt || Date.now(),
        });

        (async () => {
            setLoading(true);
            setError("");
            try {
                const results = await Promise.allSettled([
                    api.get("/me/beats", { params: { page, limit: 50 } }),
                    api.get("/me/packs", { params: { page, limit: 50 } }),
                ]);

                const beats =
                    results[0].status === "fulfilled" && results[0].value?.data?.items
                        ? results[0].value.data.items.map(mapBeat)
                        : [];

                const packs =
                    results[1].status === "fulfilled" && results[1].value?.data?.items
                        ? results[1].value.data.items.map(mapPack)
                        : [];

                if (alive) setItems([...beats, ...packs]);

                // Set total pages from PaginatedResponse
                if (results[0].status === "fulfilled") {
                    const totalBeatPages = Math.ceil(results[0].value.data.totalItems / 50);
                    setTotalPages(totalBeatPages);
                }

            } catch (e) {
                console.error(e);
                if (alive) setError("Failed to load your items.");
            } finally {
                if (alive) setLoading(false);
            }
        })();

        return () => { alive = false; };
    }, [token, page]);



    const list = useMemo(() => {
        let rows = items.slice();                     // <— use items from API

        if (status !== "all") rows = rows.filter((b) => b.status === status);

        const term = q.trim().toLowerCase();
        if (term) {
            rows = rows.filter((b) =>
                [b.title, b.genre, String(b.bpm), b.key].some((v) =>
                    String(v).toLowerCase().includes(term)
                )
            );
        }

        rows.sort((a, b) => {
            switch (sort) {
                case "sales":    return (b.sales||0) - (a.sales||0);
                case "plays":    return (b.plays||0) - (a.plays||0);
                case "price":    return (b.price||0) - (a.price||0);
                case "earnings": return earningsOf(b) - earningsOf(a);
                default:         return new Date(b.updatedAt||0) - new Date(a.updatedAt||0);
            }
        });

        return rows;
    }, [items, q, status, sort]);


    useEffect(() => {
        if (!token) setForceAuthOpen(true); // open modal when not signed in
    }, [token]);

    const totals = useMemo(() => {
        return {
            earnings: list.reduce((sum, b) => sum + earningsOf(b), 0),
            licenses: list.reduce((sum, b) => sum + b.sales, 0),
            plays: list.reduce((sum, b) => sum + b.plays, 0),
            likes: list.reduce((sum, b) => sum + b.likes, 0),
            beats: list.length
        };
    }, [list]);



    if (!token) {
        return (
            <main
                className="sell"
                style={{
                    minHeight: "60vh",
                    display: "flex",
                    flexDirection: "column",
                    alignItems: "center",
                    justifyContent: "center",
                }}
            >
                <p
                    className="text-muted mb-4"
                    style={{ fontSize: "1.1rem", fontWeight: 500 }}
                >
                    Please log in to access this feature
                </p>
                <AuthModal
                    isOpen={forceAuthOpen}
                    onClose={() => setForceAuthOpen(false)}
                />
            </main>
        );
    }

    return (
        <main className="sell">
            <header className="sell__hero">
                <div className="sell__head-left">
                    <h1 className="sell__title">Sell Beats</h1>
                    <p className="sell__sub">Manage your uploads, status, and pricing.</p>
                </div>

                <div className="sell__head-right">
                    <button className="sell__btn sell__btn--primary" onClick={() => setOpen(true)}>
                        <IoAddOutline /> Upload new beat or pack
                    </button>
                </div>

            </header>

            <div className="sell__toolbar">
                <div className="search">
                    <input
                        className="search__input"
                        placeholder="Search your beats…"
                        value={q}
                        onChange={(e) => setQ(e.target.value)}
                    />
                    <svg className="search__icon" viewBox="0 0 24 24" fill="none" stroke="currentColor">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="m21 21-4.35-4.35M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z" />
                    </svg>
                </div>

                {/* Middle: stats */}
                <div className="sell__statsBar">
                    <div className="sell__stat">
                        <span className="sell__statLabel">Earnings</span>
                        <span className="sell__statValue">{fmtCurrency(totals.earnings)}</span>
                    </div>
                    <div className="sell__stat">
                        <span className="sell__statLabel">Licenses</span>
                        <span className="sell__statValue">{totals.licenses}</span>
                    </div>
                    <div className="sell__stat">
                        <span className="sell__statLabel">Plays</span>
                        <span className="sell__statValue">{totals.plays.toLocaleString()}</span>
                    </div>
                    <div className="sell__stat hide-sm">
                        <span className="sell__statLabel">Likes</span>
                        <span className="sell__statValue">{totals.likes}</span>
                    </div>
                </div>

                <div className="sell__toolbarRight">
                    <div className="sell__segmented">
                        <button
                            className={`sell__segBtn ${status === "all" ? "is-active" : ""}`}
                            onClick={() => setStatus("all")}
                        >
                            <IoFilter /> All
                        </button>
                        <button
                            className={`sell__segBtn ${status === "published" ? "is-active" : ""}`}
                            onClick={() => setStatus("published")}
                        >
                            Published
                        </button>
                        <button
                            className={`sell__segBtn ${status === "draft" ? "is-active" : ""}`}
                            onClick={() => setStatus("draft")}
                        >
                            Drafts
                        </button>
                    </div>

                    <div className="sell__sort">
                        <IoSwapVertical className="sell__sortIcon" aria-hidden />
                        <select
                            value={sort}
                            onChange={(e) => setSort(e.target.value)}
                            aria-label="Sort"
                        >
                            <option value="updated">Recently updated</option>
                            <option value="sales">Top sales</option>
                            <option value="plays">Most plays</option>
                            <option value="price">Highest price</option>
                            <option value="earnings">Highest earnings</option>{/* NEW */}
                        </select>
                    </div>
                </div>
            </div>

            {/* Desktop table */}
            <div className="sell__table" role="table" aria-label="Your uploaded beats & packs">
                <div className="sell__tableHead" role="rowgroup">
                    <div className="sell__row sell__row--head" role="row">
                        <div className="sell__col sell__col--song" role="columnheader">Item</div>
                        <div className="sell__col" role="columnheader">Genre</div>
                        <div className="sell__col" role="columnheader">Price</div>
                        <div className="sell__col" role="columnheader">Plays</div>
                        <div className="sell__col" role="columnheader">Likes</div>
                        <div className="sell__col" role="columnheader">Licenses</div>
                        <div className="sell__col" role="columnheader">Earnings</div>
                        <div className="sell__col" role="columnheader">Updated</div>
                        <div className="sell__col sell__col--actions" role="columnheader">Actions</div>
                    </div>
                </div>

                <div className="sell__tableBody" role="rowgroup">
                    {list.map((b) => (
                        <div className="sell__row" role="row" key={b.id}>
                            <div className="sell__col sell__col--song" role="cell">
                                <img className="sell__cover" src={b.cover || "/placeholder-cover.png"} alt="" />
                                <div className="sell__songMeta">
                                    <div className="sell__titleCell">
                                        {b.title || "(untitled)"}
                                        {b.kind && (
                                            <span className={`sell__badge sell__badge--${String(b.kind).toLowerCase()}`}>
                  {b.kind}
                </span>
                                        )}
                                    </div>
                                    {b.status && <div className={`sell__status sell__status--${b.status}`}>{b.status}</div>}
                                </div>
                            </div>

                            <div className="sell__col" role="cell">{b.genre || "—"}</div>
                            <div className="sell__col" role="cell">{fmtCurrency(Number(b.price || 0))}</div>
                            <div className="sell__col" role="cell">{Number(b.plays || 0).toLocaleString()}</div>
                            <div className="sell__col" role="cell">{Number(b.likes || 0)}</div>
                            <div className="sell__col" role="cell">{Number(b.sales || 0)}</div>
                            <div className="sell__col" role="cell">{fmtCurrency(Number((b.price || 0) * (b.sales || 0)))}</div>
                            <div className="sell__col" role="cell">{fmtDate(b.updatedAt || Date.now())}</div>

                            <div className="sell__col sell__col--actions" role="cell">
                                <button className="sell__iconBtn" title="Open"  onClick={() => onOpen(b)}><IoOpenOutline /></button>
                                <button className="sell__iconBtn" title="Edit"  onClick={() => onEdit(b)}><IoPencil /></button>
                                <button className="sell__iconBtn" title="Share" onClick={() => onShare(b)}><IoShareOutline /></button>
                                <button className="sell__iconBtn sell__iconBtn--danger" title="Delete" onClick={() => onDelete(b)}><IoTrashOutline /></button>
                            </div>
                        </div>
                    ))}

                    {list.length === 0 && (
                        <div className="sell__empty">Loading...</div>
                    )}
                </div>
            </div>

            {/* Mobile cards */}
            <ul className="sell__cards">
                {list.map((b) => (
                    <li className="sell__card" key={`m-${b.id}`}>
                        <img className="sell__cardCover" src={b.cover || "/placeholder-cover.png"} alt="" />
                        <div className="sell__cardBody">
                            <div className="sell__cardTop">
                                <div className="sell__cardTitle">
                                    {b.title || "(untitled)"}
                                    {b.kind && (
                                        <span className={`sell__badge sell__badge--${String(b.kind).toLowerCase()}`}>
                {b.kind}
              </span>
                                    )}
                                </div>
                                {b.status && <span className={`sell__status sell__status--${b.status}`}>{b.status}</span>}
                            </div>

                            <div className="sell__cardSub">
                                {(b.genre || "—")}
                                {b.kind === "Beat" && b.bpm ? ` • ${b.bpm} BPM` : ""}{/* show BPM only for Beats */}
                                {b.key ? ` • ${b.key}` : ""}
                            </div>

                            <div className="sell__cardMetrics">
                                <span>{fmtCurrency(Number(b.price || 0))}</span>
                                <span>{Number(b.plays || 0).toLocaleString()} plays</span>
                                <span>{Number(b.sales || 0)} licenses</span>
                                <span>{fmtCurrency(Number((b.price || 0) * (b.sales || 0)))}</span>
                            </div>

                            <div className="sell__cardActions">
                                <button
                                    className="sell__btn sell__btn--ghost"
                                    onClick={() => onOpen(b)}
                                >
                                    <IoOpenOutline /> View
                                </button>
                                <div className="sell__cardIcons">
                                    <button className="sell__iconBtn" onClick={() => onEdit(b)}   title="Edit"><IoPencil /></button>
                                    <button className="sell__iconBtn" onClick={() => onShare(b)}  title="Share"><IoShareOutline /></button>
                                    <button className="sell__iconBtn sell__iconBtn--danger" onClick={() => onDelete(b)} title="Delete"><IoTrashOutline /></button>
                                </div>
                            </div>
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

            <UploadModal
                isOpen={open}
                onClose={() => setOpen(false)}
                onSubmitBeat={async (payload) => {
                    try {
                        const { data } = await api.post("/me/beats", payload); // JSON or FormData (your choice)
                        const mapped = {
                            id: data.id,
                            kind: "Beat",
                            title: data.title ?? "(untitled)",
                            cover: toFileUrl(data.coverUrl || data.albumCoverUrl || data.coverImagePath),
                            bpm: data.bpm ?? 0,
                            key: data.keySignature || data.key || "",
                            genre: data.genre || "",
                            price: Number(data.price ?? 0),
                            plays: Number(data.plays ?? 0),
                            likes: Number(data.likes ?? 0),
                            sales: Number(data.sales ?? 0),
                            status: data.status || "published",
                            updatedAt: data.updatedAt || data.modifiedAt || data.createdAt || Date.now(),
                        };
                        setItems((prev) => [mapped, ...prev]);
                        setOpen(false);
                    } catch (e) {
                        console.error(e);
                        toast.error("Beat upload failed.");
                    }
                }}
                onSubmitPack={async (payload) => {
                    try {
                        const { data } = await api.post("/me/packs", payload);
                        const mapped = {
                            id: data.id,
                            kind: "Pack",
                            title: data.title ?? "(untitled)",
                            cover: toFileUrl(data.coverUrl || data.albumCoverUrl || data.coverImagePath),
                            // packs usually don't have bpm/key; keep if your API provides them:
                            bpm: data.bpm ?? 0,
                            key: data.keySignature || data.key || "",
                            genre: data.genre || "",
                            price: Number(data.price ?? 0),
                            plays: Number(data.plays ?? 0),
                            likes: Number(data.likes ?? 0),
                            sales: Number(data.sales ?? 0),
                            status: data.status || "published",
                            updatedAt: data.updatedAt || data.modifiedAt || data.createdAt || Date.now(),
                        };
                        setItems((prev) => [mapped, ...prev]);
                        setOpen(false);
                    } catch (e) {
                        console.error(e);
                        toast.error("Pack upload failed.");
                    }
                }}
            />
            <ShareModal
                open={shareOpen}
                track={shareData}
                onClose={() => setShareOpen(false)}
            />
            <EditItemModal
                open={editOpen}
                item={editItem}
                onClose={() => setEditOpen(false)}
                onSaved={(serverData, payload) => {
                    // Optimistically merge local state with what we sent + what server returned
                    setItems((prev) =>
                        prev.map((it) => {
                            if (it.id !== editItem.id || it.kind !== editItem.kind) return it;
                            // prefer server fields if they exist; fall back to payload
                            return {
                                ...it,
                                title: serverData?.title ?? payload.title ?? it.title,
                                genre: serverData?.genre ?? payload.genre ?? it.genre,
                                bpm: serverData?.bpm ?? payload.bpm ?? it.bpm,
                                key: serverData?.key ?? payload.key ?? it.key,
                                tags: serverData?.tags ?? payload.tags ?? it.tags,
                                price: Number(serverData?.price ?? payload.price ?? it.price),
                                status: serverData?.status ?? payload.status ?? it.status,
                                updatedAt: Date.now(),
                            };
                        })
                    );
                }}
            />
            <ConfirmDeleteModal
                open={deleteModalOpen}
                item={itemToDelete}
                onClose={() => setDeleteModalOpen(false)}
                onConfirm={handleConfirmDelete}
            />
        </main>

    );
}