// src/pages/SearchPage.jsx
import "./searchpage.css";
import { useEffect, useRef, useState, useMemo } from "react";
import {useSearchParams, Link, useNavigate} from "react-router-dom";
import {
    searchEverything,
    listBeatFeatured, listBeatPopular, listBeatTrending, listBeatNew, listBeatApproved,
    listKitFeatured, listKitPopular, listKitTrending, listKitNew, listKitApprovedWithFlags,
    listPackFeatured, listPackPopular, listPackTrending, listPackNew, listPackApproved,
} from "../lib/api";
import { IoSearch, IoClose, IoAlbums, IoMusicalNotes, IoArchive } from "react-icons/io5";
import {
    IoShareOutline,
    IoOpenOutline,
    IoDownloadOutline,
    IoCartOutline,
    IoListOutline,
} from "react-icons/io5";
import { useCart } from "../state/cart";
import LicenseModal from "../components/LicenseModal"; // adjust path/name if yours differs
import api from "../lib/api.js"
import UploaderLink from "../components/UploaderLink.jsx";



export default function SearchPage() {
    const [params, setParams] = useSearchParams();
    const initialQ = params.get("q") || "";
    const initialKinds = (params.get("k") || "BEAT,PACK,KIT").split(",").filter(Boolean);
    const [showLicenseModal, setShowLicenseModal] = useState(false);
    const [selectedTrack, setSelectedTrack] = useState(null);
    const [openMenuId, setOpenMenuId] = useState(null); // `${kind}:${id}`
    const [q, setQ] = useState(initialQ);
    const [kinds, setKinds] = useState(initialKinds);
    const [loading, setLoading] = useState(false);
    const [results, setResults] = useState([]);
    const [err, setErr] = useState(null);
    const inputRef = useRef(null);
    const { add } = useCart();
    const [page, setPage] = useState(0);
    const pageSize = 50;
    const [totalPages, setTotalPages] = useState(0);
    const [hasMore, setHasMore] = useState(false);

// default totalPages to 1 to show the first page immediately
    const displayTotalPages = totalPages || 1;


    const addKitToCart = (kit) => {
        add({
            id: `kit-${kit.id}`,          // unique cart line id
            type: "kit",                  // lowercase (matches your cart)
            kitId: kit.id,
            title: kit.title,
            artist: kit.artistName || "Unknown",
            img: kit.coverUrl,
            price: Number(kit.price || 0),
            qty: 1,
        });
    };

    const handleBuy = (item) => {
        if (item._kind === "KIT") {
            // direct add to cart for kits (unchanged)
            addKitToCart(item);
            return;
        }

        // Build the shape LicenseModal expects (same as landing)
        setSelectedTrack({
            id: item.id,
            title: item.title,
            artistName: item.artistName || "Unknown",
            albumCoverUrl: item.coverUrl,
            kind: item._kind, // "BEAT" | "PACK"
        });
        setShowLicenseModal(true);
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

    // Close any open menu when clicking outside or pressing Escape
    useEffect(() => {
        const onDown = (e) => {
            const inMenu = e.target.closest?.(".sp-menu");
            const onBtn = e.target.closest?.(".sp-morebtn");
            const inWrap = e.target.closest?.(".sp-morewrap--inline");
            if (!inMenu && !onBtn && !inWrap) setOpenMenuId(null);
        };
        const onEsc = (e) => e.key === "Escape" && setOpenMenuId(null);

        document.addEventListener("pointerdown", onDown, true);
        document.addEventListener("keydown", onEsc);
        return () => {
            document.removeEventListener("pointerdown", onDown, true);
            document.removeEventListener("keydown", onEsc);
        };
    }, []);

    // keep URL in sync
    useEffect(() => {
        const k = kinds.join(",");
        const next = new URLSearchParams();
        if (q) next.set("q", q);
        if (k !== "BEAT,PACK,KIT") next.set("k", k);
        setParams(next, { replace: true });
    }, [q, kinds, setParams]);

    // close menu if filters/search change (safe UX)
    useEffect(() => { setOpenMenuId(null); }, [q, kinds]);

    useEffect(() => {
        setPage(0);
    }, [q, kinds]);

    // debounce search (uses ONLY lib/api.js helpers)
    useEffect(() => {
        let alive = true;
        setLoading(true);
        setErr(null);

        const t = setTimeout(async () => {
            try {
                const kindsParam = (!kinds || kinds.length === 0)
                    ? ["BEAT", "PACK", "KIT"]
                    : kinds.map(k => k.toUpperCase());

                // fetch ALL results (if your API doesn't support offset) or fetch with offset
                const raw = await searchEverything(q.trim(), { kinds: kindsParam });

                if (!alive) return;

                const normalized = normalize(raw); // normalize first

                // Slice for pagination
                const start = page * pageSize;
                const end = start + pageSize;
                setResults(normalized.slice(start, end));

                // Update pagination info
                const totalResults = normalized.length;
                setTotalPages(Math.ceil(totalResults / pageSize));
                setHasMore(end < totalResults);

            } catch (e) {
                if (!alive) return;
                console.warn("searchEverything failed:", e);
                setErr(e);
                setResults([]);
                setTotalPages(0);
                setHasMore(false);
            } finally {
                if (alive) setLoading(false);
            }
        }, 250);

        return () => { alive = false; clearTimeout(t); };
    }, [q, kinds, page]);

    const normalizedTotal = results.length;



    function sortResultsByBadges(results) {
        const indexOf = new Map(results.map((r, i) => [`${r._kind}:${r.id}`, i]));
        return [...results].sort((a, b) => {
            const sa = badgeScoreForItem(a);
            const sb = badgeScoreForItem(b);
            if (sb !== sa) return sb - sa;

            const ta = a.createdAt ? new Date(a.createdAt).getTime() : 0;
            const tb = b.createdAt ? new Date(b.createdAt).getTime() : 0;
            if (tb !== ta) return tb - ta;

            return (indexOf.get(`${a._kind}:${a.id}`) ?? 0) - (indexOf.get(`${b._kind}:${b.id}`) ?? 0);
        });
    }

    const toggleKind = (k) => {
        setKinds(prev => prev.includes(k) ? prev.filter(x => x !== k) : [...prev, k]);
    };

    const sortedResults = useMemo(() => sortResultsByBadges(results), [results]);



    async function fetchJson(url) {
        const res = await fetch(url, { credentials: "include" }); // include cookies if your API uses auth cookies
        if (!res.ok) throw new Error(`GET ${url} -> ${res.status}`);
        return res.json();
    }



    const clear = () => {
        setQ("");
        inputRef.current?.focus();
    };

    return (
        <div className="searchpage">
            <div className="sp-bar">
                <div className="sp-inputwrap">
                    <IoSearch className="sp-icon" />
                    <input
                        ref={inputRef}
                        className="sp-input"
                        placeholder="Search beats, packs, kits…"
                        value={q}
                        onChange={(e) => setQ(e.target.value)}
                        autoFocus
                    />
                    {q && (
                        <button className="sp-clear" onClick={clear} aria-label="Clear search">
                            <IoClose />
                        </button>
                    )}
                </div>

                <div className="sp-filters">
                    <FilterChip
                        label="Beats"
                        icon={<IoMusicalNotes />}
                        active={kinds.includes("BEAT")}
                        onClick={() => toggleKind("BEAT")}
                    />
                    <FilterChip
                        label="Packs"
                        icon={<IoAlbums />}
                        active={kinds.includes("PACK")}
                        onClick={() => toggleKind("PACK")}
                    />
                    <FilterChip
                        label="Kits"
                        icon={<IoArchive />}
                        active={kinds.includes("KIT")}
                        onClick={() => toggleKind("KIT")}
                    />
                </div>
            </div>

            <div className="sp-meta">
                {loading ? "Searching…" : `${normalizedTotal} result${normalizedTotal === 1 ? "" : "s"}`}
                {err && <span className="sp-error"> • something went wrong</span>}
            </div>

            <div className="sp-grid">
                {sortedResults.map(item => (
                    <SearchCard
                        key={`${item._kind}-${item.id}`}
                        item={item}
                        openMenuId={openMenuId}
                        setOpenMenuId={setOpenMenuId}
                        onBuy={handleBuy}

                    />
                ))}
            </div>

            {!loading && normalizedTotal === 0 && (
                <div className="sp-empty">
                    No results. Try different keywords or filters.
                </div>
            )}
            {showLicenseModal && selectedTrack && selectedTrack.kind !== "KIT" && (
                <LicenseModal
                    isOpen
                    track={selectedTrack}
                    onClose={() => { setShowLicenseModal(false); setSelectedTrack(null); }}
                    onSelect={({ license, action }) => {
                        if (!selectedTrack) return;

                        if (selectedTrack.kind === "PACK") {
                            cartAdd({
                                id: `pack-${selectedTrack.id}-${license.id}`,
                                type: "pack",
                                packId: selectedTrack.id,
                                title: selectedTrack.title,
                                artist: selectedTrack.artistName || "Unknown",
                                img: selectedTrack.albumCoverUrl,
                                licenseType: license.type || license.id,
                                licenseId: license.id,
                                licenseName: license.name,
                                price: Number(license.price || 0),
                                qty: 1,
                            });
                        } else {
                            cartAdd({
                                id: `beat-${selectedTrack.id}-${license.id}`,
                                type: "beat",
                                beatId: selectedTrack.id,
                                title: selectedTrack.title,
                                artist: selectedTrack.artistName || "Unknown",
                                img: selectedTrack.albumCoverUrl,
                                licenseType: license.type || license.id,
                                licenseId: license.id,
                                licenseName: license.name,
                                price: Number(license.price || 0),
                                qty: 1,
                            });
                        }

                        // if (action === "buyNow") navigate("/checkout");
                        setShowLicenseModal(false);
                        setSelectedTrack(null);
                    }}
                />
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
                    {renderPageNumbers(page, displayTotalPages, setPage)}
                </div>
                <button
                    onClick={() => setPage(page + 1)}
                    disabled={!hasMore}
                    className="tp-pagebtn tp-nextbtn"
                >
                    →
                </button>
            </div>
        </div>
    );
}

/* ================= helpers ================= */

// ---- badge sort helpers ----
const BADGE_PRIORITY = ["featured", "popular", "trending", "new"];
const BADGE_SCORE = { featured: 4, popular: 3, trending: 2, new: 1 };

function topBadge(labels = []) {
    for (const key of BADGE_PRIORITY) if (labels.includes(key)) return key;
    return null;
}

function badgeText(key) {
    switch (key) {
        case "featured": return "Featured";
        case "popular":  return "Popular";
        case "trending": return "Trending";
        case "new":      return "New";
        default:         return "";
    }
}


function badgeScoreForItem(item) {
    if (!item) return 0;
    if (item.featured) return 4;
    if (item.popular)  return 3;
    if (item.trending) return 2;
    if (item.new)      return 1;
    return 0;
}



// normalize(): set kitType for kits from type OR genre
function normalize(arr) {
    const rows = Array.isArray(arr) ? arr : [];
    return rows.map((x) => {
        const cover   = x.coverUrl ?? x.publicCoverUrl ?? x.albumCoverUrl ?? null;
        const price   = x.price ?? x.displayPrice ?? null;
        const artist  = x.artistName ?? x.ownerName ?? "Unknown";
        const kind    = (x._kind || x.type || "BEAT").toString().toUpperCase();

        const durationSec =
            x.durationSec ??
            x.durationInSeconds ??
            x.total_duration_sec ??
            x.totalDurationSec ??
            null;

        const bpm     = x.bpm ?? x.BPM ?? null;
        const bpmMin  = x.bpmMin ?? null;
        const bpmMax  = x.bpmMax ?? null;

        const location =
            x.city ?? x.region ?? x.location ?? x.ownerLocation ?? x.ownerCity ?? null;

        const genre   = x.genre ?? null;

        // 👇 key line: for KIT, prefer explicit type; fall back to genre (e.g. "Drum Kit")
        const kitType = kind === "KIT" ? (x.type ?? genre ?? null) : null;

        const tracksCount = x.tracksCount ?? x.beatsCount ?? x.trackCount ?? null;

        return {
            id: x.id,
            slug: x.slug,
            title: x.title || "(untitled)",
            artistName: artist,
            artistId: x.artistId ?? x.ownerId ?? null,
            coverUrl: cover || "",
            price,
            createdAt: x.createdAt,
            _kind: kind,
            tags: x.tags ? x.tags.split(",").map(t => t.trim()).filter(Boolean) : [],
            durationSec,
            bpm,
            bpmMin,
            bpmMax,
            location,
            genre,
            kitType,        // for KIT
            tracksCount,
            featured: x.featured || false,
            popular: x.popular || false,
            trending: x.trending || false,
            new: x.new || false,
        };
    });
}



function kindToDetailPath(item) {
    // one route works for all: your TrackPage figures out BEAT/PACK/KIT by slug
    return `/track/${encodeURIComponent(item.slug)}`;
}

function formatDuration(sec) {
    if (!Number.isFinite(sec) || sec < 0) return null;
    const m = Math.floor(sec / 60);
    const s = Math.floor(sec % 60);
    return `${m}:${s.toString().padStart(2, "0")}`;
}

function formatMeta(item) {
    const dur = item.durationSec != null ? formatDuration(item.durationSec) : null;

    if (item._kind === "BEAT") {
        // chicago drill · 145 BPM · 3:31
        const bits = [];
        if (item.genre) bits.push(item.genre);
        if (item.bpm) bits.push(`${item.bpm} BPM`);
        if (dur) bits.push(dur);
        return bits.join(" · ");
    }

    if (item._kind === "KIT") {
        // Drum Kit · 1:08   (fallback to title if no type)
        const label = item.kitType || "Kit";
        return [label, dur].filter(Boolean).join(" · ");
    }

    // PACK — 4 tracks · 13:52
    if (item._kind === "PACK") {
        const bits = [];
        if (Number.isFinite(item.tracksCount)) {
            const n = item.tracksCount;
            bits.push(`${n} track${n === 1 ? "" : "s"}`);
        }
        if (dur) bits.push(dur);
        return bits.join(" · ");
    }

    return dur || ""; // safe fallback
}

function kindBadge(kind) {
    switch (kind) {
        case "PACK": return "Pack";
        case "KIT":  return "Kit";
        default:     return "Beat";
    }
}

function goLabel(kind) {
    switch ((kind || "").toUpperCase()) {
        case "PACK": return "Go to pack";
        case "KIT":  return "Go to kit";
        default:     return "Go to track";
    }
}

function SearchCard({ item, openMenuId, setOpenMenuId, onBuy, badgesByKey }) {
    const menuKey = `${item._kind}:${item.id}`;
    const menuOpen = openMenuId === menuKey;
    const to = kindToDetailPath(item);
    const wrapRef = useRef(null);
    const navigate = useNavigate();
    const buyLabel = item._kind === "KIT" ? "Add to cart" : "Buy License";
    const key = `${item._kind}:${item.id}`;
    const badges = [];
    if (item.featured)    badges.push("featured");
    if (item.popular)     badges.push("popular");
    if (item.trending)    badges.push("trending");
    if (item.new)         badges.push("new");

    const top = topBadge(badges);





    return (
        <div ref={wrapRef} className={`sp-card ${menuOpen ? "is-menu-open" : ""}`}>
            {/* Cover */}
            <Link to={to} title={item.title} className="sp-coverlink">
                <div className="sp-cover">
                    {item.coverUrl ? <img src={item.coverUrl} alt={item.title} /> : <div className="sp-cover--ph" />}
                    <span className={`sp-kind sp-kind--${item._kind.toLowerCase()}`}>{kindBadge(item._kind)}</span>
                </div>
            </Link>

            {/* Body: title row with more menu */}
            <div className="sp-body">
                <div className="sp-row">
                    <Link to={to} className="sp-titlelink" title={item.title}>
                        <div className="sp-title">{item.title}</div>
                    </Link>

                    {top && (
                        <div className="sp-badges">
                            <span className={`sp-badge sp-badge--${top}`}>{badgeText(top)}</span>
                        </div>
                    )}

                    <div className="sp-morewrap sp-morewrap--inline">
                        <button
                            type="button"
                            className="sp-morebtn"
                            onPointerDown={(e) => e.stopPropagation()}
                            onClick={(e) => {
                                e.preventDefault();
                                setOpenMenuId(menuOpen ? null : menuKey);
                            }}
                            aria-label="More options"
                        >
                            <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
                                <circle cx="5" cy="12" r="2" />
                                <circle cx="12" cy="12" r="2" />
                                <circle cx="19" cy="12" r="2" />
                            </svg>
                        </button>

                        {menuOpen && (
                            <div
                                className="sp-menu"
                                onPointerDown={(e) => e.stopPropagation()}
                                onClick={(e) => e.stopPropagation()}
                            >
                                <button className="moremenu__item" onClick={() => { console.log("Share", item); setOpenMenuId(null); }}>
                                    <IoShareOutline size={18} /> <span>Share</span>
                                </button>
                                <button
                                    className="moremenu__item"
                                    onClick={() => {
                                        navigate(kindToDetailPath(item)); // go to /track/:slug
                                        setOpenMenuId(null);
                                    }}
                                >
                                    <IoOpenOutline size={18} /> <span>{goLabel(item._kind)}</span>
                                </button>

                                <button
                                    className="moremenu__item"
                                    onClick={() => { onBuy?.(item); setOpenMenuId(null); }}
                                >
                                    <IoCartOutline size={18} /> <span>{buyLabel}</span>
                                </button>
                                <button className="moremenu__item" onClick={() => { console.log("Add to Playlist", item); setOpenMenuId(null); }}>
                                    <IoListOutline size={18} /> <span>Add to playlist</span>
                                </button>
                            </div>
                        )}
                    </div>
                </div>

                <UploaderLink userId={item.artistId}>
                    <div className="sp-sub">{item.artistName}</div>
                </UploaderLink>

                <div className="sp-meta2">{formatMeta(item)}</div>
            </div>

            {item.tags && item.tags.length > 0 && (
                <div className="sp-tags">
                    <div className="sp-tagrow" role="list">
                        {item.tags.map((tag, i) => (
                            <span key={i} className="sp-tag" role="listitem">{tag}</span>
                        ))}
                    </div>
                </div>
            )}

            {item.price != null && (
                <button
                    type="button"
                    className="sp-pricebtn"
                    onClick={(e) => { e.preventDefault(); onBuy?.(item); }}
                >
                    ${Number(item.price).toFixed(2)}
                </button>
            )}
        </div>
    );
}

function FilterChip({ label, icon, active, onClick }) {
    return (
        <button
            className={`sp-chip ${active ? "is-active" : ""}`}
            onClick={onClick}
            aria-pressed={active ? "true" : "false"}
        >
            {icon}<span>{label}</span>
        </button>
    );
}
