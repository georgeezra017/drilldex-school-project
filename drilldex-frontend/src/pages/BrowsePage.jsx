// src/pages/BrowsePage.jsx
import {useEffect, useMemo, useState, useRef} from "react";
import {useSearchParams, useNavigate} from "react-router-dom";
import {
    IoPlay, IoPause, IoFilter, IoSwapVertical, IoChevronDown,
    IoShareOutline, IoCartOutline, IoDownloadOutline, IoListOutline, IoOpenOutline, IoMusicalNotes, IoPersonOutline
} from "react-icons/io5";
import "./browse.css";
import LicenseModal from "../components/LicenseModal.jsx";
import api from "../lib/api";
import {useCart} from "../state/cart";
import ShareModal from "../components/ShareModal.jsx";
import {derivePriceIfPossible, hydratePrices} from "../lib/pricing";
import toast from "react-hot-toast";
import UploaderLink from "../components/UploaderLink.jsx";
import useIsMobile from "../hooks/useIsMobile.js";




/* ---- helpers ---- */
const GENRES = ["UK Drill", "NY Drill", "Chicago Drill", "Dutch Drill", "French Drill", "Afro Drill"];
const PRODUCT_TYPES = ["All", "Beats", "Kits", "Packs"];

function fmtTime(sec) {
    const m = Math.floor((sec || 0) / 60);
    const s = String(Math.floor((sec || 0) % 60)).padStart(2, "0");
    return `${m}:${s}`;
}


// Build absolute URL for files served by Spring under /uploads/**
const API_ORIGIN = new URL(api.defaults.baseURL).origin;

