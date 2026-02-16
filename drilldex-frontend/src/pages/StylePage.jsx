// src/pages/StylePage.jsx
import {useEffect, useMemo, useState, useRef} from "react";
import {useLocation, useParams} from "react-router-dom";
import "./StylePage.css";
import api from "../lib/api";
import {useNavigate} from "react-router-dom";
import ShareModal from "../components/ShareModal.jsx";
import LicenseModal from "../components/LicenseModal.jsx";
import {IoEllipsisHorizontal} from "react-icons/io5"; // if needed
import {useCart} from "../state/cart.jsx";
import { derivePriceIfPossible, hydratePrices } from "../lib/pricing.js";
import toast from "react-hot-toast";
import useIsMobile from "../hooks/useIsMobile";



import {
    IoHeartOutline,
    IoChatbubbleOutline,
    IoOpenOutline,
    IoShareOutline,
    IoDownloadOutline,
    IoCartOutline,
    IoListOutline,
    IoFilter
} from "react-icons/io5"
import UploaderLink from "../components/UploaderLink.jsx";

// eslint-disable-next-line react-hooks/rules-of-hooks


const CDN_BASE = import.meta.env.VITE_S3_PUBLIC_BASE?.replace(/\/+$/, "") || "";

// Normalize any backend path into a usable URL for <img>/<audio>
const toFileUrl = (p) => {
    if (!p) return "";
    if (/^https?:\/\//i.test(p)) return p;              // already absolute
    if (p.startsWith("/uploads/https://") || p.startsWith("/uploads/http://")) {
        return p.replace(/^\/uploads\//, "");             // fix accidental double-prefix
    }
    if (p.startsWith("/uploads/")) return p;            // served by backend static
    const clean = p.replace(/^\/+/, "");
    return CDN_BASE ? `${CDN_BASE}/${clean}` : `/uploads/${clean}`;
};

// Map BeatDto → UI shape used by this page
const mapBeat = (x) => {
    const priceFromLic = derivePriceIfPossible(x);
    const splitTags = (t) => (t ? String(t).split(",").map(s => s.trim()).filter(Boolean) : []);
    return {
        id: x.id,
        slug: x.slug,
        title: x.title,
        artistName: x.artistName,
        albumCoverUrl: toFileUrl(x.coverUrl || x.albumCoverUrl || x.coverImagePath),
        previewUrl: toFileUrl(x.previewUrl || x.previewAudioPath),
        audioUrl: toFileUrl(x.previewUrl || x.audioUrl),
        genre: x.genre || "",
        bpm: Number(x.bpm || 0),
        durationInSeconds: Number(x.durationInSeconds || 0),
        tags: splitTags(x.tags),
        likeCount: x.likeCount ?? 0,
        commentCount: x.commentCount ?? 0,
        price: Number.isFinite(priceFromLic) ? priceFromLic : Number(x.price || 0),
        liked: Boolean(x.liked ?? x.isLiked ?? x.userHasLiked ?? false),
        createdAt: x.createdAt ? new Date(x.createdAt).getTime() : 0,
        ownerId: x.ownerId,
    };
};


const STYLE_DESCRIPTIONS = {
    "uk-drill":
        "UK Drill beats come cold and calculated—sliding 808s, skippy hi-hats, and sinister melodies that creep in your head. The bass glides hit like a movie score for the streets, with drums that punch sharp and leave space for vocals to cut clean. Dark, dangerous, and built for bangers.",
    "ny-drill":
        "NY Drill takes that UK bounce and gives it New York attitude—chunky 808s, wild ad-libs, and hooks that make the block move. Fast, aggressive, and packed with swagger.",
    "chicago-drill":
        "Chicago Drill beats are raw, gritty, and straight to the point—heavy 808s, haunting synths, and relentless drums. Pure street energy for unapologetic storytelling.",
    "french-drill":
        "French Drill is cinematic and powerful—layered melodies, crisp drums, and bass that rumbles under slick, polished production. Dark but classy, with a vibe that sticks.",
    "dutch-drill":
        "Dutch Drill brings the bounce—gritty textures, chant-ready hooks, and drums that keep the crowd locked in. Dark, catchy, and built for pure energy.",
    "afro-drill":
        "Afro Drill blends that smooth Afro groove with Drill’s bite—swinging percussion, melodic warmth, and bass that still hits hard. Laid-back but ready to go off.",
    "canadian-drill":
        "Canadian Drill keeps it icy—moody keys, spaced-out melodies, and deep bass that feels cold but heavy. It’s raw but polished, with a laid-back menace that matches Toronto’s underground energy.",
    "australian-drill":
        "Australian Drill is rugged and booming—hard 808s, sharp snares, and tough vocals that cut through. It’s raw, rebellious, and built for street anthems with that unmistakable Aussie grit.",
    "irish-drill":
        "Irish Drill is dark and haunting—echoing pianos, eerie synths, and pounding drums that carry raw intensity. A rebellious sound with sharp edges, made for unfiltered storytelling.",
    "german-drill":
        "German Drill is heavy and industrial—aggressive basslines, pounding kicks, and cold melodies that hit like steel. Dark, uncompromising, and built for pure dominance.",
    "spanish-drill":
        "Spanish Drill brings fire and flair—fast hi-hats, rolling 808s, and melodies laced with Latin flavor. Energetic, bold, and made for movement.",
    "italian-drill":
        "Italian Drill is cinematic and stylish—grand melodies, dramatic builds, and booming drums that feel like a crime saga soundtrack. Dark elegance with raw street power.",
    "brazilian-drill":
        "Brazilian Drill is explosive and rhythmic—808s that slam, percussion that bounces with favela energy, and melodies full of heat. Intense, raw, and unstoppable.",
};


function titleFromSlug(slug = "") {
    return slug
        .split("-")
        .map((s) => s.charAt(0).toUpperCase() + s.slice(1))
        .join(" ");
}

export default function StylePage() {
    const {slug} = useParams();
    const {state} = useLocation();
    const title = state?.title || titleFromSlug(slug);
    const heroImage = state?.image;
    const [openMenuFor, setOpenMenuFor] = useState(null);
    const [isPlaying, setIsPlaying] = useState(false);
    const [nowKey, setNowKey] = useState(null);
    const BEAT_KEY = (id) => `beat:${id}`;
    const [filtersOpen, setFiltersOpen] = useState(false);
    const [page, setPage] = useState(0);
    const [hasMore, setHasMore] = useState(true);
    const [q, setQ] = useState("");
    const [bpm, setBpm] = useState([30, 200]);
    const [price, setPrice] = useState([0, 200]);
    const [sort, setSort] = useState("relevance");
    const lastQueuedIds = useRef(new Map());
    const lastBumpedIndex = useRef(new Map());
    const [beats, setBeats] = useState([]);
    const [loading, setLoading] = useState(true);
    const [intent, setIntent] = useState(null);
    const previewCache = useRef(new Map());
    const lastSourceType = useRef(null);
    const navigate = useNavigate();
    const {add, clear} = useCart();
    const [shareOpen, setShareOpen] = useState(false);
    const [shareTrack, setShareTrack] = useState(null);
    const [showLicenseModal, setShowLicenseModal] = useState(false);
    const [selectedTrack, setSelectedTrack] = useState(null);
    const [totalPages, setTotalPages] = useState(1); // backend will dictate this


    const onShare = (track) => {
        setShareTrack({
            id: track.id, slug: track.slug, title: track.title,
            artistName: track.artistName, albumCoverUrl: track.albumCoverUrl
        });
        setShareOpen(true);
    };
    const onGoToTrack = (b) => navigate(`/track/${b.slug || b.id}`);
    const onGoToComments = (b) => navigate(`/track/${b.slug || b.id}#comments`);
    const onAddToPlaylist = (b) => addToPlaylist(b);

    const onBuy = (beat) => {
        setSelectedTrack({
            id: beat.id, title: beat.title, artistName: beat.artistName,
            albumCoverUrl: beat.albumCoverUrl, kind: "BEAT",
        });
        setShowLicenseModal(true);
    };

    useEffect(() => {
        const onState = (e) => {
            const d = e.detail || {};
            const listLen = Array.isArray(d.list) ? d.list.length : 0;
            const emptySnapshot = listLen === 0 && d.trackId == null;

            console.log("[Browse] audio:state", {
                sourceKey: d.sourceKey,
                playing: d.playing,
                trackId: d.trackId,
                listLen,
            });

            // 1) Ignore pre-init noise: empty + no sourceKey
            if (emptySnapshot && !d.sourceKey) return;

            // 2) If the queue was cleared *for our current source*, treat as paused
            const currentKey = (lastSnapshotRef.current && lastSnapshotRef.current.sourceKey) || nowKey;
            if (emptySnapshot && d.sourceKey === currentKey) {
                setIsPlaying(false);
                setIntent((prev) =>
                    prev && (prev.key === (d.sourceKey ?? nowKey)) && prev.playing === false ? null : prev
                );
                // Do NOT overwrite lastSnapshotRef on an empty snapshot
                return;
            }

            // 3) Non-empty snapshot: commit it
            if (typeof d.sourceKey === "string") setNowKey(d.sourceKey);
            if (typeof d.playing === "boolean") setIsPlaying(d.playing);

            if (typeof d.sourceKey === "string" && typeof d.playing === "boolean") {
                setIntent((prev) =>
                    prev && prev.key === d.sourceKey && prev.playing === d.playing ? null : prev
                );
            }

            // Remember only non-empty snapshots
            lastSnapshotRef.current = {
                sourceKey: d.sourceKey || null,
                playing: !!d.playing,
                trackId: d.trackId ?? null,
                listLen,
            };
        };

        document.addEventListener("audio:state", onState);
        document.dispatchEvent(new CustomEvent("audio:get-state"));
        return () => document.removeEventListener("audio:state", onState);
    }, []);

    const priceLoaded = useRef(new Set());
    const patchBeat = (id, patch) =>
        setBeats(prev => prev.map(b => (b.id === id ? {...b, ...patch} : b)));


    const fetchPreviewUrl = async (id) => {
        if (previewCache.current.has(id)) return previewCache.current.get(id);
        const {data} = await api.get(`/beats/${id}/preview-url`);
        const url = data?.url;
        if (url) previewCache.current.set(id, url);
        return url;
    };



     const toPlaylistItem = (b, url = "") => ({
           id: b.id,
           title: b.title,
           artistName: b.artistName || "Unknown",
           albumCoverUrl: b.albumCoverUrl || "",
           audioUrl: url || b.audioUrl || b.previewUrl || "",
           durationInSeconds: Number(b.durationInSeconds || 0),
         });

         async function addToPlaylist(b) {
               try {
                     // Try to include a fresh presigned URL when possible
                         let url = b.audioUrl || b.previewUrl || "";
                     if (!url) {
                           try { url = await fetchPreviewUrl(b.id); } catch {}
                         }
                     const item = toPlaylistItem(b, url);
                     document.dispatchEvent(new CustomEvent("playlist:add", { detail: item }));
                     toast.success("Added to playlist");
                   } catch {
                     toast.error("Couldn't add to playlist");
                   }
             }

    const lastSnapshotRef = useRef({
        sourceKey: null,
        playing: false,
        trackId: null,
        listLen: 0,
        currentIndex: null,
        currentTime: 0,
    });

    const hasLiveQueue = (key) => {
        const s = lastSnapshotRef.current || {};
        return s.sourceKey === key && s.trackId != null && s.listLen > 0;
    };

    // build a simple playable list from the current *filtered* rows
    const makePlayableList = (rows) =>
        rows.map(b => ({
            id: b.id,
            title: b.title,
            artistName: b.artistName || "Unknown",
            albumCoverUrl: b.albumCoverUrl || "",
            audioUrl: b.audioUrl || b.previewUrl || "", // will be swapped with fresh URL for clicked row
            durationInSeconds: Number(b.durationInSeconds || 0),
        }));

    async function onPlay(beatId) {
        const index = filtered.findIndex(b => b.id === beatId);
        if (index < 0) return;

        const key = BEAT_KEY(beatId);
        const sameKey = nowKey === key;
        const live = hasLiveQueue(key);

        // Same row clicked
        if (sameKey) {
            if (live) {
                document.dispatchEvent(new CustomEvent(isPlaying ? "audio:pause" : "audio:resume"));
                setIntent({ key, playing: !isPlaying });
                return;
            }
            // Queue was cleared (pause emptied it) → rebuild & start again
            const list = makePlayableList(filtered);
            document.dispatchEvent(new CustomEvent("audio:play-list", {
                detail: { list, index, sourceKey: key }
            }));
            setIntent({ key, playing: true });

            // Swap in fresh presigned URL for the clicked track
            const url = await fetchPreviewUrl(beatId).catch(() => null);
            if (url) {
                document.dispatchEvent(new CustomEvent("audio:swap-src", {
                    detail: { sourceKey: key, index, url }
                }));
            }
            return;
        }

        // Different card → queue the current filtered list starting at this index
        setNowKey(key);
        setIntent({ key, playing: true });

        const list = makePlayableList(filtered);
        document.dispatchEvent(new CustomEvent("audio:play-list", {
            detail: { list, index, sourceKey: key }
        }));

        // Fresh presigned URL for the clicked track
        const url = await fetchPreviewUrl(beatId).catch(() => null);
        if (url) {
            document.dispatchEvent(new CustomEvent("audio:swap-src", {
                detail: { sourceKey: key, index, url }
            }));
        }
    }

    useEffect(() => {
        const onTrackStart = (e) => {
            const { sourceKey, index } = e.detail || {};
            if (!sourceKey || !Number.isInteger(index)) return;

            const ids = lastQueuedIds.current.get(sourceKey);
            if (!ids || !ids[index]) return;

            const lastIdx = lastBumpedIndex.current.get(sourceKey);
            if (lastIdx === index) return; // already bumped this start

            lastBumpedIndex.current.set(sourceKey, index);
        };

        document.addEventListener("audio:track-start", onTrackStart);
        return () => document.removeEventListener("audio:track-start", onTrackStart);
    }, []);

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
        const idFor = (sourceKey, indexOrId) => {
            if (Number.isInteger(indexOrId)) {
                const ids = lastQueuedIds.current.get(sourceKey);
                return ids && ids[indexOrId] != null ? ids[indexOrId] : null;
            }
            // already an id
            return indexOrId != null ? indexOrId : null;
        };

        const onPlayList = (e) => {
            const { sourceKey, list, index } = e.detail || {};
            if (!sourceKey || !Array.isArray(list)) return;

            // Remember the ids we queued
            const ids = list.map(t => t?.id).filter(Boolean);
            lastQueuedIds.current.set(sourceKey, ids);

            // Track current index if provided
            lastBumpedIndex.current.set(sourceKey, Number.isInteger(index) ? index : -1);

            // Also set nowKey to the actual current track in that list
            if (Number.isInteger(index) && ids[index] != null) {
                setNowKey(BEAT_KEY(ids[index]));
            }
        };

        const onTrackChanged = (e) => {
            const { sourceKey, index } = e.detail || {};
            if (!sourceKey || !Number.isInteger(index)) return;

            lastBumpedIndex.current.set(sourceKey, index);

            // Move highlight to the real current row
            const curId = idFor(sourceKey, index);
            if (curId != null) {
                const key = BEAT_KEY(curId);
                setNowKey(key);
                // any click-intent that targeted this key is no longer needed
                setIntent(prev => (prev && prev.key === key ? null : prev));
            }
        };

        const onState = (e) => {
            const d = e.detail || {};
            const listLen = Array.isArray(d.list) ? d.list.length : 0;
            const emptySnapshot = listLen === 0 && d.trackId == null;

            // If your bar emits an "empty" snapshot on pause, don't clobber last good one
            if (emptySnapshot) {
                const currentKey = (lastSnapshotRef.current && lastSnapshotRef.current.sourceKey) || nowKey;
                if (d.sourceKey === currentKey) setIsPlaying(false);
                return;
            }

            // Non-empty snapshot: persist + drive UI
            const curId =
                (d.trackId != null ? d.trackId : idFor(d.sourceKey, d.currentIndex));

            if (curId != null) {
                setNowKey(BEAT_KEY(curId));              // << keep highlight on the actual playing row
            }
            if (typeof d.playing === "boolean") setIsPlaying(d.playing);

            if (d.sourceKey && Number.isInteger(d.currentIndex)) {
                lastBumpedIndex.current.set(d.sourceKey, d.currentIndex);
            }

            lastSnapshotRef.current = {
                sourceKey: d.sourceKey || null,
                playing: !!d.playing,
                trackId: d.trackId ?? curId ?? null,
                listLen,
                currentIndex: Number.isInteger(d.currentIndex) ? d.currentIndex : null,
                currentTime: Number.isFinite(d.currentTime) ? d.currentTime : 0,
            };

            // If the real player state matches a previous click intent, clear it
            if (typeof d.sourceKey === "string" && typeof d.playing === "boolean") {
                setIntent(prev =>
                    prev && prev.key === BEAT_KEY(curId ?? -1) && prev.playing === d.playing ? null : prev
                );
            }
        };

        document.addEventListener("audio:play-list", onPlayList);
        document.addEventListener("audio:track-changed", onTrackChanged);
        document.addEventListener("audio:state", onState);

        return () => {
            document.removeEventListener("audio:play-list", onPlayList);
            document.removeEventListener("audio:track-changed", onTrackChanged);
            document.removeEventListener("audio:state", onState);
        };
    }, []);


    const formatDuration = (sec = 0) => {
        const s = Math.max(0, Number(sec) || 0);
        const m = Math.floor(s / 60);
        const r = Math.floor(s % 60);
        return `${m}m ${String(r).padStart(2, "0")}s`;
    };


    const likeInFlight = useRef(new Set());

    async function toggleLikeBeat(b) {
        const beatId = b?.id;
        if (!beatId || likeInFlight.current.has(beatId)) return;
        likeInFlight.current.add(beatId);
        try {
            const wasLiked = !!b.liked;
            const wasCount = Number.isFinite(b.likeCount) ? b.likeCount : 0;
            if (wasLiked) {
                patchBeat(beatId, {liked: false, likeCount: Math.max(0, wasCount - 1)});
                const {data} = await api.delete(`/beats/${beatId}/like`);
                patchBeat(beatId, {
                    liked: !!(data?.liked ?? data?.isLiked ?? data?.userHasLiked ?? false),
                    likeCount: Number.isFinite(data?.likeCount) ? data.likeCount : Math.max(0, wasCount - 1),
                });
                return;
            }
            // like path
            const {data} = await api.post(`/beats/${beatId}/like`);
            patchBeat(beatId, {
                liked: !!(data?.liked ?? data?.isLiked ?? data?.userHasLiked ?? true),
                likeCount: Number.isFinite(data?.likeCount) ? data.likeCount : wasCount + 1,
            });
        } catch (err) {


            const status = err?.response?.status;
            if (status === 401) toast.error("Please sign in to like beats.");
            else toast.error("Couldn't update like. Please try again.");
        } finally {
            likeInFlight.current.delete(beatId);
        }
    }

    useEffect(() => {
        const onKey = (e) => {
            if (e.key === "Escape") setFiltersOpen(false);
        };
        if (filtersOpen) {
            document.body.style.overflow = "hidden";
            window.addEventListener("keydown", onKey);
        } else {
            document.body.style.overflow = "";
        }
        return () => {
            document.body.style.overflow = "";
            window.removeEventListener("keydown", onKey);
        };
    }, [filtersOpen]);

    useEffect(() => {
        let alive = true;
        setLoading(true);
        setBeats([]);

        (async () => {
            try {
                const { data } = await api.get(`/beats/styles/${slug}`, {
                    params: {
                        limit: 50,
                        page: page,
                    },
                });

                if (!alive) return;

                const items = Array.isArray(data?.items)
                    ? data.items.map(mapBeat)
                    : Array.isArray(data)
                        ? data.map(mapBeat)
                        : [];

                setBeats(items);
                setTotalPages(data?.totalPages || 1);
                setHasMore((data?.page ?? 0) + 1 < (data?.totalPages ?? 1));

                hydratePrices("beats", items, (id, patch) => {
                    setBeats(prev => prev.map(b => (b.id === id ? { ...b, ...patch } : b)));
                });
            } catch (err) {
                console.warn("StylePage fetch failed:", err);
                if (alive) setBeats([]);
            } finally {
                if (alive) setLoading(false);
            }
        })();

        return () => {
            alive = false;
        };
    }, [slug, page]); // ✅ Only fetch again on page or slug change




    const filtered = useMemo(() => {
        let rows = [...beats];

        // Fulltext filter
        const k = q.trim().toLowerCase();
        if (k) {
            rows = rows.filter(
                (b) =>
                    b.title.toLowerCase().includes(k) ||
                    b.artistName.toLowerCase().includes(k) ||
                    (b.tags || "").toLowerCase().includes(k)
            );
        }

        // BPM range
        rows = rows.filter((b) => b.bpm >= bpm[0] && b.bpm <= bpm[1]);

        // Price range
        rows = rows.filter((b) => b.price >= price[0] && b.price <= price[1]);

        // Sorting logic
        switch (sort) {
            case "newest":
                rows.sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
                break;
            case "priceLow":
                rows.sort((a, b) => a.price - b.price);
                break;
            case "priceHigh":
                rows.sort((a, b) => b.price - a.price);
                break;
            case "bpmHigh":
                rows.sort((a, b) => b.bpm - a.bpm);
                break;
            case "bpmLow":
                rows.sort((a, b) => a.bpm - b.bpm);
                break;
            default:
                // Tier-based relevance sorting
                rows.sort((a, b) => {
                    const tier = (item) => item.featured ? 1 : 0;

                    // Sort by featured first
                    const tierDiff = tier(b) - tier(a);
                    if (tierDiff !== 0) return tierDiff;

                    // Then sort by (likes * 10) + plays
                    const scoreA = (a.likeCount || 0) * 10 + (a.playCount || 0);
                    const scoreB = (b.likeCount || 0) * 10 + (b.playCount || 0);
                    if (scoreB !== scoreA) return scoreB - scoreA;

                    // Fallback: newest first
                    return Number(b.createdAt || 0) - Number(a.createdAt || 0);
                });
        }

        return rows;
    }, [beats, q, bpm, price, sort]);

    const isMobile = useIsMobile();

    const getSmartTitle = (title) => {
        if (!title) return "";
        if (isMobile) {
            // mobile: aggressively shorten
            return title.length > 12 ? title.slice(0, 10) + "…" : title;
        } else {
            // desktop: let CSS ellipsize
            return title;
        }
    };


    return (
        <div className="stylepage">
            {/* header */}
            <div className="stylepage__header">
                <div className="stylepage__title">
                    <div className="stylepage__coverWrap">
                        <img src={heroImage} alt={title} className="stylepage__cover"/>
                    </div>

                    <div className="stylepage__titleCol">
                        <h1 className="stylepage__h1">{title}</h1>
                        <p className="stylepage__desc">
                            {STYLE_DESCRIPTIONS[slug] ||
                                "This drill style blends hard-hitting percussion with moody melodies and sliding 808s."}
                        </p>
                        <div className="stylepage__meta">
                            {loading
                                ? "Loading…"
                                : filtered.length === 0
                                    ? "No beats yet"
                                    : `${filtered.length} tracks`}
                        </div>
                    </div>
                </div>
            </div>

            {/* main grid */}
            <div className="stylepage__grid">
                {/* left: thin list */}
                <section className="stylepage__list">
                    {/* moved search bar here */}
                    <div className="search stylepage__search--above-list">
                        <input
                            className="search__input"
                            placeholder="Search..."
                            value={q}
                            onChange={(e) => setQ(e.target.value)}
                        />
                        <svg className="search__icon" viewBox="0 0 24 24" fill="none" stroke="currentColor">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2"
                                  d="m21 21-4.35-4.35M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z"/>
                        </svg>
                    </div>
                    {/* Mobile: “Filters” trigger button */}
                    {!filtersOpen && (
                        <button
                            className="filtersBtn"
                            type="button"
                            onClick={() => setFiltersOpen(true)}
                            aria-haspopup="dialog"
                            aria-controls="filtersSheet"
                        >
                            <IoFilter aria-hidden/> Filters
                        </button>
                    )}

                    {loading ? (
                        <div className="list__empty" role="status" aria-live="polite">
                            Loading beats...
                        </div>
                    ) : beats.length === 0 ? (
                        <div className="list__empty" role="status" aria-live="polite">
                            No beats yet for {title}.
                        </div>
                    ) : filtered.length === 0 ? (
                        <div className="list__empty" role="status" aria-live="polite">
                            No beats match your filters.
                        </div>
                    ): (
                        <ul className="charts__feed">
                            {filtered.map((b, i) => {
                                const rowKey = `${b.id}:${i}`;
                                return (
                                    <li key={rowKey} className="chartrow">
                                        {/* Rank (optional — if you want order) */}
                                        <span className="rank">{i + 1}</span>

                                        {/* Cover */}
                                        <div
                                            className="coverWrap"
                                            onClick={() => onPlay(b.id)}
                                            role="button"
                                            aria-label={`Play ${b.title} by ${b.artistName}`}
                                        >
                                            <img src={b.albumCoverUrl} alt={b.title}/>
                                             <div className="list__overlay">
                                               {(() => {
                                                 const rowKey = BEAT_KEY(b.id);
                                                const showPause = intent?.key === rowKey
                                                   ? intent.playing
                                                       : (nowKey === rowKey && isPlaying);
                                                return showPause ? (
                                                       <svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true">
                                                             <path d="M6 5h4v14H6zM14 5h4v14h-4z"/>
                                                           </svg>
                                                     ) : (
                                                       <svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true">
                                                             <path d="M8 5v14l11-7z"/>
                                                           </svg>
                                                     );
                                               })()}
                                             </div>
                                        </div>

                                        {/* Meta */}
                                        <div className="meta">
                                            <div className="titleRow">
                                                <div className="title" title={b.title}>
                                                    {getSmartTitle(b.title)}
                                                </div>
                                                {/* could reuse your boardpill here or genre pill */}
                                                <span className="boardpill">{b.genre}</span>
                                            </div>

                                            <div className="sub">
                                                <UploaderLink userId={b.ownerId}>
                                                    {b.artistName || "Unknown Artist"}
                                                </UploaderLink>{" "}
                                                • {b.genre || "Drill"} • {Number(b.bpm || 0)} BPM • {formatDuration(b.durationInSeconds)}
                                            </div>

                                            <div className="tags">
                                                 {Array.isArray(b.tags) && b.tags.slice(0, 3).map(t => (
                                                   <span key={t} className="tag">{t}</span>
                                                 ))}
                                                 {Array.isArray(b.tags) && b.tags.length > 3 && (
                                                   <span className="tag tag--more">+{b.tags.length - 3}</span>
                                                 )}
                                            </div>
                                        </div>

                                        {/* Stats + Price */}
                                        <div className="stats">
                                            {/* More menu */}
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
                                                        <button className="moremenu__item" onClick={() => {
                                                            onShare(b);
                                                            setOpenMenuFor(null);
                                                        }}>
                                                            <IoShareOutline size={18}/> <span>Share</span>
                                                        </button>
                                                        <button className="moremenu__item" onClick={() => {
                                                            onGoToTrack(b);
                                                            setOpenMenuFor(null);
                                                        }}>
                                                            <IoOpenOutline size={18}/> <span>Go to track</span>
                                                        </button>
                                                        <button className="moremenu__item" onClick={() => {
                                                            onAddToPlaylist(b);
                                                            setOpenMenuFor(null);
                                                        }}>
                                                            <IoListOutline size={18}/> <span>Add to playlist</span>
                                                        </button>
                                                    </div>
                                                )}
                                            </div>

                                            {/* Stats pills */}
                                            <button className={`pill pill--clickable ${b.liked ? "is-active" : ""}`}
                                                    onClick={(e) => {
                                                        e.stopPropagation();
                                                        toggleLikeBeat(b);
                                                    }} aria-pressed={!!b.liked} title={b.liked ? "Unlike" : "Like"}>
                                                <IoHeartOutline/> {b.likeCount}
                                            </button>
                                            <button className="pill pill--clickable" onClick={(e) => {
                                                e.stopPropagation();
                                                onGoToComments(b);
                                            }} title="Open comments">
                                                <IoChatbubbleOutline/> {b.commentCount}
                                            </button>

                                            {/* Price button */}
                                            <button className="price-btn" onClick={(e) => {
                                                e.stopPropagation();
                                                onBuy(b);
                                            }}>
                                                <IoCartOutline size={18} style={{marginRight: "6px"}}/>
                                                ${Number(b.price || 0).toFixed(2)}
                                            </button>
                                        </div>
                                    </li>
                                );
                            })}
                        </ul>
                    )}

                    {/*pagination*/}
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
                            disabled={!hasMore}
                            className="tp-pagebtn tp-nextbtn"
                        >
                            →
                        </button>
                    </div>
                </section>

                {/* right: sticky filters */}
                {/* Desktop / tablet: sticky sidebar */}
                <aside className="stylepage__aside">
                    <div className="asidecard asidecard--sticky">
                        <h3 className="asidecard__title">Filters</h3>
                        <FiltersForm
                            bpm={bpm} setBpm={setBpm}
                            price={price} setPrice={setPrice}
                            sort={sort} setSort={setSort}
                            onReset={() => {
                                setQ("");
                                setBpm([30, 200]);
                                setPrice([0, 200]);
                                setSort("relevance");
                            }}
                        />
                    </div>
                </aside>


                {/* Mobile: full‑screen sheet */}
                <div
                    id="filtersSheet"
                    className={`filtersSheet ${filtersOpen ? "is-open" : ""}`}
                    role="dialog"
                    aria-modal="true"
                    aria-labelledby="filtersSheetTitle"
                >
                    <div className="filtersSheet__backdrop" onClick={() => setFiltersOpen(false)}/>
                    <div className="filtersSheet__panel">
                        <div className="filtersSheet__head">
                            <h3 id="filtersSheetTitle">Filters</h3>
                            <button className="filtersSheet__close" onClick={() => setFiltersOpen(false)}
                                    aria-label="Close">✕
                            </button>
                        </div>

                        <div className="filtersSheet__body">
                            <FiltersForm
                                bpm={bpm} setBpm={setBpm}
                                price={price} setPrice={setPrice}
                                sort={sort} setSort={setSort}
                                onReset={() => {
                                    setQ("");
                                    setBpm([30, 200]);
                                    setPrice([0, 200]);
                                    setSort("relevance");
                                }}
                            />
                        </div>

                        <div className="filtersSheet__foot">
                            <button className="filter__reset" onClick={() => {
                                setQ("");
                                setBpm("any");
                                setPrice("any");
                                setMood("any");
                                setSort("relevance");
                            }}>
                                Reset
                            </button>
                            <button className="filtersSheet__apply" onClick={() => setFiltersOpen(false)}>
                                Apply
                            </button>
                        </div>
                    </div>
                </div>
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
                        id: `beat-${selectedTrack.id}-${license.type}`,
                        type: "beat",
                        beatId: selectedTrack.id,
                        title: selectedTrack.title,
                        img: selectedTrack.albumCoverUrl || "",
                        licenseType: license.type,
                        licenseName: license.name,
                        price: Number(license.price || 0),
                        qty: 1,
                    };
                    if (action === "addToCart") add(item);
                    else if (action === "buyNow") {
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

function FiltersForm({ bpm, setBpm, price, setPrice, sort, setSort, onReset }) {
    return (
        <>
            <div className="filter__label">
                <div className="filter__label-text">BPM</div>
                <div className="range">
                    <input
                        type="range"
                        min={30}
                        max={200}
                        step={1}
                        value={bpm[0]}
                        onChange={(e) => setBpm([+e.target.value, bpm[1]])}
                    />
                    <input
                        type="range"
                        min={30}
                        max={200}
                        step={1}
                        value={bpm[1]}
                        onChange={(e) => setBpm([bpm[0], +e.target.value])}
                    />
                    <div className="range__labels"><span>{bpm[0]}</span><span>{bpm[1]}</span></div>
                </div>
            </div>

            <div className="filter__label">
                <div className="filter__label-text">Price</div>
                <div className="range">
                    <input
                        type="range"
                        min={0}
                        max={200}
                        step={1}
                        value={price[0]}
                        onChange={(e) => setPrice([+e.target.value, price[1]])}
                    />
                    <input
                        type="range"
                        min={0}
                        max={200}
                        step={1}
                        value={price[1]}
                        onChange={(e) => setPrice([price[0], +e.target.value])}
                    />
                    <div className="range__labels"><span>${price[0]}</span><span>${price[1]}</span></div>
                </div>
            </div>

            <label className="filter__label">
                Sort by
                <select className="filter__select" value={sort} onChange={(e) => setSort(e.target.value)}>
                    <option value="relevance">Relevance</option>
                    <option value="newest">Newest</option>
                    <option value="priceLow">Price: Low → High</option>
                    <option value="priceHigh">Price: High → Low</option>
                    <option value="bpmLow">BPM: Low → High</option>
                    <option value="bpmHigh">BPM: High → Low</option>
                </select>
            </label>

            <button className="filter__reset" type="button" onClick={onReset}>
                Reset filters
            </button>
        </>
    );
}