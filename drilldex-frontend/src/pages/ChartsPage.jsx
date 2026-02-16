import {useEffect, useMemo, useState, useRef} from "react";
import LicenseModal from "../components/LicenseModal.jsx";
import {
    IoHeartOutline,
    IoChatbubbleOutline,
    IoOpenOutline,
    IoShareOutline,
    IoDownloadOutline,
    IoCartOutline,
    IoListOutline,
} from "react-icons/io5";
import "./charts.css";
import api from "../lib/api";
import {useSearchParams} from "react-router-dom";
import {useNavigate} from "react-router-dom";
import ShareModal from "../components/ShareModal.jsx";
import {useCart} from "../state/cart.jsx";
import {derivePriceIfPossible, hydratePrices} from "../lib/pricing";
import toast from "react-hot-toast";
import UploaderLink from "../components/UploaderLink";
import useIsMobile from "../hooks/useIsMobile";
import { queueBeatsList } from "../lib/playerQueue";

/* -------- helpers (paths & mapping) -------- */
const CDN_BASE = import.meta.env.VITE_S3_PUBLIC_BASE?.replace(/\/+$/, "") || "";

const toFileUrl = (p) => {
    if (!p) return null; // <-- null, not empty string
    if (/^https?:\/\//i.test(p)) return p;
    if (p.startsWith("/uploads/https://") || p.startsWith("/uploads/http://")) {
        return p.replace(/^\/uploads\//, "");
    }
    if (p.startsWith("/uploads/")) return p;
    const clean = p.replace(/^\/+/, "");
    return CDN_BASE ? `${CDN_BASE}/${clean}` : `/uploads/${clean}`;
};

const toChartRow = (x) => {
    const inline = derivePriceIfPossible(x); // min from inline licenses, if present

    return {
        key: `Beat:${x.id}`,                // composite key
        id: x.id,
        slug: x.slug,
        liked: Boolean(x.liked),
        likeCount: Number(x.likeCount ?? 0),
        commentCount: Number(x.commentCount ?? 0),

        title: x.title,
        artist: x.artistName,
        ownerId: x.ownerId,
        genre: x.genre,
        bpm: x.bpm,
        duration: Number(x.durationInSeconds || 0),
        img: toFileUrl(x.coverUrl || x.albumCoverUrl || x.coverImagePath) || null,
        tags: String(x.tags || "").split(",").map(t => t.trim()).filter(Boolean).slice(0, 6),

        // IMPORTANT: use derived min when available, else null so we can hydrate
        price: inline ?? null,
        playCount: Number(x.playCount || 0),

        plan: x.ownerPlan ?? "free", // you need to add this in your backend response

    };
};



/* -------- component -------- */
function fmtTime(sec) {
    const m = Math.floor(sec / 60);
    const s = String(sec % 60).padStart(2, "0");
    return `${m}m ${s}s`;
}

export default function ChartsPage() {
    const [{sections, all}, setData] = useState({
        sections: {featured: [], new: [], popular: [], trending: []},
        all: [],
    });
    const [active, setActive] = useState("all");
    const [openMenuFor, setOpenMenuFor] = useState(null);
    const [loading, setLoading] = useState(true);
    const [player, setPlayer] = useState({playing: false, sourceKey: "", trackId: null});
    const [showLicenseModal, setShowLicenseModal] = useState(false);
    const [selectedTrack, setSelectedTrack] = useState(null);
    const [searchParams, setSearchParams] = useSearchParams();
    const likeInFlight = useRef(new Set());
    const {add, clear} = useCart();
    const navigate = useNavigate();
    const beatSlugOrId = (b) => b?.slug || b?.id;
    const [page, setPage] = useState(0);
    const [totalPages, setTotalPages] = useState(1);
    const [hasMore, setHasMore] = useState(true);
    const [shareOpen, setShareOpen] = useState(false);
    const [shareTrack, setShareTrack] = useState(null);
    const [nowKey, setNowKey] = useState(null);
    const [intent, setIntent] = useState(null); // optional: UI sync


    function onShare(b) {
        // Shape it like LandingPage expects
        setShareTrack({
            id: b.id,
            slug: b.slug,
            title: b.title,
            artistName: b.artist,     // charts uses `artist`
            albumCoverUrl: b.img,     // charts uses `img`
        });
        setShareOpen(true);
    }

    // ---- URL <-> state sync for ?tab= ----
    const VALID_TABS = new Set(["all", "featured", "trending", "popular", "new"]);


    function goToTrack(b) {
        const seg = beatSlugOrId(b);
        if (!seg) return;
        navigate(`/track/${seg}`);
    }

    function goToComments(b) {
        const seg = beatSlugOrId(b);
        if (!seg) return;
        navigate(`/track/${seg}#comments`);
    }

    // Apply tab from URL on load & when URL changes (e.g., back/forward)
    useEffect(() => {
        const q = (searchParams.get("tab") || "all").toLowerCase();
        setActive(VALID_TABS.has(q) ? q : "all");
    }, [searchParams]);

    // When user clicks a tab, update both state and URL
    const selectTab = (t) => {
        const tab = VALID_TABS.has(t) ? t : "all";
        setActive(tab);
        if (tab === "all") {
            // keep URL clean
            setSearchParams({});
        } else {
            setSearchParams({tab});
        }
    };
    const onBuy = (track) => {
        setSelectedTrack(track);
        setShowLicenseModal(true);
    };

    // Normalize a charts row into the playlist item shape
    function asPlaylistItem(row = {}) {
        return {
            id: row.id,
            title: row.title,
            artistName: row.artist || "Unknown",
            albumCoverUrl: row.img || "",
            // It's ok if this is empty; the AudioBar can fetch on-demand
            audioUrl: row.previewUrl || "",
            durationInSeconds: row.duration || 0,
        };
    }

    const dedupeById = (arr) => {
        const seen = new Set();
        return arr.filter(item => (seen.has(item.id) ? false : (seen.add(item.id), true)));
    };


    // const playedOnce = useRef(new Set());
    //
    // function incrementPlayBeat(id) {
    //     if (!id) return;
    //     const key = `beat:${id}`;
    //     if (playedOnce.current.has(key)) return;
    //     playedOnce.current.add(key);
    //     api.post(`/beats/${id}/play`).catch(() => {
    //     });
    // }

    // Helper to patch a beat across all local lists
    function patchBeatEverywhere(beatId, patch) {
        setData(prev => {
            const patchList = (list) => list.map(it => (it.id === beatId ? {...it, ...patch} : it));
            return {
                sections: {
                    featured: patchList(prev.sections.featured),
                    new: patchList(prev.sections.new),
                    popular: patchList(prev.sections.popular),
                    trending: patchList(prev.sections.trending),
                },
                all: patchList(prev.all),
            };
        });
    }

    async function toggleLikeBeat(b) {
        const beatId = b?.id;
        if (!beatId || likeInFlight.current.has(beatId)) return;
        likeInFlight.current.add(beatId);

        try {
            const wasLiked = !!b.liked;
            const wasCount = Number.isFinite(b.likeCount) ? b.likeCount : 0;

            if (wasLiked) {
                // Optimistic UNLIKE
                const dec = Math.max(0, wasCount - 1);
                patchBeatEverywhere(beatId, {liked: false, likeCount: dec});

                const resp = await api.delete(`/beats/${beatId}/like`);
                const dto = resp?.data || {};
                patchBeatEverywhere(beatId, {
                    liked: !!(dto.liked ?? dto.isLiked ?? dto.userHasLiked ?? false),
                    likeCount: Number.isFinite(dto.likeCount) ? dto.likeCount : dec,
                });
                return;
            }

            // LIKE: no optimistic +1 to avoid flicker—use server result
            const resp = await api.post(`/beats/${beatId}/like`);
            const dto = resp?.data || {};
            const finalLiked = !!(dto.liked ?? dto.isLiked ?? dto.userHasLiked ?? true);
            const finalCount = Number.isFinite(dto.likeCount) ? dto.likeCount : wasCount + (finalLiked ? 1 : 0);

            patchBeatEverywhere(beatId, {liked: finalLiked, likeCount: finalCount});
        } catch (err) {
            // revert safely
            patchBeatEverywhere(beatId, {liked: !!b.liked, likeCount: Number.isFinite(b.likeCount) ? b.likeCount : 0});
            const status = err?.response?.status;
            if (status === 401) toast.error("Please sign in to like beats.");
            else toast.error("Couldn't update like. Please try again.");
        } finally {
            likeInFlight.current.delete(beatId);
        }
    }



    useEffect(() => {
        // items in the displayed feed that still need a price
        const needs = (all || []).filter(r => r.price == null && !r._hydratedPrice);
        if (!needs.length) return;

        let cancelled = false;

        const patchEverywhere = (beatId, patchObj) => {
            if (cancelled) return;
            setData(prev => {
                const patchList = (list) =>
                    list.map(row =>
                        (row.id === beatId ? {...row, ...patchObj, _hydratedPrice: true} : row)
                    );
                return {
                    sections: {
                        featured: patchList(prev.sections.featured),
                        new: patchList(prev.sections.new),
                        popular: patchList(prev.sections.popular),
                        trending: patchList(prev.sections.trending),
                    },
                    all: patchList(prev.all),
                };
            });
        };

        (async () => {
            await hydratePrices("beats", needs, (id, p) => patchEverywhere(id, p));
        })();

        return () => {
            cancelled = true;
        };
    }, [all]);

    function hasLiveQueue(key) {
        return player.sourceKey === key && player.trackId != null;
    }

    async function playChartRow(b) {
        const key = `charts:${b.id}`;

        // ✅ Simple toggle logic (same as LandingPage)
        if (player.sourceKey === key) {
            document.dispatchEvent(new CustomEvent(player.playing ? "audio:pause" : "audio:resume"));
            return;
        }

        const fullList = feed.map(row => ({
            id: row.id,
            title: row.title,
            artistName: row.artist || "Unknown",
            albumCoverUrl: row.img || "",
            audioUrl: row.previewUrl || "",
            durationInSeconds: row.duration || 0,
        }));

        const index = fullList.findIndex(it => it.id === b.id);
        if (index === -1) return;

        await queueBeatsList(fullList, index, key);
        setNowKey(key);
    }



    function renderPageNumbers(page, totalPages, setPage) {
        const pageButtons = [];
        const maxPagesToShow = 7;

        const createBtn = (p) => (
            <button
                key={p}
                onClick={() => setPage(p)}
                className={`tp-pagebtn ${p === page ? "active" : ""}`}
            >
                {p + 1}
            </button>
        );

        const ellipsis = (key) => <span key={key} className="tp-ellipsis">…</span>;

        if (totalPages <= maxPagesToShow) {
            for (let i = 0; i < totalPages; i++) pageButtons.push(createBtn(i));
        } else {
            pageButtons.push(createBtn(0)); // first page

            if (page > 3) pageButtons.push(ellipsis("start-ellipsis"));

            let start = Math.max(1, page - 1);
            let end = Math.min(totalPages - 2, page + 1);

            for (let i = start; i <= end; i++) pageButtons.push(createBtn(i));

            if (page < totalPages - 4) pageButtons.push(ellipsis("end-ellipsis"));

            pageButtons.push(createBtn(totalPages - 1)); // last page
        }

        return pageButtons;
    }

    function addToPlaylist(row) {
        // Charts page only shows beats, so treat as a single track
        const item = asPlaylistItem(row);
        document.dispatchEvent(new CustomEvent("playlist:add", { detail: item }));
        toast.success("Added to playlist");
    }

    // load real data
    useEffect(() => {
        let alive = true;
        setLoading(true);

        (async () => {
            try {
                const pageSize = 50; // or whatever you want per page
                // Use page state for pagination
                const featPage = active === "featured" ? page : 0;
                const newPage = active === "new" ? page : 0;
                const popPage = active === "popular" ? page : 0;
                const trendPage = active === "trending" ? page : 0;
                const allPage = active === "all" ? page : 0;

                // 1) Fetch tagged beats
                const [featRes, newRes, popRes, trendRes, approvedRes] = await Promise.all([
                    api.get("/beats/featured", { params: { limit: pageSize, page: featPage } }),
                    api.get("/beats/new", { params: { limit: pageSize, page: newPage } }),
                    api.get("/beats/popular", { params: { limit: pageSize, page: popPage } }),
                    api.get("/beats/trending", { params: { limit: pageSize, page: trendPage } }),
                    api.get("/beats/approved", { params: { limit: pageSize, page: allPage } }), // for untagged
                ]);

                if (!alive) return;

                const seen = new Map();
                const addBeats = (res, tag) => {
                    const beats = res?.data?.items || [];
                    beats.forEach((b, idx) => {
                        if (!b.id) return;
                        const existing = seen.get(b.id);
                        if (existing) {
                            existing.assignedTags.add(tag);
                        } else {
                            const row = toChartRow(b);
                            row.assignedTags = new Set(tag ? [tag] : []); // empty for approved
                            row.sortIndex = idx;
                            seen.set(b.id, row);
                        }
                    });
                };

                addBeats(featRes, "featured");
                addBeats(newRes, "new");
                addBeats(popRes, "popular");
                addBeats(trendRes, "trending");
                addBeats(approvedRes, ""); // untagged

                const allBeats = Array.from(seen.values());

                // 4) Update state
                setData({
                    sections: {
                        featured: allBeats.filter(b => b.assignedTags.has("featured")).sort((a,b)=>a.sortIndex-b.sortIndex),
                        new: allBeats.filter(b => b.assignedTags.has("new")).sort((a,b)=>a.sortIndex-b.sortIndex),
                        popular: allBeats.filter(b => b.assignedTags.has("popular")).sort((a,b)=>a.sortIndex-b.sortIndex),
                        trending: allBeats.filter(b => b.assignedTags.has("trending")).sort((a,b)=>a.sortIndex-b.sortIndex),
                    },
                    all: allBeats,
                    totalCounts: {
                        featured: featRes.data?.totalItems || 0,
                        new: newRes.data?.totalItems || 0,
                        popular: popRes.data?.totalItems || 0,
                        trending: trendRes.data?.totalItems || 0,
                        all: approvedRes.data?.totalItems || 0,
                    },
                });

                const currentFeed = active === "all"
                    ? allBeats
                    : allBeats.filter(b => b.assignedTags.has(active));

                setTotalPages(Math.ceil(currentFeed.length / pageSize));
                setHasMore(page < Math.ceil(currentFeed.length / pageSize) - 1);

            } catch (e) {
                console.error("Charts fetch failed:", e);
                setData({
                    sections: { featured: [], new: [], popular: [], trending: [] },
                    all: [],
                    totalCounts: {},
                });
            } finally {
                if (alive) setLoading(false);
            }
        })();

        return () => { alive = false; };
    }, [page, active]);

    function getMainBoard(beat, currentTab = null) {
        if (currentTab && beat.assignedTags.has(currentTab)) return currentTab;

        // fallback: show first tag in priority order
        const PRIORITY = ["featured", "popular", "trending", "new"];
        for (let p of PRIORITY) {
            if (beat.assignedTags.has(p)) return p;
        }
        return null; // no tag
    }

    // close menus on outside click / esc
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

    useEffect(() => {
        const onState = (e) => {
            const d = e.detail || {};
            const resolvedTrackId =
                typeof d.trackId === "number" ? d.trackId
                    : d.current?.id ?? null;

            setPlayer({
                playing: !!d.playing,
                sourceKey: d.sourceKey || "",
                trackId: resolvedTrackId,
                trackList: Array.isArray(d.trackList) ? d.trackList : [],
            });
        };
        document.addEventListener("audio:state", onState);
        document.dispatchEvent(new CustomEvent("audio:get-state"));
        return () => document.removeEventListener("audio:state", onState);
    }, []);

    // filter feed by active tab
    const feed = useMemo(() => {
        // Clone to avoid mutation
        const list = [...(active === "all" ? all : sections?.[active] ?? [])];

        // Sort based on tag priority and then likes/plays
        const TAG_PRIORITY = {
            featured: 4,
            popular: 3,
            trending: 2,
            new: 1,
            "": 0,
        };

        list.sort((a, b) => {
            const getTopTag = (x) => {
                const tags = Array.from(x.assignedTags || []);
                const highest = tags.reduce((max, tag) => Math.max(max, TAG_PRIORITY[tag] || 0), 0);
                return highest;
            };

            const aTag = getTopTag(a);
            const bTag = getTopTag(b);

            if (aTag !== bTag) return bTag - aTag; // higher priority first

            // If same tag group, sort by likeCount then playCount
            const scoreA = (a.likeCount || 0) * 10 + (a.playCount || 0);
            const scoreB = (b.likeCount || 0) * 10 + (b.playCount || 0);
            if (scoreB !== scoreA) return scoreB - scoreA;
            return Number(b.createdAt || 0) - Number(a.createdAt || 0);
        });

        return list;
    }, [active, all, sections]);

    // console.log("Active tab:", active);
    // console.log("feed:", feed.map(b => b.title));

    const isMobile = useIsMobile();

    const getSmartTitle = (title) => {
        return title || "";
    };


    return (
        <div className="charts">
            <header className="charts__hero">
                <h1>Charts</h1>
                <p className="charts__sub">One feed • filter by Featured, New, Popular, or Trending</p>

                <div className="charts__filters" role="tablist" aria-label="Charts filters">
                    {["all", "featured", "trending", "popular", "new"].map((t) => (
                        <button
                            key={t}
                            role="tab"
                            aria-selected={active === t}
                            className={`charts__filter ${t} ${active === t ? "is-active" : ""}`}
                            onClick={() => selectTab(t)}
                            disabled={loading}
                        >
                            {t === "all" ? "All" : t[0].toUpperCase() + t.slice(1)}
                        </button>
                    ))}
                </div>
            </header>

            <main className="charts__main">
                <div className="charts-header">
                    <div className="search">
                        <input className="search__input" placeholder="Search beats, tags, artists…" disabled={loading}/>
                        <svg className="search__icon" viewBox="0 0 24 24" fill="none" stroke="currentColor">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2"
                                  d="m21 21-4.35-4.35M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z"/>
                        </svg>
                    </div>

                    <div className="results__meta1">
                        <span>{loading ? "Loading…" : `${feed.length} results`}</span>
                    </div>
                </div>

                {(!loading && feed.length === 0) ? (
                    <div className="charts__empty" role="status" aria-live="polite">
                        <h3 className="charts__emptyTitle">No beats here yet</h3>
                        <p className="charts__emptySub">
                            Be the first to upload a beat
                            {typeof active === "string" && active !== "all" ? <> to <strong>{active[0].toUpperCase() + active.slice(1)}</strong></> : ""}.
                        </p>
                    </div>
                ) : (
                    <ul className="charts__feed">
                        {feed.map((b, idx) => {
                            const rowKey = `charts:${b.id}`;
                            return (
                                <li key={rowKey} className="chartrow">
                                    <span className="rank">{idx + 1}</span>

                                    <div
                                        className="coverWrap"
                                        role="button"
                                        tabIndex={0}
                                        onClick={() => playChartRow(b)}
                                        onKeyDown={(e) => (e.key === "Enter" || e.key === " ") && playChartRow(b)}
                                    >
                                        {b.img ? (
                                            <img src={b.img} alt={b.title}/>
                                        ) : (
                                            <div className="coverWrap__ph" aria-label="No cover"/>
                                        )}
                                        {/* Hover overlay */}
                                        <div className="coverWrap__overlay">
                                            <button
                                                className="coverWrap__playbtn"
                                                aria-label="Play/Pause"
                                                onClick={(e) => {
                                                    e.stopPropagation();
                                                    e.preventDefault();
                                                    playChartRow(b);
                                                }}
                                            >
                                                {(player.playing && player.trackId === b.id) ? (
                                                    // pause icon
                                                    <svg viewBox="0 0 24 24" aria-hidden="true">
                                                        <path d="M6 5h4v14H6zM14 5h4v14h-4z"/>
                                                    </svg>
                                                ) : (
                                                    // play icon
                                                    <svg viewBox="0 0 24 24" aria-hidden="true">
                                                        <path d="M8 5v14l11-7z"/>
                                                    </svg>
                                                )}
                                            </button>
                                        </div>
                                    </div>

                                    <div className="meta">
                                        <div className="titleRow">
                                            <div className="title" title={b.title}>
                                                {getSmartTitle(b.title)}
                                            </div>
                                            {getMainBoard(b, active) && (
                                                <span className={`boardpill boardpill--${getMainBoard(b, active)}`}>
            {getMainBoard(b, active)}
        </span>
                                            )}
                                        </div>

                                        <div className="sub">
                                            <UploaderLink userId={b.ownerId || b.artistId}>
                                                {b.artist}
                                            </UploaderLink> • {b.genre} • {b.bpm} BPM • {fmtTime(b.duration)}
                                        </div>

                                        <div className="tags">
                                            {b.tags.map((t) => (
                                                <span className="tag" key={t}>{t}</span>
                                            ))}
                                        </div>
                                    </div>

                                    <div className="stats">
                                        <div className="moremenu">
                                            <button
                                                className="more-actions-btn moremenu__btn"
                                                onClick={(e) => {
                                                    e.stopPropagation();
                                                    setOpenMenuFor(openMenuFor === rowKey ? null : rowKey);
                                                }}
                                                onMouseDown={(e) => e.stopPropagation()}
                                                aria-label="More actions"
                                            >
                                                <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"
                                                     aria-hidden>
                                                    <circle cx="5" cy="12" r="2"/>
                                                    <circle cx="12" cy="12" r="2"/>
                                                    <circle cx="19" cy="12" r="2"/>
                                                </svg>
                                            </button>

                                            {openMenuFor === rowKey && (
                                                <div
                                                    className="moremenu__panel"
                                                    role="menu"
                                                    onMouseDown={(e) => e.stopPropagation()}
                                                    onClick={(e) => e.stopPropagation()}
                                                >
                                                    <button
                                                        className="moremenu__item"
                                                        onClick={() => {
                                                            onShare(b);
                                                            setOpenMenuFor(null);
                                                        }}
                                                    >
                                                        <IoShareOutline size={18}/> <span>Share</span>
                                                    </button>
                                                    <button
                                                        className="moremenu__item"
                                                        onClick={() => {
                                                            goToTrack(b);
                                                            setOpenMenuFor(null);
                                                        }}
                                                    >
                                                        <IoOpenOutline size={18}/> <span>Go to track</span>
                                                    </button>
                                                    <button
                                                        className="moremenu__item"
                                                        onClick={() => {
                                                            onBuy(b);
                                                            setOpenMenuFor(null);
                                                        }}
                                                    >
                                                        <IoCartOutline size={18}/> <span>Buy license</span>
                                                    </button>
                                                    <button
                                                        className="moremenu__item"
                                                        onClick={() => {
                                                            addToPlaylist(b);
                                                            setOpenMenuFor(null);
                                                        }}
                                                    >
                                                        <IoListOutline size={18}/> <span>Add to playlist</span>
                                                    </button>
                                                </div>
                                            )}
                                        </div>

                                        <button
                                            className={`pill pill--clickable ${b.liked ? "is-active" : ""}`}
                                            onClick={(e) => {
                                                e.stopPropagation();
                                                toggleLikeBeat(b);
                                            }}
                                            aria-pressed={!!b.liked}
                                            aria-label={b.liked ? "Unlike" : "Like"}
                                            title={b.liked ? "Unlike" : "Like"}
                                        >
                                            <svg viewBox="0 0 24 24" aria-hidden="true" width="20" height="20">
                                                <path
                                                    className="icon-heart"
                                                    d="M4.318 6.318a4.5 4.5 0 016.364 0L12 7.636l1.318-1.318a4.5 4.5 0 116.364 6.364L12 21.364 4.318 12.682a4.5 4.5 0 010-6.364z"
                                                />
                                            </svg>
                                            {b.likeCount}
                                        </button>
                                        <button
                                            className="pill pill--clickable"
                                            onClick={(e) => {
                                                e.stopPropagation();
                                                goToComments(b);
                                            }}
                                            aria-label="Open comments"
                                            title="Open comments"
                                        >
                                            <IoChatbubbleOutline/> {b.commentCount}
                                        </button>
                                        <button className="price-btn" onClick={() => onBuy(b)}>
                                            <IoCartOutline size={18} style={{marginRight: "6px"}}/>
                                            {b.price == null ? "Buy" : `$${Number(b.price).toFixed(2)}`}
                                        </button>
                                    </div>
                                </li>
                            );
                        })}
                    </ul>
                )}
            </main>


            <div className="tp-pagination-bar">
                <button
                    onClick={() => setPage(page - 1)}
                    disabled={page === 0 || totalPages <= 1}
                    className="tp-pagebtn tp-backbtn"
                >
                    ←
                </button>

                <div className="tp-pagination-scroll">
                    {renderPageNumbers(page, totalPages, setPage)}
                </div>

                <button
                    onClick={() => setPage(page + 1)}
                    disabled={page >= totalPages - 1 || totalPages <= 1}
                    className="tp-pagebtn tp-nextbtn"
                >
                    →
                </button>
            </div>


            <LicenseModal
                isOpen={showLicenseModal}
                track={selectedTrack}
                onClose={() => {
                    setShowLicenseModal(false);
                    setSelectedTrack(null);
                }}
                onSelect={({license, action}) => {
                    if (!selectedTrack) return;

                    const item = {
                        id: `beat-${selectedTrack.id}-${license.type}`, // use license.type for deduping
                        type: "beat",
                        beatId: selectedTrack.id,
                        title: selectedTrack.title,
                        artist: selectedTrack.artistName || selectedTrack.artist || "Unknown",
                        img:
                            selectedTrack.cover ||
                            selectedTrack.albumCoverUrl ||
                            selectedTrack.img ||
                            "",
                        licenseId: license.backendId,
                        licenseName: license.name,
                        licenseType: license.type,
                        price: Number(license.price || 0),
                        qty: 1,
                    };

                    // Fire optional event (if anything listens to it)
                    document.dispatchEvent(new CustomEvent("cart:add", {detail: item}));

                    if (action === "addToCart") {
                        add(item);
                    } else if (action === "buyNow") {
                        clear();
                        add(item);
                        setTimeout(() => {
                            window.location.href = "/checkout";
                        }, 50);
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