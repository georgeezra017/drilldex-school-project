import { useEffect, useMemo, useState } from "react";
import {
    IoAddOutline, IoSwapVertical, IoFilter, IoPencil, IoShareOutline,
    IoTrashOutline, IoOpenOutline, IoAlbumsOutline
} from "react-icons/io5";
import "./SellKits.css";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../state/auth.jsx";
import AuthModal from "../components/AuthModal";
import UploadKitModal from "../components/UploadKitModal.jsx";
import toast from "react-hot-toast";
import ShareModal from "../components/ShareModal";
import EditItemModal from "../components/EditItemModal";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";


import api, { uploadKitFromState } from "../lib/api";

/* ---------------- helpers ---------------- */

const CDN_BASE = import.meta.env.VITE_S3_PUBLIC_BASE?.replace(/\/+$/, "") || "";

/** Turn DB path/keys into a usable URL (S3 first, fall back to /uploads). */
const toFileUrl = (p) => {
    if (!p) return "";
    if (/^https?:\/\//i.test(p)) return p;                 // already absolute
    if (p.startsWith("/uploads/")) return p;               // served by backend static
    const clean = p.replace(/^\/+/, "");
    return CDN_BASE ? `${CDN_BASE}/${clean}` : `/uploads/${clean}`;
};

const fmtDate = (d) =>
    d
        ? new Date(d).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" })
        : "—";

const fmtCurrency = (n) =>
    Number(n || 0).toLocaleString(undefined, { style: "currency", currency: "USD", maximumFractionDigits: 2 });

/** Map API kit -> UI shape we need */
const mapKit = (x) => ({
    id: x?.id,
    slug: x?.slug,
    title: x?.name || x?.title || "(untitled)",
    cover: toFileUrl(x?.coverUrl || x?.coverImagePath || x?.albumCoverUrl),
    type: x?.type || x?.kind || "Kit",
    contents: {
        samples: Number(x?.samplesCount ?? x?.sampleCount ?? 0),
        presets: Number(x?.presetsCount ?? x?.presetCount ?? 0),
        loops:   Number(x?.loopsCount   ?? x?.loopCount   ?? 0),
    },
    price: Number(x?.price ?? x?.minPrice ?? 0),
    downloads: Number(x?.downloads ?? x?.downloadCount ?? 0),
    licenses: Number(x?.sales ?? x?.licenses ?? 0),
    earnings: Number(x?.earnings ?? 0),
    status: x?.status ?? (x?.approved ? "published" : x?.rejected ? "rejected" : "draft"),
    updatedAt: x?.updatedAt || x?.modifiedAt || x?.createdAt || null,
    shareUrl: x?.publicUrl || (x?.id ? `${window.location.origin}/kit/${x.id}` : undefined),
    uploader: x?.uploader || x?.user?.displayName || x?.artistName || "",
});

/* ---------------- component ---------------- */

export default function SellKitsPage() {
    const [q, setQ] = useState("");
    const [status, setStatus] = useState("all");
    const [sort, setSort] = useState("updated");
    const [deleteModalOpen, setDeleteModalOpen] = useState(false);
    const [itemToDelete, setItemToDelete] = useState(null);
    const [openKit, setOpenKit] = useState(false);
    const [kits, setKits] = useState([]);
    const [loading, setLoading] = useState(true);
    const [err, setErr] = useState("");
    const [editModalOpen, setEditModalOpen] = useState(false);
    const [shareModalOpen, setShareModalOpen] = useState(false);
    const [selectedKit, setSelectedKit] = useState(null);
    const { token } = useAuth();
    const navigate = useNavigate();
    const [forceAuthOpen, setForceAuthOpen] = useState(false);
    const [page, setPage] = useState(0);
    const [totalPages, setTotalPages] = useState(1);


    useEffect(() => {
        if (!token) setForceAuthOpen(true);
    }, [token]);

    // platform fee for display (you can wire from backend later)

    const earningsOf = (k) => Number(k.earnings || 0);

    async function loadKits() {
        if (!token) return;
        setLoading(true);
        setErr("");

        try {
            const res = await api.get("/kits/mine", {
                params: {
                    page,
                    limit: 50,
                    includeStats: true
                }
            });

            const items = Array.isArray(res.data.items)
                ? res.data.items.map(mapKit)
                : [];

            setKits(items);

            // total pages
            if (res.data.totalItems != null) {
                setTotalPages(Math.ceil(res.data.totalItems / 50));
            }

        } catch (e) {
            console.error(e);
            setErr("Failed to load kits.");
        } finally {
            setLoading(false);
        }
    }

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

    useEffect(() => {
        loadKits();
    }, [token, page]);



    const filtered = useMemo(() => {
        let rows = [...kits];

        if (status !== "all") rows = rows.filter((k) => k.status === status);

        const term = q.trim().toLowerCase();
        if (term) {
            rows = rows.filter((k) =>
                [k.title, k.type].some((v) => (v || "").toLowerCase().includes(term))
            );
        }

        rows.sort((a, b) => {
            switch (sort) {
                case "price":      return b.price - a.price;
                case "downloads":  return b.downloads - a.downloads;
                case "licenses":   return b.licenses - a.licenses;
                case "earnings":   return earningsOf(b) - earningsOf(a);
                default:           return new Date(b.updatedAt || 0) - new Date(a.updatedAt || 0);
            }
        });

        return rows;
    }, [q, status, sort, kits]);

    const totals = useMemo(
        () => ({
            earnings: filtered.reduce((s, k) => s + earningsOf(k), 0),
            licenses: filtered.reduce((s, k) => s + (k.licenses || 0), 0),
            downloads: filtered.reduce((s, k) => s + (k.downloads || 0), 0),
            kits: filtered.length,
        }),
        [filtered]
    );

    const onOpen = (kit) => {
        if (!kit?.slug) return;
        window.open(`/track/${kit.slug}`, "_blank");
    };

    const onEdit = async (kit) => {
        try {
            const res = await api.get(`/kits/${kit.id}/contents`);
            const fileUrls = res.data?.fileUrls ?? [];
            const coverUrl = res.data?.coverUrl ?? null;

            const children = fileUrls.map((url, i) => ({
                index: i,
                url,
                title: url.split("/").pop() || `File ${i + 1}`,
                coverUrl: coverUrl,
            }));

            setSelectedKit({ ...kit, children, kind: "Kit" }); // ✅ FIXED: Explicitly set kind
            setEditModalOpen(true);
        } catch (e) {
            toast.error("Failed to load kit contents.");
        }
    };

    const onShare = (kit) => {
        setSelectedKit(kit);
        setShareModalOpen(true);
    };

    const onDelete = (kit) => {
        setItemToDelete(kit);
        setDeleteModalOpen(true);
    };

    const handleKitUpdate = async (kit, updatedFields) => {
        const id = kit?.id;
        if (!id) return;

        try {
            await api.patch(`/me/kits/${id}`, updatedFields);
            toast.success("Kit updated.");
            setEditModalOpen(false);
            await loadKits();
        } catch (e) {
            console.error("Failed to update kit:", e?.response?.data || e);
            toast.error("Failed to update kit.");
        }
    };

    const handleRemoveChildFromKit = async (kit, child) => {
        if (!kit?.id || !child?.id) return;

        try {
            await api.delete(`/kits/${kit.id}/items/${child.id}`);
            toast.success("Item removed.");
            await loadKits();
        } catch (e) {
            toast.error("Failed to remove item.");
            throw e; // so the modal reverts if needed
        }
    };

    const handleConfirmDelete = async (kit) => {
        setDeleteModalOpen(false);
        const prev = kits;
        setKits((rows) => rows.filter((x) => x.id !== kit.id));

        try {
            await api.delete(`/kits/${kit.id}`);
            toast.success("Kit deleted successfully.");
        } catch (e) {
            console.error(e);
            toast.error("Delete failed.");
            setKits(prev); // revert UI
        }
    };

    if (!token) {
        return (
            <main className="kits" style={{ minHeight: "60vh", display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center" }}>
                <p className="text-muted mb-4" style={{ fontSize: "1.1rem", fontWeight: 500 }}>
                    Please log in to access this feature
                </p>
                <AuthModal isOpen={forceAuthOpen} onClose={() => setForceAuthOpen(false)} />
            </main>
        );
    }

    return (
        <main className="kits">
            {/* Header */}
            <header className="kits__hero">
                <div>
                    <h1 className="kits__title">Sell Kits</h1>
                    <p className="kits__sub">Manage your sample packs, presets and loop packs.</p>
                </div>
                <button className="kits__btn kits__btn--primary" onClick={() => setOpenKit(true)}>
                    <IoAddOutline /> Upload new kit
                </button>
            </header>

            {/* Toolbar */}
            <div className="kits__toolbar">
                {/* Search */}
                <div className="search">
                    <input
                        className="search__input"
                        placeholder="Search your kits..."
                        value={q}
                        onChange={(e) => setQ(e.target.value)}
                    />
                    <svg className="search__icon" viewBox="0 0 24 24" fill="none" stroke="currentColor">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="m21 21-4.35-4.35M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z" />
                    </svg>
                </div>

                {/* Center stats */}
                <div className="kits__statsBar">
                    <div className="kits__stat">
                        <span className="kits__statLabel">Total Revenue</span>
                        <span className="kits__statValue">{fmtCurrency(totals.earnings)}</span>
                    </div>
                    <div className="kits__stat">
                        <span className="kits__statLabel">Units Sold</span>
                        <span className="kits__statValue">{totals.licenses.toLocaleString()}</span>
                    </div>
                    <div className="kits__stat">
                        <span className="kits__statLabel">Avg. Sale Price</span>
                        <span className="kits__statValue">
      {fmtCurrency(
          totals.licenses > 0 ? totals.earnings / totals.licenses : 0
      )}
    </span>
                    </div>
                    <div className="kits__stat hide-sm">
                        <span className="kits__statLabel"># of Kits</span>
                        <span className="kits__statValue">{totals.kits}</span>
                    </div>
                </div>

                {/* Right controls */}
                <div className="kits__toolbarRight">
                    <div className="kits__segmented">
                        <button
                            className={`kits__segBtn ${status === "all" ? "is-active" : ""}`}
                            onClick={() => setStatus("all")}
                            title="All"
                        >
                            <IoFilter /> All
                        </button>
                        <button
                            className={`kits__segBtn ${status === "published" ? "is-active" : ""}`}
                            onClick={() => setStatus("published")}
                        >
                            Published
                        </button>
                        <button
                            className={`kits__segBtn ${status === "draft" ? "is-active" : ""}`}
                            onClick={() => setStatus("draft")}
                        >
                            Drafts
                        </button>
                    </div>

                    <div className="kits__sort">
                        <IoSwapVertical className="kits__sortIcon" />
                        <select value={sort} onChange={(e) => setSort(e.target.value)}>
                            <option value="updated">Recently updated</option>
                            <option value="price">Highest price</option>
                            <option value="downloads">Most downloads</option>
                            <option value="licenses">Most licenses</option>
                            <option value="earnings">Highest earnings</option>
                        </select>
                    </div>
                </div>
            </div>

            {/* Loading / error */}
            {loading && <div className="kits__empty">Loading your kits…</div>}
            {!loading && err && <div className="kits__empty">{err}</div>}

            {/* Desktop table */}
            {!loading && !err && (
                <div className="kits__table" role="table" aria-label="Your kits">
                    <div className="kits__tableHead" role="rowgroup">
                        <div className="kits__row kits__row--head" role="row">
                            <div className="kits__col kits__col--kit" role="columnheader">
                                <IoAlbumsOutline /> Kit
                            </div>
                            <div className="kits__col" role="columnheader">Type</div>
                            <div className="kits__col" role="columnheader">Price</div>
                            <div className="kits__col" role="columnheader">Downloads</div>
                            <div className="kits__col" role="columnheader">Earnings</div>
                            <div className="kits__col" role="columnheader">Updated</div>
                            <div className="kits__col kits__col--actions" role="columnheader">Actions</div>
                        </div>
                    </div>

                    <div className="kits__tableBody" role="rowgroup">
                        {filtered.map((k) => (
                            <div className="kits__row" role="row" key={k.id}>
                                <div className="kits__col kits__col--kit" role="cell">
                                    <img className="kits__cover" src={k.cover} alt="" />
                                    <div className="kits__kitMeta">
                                        <div className="kits__titleCell">{k.title}</div>
                                        <div className={`kits__status kits__status--${k.status}`}>{k.status}</div>
                                    </div>
                                </div>

                                <div className="kits__col" role="cell">{k.type}</div>
                                <div className="kits__col" role="cell">{fmtCurrency(k.price)}</div>
                                <div className="kits__col" role="cell">{(k.downloads || 0).toLocaleString()}</div>
                                <div className="kits__col" role="cell">{fmtCurrency(earningsOf(k))}</div>
                                <div className="kits__col" role="cell">{fmtDate(k.updatedAt)}</div>

                                <div className="kits__col kits__col--actions" role="cell">
                                    <button className="kits__iconBtn" title="Open" onClick={() => onOpen(k)}><IoOpenOutline /></button>
                                    <button className="kits__iconBtn" title="Edit" onClick={() => onEdit(k)}><IoPencil /></button>
                                    <button className="kits__iconBtn" title="Share" onClick={() => onShare(k)}><IoShareOutline /></button>
                                    <button className="kits__iconBtn kits__iconBtn--danger" title="Delete" onClick={() => onDelete(k)}><IoTrashOutline /></button>
                                </div>
                            </div>
                        ))}

                        {filtered.length === 0 && (
                            <div className="kits__empty">No kits match your filters.</div>
                        )}
                    </div>
                </div>
            )}

            {/* Mobile cards */}
            {!loading && !err && (
                <ul className="kits__cards">
                    {filtered.map((k) => (
                        <li className="kits__card" key={`m-${k.id}`}>
                            <img className="kits__cardCover" src={k.cover} alt="" />
                            <div className="kits__cardBody">
                                <div className="kits__cardTop">
                                    <div className="kits__cardTitle">{k.title}</div>
                                    <span className={`kits__status kits__status--${k.status}`}>{k.status}</span>
                                </div>
                                <div className="kits__cardSub">
                                    {k.type}
                                </div>
                                <div className="kits__cardMetrics">
                                    <span>{fmtCurrency(k.price)}</span>
                                    <span>{(k.downloads || 0).toLocaleString()} downloads</span>
                                    <span>{fmtCurrency(earningsOf(k))}</span>
                                </div>
                                <div className="kits__cardActions">
                                    <button className="kits__btn kits__btn--ghost" onClick={() => onOpen(k)}>
                                        <IoOpenOutline /> View
                                    </button>
                                    <div className="kits__cardIcons">
                                        <button className="kits__iconBtn" onClick={() => onEdit(k)} title="Edit"><IoPencil /></button>
                                        <button className="kits__iconBtn" onClick={() => onShare(k)} title="Share"><IoShareOutline /></button>
                                        <button className="kits__iconBtn kits__iconBtn--danger" onClick={() => onDelete(k)} title="Delete"><IoTrashOutline /></button>
                                    </div>
                                </div>
                            </div>
                        </li>
                    ))}
                </ul>
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
                    disabled={page >= totalPages - 1}
                    className="tp-pagebtn tp-nextbtn"
                >
                    →
                </button>
            </div>

            <UploadKitModal
                isOpen={openKit}
                onClose={() => setOpenKit(false)}
                onSubmit={async (payload) => {
                    try {
                        await uploadKitFromState(payload);
                        setOpenKit(false);
                        await loadKits(); // refresh list after successful upload
                    } catch (e) {
                        alert(e?.message || "Upload failed.");
                    }
                }}
            />
            <ConfirmDeleteModal
                open={deleteModalOpen}
                item={itemToDelete}
                onClose={() => setDeleteModalOpen(false)}
                onConfirm={handleConfirmDelete}
            />
            {editModalOpen && selectedKit && (
                <EditItemModal
                    open={editModalOpen}
                    item={selectedKit}
                    onClose={() => setEditModalOpen(false)}
                    onSave={handleKitUpdate} // implement if needed
                    onRemoveChild={handleRemoveChildFromKit} // implement if needed
                />
            )}

            {shareModalOpen && selectedKit && (
                <ShareModal
                    open={shareModalOpen}
                    track={selectedKit}
                    onClose={() => setShareModalOpen(false)}
                />
            )}
        </main>
    );
}