function toFileUrl(input) {
    if (!input) return "";

    // Normalize slashes & trim
    let s = String(input).replace(/\\/g, "/").trim();

    // 1) If an absolute URL exists anywhere (even after "/uploads/"), return it.
    const abs = s.match(/https?:\/\/[^\s]+/i);
    if (abs) return abs[0];

    // 2) Already a plain absolute URL?
    if (/^https?:\/\//i.test(s)) return s;

    // 3) Keep the last "/uploads/..." segment if present
    const i = s.lastIndexOf("/uploads/");
    let path = i >= 0 ? s.slice(i) : s;

    // 4) If still not starting with /uploads/, accept known subfolders
    if (!path.startsWith("/uploads/")) {
        if (/^(covers|audio|previews|kits)\//i.test(path)) {
            path = `/uploads/${path}`;
        } else {
            return ""; // unknown / not a public file we should expose
        }
    }

    // 5) Collapse duplicate slashes
    path = path.replace(/\/{2,}/g, "/");

    return `${API_ORIGIN}${path}`;
}

;

function annotateTag(items, tag) {
    if (!Array.isArray(items)) return [];
    return items.map(it => ({
        ...it,
        isNew: it.isNew || tag === "New",
        isPopular: it.isPopular || tag === "Popular",
        isTrending: it.isTrending || tag === "Trending",
    }));
}

const TAG_PATHS = {
    Beat: {New: "/beats/new", Popular: "/beats/popular", Trending: "/beats/trending"},
    Kit: {New: "/kits/new", Popular: "/kits/popular", Trending: "/kits/trending"},
    Pack: {New: "/packs/new", Popular: "/packs/popular", Trending: "/packs/trending"},
};

async function fetchByTag(kind, tag) {
    const path = TAG_PATHS[kind]?.[tag];
    if (!path) return [];
    try {
        const {data} = await api.get(path, {params: {limit: 60}});
        return Array.isArray(data) ? data : [];
    } catch {
        return [];
    }
}

function normalizeTags(t) {
    if (Array.isArray(t)) return t;
    if (!t) return [];
    return String(t).split(",").map(s => s.trim()).filter(Boolean);
}


/* ---- page ---- */
export default function BrowsePage() {
    const [searchParams] = useSearchParams();

    const typeParam = (searchParams.get("type") || "").toLowerCase(); // "beats" | "kits" | "packs"

    const initialType =
        typeParam === "beats" ? "Beats" :
            typeParam === "kits" ? "Kits" :
                typeParam === "packs" ? "Packs" : "All";
    const initialFeaturedOnly = ["1", "true", "yes"].includes(
        (searchParams.get("featured") || "").toLowerCase()
    );

    const [productType, setProductType] = useState(initialType);
    const [featuredOnly, setFeaturedOnly] = useState(initialFeaturedOnly);
    const [beats, setBeats] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState("");
    const {add, clear} = useCart();
    const [query, setQuery] = useState("");
    const [selectedGenre, setSelectedGenre] = useState("");
    const [bpmRange, setBpmRange] = useState([120, 160]);
    const [priceRange, setPriceRange] = useState([0, 500]);
    const [sort, setSort] = useState("relevance");
    const [showMobileFilters, setShowMobileFilters] = useState(false);
    const [openMenuFor, setOpenMenuFor] = useState(null);
    const [shareOpen, setShareOpen] = useState(false);
    const [shareTrack, setShareTrack] = useState(null);
    const [showLicenseModal, setShowLicenseModal] = useState(false);
    const [selectedTrack, setSelectedTrack] = useState(null);
    const navigate = useNavigate();
    const [nowKey, setNowKey] = useState(null);
    const [isPlaying, setIsPlaying] = useState(false);
    const [intent, setIntent] = useState(null);
    const BROWSE_TAGS = ["All", "Featured", "Trending", "Popular", "New"];
    const BEAT_KEY = (id) => `beat:${id}`;
    const PACK_KEY = (id) => `pack:${id}`;
    const KIT_KEY = (id) => `kit:${id}`;
    const [page, setPage] = useState(0);
    const [totalPages, setTotalPages] = useState(1);
    const [hasMore, setHasMore] = useState(true);
    const previewCache = useRef(new Map());
    const previewInflight = useRef(new Map());
    const lastSourceType = useRef(null);
    const playedOnce = useRef(new Set());
    const DEBUG = true;
    const dlog = (...args) => {
        if (DEBUG) console.log("[Browse]", ...args);
    };
    const [browseTag, setBrowseTag] = useState(
        initialFeaturedOnly ? "Featured" : "All"
    );

    function toQueueBeat(b) {
        return {
            id: b.id,
            slug: b.slug,
            title: b.title,
            artistName: b.artist || "Unknown",
            albumCoverUrl: b.img || "/placeholder-cover.png",
            genre: b.genre || "",
            bpm: Number(b.bpm || 0),
            durationInSeconds: Number(b.duration || 0),
            // price/tags are ignored by the player but harmless to include
            price: Number(b.price || 0),
            tags: Array.isArray(b.tags) ? b.tags : [],
        };
    }

    // Normalize one row to the playlist item shape
    function asPlaylistItem(row = {}) {
        return {
            id: row.id,
            title: row.title,
            artistName: row.artist || row.artistName || "Unknown",
            albumCoverUrl: row.img || row.coverUrl || row.albumCoverUrl || "/placeholder-cover.png",
            audioUrl: row.audioUrl || "",             // ok if empty; AudioBar can fetch on play
            durationInSeconds: Number(row.duration || row.durationInSeconds || 0),
        };
    }

    // Add to playlist: Beats directly, Packs via preview playlist, Kits = ignored
    async function addToPlaylist(item) {
        const kind = item.kind; // "Beat" | "Pack" | "Kit"

        if (kind === "Beat") {
            document.dispatchEvent(new CustomEvent("playlist:add", {detail: asPlaylistItem(item)}));
            toast.success("Added to playlist");
            return;
        }

        if (kind === "Pack") {
            const t = toast.loading("Adding pack previews…");
            try {
                const list = await fetchPackPreviewPlaylist(item.id);
                if (!list.length) {
                    toast.error("No previews found for this pack.", {id: t});
                    return;
                }
                document.dispatchEvent(new CustomEvent("playlist:add", {detail: {items: list}}));
                toast.success("Pack added to playlist", {id: t});
            } catch {
                toast.error("Couldn't add pack", {id: t});
            }
            return;
        }

        // Kits aren't musical playlists
        if (kind === "Kit") {
            toast("Kits can’t be added to the playlist.", {icon: "ℹ️"});
        }
    }

    useEffect(() => {
        window.scrollTo({top: 0, left: 0, behavior: "auto"});
    }, [location.pathname]);

    // put near other refs
    const intentRef = useRef(null);
    useEffect(() => {
        intentRef.current = intent;
    }, [intent]);

    const lastSnapshotRef = useRef({sourceKey: null, playing: false, trackId: null, listLen: 0});
    const hasLiveQueue = (key) => {
        const s = lastSnapshotRef.current || {};
        return s.sourceKey === key && s.trackId != null && s.listLen > 0;
    };

    useEffect(() => {
        const onState = (e) => {
            const d = e.detail || {};
            const listLen = Array.isArray(d.list) ? d.list.length : 0;
            const emptySnapshot = listLen === 0 && d.trackId == null;

            dlog("audio:state", {
                sourceKey: d.sourceKey, playing: d.playing, trackId: d.trackId, listLen
            });

            // Ignore totally empty / pre-init noise (e.g. sourceKey === "")
            if (emptySnapshot && !d.sourceKey) {
                return;
            }

            // If AudioBar cleared the queue for the current source, show "paused"
            // but keep the highlighted card; DO NOT overwrite last non-empty snapshot.
            if (emptySnapshot) {
                // learn source type if present
                if (typeof d.sourceKey === "string") {
                    const t = String(d.sourceKey).split(":")[0];
                    if (t) lastSourceType.current = t;
                }
                setIsPlaying(false);
                setIntent((prev) =>
                    prev && (prev.key === (d.sourceKey ?? nowKey)) && prev.playing === false ? null : prev
                );
                return;
            }

            // --- Non-empty snapshot: commit everything and remember it ---
            if (typeof d.sourceKey === "string") setNowKey(d.sourceKey);
            const t = String(d.sourceKey || "").split(":")[0];
            if (t) lastSourceType.current = t;
            if (typeof d.playing === "boolean") setIsPlaying(d.playing);

            // Clear an intent that has been satisfied by the player
            if (typeof d.sourceKey === "string" && typeof d.playing === "boolean") {
                setIntent((prev) =>
                    prev && prev.key === d.sourceKey && prev.playing === d.playing ? null : prev
                );
            }

            // Remember last non-empty snapshot for hasLiveQueue()
            lastSnapshotRef.current = {
                sourceKey: d.sourceKey || null,
                playing: !!d.playing,
                trackId: d.trackId ?? null,
                listLen
            };
        };

        document.addEventListener("audio:state", onState);
        document.dispatchEvent(new CustomEvent("audio:get-state"));
        return () => document.removeEventListener("audio:state", onState);
    }, []); // ← important: no deps; don't re-register every render


    function mapItem(x, kind) {
        const isBeatOrPack = kind === "Beat" || kind === "Pack";
        const inline = isBeatOrPack ? derivePriceIfPossible(x) : null;

        return {
            id: x.id,
            slug: x.slug,
            title: x.title ?? "(untitled)",
            artist: x.artistName ?? x.ownerName ?? x.creatorName ?? x.uploader ?? x.artist ?? "Unknown",
            ownerId: x.ownerId ?? x.userId ?? x.creatorId ?? null,
            bpm: Number(x.bpm ?? 0),
            genre: x.genre ?? "",
            price: isBeatOrPack ? (inline ?? null) : Number(x.price ?? 0),
            img: toFileUrl(x.coverUrl || x.albumCoverUrl || x.coverImagePath) || "",
            duration: Number(x.totalDurationSec || x.durationInSeconds || 0),
            audioUrl: toFileUrl(x.previewUrl || x.previewAudioPath || x.previewUrlMp3 || ""),
            freeDownloadUrl: null,
            tags: Array.isArray(x.tags) ? x.tags : normalizeTags(x.tags),
            kind, // "Beat" | "Pack" | "Kit"
            featured: Boolean(x.featured ?? x.isFeatured ?? x.is_featured),
            createdAt: Number(x.createdAt ?? 0),
            beatsCount: Number(x.beatsCount ?? 0),
            isNew: !!x.isNew,
            isPopular: !!x.isPopular,
            isTrending: !!x.isTrending,

            likeCount: Number(x.likeCount ?? x.likes ?? 0),
            playCount: Number(x.playCount ?? 0),
        };
    }


    function incrementKitPlay(kitId) {
        if (!kitId) return;
        if (playedOnce.current.has(`kit:${kitId}`)) return;
        playedOnce.current.add(`kit:${kitId}`);

    }

    useEffect(() => {
        if (browseTag === "All" && featuredOnly) setFeaturedOnly(false);
    }, [browseTag, featuredOnly]);

    useEffect(() => {
        let alive = true;

        if (browseTag === "All" && featuredOnly) setFeaturedOnly(false);

        (async () => {
            setLoading(true);
            setError("");

            const LIMIT = 60;
            const typesToFetch = productType === "All" ? ["Beats", "Kits", "Packs"] : [productType];

            try {
                if (browseTag === "All") {
                    // --- Full All view with approved + featured/popular/trending/new ---
                    const [bResp, kResp, pResp] = await Promise.all([
                        api.get("/beats/approved", {
                            params: {
                                limit: 50,
                                page: 0
                            }
                        }).then(r => r.data).catch(() => ({items: []})),
                        api.get("/kits/approved", {
                            params: {
                                limit: 50,
                                page: 0
                            }
                        }).then(r => r.data).catch(() => ({items: []})),
                        api.get("/packs/approved", {
                            params: {
                                limit: 50,
                                page: 0
                            }
                        }).then(r => r.data).catch(() => ({items: []})),
                    ]);

                    const [bFeat, bPop, bTrend, bNew,
                        kFeat, kPop, kTrend, kNew,
                        pFeat, pPop, pTrend, pNew] = await Promise.all([
                        api.get("/beats/featured", { params: { limit: 25 } }).then(r => r.data?.items ?? []),
                        api.get("/beats/popular", { params: { limit: 25 } }).then(r => r.data?.items ?? []),
                        api.get("/beats/trending", { params: { limit: 25 } }).then(r => r.data?.items ?? []),
                        api.get("/beats/new", { params: { limit: 25 } }).then(r => r.data?.items ?? []),

                        api.get("/kits/featured", { params: { limit: 25 } }).then(r => r.data?.items ?? []),
                        api.get("/kits/popular", { params: { limit: 25 } }).then(r => r.data?.items ?? []),
                        api.get("/kits/trending", { params: { limit: 25 } }).then(r => r.data?.items ?? []),
                        api.get("/kits/new", { params: { limit: 25 } }).then(r => r.data?.items ?? []),

                        api.get("/packs/featured", { params: { limit: 25 } }).then(r => r.data?.items ?? []),
                        api.get("/packs/popular", { params: { limit: 25 } }).then(r => r.data?.items ?? []),
                        api.get("/packs/trending", { params: { limit: 25 } }).then(r => r.data?.items ?? []),
                        api.get("/packs/new", { params: { limit: 25 } }).then(r => r.data?.items ?? []),
                    ]);

                    const toSet = arr => new Set((arr || []).map(x => x.id));

                    const B = {
                        featured: toSet(bFeat),
                        popular: toSet(bPop),
                        trending: toSet(bTrend),
                        fresh: toSet(bNew)
                    };
                    const K = {
                        featured: toSet(kFeat),
                        popular: toSet(kPop),
                        trending: toSet(kTrend),
                        fresh: toSet(kNew)
                    };
                    const P = {
                        featured: toSet(pFeat),
                        popular: toSet(pPop),
                        trending: toSet(pTrend),
                        fresh: toSet(pNew)
                    };

                    const beatItems = (bResp?.items ?? []).map(x => {
                        const row = mapItem(x, "Beat");
                        return {
                            ...row,
                            featured: B.featured.has(row.id) || row.featured,
                            isPopular: B.popular.has(row.id) || row.isPopular,
                            isTrending: B.trending.has(row.id) || row.isTrending,
                            isNew: B.fresh.has(row.id) || row.isNew,
                        };
                    });

                    const kitItems = (kResp?.items ?? []).map(x => {
                        const row = mapItem(x, "Kit");
                        return {
                            ...row,
                            featured: K.featured.has(row.id) || row.featured,
                            isPopular: K.popular.has(row.id) || row.isPopular,
                            isTrending: K.trending.has(row.id) || row.isTrending,
                            isNew: K.fresh.has(row.id) || row.isNew,
                        };
                    });

                    const packItems = (pResp?.items ?? []).map(x => {
                        const row = mapItem(x, "Pack");
                        return {
                            ...row,
                            featured: P.featured.has(row.id) || row.featured,
                            isPopular: P.popular.has(row.id) || row.isPopular,
                            isTrending: P.trending.has(row.id) || row.isTrending,
                            isNew: P.fresh.has(row.id) || row.isNew,
                        };
                    });

                    if (alive) {
                        const merged = [...beatItems, ...kitItems, ...packItems];
                        setBeats(merged);
                        if (!merged.length) setError("No items yet.");
                        setLoading(false);
                    }
                    return;
                }

                // --- Specific tag / filtered view ---
                const results = await Promise.all(
                    typesToFetch.map(async t => {
                        let path = "";
                        switch (browseTag) {
                            case "Trending":
                                path = `/${t.toLowerCase()}/trending`;
                                break;
                            case "Popular":
                                path = `/${t.toLowerCase()}/popular`;
                                break;
                            case "New":
                                path = `/${t.toLowerCase()}/new`;
                                break;
                            case "Featured":
                                path = `/${t.toLowerCase()}/featured`;
                                break;
                            default:
                                path = `/${t.toLowerCase()}/approved`;
                                break;
                        }
                        const {data} = await api.get(path, {params: {limit: LIMIT, page}});
                        const items = Array.isArray(data?.items) ? data.items : [];
                        // annotate with the current tag
                        return items.map(x => ({
                            ...mapItem(x, t.slice(0, -1)),
                            isNew: browseTag === "New" || x.isNew,
                            isPopular: browseTag === "Popular" || x.isPopular,
                            isTrending: browseTag === "Trending" || x.isTrending,
                            featured: browseTag === "Featured" || x.featured,
                        }));
                    })
                );

                if (!alive) return;

// Flatten all types
                const combinedItems = results.flat();

                setBeats(combinedItems);

                await (async () => {
                    const packsToFix = combinedItems.filter(
                        (x) => x.kind === "Pack" && (!x.duration || !x.beatsCount)
                    );

                    if (packsToFix.length) {
                        const hydrated = await Promise.all(
                            packsToFix.map(async (p) => {
                                try {
                                    const { data } = await api.get(`/packs/${p.id}`);
                                    return {
                                        id: p.id,
                                        duration: data.totalDurationSec || 0,
                                        beatsCount: Array.isArray(data.beats) ? data.beats.length : 0,
                                    };
                                } catch {
                                    return null;
                                }
                            })
                        );

                        setBeats((prev) =>
                            prev.map((row) => {
                                const fixed = hydrated.find((h) => h && h.id === row.id);
                                return fixed && row.kind === "Pack"
                                    ? { ...row, ...fixed }
                                    : row;
                            })
                        );
                    }
                })();

                const totalItems = results.reduce((sum, r) => sum + (r.totalItems ?? 0), 0);
                const totalPages = totalItems > 0 ? Math.ceil(totalItems / LIMIT) : 1;
                setTotalPages(totalPages);
                setHasMore(page < totalPages - 1);

                if (!combinedItems.length) setError("No items yet.");

            } catch (e) {
                console.error(e);
                if (alive) setError("Failed to load items."); // real error only
            } finally {
                if (alive) setLoading(false);
            }
        })();

        return () => {
            alive = false;
        };
    }, [browseTag, page, productType, featuredOnly]);

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

    const onBuy = (item) => {
        if (item.kind === "Kit") {
            addKitToCart(item);
            return;
        }
        // Beats & Packs still go through LicenseModal
        setSelectedTrack(item);
        setShowLicenseModal(true);
    };

    // --- add helper: build a preview playlist for a pack ---
    async function fetchPackPreviewPlaylist(packId) {
        // Try a pack-level playlist endpoint first (if your backend exposes it)
        try {
            const {data} = await api.get(`/packs/${packId}/preview-playlist`);
            // expected shape: [{ id, title, artistName, coverUrl, previewUrl }]
            if (Array.isArray(data) && data.length) {
                return data.map(t => ({
                    id: t.id,
                    title: t.title,
                    artistName: t.artistName || t.artist || "Unknown",
                    albumCoverUrl: toFileUrl(t.coverUrl || t.albumCoverUrl || t.coverImagePath) || "/placeholder-cover.png",
                    audioUrl: toFileUrl(t.previewUrl || t.previewAudioPath || ""),
                    packId: packId
                })).filter(x => !!x.audioUrl);

            }
        } catch (_) { /* fall back below */
        }

        // Fallback: fetch pack detail, then presign each beat's preview
        try {
            const {data: detail} = await api.get(`/packs/${packId}`); // should include beats[]
            const beats = Array.isArray(detail?.beats) ? detail.beats : [];
            const list = [];
            for (const b of beats) {
                // get short-lived preview for each beat
                let url = "";
                try {
                    const {data} = await api.get(`/beats/${b.id}/preview-url`);
                    url = data?.url || "";
                } catch (_) {
                }
                if (!url) continue;

                list.push({
                    id: b.id,
                    title: b.title || "(untitled)",
                    artistName: b.artistName || b.artist || "Unknown",
                    albumCoverUrl: toFileUrl(b.coverUrl || b.albumCoverUrl || b.coverImagePath) || "/placeholder-cover.png",
                    audioUrl: url,
                });
            }
            return list;
        } catch (_) {
            return [];
        }
    }


    async function fetchKitPreviewPlaylist(kitId) {
        try {
            const {data} = await api.get(`/kits/${kitId}/preview-playlist`);
            // data: [{ id, title, artistName, coverUrl, previewUrl }]
            return (data || []).map(t => ({
                id: t.id,
                title: t.title,
                artistName: t.artistName || "Unknown",
                albumCoverUrl: toFileUrl(t.coverUrl) || "/placeholder-cover.png",
                audioUrl: toFileUrl(t.previewUrl),
            })).filter(x => !!x.audioUrl);
        } catch {
            return [];
        }
    }

    // Close “more” menu on outside click/esc
    useEffect(() => {
        const onDoc = (e) => {
            if (!e.target.closest(".moremenu__panel") && !e.target.closest(".more-actions-btn")) {
                setOpenMenuFor(null);
            }
        };
        const onEsc = (e) => e.key === "Escape" && setOpenMenuFor(null);
        document.addEventListener("mousedown", onDoc);
        document.addEventListener("keydown", onEsc);
        return () => {
            document.removeEventListener("mousedown", onDoc);
            document.removeEventListener("keydown", onEsc);
        };
    }, []);

    async function getBeatPreviewUrl(beatId, {bust = false} = {}) {
        if (!bust && previewCache.current.has(beatId)) {
            return previewCache.current.get(beatId);
        }
        if (!bust && previewInflight.current.has(beatId)) {
            return previewInflight.current.get(beatId);
        }

        const p = (async () => {
            try {
                const {data} = await api.get(`/beats/${beatId}/preview-url`);
                const url = data?.url || "";
                if (url) previewCache.current.set(beatId, url);
                return url;
            } catch {
                return "";
            } finally {
                previewInflight.current.delete(beatId);
            }
        })();

        previewInflight.current.set(beatId, p);
        return p;
    }

// Build playlist from the current filtered list; bust only the clicked beat
    async function buildBeatPlaylist(filteredBeats, clickedBeatId, {bustClickedOnly = true, bustAll = false} = {}) {
        const beatsOnly = filteredBeats.filter(x => x.kind === "Beat");
        if (!beatsOnly.length) return [];

        const urls = await Promise.all(
            beatsOnly.map(b => {
                const bust =
                    bustAll ||
                    (bustClickedOnly && b.id === clickedBeatId);
                return getBeatPreviewUrl(b.id, {bust});
            })
        );

        const list = [];
        for (let i = 0; i < beatsOnly.length; i++) {
            const b = beatsOnly[i];
            const url = urls[i];
            if (!url) continue;
            list.push({
                id: b.id,
                title: b.title,
                artistName: b.artist || "Unknown",
                albumCoverUrl: b.img || "/placeholder-cover.png",
                audioUrl: url,
                durationInSeconds: Number(b.duration || 0),
            });
        }
        return list;
    }

    // function incrementBeatPlayAlways(id) {
    //     if (!id) return;
    //     api.post(`/beats/${id}/play`).catch(() => {
    //     });
    // }


// --- modify existing onPlay ---
    async function onPlay(item) {
        try {
            const kind = item.kind; // "Beat" | "Pack" | "Kit"
            const key =
                kind === "Pack" ? PACK_KEY(item.id) :
                    kind === "Kit" ? KIT_KEY(item.id) :
                        BEAT_KEY(item.id);

            const sameKey = nowKey === key;
            const live = hasLiveQueue(key);

            // --- Same card clicked again ---
            if (sameKey) {
                if (live) {
                    // Normal toggle if queue still exists
                    document.dispatchEvent(new CustomEvent(isPlaying ? "audio:pause" : "audio:resume"));
                    setIntent({key, playing: !isPlaying});
                    return;
                }
                // Queue was cleared (pause emptied list) → rebuild & re-queue
                if (kind === "Pack") {
                    const list = await fetchPackPreviewPlaylist(item.id);
                    if (!list.length) return;
                    document.dispatchEvent(new CustomEvent("audio:play-list", {
                        detail: {list, index: 0, sourceKey: key}
                    }));
                    setIntent({key, playing: true});
                    return;
                }
                if (kind === "Kit") {
                    const list = await fetchKitPreviewPlaylist(item.id);
                    if (!list.length) return;
                    incrementKitPlay(item.id);
                    document.dispatchEvent(new CustomEvent("audio:play-list", {
                        detail: {list, index: 0, sourceKey: key}
                    }));
                    setIntent({key, playing: true});
                    return;
                }
                // Beat: rebuild the beat playlist and start from this track
                {
                    const switchingFromOtherType = lastSourceType.current && lastSourceType.current !== "beat";
                    const list = await buildBeatPlaylist(filtered, item.id, {
                        bustClickedOnly: true,
                        bustAll: switchingFromOtherType
                    });
                    if (!list.length) return;
                    const startIndex = list.findIndex(t => t.id === item.id);
                    if (startIndex < 0) return;
                    document.dispatchEvent(new CustomEvent("audio:play-list", {
                        detail: {list, index: startIndex, sourceKey: key}
                    }));
                    setIntent({key, playing: true});
                    lastSourceType.current = "beat";
                    return;
                }
            }

            // --- Different card (new source) ---
            if (kind === "Pack") {
                const list = await fetchPackPreviewPlaylist(item.id);
                if (!list.length) return;
                document.dispatchEvent(new CustomEvent("audio:play-list", {
                    detail: {list, index: 0, sourceKey: key}
                }));
                setNowKey(key);
                setIntent({key, playing: true});
                return;
            }

            if (kind === "Kit") {
                const list = await fetchKitPreviewPlaylist(item.id);
                if (!list.length) return;
                incrementKitPlay(item.id);
                document.dispatchEvent(new CustomEvent("audio:play-list", {
                    detail: {list, index: 0, sourceKey: key}
                }));
                setNowKey(key);
                setIntent({key, playing: true});
                return;
            }

            // Beat
            {
                const switchingFromOtherType = lastSourceType.current && lastSourceType.current !== "beat";
                const list = await buildBeatPlaylist(filtered, item.id, {
                    bustClickedOnly: true,
                    bustAll: switchingFromOtherType
                });
                if (!list.length) return;
                const startIndex = list.findIndex(t => t.id === item.id);
                if (startIndex < 0) return;
                document.dispatchEvent(new CustomEvent("audio:play-list", {
                    detail: {list, index: startIndex, sourceKey: key}
                }));
                setNowKey(key);
                setIntent({key, playing: true});
                lastSourceType.current = "beat";
            }
        } catch (e) {
            console.error("play failed", e);
        }
    }


    // Replace your renderTagPills with this:
    const renderTagPills = (item) => {
        if (browseTag === "All") {
            // Correct priority: Featured > Popular > Trending > New
            const tag =
                item.featured
                    ? {key: "featured", cls: "tag--featured", label: "Featured"}
                    : item.isPopular
                        ? {key: "popular", cls: "tag--popular", label: "Popular"}
                        : item.isTrending
                            ? {key: "trending", cls: "tag--trending", label: "Trending"}
                            : item.isNew
                                ? {key: "new", cls: "tag--new", label: "New"}
                                : null;

            return tag ? <span key={tag.key} className={`tag ${tag.cls}`}>{tag.label}</span> : null;
        }

        // Fallback for other filters (e.g. "Popular", "Trending")
        switch (browseTag) {
            case "Featured":
                return item.featured ? <span className="tag tag--featured">Featured</span> : null;
            case "Trending":
                return item.isTrending ? <span className="tag tag--trending">Trending</span> : null;
            case "Popular":
                return item.isPopular ? <span className="tag tag--popular">Popular</span> : null;
            case "New":
                return item.isNew ? <span className="tag tag--new">New</span> : null;
            default:
                return null;
        }
    };

    const addKitToCart = (kit) => {
        add({
            id: `kit-${kit.id}`,
            type: "kit",
            kitId: kit.id,
            title: kit.title,
            artist: kit.artist || "Unknown",
            img: kit.img,
            price: Number(kit.price || 0),
            qty: 1,
        });
    };


    const filtered = useMemo(() => {
        const q = query.trim().toLowerCase();
        const qTokens = q ? q.split(/\s+/).filter(Boolean) : [];

        const list = beats.filter((b) => {
            if (featuredOnly && !b.featured) return false;

            // enforce Tag
            const matchesBrowseTag = (() => {
                switch (browseTag) {
                    case "Featured":
                        return !!b.featured;
                    case "New":
                        return !!b.isNew;
                    case "Popular":
                        return !!b.isPopular;
                    case "Trending":
                        return !!b.isTrending;
                    default:
                        return true; // "All"
                }
            })();

            if (!matchesBrowseTag) return false;

            // type
            const matchesType =
                productType === "All" ||
                (productType === "Beats" && b.kind === "Beat") ||
                (productType === "Kits" && b.kind === "Kit") ||
                (productType === "Packs" && b.kind === "Pack");

            if (!matchesType) return false;

            // text
            const hay = [
                b.title || "",
                b.artist || "",
                b.genre || "",
                Array.isArray(b.tags) ? b.tags.join(" ") : (b.tags || "")
            ].join(" ").toLowerCase();

            const matchesQ = !qTokens.length || qTokens.every(t => hay.includes(t));

            // ranges
            const bpm = Number.isFinite(b.bpm) ? b.bpm : 0;
            const price = Number.isFinite(b.price) ? b.price : 0;

            const genreKey = (selectedGenre || "").toLowerCase();
            const matchesGenre = !genreKey || (b.genre || "").toLowerCase() === genreKey;

            const matchesBpm = bpm === 0 || (bpm >= bpmRange[0] && bpm <= bpmRange[1]);
            const matchesPrice = price >= priceRange[0] && price <= priceRange[1];

            return matchesQ && matchesGenre && matchesBpm && matchesPrice;
        });

        // Sorting (with simple tiebreakers)
        const byNumAsc = (a, b, key) => (Number(a[key] ?? 0) - Number(b[key] ?? 0)) || String(a.title).localeCompare(String(b.title));
        const byNumDesc = (a, b, key) => (Number(b[key] ?? 0) - Number(a[key] ?? 0)) || String(a.title).localeCompare(String(b.title));

        const score = (b) => {
            if (!q) return 0;
            const title = (b.title || "").toLowerCase();
            const artist = (b.artist || "").toLowerCase();
            const genre = (b.genre || "").toLowerCase();
            let s = 0;
            if (title.includes(q)) s += 3;
            if (artist.includes(q)) s += 2;
            if (genre.includes(q)) s += 1;
            return s;
        };

        let out = list.slice();

        switch (sort) {
            case "newest":
                out.sort((a, b) => byNumDesc(a, b, "createdAt"));
                break;
            case "priceLow":
                out.sort((a, b) => byNumAsc(a, b, "price"));
                break;
            case "priceHigh":
                out.sort((a, b) => byNumDesc(a, b, "price"));
                break;
            case "bpmLow":
                out.sort((a, b) => byNumAsc(a, b, "bpm"));
                break;
            case "bpmHigh":
                out.sort((a, b) => byNumDesc(a, b, "bpm"));
                break;
            default:
                if (q) out.sort((a, b) => score(b) - score(a)); // relevance only when searching
                break;
        }

        // Float Featured to the top in the "All" view (stable; preserves intra-group order)
        if (browseTag === "All") {
            out.sort((a, b) => {

                const tier = (item) => {
                    if (item.featured) return 4;    // Highest priority
                    if (item.isPopular) return 3;
                    if (item.isTrending) return 2;
                    if (item.isNew) return 1;
                    return 0;                       // Default / other
                };

                // Compare tier first
                const tierDiff = tier(b) - tier(a);
                if (tierDiff !== 0) return tierDiff;

                // If in the same tier → sort by likes & plays
                const scoreA = (a.likeCount || 0) * 10 + (a.playCount || 0);
                const scoreB = (b.likeCount || 0) * 10 + (b.playCount || 0);

                // Higher score = higher position
                if (scoreB !== scoreA) return scoreB - scoreA;

                // Fallback: newest first if all else equal
                return Number(b.createdAt || 0) - Number(a.createdAt || 0);
            });
        }

        return out;
    }, [beats, query, selectedGenre, bpmRange, priceRange, sort, productType, browseTag, featuredOnly]);

    const onShare = (item) => {
        // normalize -> shape ShareModal expects
        setShareTrack({
            id: item.id,
            slug: item.slug, // if you have it; safe if undefined
            title: item.title,
            artistName: item.artist || item.artistName || "Unknown",
            albumCoverUrl: item.img || item.coverUrl || item.albumCoverUrl,
        });
        setShareOpen(true);
    };

    const onGoToItem = (item) => {
        const seg = item.slug || item.id;       // prefer slug
        const base =
            item.kind === "Pack" ? "pack" :
                item.kind === "Kit" ? "kit" :
                    "track";       // Beat -> track
        window.location.href = `/${base}/${seg}`;
    };




    useEffect(() => {
        // Hydrate Beats & Packs that still have price === null
        const beatsOnly = beats.filter(b => b.kind === "Beat" && b.price == null);
        const packsOnly = beats.filter(b => b.kind === "Pack" && b.price == null);
        if (!beatsOnly.length && !packsOnly.length) return;

        let cancelled = false;
        (async () => {
            const patch = (id, patchObj, expectedKind) => {
                if (cancelled) return;
                setBeats(prev =>
                    prev.map(row =>
                        row.id === id && row.kind === expectedKind
                            ? {...row, ...patchObj, _hydratedPrice: true}
                            : row
                    )
                );
            };

            if (beatsOnly.length) {
                await hydratePrices("beats", beatsOnly, (id, p) => patch(id, p, "Beat"));
            }
            if (packsOnly.length) {
                await hydratePrices("packs", packsOnly, (id, p) => patch(id, p, "Pack"));
            }
        })();

        return () => {
            cancelled = true;
        };
    }, [beats]);

    const isMobile = useIsMobile();

    const getSmartTitle = (title) => {
        if (!title) return "";
        if (isMobile) {
            // mobile: aggressively shorten
            return title.length > 12 ? title.slice(0, 12) + "…" : title;
        } else {
            // desktop: let CSS ellipsize
            return title;
        }
    };


    return (
        <div className="browse">
            {/* Hero / Search */}
            <section className="browse-hero">
                <div className="browse-hero__top">
                    <h1>Browse beats, kits & packs</h1>
                </div>

                <div className="browse-search">
                    <div className="search stylepage__search--above-list">
                        <input
                            className="search__input"
                            placeholder="Search..."
                            value={query}
                            onChange={(e) => setQuery(e.target.value)}
                            aria-label="Search beats"
                        />
                        <svg className="search__icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden>
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2"
                                  d="m21 21-4.35-4.35M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z"/>
                        </svg>
                    </div>
                </div>


            </section>

            <div className="browse-layout">
                {/* Filters (left) */}
                <aside className={`filters ${showMobileFilters ? "is-open" : ""}`} id="mobileFilters"
                       aria-label="Filters">
                    <button className="filters__close" onClick={() => setShowMobileFilters(false)}
                            aria-label="Close filters" title="Close">✕
                    </button>

                    <div className="filters__group">
                        <div className="filters__title">Product Type</div>
                        <div className="filters__chips">
                            {PRODUCT_TYPES.map(type => (
                                <button
                                    key={type}
                                    className={`chip chip--small ${productType === type ? "is-active" : ""}`}
                                    onClick={() => setProductType(type)}
                                    aria-pressed={productType === type}
                                >
                                    {type}
                                </button>
                            ))}
                        </div>
                    </div>

                    <div className="filters__group">
                        <div className="filters__title">Tag</div>
                        <div className="filters__chips">
                            {BROWSE_TAGS.map(tag => (
                                <button
                                    key={tag}
                                    className={`chip chip--small ${browseTag === tag ? "is-active" : ""}`}
                                    onClick={() => setBrowseTag(tag)}
                                    aria-pressed={browseTag === tag}
                                >
                                    {tag}
                                </button>
                            ))}
                        </div>
                    </div>

                    <div className="filters__group">
                        <div className="filters__title">Genres</div>
                        <div className="filters__chips" role="radiogroup" aria-label="Genres">
                            {GENRES.map((g) => {
                                const isActive = selectedGenre === g;
                                return (
                                    <button
                                        key={g}
                                        type="button"
                                        role="radio"
                                        aria-checked={isActive}
                                        className={`chip chip--small ${isActive ? "is-active" : ""}`}
                                        onClick={() => setSelectedGenre(isActive ? "" : g)} // click again to clear
                                    >
                                        {g}
                                    </button>
                                );
                            })}
                        </div>
                    </div>

                    <div className="filters__group">
                        <div className="filters__title">BPM</div>
                        <div className="range">
                            <input type="range" min="60" max="200" value={bpmRange[0]}
                                   onChange={e => setBpmRange([+e.target.value, bpmRange[1]])}/>
                            <input type="range" min="60" max="200" value={bpmRange[1]}
                                   onChange={e => setBpmRange([bpmRange[0], +e.target.value])}/>
                            <div className="range__labels"><span>{bpmRange[0]}</span><span>{bpmRange[1]}</span></div>
                        </div>
                    </div>

                    <div className="filters__group">
                        <div className="filters__title">Price</div>
                        <div className="range">
                            <input type="range" min="0" max="500" step="10" value={priceRange[0]}
                                   onChange={e => setPriceRange([+e.target.value, priceRange[1]])}/>
                            <input type="range" min="0" max="500" step="10" value={priceRange[1]}
                                   onChange={e => setPriceRange([priceRange[0], +e.target.value])}/>
                            <div className="range__labels"><span>${priceRange[0]}</span><span>${priceRange[1]}</span>
                            </div>
                        </div>
                    </div>

                    <button className="filters__clear" onClick={() => {
                        setSelectedGenre("");
                        setBpmRange([120, 160]);
                        setPriceRange([0, 500]);
                        setSort("relevance");
                    }}>Reset filters
                    </button>
                </aside>

                {/* Results */}
                <section className="results">
                    <div className="results__meta">
                        <div className="meta-left">
           <span style={{color: "white"}}>
  {loading
      ? "Loading…"
      : filtered.length === 0
          ? "Upload the first item."
          : `${filtered.length} results`}
</span>
                        </div>

                        <div className="meta-right">
                            <button className="filters__toggle" onClick={() => setShowMobileFilters(true)}>
                                <IoFilter/> Filters
                            </button>

                            <div className="sort-select">
                                <IoSwapVertical aria-hidden className="sort-icon"/>
                                <select value={sort} onChange={(e) => setSort(e.target.value)}
                                        aria-label="Sort results">
                                    <option value="relevance">Relevance</option>
                                    <option value="newest">Newest</option>
                                    <option value="priceLow">Price: Low → High</option>
                                    <option value="priceHigh">Price: High → Low</option>
                                    <option value="bpmLow">BPM: Low → High</option>
                                    <option value="bpmHigh">BPM: High → Low</option>
                                </select>
                                <IoChevronDown className="chevron-icon" aria-hidden/>
                            </div>
                        </div>
                    </div>

                    {/* Empty state */}
                    {!loading && !error && filtered.length === 0 && (
                        <div className="browse-empty">Upload your first beat, pack or track</div>
                    )}

                    {/* Grid */}
                    {filtered.length > 0 && (
                        <ul className="beat-grid">
                            {filtered.map(b => {
                                const rowKey = `${b.kind}:${b.id}`;           // unique across types
                                const isOpen = openMenuFor === rowKey;

                                return (
                                    <li key={rowKey} className={`beat ${isOpen ? "is-menu-open" : ""}`}>
                                        <div
                                            className="beat__thumb"
                                            role="button"
                                            tabIndex={0}
                                            onClick={() => onPlay(b)}
                                            onKeyDown={(e) => (e.key === "Enter" || e.key === " ") && onPlay(b)}
                                            aria-label={`Play ${b.title} by ${b.artist}`}
                                        >
                                            <img src={b.img || "/placeholder-cover.png"} alt={b.title}/>
                                            {/*<div className="beat-card__overlay">*/}
                                            {/*    {nowKey === (b.kind === "Pack" ? PACK_KEY(b.id) : b.kind === "Kit" ? KIT_KEY(b.id) : BEAT_KEY(b.id)) && isPlaying ? (*/}
                                            {/*        // pause icon*/}
                                            {/*         <svg viewBox="0 0 24 24" aria-hidden="true">*/}
                                            {/*            <path d="M6 5h4v14H6zM14 5h4v14h-4z"/>*/}
                                            {/*        </svg>*/}
                                            {/*    ) : (*/}
                                            {/*        // play icon*/}

                                            {/*        <svg viewBox="0 0 24 24" aria-hidden="true">*/}
                                            {/*            <path d="M8 5v14l11-7z"/>*/}
                                            {/*        </svg>*/}
                                            {/*    )}*/}
                                            {/*</div>*/}
                                            <div className="beat-card__overlay">
                                                {(() => {
                                                    const rowKey = b.kind === "Pack" ? PACK_KEY(b.id)
                                                        : b.kind === "Kit" ? KIT_KEY(b.id)
                                                            : BEAT_KEY(b.id);
                                                    const showPause = intent?.key === rowKey
                                                        ? intent.playing
                                                        : (nowKey === rowKey && isPlaying);
                                                    return showPause ? (
                                                        <svg viewBox="0 0 24 24" aria-hidden="true">
                                                            <path d="M6 5h4v14H6zM14 5h4v14h-4z"/>
                                                        </svg>
                                                    ) : (
                                                        <svg viewBox="0 0 24 24" aria-hidden="true">
                                                            <path d="M8 5v14l11-7z"/>
                                                        </svg>
                                                    );
                                                })()}
                                            </div>
                                        </div>

                                        <div className="beat__body">
                                            <div className="beat__header">
                                                <div className="beat__title" title={b.title}>
                                                    {getSmartTitle(b.title)}
                                                    <span
                                                        className={`badge badge--${b.kind.toLowerCase()}`}>{b.kind}</span>
                                                    {renderTagPills(b)}

                                                </div>

                                                <div className="moremenu">
                                                    <button
                                                        className="more-actions-btn"
                                                        aria-label="More actions"
                                                        aria-expanded={isOpen}
                                                        onClick={(e) => {
                                                            e.stopPropagation();
                                                            setOpenMenuFor(prev => (prev === rowKey ? null : rowKey));
                                                        }}
                                                        onMouseDown={(e) => e.stopPropagation()}
                                                    >
                                                        <svg viewBox="0 0 24 24" width="20" height="20"
                                                             fill="currentColor" aria-hidden>
                                                            <circle cx="12" cy="5" r="2"/>
                                                            <circle cx="12" cy="12" r="2"/>
                                                            <circle cx="12" cy="19" r="2"/>
                                                        </svg>
                                                    </button>

                                                    {isOpen && (
                                                        <div
                                                            className="moremenu__panel"
                                                            role="menu"
                                                            onMouseDown={(e) => e.stopPropagation()}
                                                            onClick={(e) => e.stopPropagation()}
                                                        >
                                                            <button className="moremenu__item" onClick={() => {
                                                                onShare(b);
                                                                setOpenMenuFor(null);
                                                            }}>
                                                                <IoShareOutline size={18}/> <span>Share</span>
                                                            </button>
                                                            {b.ownerId && (
                                                                <button
                                                                    className="moremenu__item"
                                                                    onClick={() => {
                                                                        navigate(`/profile/${b.ownerId}`);
                                                                        setOpenMenuFor(null);
                                                                    }}
                                                                >
                                                                    <IoPersonOutline size={18}/> <span>Go to Artist Profile</span>
                                                                </button>
                                                            )}
                                                            <button
                                                                className="moremenu__item"
                                                                onClick={() => {
                                                                    onGoToItem(b);
                                                                    setOpenMenuFor(null);
                                                                }}
                                                            >
                                                                <IoOpenOutline size={18}/>
                                                                <span>
    {b.kind === "Pack" ? "Go to pack" : b.kind === "Kit" ? "Go to kit" : "Go to track"}
  </span>
                                                            </button>
                                                            {(b.kind === "Beat" || b.kind === "Pack") && (
                                                                <button
                                                                    className="moremenu__item"
                                                                    onClick={() => {
                                                                        onBuy(b);
                                                                        setOpenMenuFor(null);
                                                                    }}
                                                                >
                                                                    <IoCartOutline size={18}/>
                                                                    <span>Buy license</span>
                                                                </button>
                                                            )}
                                                            <button className="moremenu__item" onClick={() => {
                                                                addToPlaylist(b);
                                                                setOpenMenuFor(null);
                                                            }}>
                                                                <IoListOutline size={18}/> <span>Add to playlist</span>
                                                            </button>
                                                        </div>
                                                    )}
                                                </div>
                                            </div>

                                            <div className="beat__artist" title={b.artist}>
                                                {b.artist && b.ownerId ? (
                                                    <UploaderLink userId={b.ownerId}>
                                                        {b.artist}
                                                    </UploaderLink>
                                                ) : (
                                                    b.artist
                                                )}
                                            </div>

                                            <div className="beat__metaRow">
                                                <div className="beat__sub">
                                                    {b.kind === "Pack" && (
                                                        `${b.genre || ""}${b.genre ? " · " : ""}${
                                                            Number.isFinite(b.beatsCount) ? `${b.beatsCount} tracks · ` : ""
                                                        }${fmtTime(b.duration)}`
                                                    )}

                                                    {b.kind === "Kit" && (
                                                        // Kits: no BPM — just Genre · Duration (hide the dot if no genre)
                                                        `${b.genre || ""}${b.genre ? " · " : ""}${fmtTime(b.duration)}`
                                                    )}

                                                    {b.kind === "Beat" && (
                                                        `${b.genre} · ${b.bpm} BPM · ${fmtTime(b.duration)}`
                                                    )}
                                                </div>

                                                {Array.isArray(b.tags) && b.tags.length > 0 && (
                                                    <div className="beat__tags" title={b.tags.join(", ")}>
                                                        {b.tags.map(t => (
                                                            <span key={t} className="tag">{t}</span>
                                                        ))}
                                                    </div>
                                                )}

                                                <button
                                                    className="price-btn"
                                                    onClick={(e) => {
                                                        e.stopPropagation();
                                                        onBuy(b);
                                                    }}
                                                >
                                                    <IoCartOutline size={18} style={{marginRight: "6px"}}/>
                                                    {b.price == null ? "Buy" : `$${b.price.toFixed(2)}`}
                                                </button>
                                            </div>
                                        </div>
                                    </li>
                                );
                            })}
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
                            disabled={page >= totalPages - 1}   // <-- key change
                            className="tp-pagebtn tp-nextbtn"
                        >
                            →
                        </button>
                    </div>

                    {showMobileFilters && (
                        <button className="filters__backdrop" aria-label="Close filters"
                                onClick={() => setShowMobileFilters(false)}/>
                    )}
                </section>
            </div>

            <LicenseModal
                isOpen={showLicenseModal}
                track={selectedTrack}
                onClose={() => {
                    setShowLicenseModal(false);
                    setSelectedTrack(null);
                }}
                onSelect={({beatId, license, action}) => {
                    if (!selectedTrack) return;

                    let item;

                    if (selectedTrack.kind === "Pack") {
                        // PACK: license-priced, backend endpoint /purchases/pack
                        item = {
                            id: `pack-${selectedTrack.id}-${license.id}`, // unique per pack+license
                            type: "pack",
                            packId: selectedTrack.id,
                            title: selectedTrack.title,
                            img: selectedTrack.img,
                            licenseType: license.type || license.id,
                            licenseId: license.id,
                            licenseName: license.name,
                            price: Number(license.price || 0),
                            qty: 1,
                        };
                    } else {
                        // BEAT (default): needs licenseType for backend /purchases/beat
                        item = {
                            id: `beat-${beatId || selectedTrack.id}-${license.type}`, // unique per beat+license
                            type: "beat",
                            beatId: beatId || selectedTrack.id,
                            title: selectedTrack.title,
                            artist: selectedTrack.artist,
                            img: selectedTrack.img,
                            licenseType: license.type,          // <-- important for /purchases/beat
                            licenseName: license.name,
                            price: Number(license.price || 0),
                            qty: 1,
                        };
                    }

                    if (action === "addToCart") {
                        add(item);
                    } else if (action === "buyNow") {
                        clear();
                        add(item);
                        window.location.href = "/checkout";
                    }

                    setShowLicenseModal(false);
                    setSelectedTrack(null);
                }}
            />
            <ShareModal
                open={shareOpen}
                track={shareTrack}
                onClose={() => {
                    setShareOpen(false);
                    setShareTrack(null);
                }}
            />

        </div>
    );
}
