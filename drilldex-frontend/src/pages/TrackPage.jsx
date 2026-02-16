// src/pages/TrackPage.jsx
import "./trackpage.css";
import {useEffect, useMemo, useState} from "react";
import {useParams, useNavigate} from "react-router-dom";
import {
    IoCartOutline,
    IoPlay,
    IoHeartOutline,
    IoAdd,
    IoEllipsisHorizontal,
    IoChatbubbleOutline,
    IoCheckmarkCircleOutline
} from "react-icons/io5";
import {useRef} from "react";
import api from "../lib/api";
import {useCart} from "../state/cart.jsx";
import {
    getBeatComments,
    postBeatComment,
    getPackComments,
    postPackComment,
    getKitComments,
    postKitComment
} from "../lib/api";
import {useLocation} from "react-router-dom";
import {toast} from "react-hot-toast";
import UploaderLink from "../components/UploaderLink.jsx";


export default function TrackPage() {
    const [comments, setComments] = useState([]);
    const [commentsCursor, setCommentsCursor] = useState(null);
    const [commentsLoading, setCommentsLoading] = useState(false);
    const [postingComment, setPostingComment] = useState(false);
    const commentsRef = useRef(null);
    const [newComment, setNewComment] = useState("");
    const {slug} = useParams();
    const navigate = useNavigate();
    const [item, setItem] = useState(null);      // beat or pack
    const [kind, setKind] = useState("BEAT");    // "BEAT" | "PACK"
    const [contents, setContents] = useState([]); // pack contents (for UI)
    const [selectedIdx, setSelectedIdx] = useState(0);
    const [licenses, setLicenses] = useState([]);
    const selected = licenses[selectedIdx] || null;
    const [loading, setLoading] = useState(true);
    const [err, setErr] = useState(null);
    const [isPlaying, setIsPlaying] = useState(false);
    const [nowKey, setNowKey] = useState(null);
    const playedOnce = useRef(false); // increment play only once
    const {add} = useCart();
    const previewCache = new Map();
    const [currentPackIndex, setCurrentPackIndex] = useState(-1);
    const packPlaylistRef = useRef(null);
    const [currentKitIndex, setCurrentKitIndex] = useState(-1);
    const kitPlaylistRef = useRef(null);
    const BEAT_KEY = (id) => `beat:${id}`;
    const KIT_KEY = (id) => `kit:${id}`;
    const PACK_KEY = (id) => `pack:${id}`;
    const isBeat = kind === "BEAT";
    const isPack = kind === "PACK";
    const isKit = kind === "KIT";
    const location = useLocation();
    const CATALOG = useMemo(() => ({
        MP3: {
            id: "MP3",
            name: "MP3 Lease",
            files: ["MP3"],
            features: [
                "Distribute up to 2,500 copies",
                "Online audio streams up to 50,000",
                "1 music video",
                "Credit required (Prod. by Artist)",
            ],
        },
        WAV: {
            id: "WAV",
            name: "WAV Lease",
            files: ["MP3", "WAV"],
            features: [
                "Distribute up to 7,500 copies",
                "Online audio streams up to 100,000",
                "1 radio station",
                "Up to 200 paid performances",
                "Credit required (Prod. by Artist)",
            ],
        },
        PREMIUM: {
            id: "PREMIUM",
            name: "Premium (WAV + Stems)",
            files: ["MP3", "WAV", "STEMS"],
            features: [
                "Distribute up to 500,000 copies",
                "Online audio streams up to 1,000,000",
                "Unlimited music videos",
                "Radio broadcasting rights (unlimited stations)",
                "For-profit live performances",
            ],
        },
        EXCLUSIVE: {
            id: "EXCLUSIVE",
            name: "Exclusive Rights",
            files: ["MP3", "WAV", "STEMS", "PROJECT (if available)"],
            features: [
                "Unlimited copies",
                "Unlimited online audio streams",
                "Unlimited music videos",
                "Transferable rights per contract",
                "Beat removed from store",
            ],
        },
    }), []);


    async function ensurePackPlaylist() {
        if (packPlaylistRef.current && Array.isArray(packPlaylistRef.current) && packPlaylistRef.current.length) {
            return packPlaylistRef.current;
        }
        const {data} = await api.get(`/packs/${item.id}/preview-playlist`);
        const list = mapPreviewItemsToPlaylist(data, item.coverUrl || item.imageUrl);
        packPlaylistRef.current = list;
        return list;
    }

    function normalizeCommentResponse(res) {
        if (Array.isArray(res)) return {items: res, nextCursor: null, total: res.length};
        const items = Array.isArray(res?.items) ? res.items : [];
        const nextCursor = res?.nextCursor ?? null;
        const total = typeof res?.total === "number" ? res.total : items.length;
        return {items, nextCursor, total};
    }

    function getUserBasics(u) {
        if (!u) return {name: "User", avatarUrl: null, userId: null};
        const name = u.name || u.displayName || u.username || u.email || "User";
        const userId = u.id ?? u.userId ?? u.ownerId ?? null;
        const avatarUrl = u.avatarUrl || u.avatar || null;
        return {name, avatarUrl, userId};
    }

    async function ensureKitPlaylist() {
        if (kitPlaylistRef.current && Array.isArray(kitPlaylistRef.current) && kitPlaylistRef.current.length) {
            return kitPlaylistRef.current;
        }
        const {data} = await api.get(`/kits/${item.id}/preview-playlist`);
        const list = mapPreviewItemsToPlaylist(data, item.coverUrl || item.imageUrl);
        kitPlaylistRef.current = list;
        return list;
    }

    async function playKitIndex(index) {
        if (!item?.id || kind !== "KIT") return;

        const key = KIT_KEY(item.id);
        const list = await ensureKitPlaylist();
        if (!list.length) return;

        // toggle pause/resume on same row
        if (nowKey === key && currentKitIndex === index) {
            document.dispatchEvent(new CustomEvent(isPlaying ? "audio:pause" : "audio:resume"));
            setIsPlaying(!isPlaying);
            return;
        }

        document.dispatchEvent(new CustomEvent("audio:play-list", {
            detail: {list, index, sourceKey: key}
        }));
        setNowKey(key);
        setIsPlaying(true);
        setCurrentKitIndex(index);
    }

    useEffect(() => {
        window.scrollTo({top: 0, left: 0, behavior: "auto"});
    }, [location.pathname]);

    async function playPackIndex(index) {
        if (!item?.id || kind !== "PACK") return;

        const key = PACK_KEY(item.id);
        const list = await ensurePackPlaylist();
        if (!list.length) return;

        // If same list & same index — toggle play/pause
        if (nowKey === key && currentPackIndex === index) {
            document.dispatchEvent(new CustomEvent(isPlaying ? "audio:pause" : "audio:resume"));
            setIsPlaying(!isPlaying);
            return;
        }

        document.dispatchEvent(new CustomEvent("audio:play-list", {
            detail: {list, index, sourceKey: key}
        }));
        setNowKey(key);
        setIsPlaying(true);
        setCurrentPackIndex(index);
    }

    function scrollToComments() {
        commentsRef.current?.scrollIntoView({behavior: "smooth", block: "start"});
    }


    const kindToPath = (k) => {
        const s = String(k || "").toUpperCase();
        if (s === "BEAT") return "beats";
        if (s === "PACK") return "packs";
        if (s === "KIT") return "kits";
        return "beats";
    };

    async function fetchLiked(kind, id) {
        const path = kindToPath(kind);
        try {
            const {data} = await api.get(`/${path}/${id}/liked`);
            return Boolean(data?.liked ?? data);
        } catch {
            return false;
        }
    }

    async function setLiked(kind, id, liked) {
        const path = kindToPath(kind);
        if (liked) {
            await api.post(`/${path}/${id}/like`);
        } else {
            await api.delete(`/${path}/${id}/like`);
        }
    }

    const clamp0 = (n) => (n < 0 ? 0 : n);


    async function loadComments(cursor = null) {
        if (!item?.id) return;
        setCommentsLoading(true);
        try {
            const fetcher =
                kind === "PACK" ? getPackComments :
                    kind === "KIT" ? getKitComments :
                        getBeatComments;
            const raw = await fetcher(item.id, {cursor, limit: 20});
            const {items, nextCursor, total} = normalizeCommentResponse(raw);
            setComments(prev =>
                cursor ? [...(Array.isArray(prev) ? prev : []), ...items] : items
            );
            setCommentsCursor(nextCursor);
            if (typeof total === "number") {
                setItem(prevItem => prevItem ? {...prevItem, commentCount: total} : prevItem);
            }
        } catch {
            toast.error("Couldn't load comments.");
        } finally {
            setCommentsLoading(false);
        }
    }

    useEffect(() => {
        if ((kind === "BEAT" || kind === "PACK" || kind === "KIT") && item?.id) {
            loadComments(null); // initial page
        } else {
            // clear when navigating to packs/kits or before item is loaded
            setComments([]);
            setCommentsCursor(null);
        }
    }, [kind, item?.id]);


    useEffect(() => {
        let alive = true;
        setLoading(true);
        setErr(null);

        (async () => {
            try {
                // 1) Try BEAT by slug
                try {
                    const res = await api.get(`/beats/by-slug/${encodeURIComponent(slug)}`);
                    if (!alive) return;
                    setItem(res.data);
                    setKind("BEAT");
                    setContents([]);

                    try {
                        const liked = await fetchLiked("BEAT", res.data.id);
                        if (alive) setItem(p => (p ? {...p, __liked: liked} : p));
                    } catch {
                    }

                    // licenses for beat
                    try {
                        const lres = await api.get(`/beats/${res.data.id}/licenses`);
                        if (alive) {
                            const list = Array.isArray(lres.data) ? lres.data : [];
                            const normalized = list
                                .map(row => {
                                    const t = String(row.type || "").toUpperCase();
                                    const meta = CATALOG[t];
                                    if (!meta) return null;
                                    return {
                                        backendId: row.id,
                                        id: meta.id,
                                        type: t,
                                        name: meta.name,
                                        files: meta.files,
                                        features: meta.features,
                                        price: Number(row.price ?? row.amount ?? 0),
                                        featured: !!row.featured,
                                    };
                                })
                                .filter(Boolean)
                                .sort((a, b) => {
                                    const order = ["MP3", "WAV", "PREMIUM", "EXCLUSIVE"];
                                    return order.indexOf(a.type) - order.indexOf(b.type);
                                });
                            setLicenses(normalized);
                            setSelectedIdx(0);
                        }
                    } catch {
                        if (alive) setLicenses([]);
                    }

                } catch (errBeat) {
                    // 2) Fallback: PACK by slug
                    try {
                        const pres = await api.get(`/packs/by-slug/${encodeURIComponent(slug)}`);
                        if (!alive) return;
                        setItem(pres.data);
                        setKind("PACK");

                        try {
                            const liked = await fetchLiked("PACK", pres.data.id);
                            if (alive) setItem(p => (p ? {...p, __liked: liked} : p));
                        } catch {
                        }

                        // pack licenses
                        try {
                            const lres = await api.get(`/packs/${pres.data.id}/licenses`);
                            if (alive) {
                                const list = Array.isArray(lres.data) ? lres.data : [];
                                const normalized = list
                                    .map(row => {
                                        const t = String(row.type || "").toUpperCase();
                                        const meta = CATALOG[t];
                                        if (!meta) {
                                            return {
                                                backendId: row.id,
                                                id: row.id || t || "LICENSE",
                                                type: t,
                                                name: row.name || row.title || "License",
                                                files: Array.isArray(row.files) ? row.files : [],
                                                features: Array.isArray(row.features) ? row.features : [],
                                                price: Number(row.price ?? row.amount ?? 0),
                                                featured: !!row.featured,
                                            };
                                        }
                                        return {
                                            backendId: row.id,
                                            id: meta.id,
                                            type: t,
                                            name: meta.name,
                                            files: meta.files,
                                            features: meta.features,
                                            price: Number(row.price ?? row.amount ?? 0),
                                            featured: !!row.featured,
                                        };
                                    })
                                    .filter(Boolean);
                                setLicenses(normalized);
                                setSelectedIdx(0);
                            }
                        } catch {
                            if (alive) setLicenses([]);
                        }

                        // pack contents (for list display)
                        try {
                            const cres = await api.get(`/packs/${pres.data.id}/contents`);
                            if (alive) setContents(Array.isArray(cres.data) ? cres.data : []);
                        } catch {
                            if (alive) setContents([]);
                        }

                    } catch (err) {
                        // 3) Final fallback: KIT by slug
                        const kres = await api.get(`/kits/by-slug/${encodeURIComponent(slug)}`);
                        if (!alive) return;
                        setItem(kres.data);
                        setKind("KIT");

                        try {
                            const liked = await fetchLiked("KIT", kres.data.id);
                            if (alive) setItem(p => (p ? {...p, __liked: liked} : p));
                        } catch {
                        }

                        // kit licenses
                        try {
                            const lres = await api.get(`/kits/${kres.data.id}/licenses`);
                            if (alive) {
                                const list = Array.isArray(lres.data) ? lres.data : [];
                                const normalized = list
                                    .map(row => {
                                        const t = String(row.type || "").toUpperCase();
                                        const meta = CATALOG[t];
                                        if (!meta) {
                                            return {
                                                backendId: row.id,
                                                id: row.id || t || "LICENSE",
                                                type: t,
                                                name: row.name || row.title || "License",
                                                files: Array.isArray(row.files) ? row.files : [],
                                                features: Array.isArray(row.features) ? row.features : [],
                                                price: Number(row.price ?? row.amount ?? 0),
                                                featured: !!row.featured,
                                            };
                                        }
                                        return {
                                            backendId: row.id,
                                            id: meta.id,
                                            type: t,
                                            name: meta.name,
                                            files: meta.files,
                                            features: meta.features,
                                            price: Number(row.price ?? row.amount ?? 0),
                                            featured: !!row.featured,
                                        };
                                    })
                                    .filter(Boolean);
                                setLicenses(normalized);
                                setSelectedIdx(0);
                            }
                        } catch {
                            if (alive) setLicenses([]);
                        }

                        // kit contents (for list display)
                        try {
                            const cres = await api.get(`/kits/${kres.data.id}/contents`);
                            if (alive) setContents(Array.isArray(cres.data) ? cres.data : []);
                        } catch {
                            if (alive) setContents([]);
                        }
                    }
                }
            } catch (e) {
                if (alive) setErr(e);
            } finally {
                if (alive) setLoading(false);
            }
        })();

        return () => {
            alive = false;
        };
    }, [slug, CATALOG]);

    // Listen to AudioBar state changes
    useEffect(() => {
        const onState = (e) => {
            const d = e.detail || {};
            if (typeof d.playing === "boolean") setIsPlaying(d.playing);
            if (typeof d.sourceKey === "string") setNowKey(d.sourceKey);
        };
        document.addEventListener("audio:state", onState);
        return () => document.removeEventListener("audio:state", onState);
    }, []);

    async function toggleLike() {
        if (!item) return;
        const nextLiked = !item.__liked;

        // optimistic UI
        setItem(prev => prev ? {
            ...prev,
            __liked: nextLiked,
            likeCount: Math.max(0, (prev.likeCount ?? 0) + (nextLiked ? 1 : -1)),
        } : prev);

        try {
            await setLiked(kind, item.id, nextLiked); // uses POST /like or DELETE /like
            toast.success(nextLiked ? "successfully liked" : "successfully unliked");

            // inform other views (e.g., AudioBar) to sync
            document.dispatchEvent(new CustomEvent("likes:changed", {
                detail: {
                    kind: kindToPath(kind).slice(0, -1), // "beat" | "pack" | "kit"
                    id: String(item.id),
                    liked: nextLiked,
                    source: "TrackPage",
                }
            }));
        } catch {
            // revert on failure
            setItem(prev => prev ? {
                ...prev,
                __liked: !nextLiked,
                likeCount: Math.max(0, (prev.likeCount ?? 0) + (nextLiked ? -1 : 1)),
            } : prev);
            toast.error("Couldn't update like");
        }
    }

    useEffect(() => {
        const onLikeChanged = (e) => {
            const d = e.detail || {};
            if (!item) return;

            const thisKind = kindToPath(kind).slice(0, -1); // "beat" | "pack" | "kit"
            if (d.kind === thisKind && String(d.id) === String(item.id)) {
                setItem(prev => prev ? {
                    ...prev,
                    __liked: !!d.liked,
                    likeCount: Math.max(
                        0,
                        (prev.likeCount ?? 0) +
                        (d.liked ? (prev.__liked ? 0 : 1) : (prev.__liked ? -1 : 0))
                    ),
                } : prev);
            }
        };
        document.addEventListener("likes:changed", onLikeChanged);
        return () => document.removeEventListener("likes:changed", onLikeChanged);
    }, [item, kind]);


    // cache presigned preview URLs (beat-only)
    const getPreviewUrl = async (id, api) => {
        if (previewCache.has(id)) return previewCache.get(id);
        const {data} = await api.get(`/beats/${id}/preview-url`);
        const url = data?.url || "";
        if (url) previewCache.set(id, url);
        return url;
    };

    // map pack contents → player items
    const mapPreviewItemsToPlaylist = (arr, fallbackCover) =>
        (Array.isArray(arr) ? arr : []).map((t, i) => ({
            id: t.id ?? i,
            title: t.title,
            artistName: t.artistName || t.creatorName || "Unknown",
            albumCoverUrl: t.coverUrl || fallbackCover || "",
            audioUrl: t.previewUrl || "",
            durationInSeconds: t.durationInSeconds || 0,
        }));


    // const total = useMemo(() => {
    //     const lic = licenses?.[selectedIdx];
    //     return lic ? Number(lic.price || 0) : 0;
    // }, [licenses, selectedIdx]);

    const total = useMemo(() => {
        if (isKit) {
            // kits often have a single base price
            return Number(item?.price ?? item?.amount ?? 0);
        }
        const lic = licenses?.[selectedIdx];
        return lic ? Number(lic.price || 0) : 0;
    }, [isKit, item?.price, item?.amount, licenses, selectedIdx]);

    // ⬇️ Add these guards here
    if (loading) {
        return (
            <div className="trackpage">
                <div className="trackpage__inner" style={{padding: "60px 12px 0"}}>
                    <p className="tp-muted">Loading…</p>
                </div>
            </div>
        );
    }

    if (err) {
        return (
            <div className="trackpage">
                <div className="trackpage__inner" style={{padding: "60px 12px 0"}}>
                    <p className="tp-muted">We couldn’t load that track.</p>
                    <div className="tp-back">
                        <button onClick={() => navigate(-1)}>← Back</button>
                    </div>
                </div>
            </div>
        );
    }

    if (!item) return null;


    function addSelectedLicenseToCart() {
        if (!item) return;

        const cover = item.coverUrl || item.albumCoverUrl || "";
        const type = kind.toLowerCase();

        if (isKit && licenses.length === 0) {
            // ✅ Kits have no licenses, just add a clean numeric ID
            const price = Number(item.price ?? item.amount ?? 0);
            add({
                id: `kit:${item.id}`,
                type: "kit",
                kitId: item.id,           // ✅ ensures backend gets numeric kitId
                title: item.title,
                artist: item.creatorName ?? item.artistName ?? "Unknown",
                img: cover,
                price,
                qty: 1,
            });
            toast.success("Kit added to cart");
            return;
        }

        // ✅ For beats & packs (which do have licenses)
        const lic = licenses?.[selectedIdx];
        if (!lic) return;

        add({
            id: `${type}:${item.id}:${lic.type}`, // cleaner cart key
            type,
            beatId: type === "beat" ? item.id : undefined,
            packId: type === "pack" ? item.id : undefined,
            licenseType: lic.type,                // ✅ backend expects this
            title: item.title,
            artist: item.artistName ?? item.creatorName ?? "Unknown",
            img: cover,
            price: Number(lic.price || 0),
            qty: 1,
        });

        toast.success(`${type.charAt(0).toUpperCase() + type.slice(1)} added to cart`);
    }


    async function handlePlay() {
        if (!item?.id) return;

        if (kind === "BEAT") {
            const key = BEAT_KEY(item.id);
            if (nowKey === key) {
                document.dispatchEvent(new CustomEvent(isPlaying ? "audio:pause" : "audio:resume"));
                setIsPlaying(!isPlaying);
                return;
            }
            try {
                // Prefer preview URL for catalog track pages
                const {data} = await api.get(`/beats/${item.id}/preview-url`);
                const url = data?.url || "";
                if (!url) return;

                const list = [{
                    id: item.id,
                    title: item.title,
                    artistName: item.artistName || "Unknown",
                    albumCoverUrl: item.coverUrl || item.imageUrl || "",
                    audioUrl: url,
                }];

                document.dispatchEvent(new CustomEvent("audio:play-list", {
                    detail: {list, index: 0, sourceKey: key}
                }));
                setNowKey(key);
                setIsPlaying(true);
            } catch (e) {
                console.error("Beat preview failed:", e);
            }
            return;
        }

        if (kind === "PACK") {
            const key = PACK_KEY(item.id);
            if (nowKey === key) {
                document.dispatchEvent(new CustomEvent(isPlaying ? "audio:pause" : "audio:resume"));
                setIsPlaying(!isPlaying);
                return;
            }
            try {
                const {data} = await api.get(`/packs/${item.id}/preview-playlist`);
                const list = mapPreviewItemsToPlaylist(data, item.coverUrl || item.imageUrl);
                if (!list.length) return;
                document.dispatchEvent(new CustomEvent("audio:play-list", {
                    detail: {list, index: 0, sourceKey: key}
                }));
                setNowKey(key);
                setIsPlaying(true);
                setCurrentPackIndex(0);
            } catch (e) {
                console.error("Pack preview playlist failed:", e);
            }
            return;
        }

        if (kind === "KIT") {                          // ⬅️ NEW
            const key = KIT_KEY(item.id);
            if (nowKey === key) {
                document.dispatchEvent(new CustomEvent(isPlaying ? "audio:pause" : "audio:resume"));
                setIsPlaying(!isPlaying);
                return;
            }
            try {
                const {data} = await api.get(`/kits/${item.id}/preview-playlist`);
                const list = mapPreviewItemsToPlaylist(data, item.coverUrl || item.imageUrl);
                if (!list.length) return;
                document.dispatchEvent(new CustomEvent("audio:play-list", {
                    detail: {list, index: 0, sourceKey: key}
                }));
                setNowKey(key);
                setIsPlaying(true);
                setCurrentKitIndex(0);
            } catch (e) {
                console.error("Kit preview playlist failed:", e);
            }
            return;
        }

        // BEAT flow (unchanged)...
    }

    function onAudioEnded() {
        setIsPlaying(false);
    }

    const cover = item.coverUrl || item.albumCoverUrl || "";
    const created = item.createdAt
        ? new Date(item.createdAt).toLocaleDateString()
        : "-";

    const commentCount =
        item?.commentCount ??
        (Array.isArray(comments) ? comments.length : 0);


    return (
        <div className="trackpage">
            <div className="trackpage__inner">
                {/* Left column */}
                <aside className="tp-left">
                    <div className="tp-card">
                        <div className="tp-cover">
                            {cover && <img src={cover} alt={item.title}/>}
                            <button className="tp-play" onClick={handlePlay} aria-label={isPlaying ? "Pause" : "Play"}>
                                {(isPlaying && nowKey === (isPack ? PACK_KEY(item.id) : isKit ? KIT_KEY(item.id) : BEAT_KEY(item.id))) ? (
                                    <svg viewBox="0 0 24 24" width="26" height="26" aria-hidden="true">
                                        <path d="M6 5h4v14H6zM14 5h4v14h-4z" fill="currentColor"/>
                                    </svg>
                                ) : (
                                    <IoPlay size={26}/>
                                )}
                            </button>

                        </div>

                        <div className="tp-titleblock">
                            <h1 className="tp-title" title={item.title}>
                                {item.title}
                            </h1>
                            <div className="tp-artistline">
                               <span className="tp-artist">
  {(kind === "PACK" || kind === "KIT") && item.ownerId ? (
      <UploaderLink userId={item.ownerId}>
          {item.creatorName || item.artistName || "Unknown"}
      </UploaderLink>
  ) : item.artistName && item.ownerId ? (
      <UploaderLink userId={item.ownerId}>
          {item.artistName}
      </UploaderLink>
  ) : (
      (kind === "PACK" || kind === "KIT")
          ? (item.creatorName || item.artistName || "Unknown")
          : (item.artistName || "Unknown")
  )}
</span>
                                {item.verified && <span className="tp-verified" aria-label="Verified"/>}
                            </div>
                        </div>


                        <div className="tp-actions-row">
                            <button
                                className={`tp-action ${item.__liked ? "is-active" : ""}`}
                                title="Like"
                                onClick={toggleLike}
                                aria-pressed={item.__liked ? "true" : "false"}
                            >
                                <IoHeartOutline className="tp-action-icon"/>
                                <span className="tp-action-num">{(item.likeCount ?? 0).toLocaleString()}</span>
                            </button>

                            <button className="tp-action" title="Comments" onClick={scrollToComments}>
                                <IoChatbubbleOutline className="tp-action-icon"/>
                                <span className="tp-action-num">{commentCount.toLocaleString()}</span>
                            </button>


                            <button className="tp-action" title="Add">
                                <IoAdd className="tp-action-icon"/>
                                <span className="tp-action-num">Add</span>
                            </button>

                            <button className="tp-action" title="More">
                                <IoEllipsisHorizontal className="tp-action-icon"/>
                                <span className="tp-action-num">More</span>
                            </button>
                        </div>

                        <div className="tp-info">
                            <div className="tp-infotitle">Information</div>
                            <dl className="tp-infolist">
                                <dt>Published</dt>
                                <dd>{created}</dd>
                                {kind === "BEAT" && (
                                    <>
                                        <dt>BPM</dt>
                                        <dd>{item.bpm}</dd>
                                        {/*<dt>Key</dt>*/}
                                        {/*<dd>{item.key || "-"}</dd>*/}
                                    </>
                                )}
                                <dt>Plays</dt>
                                <dd>{item.playCount?.toLocaleString?.() ?? 0}</dd>
                            </dl>
                        </div>

                        <div className="tp-tags">
                            <div className="tp-infotitle">Tags</div>
                            <div className="tp-tagrow">
                                {(String(item.tags || "")
                                        .split(",")
                                        .map(s => s.trim())
                                        .filter(Boolean)
                                ).map((t) => (
                                    <span key={t} className="tp-tag">
                    {t}
                  </span>
                                ))}
                            </div>
                        </div>
                    </div>
                </aside>

                {/* Right column */}
                <section className="tp-right">
                    <div className="tp-card tp-rightcard">
                        <div className="tp-lic-header">
                            <h2>{isKit ? "Price" : "Licensing"}</h2>
                            <div className="tp-lic-cta">
                                <button className="tp-addcart" onClick={addSelectedLicenseToCart}>
                                    <IoCartOutline size={18}/>
                                    ${total.toFixed(2)}
                                </button>
                            </div>
                        </div>

                        {/*<div className="tp-licenses">*/}
                        {/*    {licenses.map((lic, i) => {*/}
                        {/*        const isSelected = selectedIdx === i;*/}
                        {/*        return (*/}
                        {/*            <button*/}
                        {/*                key={lic.name + i}*/}
                        {/*                className={`tp-license ${isSelected ? "is-selected" : ""}`}*/}
                        {/*                onClick={() => setSelectedIdx(i)}*/}
                        {/*            >*/}
                        {/*                <div className="tp-license-name">*/}
                        {/*                    {lic.name}*/}
                        {/*                    {lic.featured && <span className="tp-badge">★</span>}*/}
                        {/*                </div>*/}
                        {/*                <div className="tp-license-price">${Number(lic.price || 0).toFixed(2)}</div>*/}
                        {/*                <div className="tp-license-formats">*/}
                        {/*                    {Array.isArray(lic.formats) ? lic.formats.join(", ") : ""}*/}
                        {/*                </div>*/}
                        {/*            </button>*/}
                        {/*        );*/}
                        {/*    })}*/}
                        {/*</div>*/}

                        {/* Show license picker for Beats/Packs, or for Kits only if licenses exist */}
                        {((!isKit) || (isKit && licenses.length > 0)) && (
                            <div className="tp-licenses">
                                {licenses.map((lic, i) => {
                                    const isSelected = selectedIdx === i;
                                    return (
                                        <button
                                            key={lic.name + i}
                                            className={`tp-license ${isSelected ? "is-selected" : ""}`}
                                            onClick={() => setSelectedIdx(i)}
                                        >
                                            <div className="tp-license-name">
                                                {lic.name}
                                                {lic.featured && <span className="tp-badge">★</span>}
                                            </div>
                                            <div className="tp-license-price">
                                                ${Number(lic.price || 0).toFixed(2)}
                                            </div>
                                            <div className="tp-license-formats">
                                                {/* use files, not formats */}
                                                {Array.isArray(lic.files) ? lic.files.join(", ") : ""}
                                            </div>
                                        </button>
                                    );
                                })}
                            </div>
                        )}

                        {selected && (
                            <div className="tp-lic-details">
                                <div className="tp-lic-details-head">
                                    <div className="tp-lic-details-name">{selected.name}</div>
                                    <div
                                        className="tp-lic-details-price">${Number(selected.price || 0).toFixed(2)}</div>
                                </div>

                                <div className="tp-lic-details-sub">Usage Terms</div>

                                <ul className="tp-lic-perks">
                                    {selected.features.map((f, i) => (
                                        <li key={i} className="tp-lic-perk">
                                            <IoCheckmarkCircleOutline className="tp-perk-icon"/>
                                            <span>{f}</span>
                                        </li>
                                    ))}
                                </ul>

                                <div className="tp-lic-receives">
                                    You’ll receive:
                                    <div className="tp-lic-files">
                                        {selected.files.map((f) => (
                                            <span key={f} className="tp-chip tp-chip--file">{f}</span>
                                        ))}
                                    </div>
                                </div>
                            </div>
                        )}

                        {(kind === "PACK" || kind === "KIT") && (
                            <div className="tp-pack-contents">
                                <div className="tp-infotitle">Contents</div>
                                {contents.length === 0 ? (
                                    <div className="tp-muted">No items found in this {kind.toLowerCase()}.</div>
                                ) : (
                                    <ul className="tp-packlist">
                                        {contents.map((c, i) => {
                                            const isPack = kind === "PACK";
                                            const keyActive = isPack ? PACK_KEY(item.id) : KIT_KEY(item.id);
                                            const rowActive = isPack
                                                ? (nowKey === keyActive && currentPackIndex === i)
                                                : (nowKey === keyActive && currentKitIndex === i);

                                            const onRowClick = isPack
                                                ? () => playPackIndex(i)
                                                : () => playKitIndex(i);

                                            return (
                                                <li key={c.id || i} className="tp-packitem">
                                                    <div className="tp-packitem-left" onClick={onRowClick}
                                                         role="button">
                                                        <div className="tp-packitem-cover">
                                                            {(c.coverUrl || c.imageUrl) && (
                                                                <>
                                                                    <img src={c.coverUrl || c.imageUrl} alt={c.title}/>
                                                                    <div className="beat-card__overlay">
                                                                        {rowActive && isPlaying ? (
                                                                            <svg viewBox="0 0 24 24" aria-hidden="true">
                                                                                <path d="M6 5h4v14H6zM14 5h4v14h-4z"/>
                                                                            </svg>
                                                                        ) : (
                                                                            <svg viewBox="0 0 24 24" aria-hidden="true">
                                                                                <path d="M8 5v14l11-7z"/>
                                                                            </svg>
                                                                        )}
                                                                    </div>
                                                                </>
                                                            )}
                                                        </div>
                                                        <div className="tp-packitem-meta">
                                                            <div className="tp-packitem-title"
                                                                 title={c.title}>{c.title}</div>
                                                            <div
                                                                className="tp-packitem-sub">{c.artistName || c.creatorName || "Unknown"}</div>
                                                        </div>
                                                    </div>
                                                </li>
                                            );
                                        })}
                                    </ul>
                                )}
                            </div>
                        )}


                        <div className="tp-divider"/>

                        <div ref={commentsRef} className="tp-comments">
                            <h3>Comments</h3>

                            {Array.isArray(comments) && comments.length > 0 ? (
                                <ul className="tp-commentlist">
                                    {Array.isArray(comments) && comments.map((c, i) => {
                                        const {name, avatarUrl, userId} = getUserBasics(c.user);
                                        const age = c.ago ?? c.timeAgo ?? c.age ?? ""; // <-- from backend only


                                        return (
                                            <li key={c.id ?? i} className="tp-comment">
                                                <div className="tp-avatar" aria-hidden="true">
                                                    {avatarUrl ? (
                                                        <img
                                                            src={avatarUrl}
                                                            alt={name}
                                                            onError={(e) => {
                                                                e.currentTarget.src = "/img/avatar-fallback.png";
                                                            }}
                                                        />
                                                    ) : (
                                                        <span className="tp-initial">{name?.charAt(0) || "?"}</span>
                                                    )}
                                                </div>
                                                <div className="tp-commentbody">
                                                    <div className="tp-commenttop">
                                                <span className="tp-commentuser">
                {userId ? (
                    <UploaderLink userId={userId}>{name}</UploaderLink>
                ) : (
                    name
                )}
              </span>
                                                        <span className="tp-commentago">{age}</span>
                                                    </div>
                                                    <div className="tp-commenttext">{c.text}</div>
                                                </div>
                                            </li>
                                        );
                                    })}
                                </ul>
                            ) : (
                                <div className="tp-muted">No comments yet</div>
                            )}

                            {commentsCursor && (
                                <div style={{marginTop: 10}}>
                                    <button
                                        className="tp-sendbtn"
                                        disabled={commentsLoading}
                                        onClick={() => loadComments(commentsCursor)}
                                    >
                                        {commentsLoading ? "Loading…" : "Load more"}
                                    </button>
                                </div>
                            )}

                            <form
                                className="tp-commentinput"
                                onSubmit={(e) => {
                                    e.preventDefault();
                                    if (!newComment.trim()) return;
                                    if (kind !== "BEAT" && kind !== "PACK" && kind !== "KIT") return;


                                    const me = (() => {
                                        try {
                                            return JSON.parse(localStorage.getItem("me") || "{}");
                                        } catch {
                                            return {};
                                        }
                                    })();

                                    // Optimistic insert
                                    const tempId = `temp-${Date.now()}`;
                                    const optimistic = {
                                        id: tempId,
                                        text: newComment,
                                        ago: "just now",
                                        user: {
                                            name: me.displayName || me.name || "You",
                                            avatarUrl: me.avatarUrl || null
                                        },
                                        _optimistic: true,
                                    };

                                    setComments((prev) => [optimistic, ...prev]);
                                    setItem((prev) => (prev ? {
                                        ...prev,
                                        commentCount: (prev.commentCount || 0) + 1
                                    } : prev));
                                    const text = newComment;
                                    setNewComment("");
                                    setPostingComment(true);
                                    const poster =
                                        kind === "PACK" ? postPackComment :
                                            kind === "KIT" ? postKitComment :
                                                postBeatComment;
                                    poster(item.id, text)
                                        .then((created) => {
                                            // swap the optimistic temp comment with the real one
                                            setComments((prev) => {
                                                const copy = [...prev];
                                                const idx = copy.findIndex((c) => c.id === tempId);
                                                if (idx !== -1) copy[idx] = created;
                                                return copy;
                                            });
                                        })
                                        .catch(() => {
                                            // revert on error
                                            setComments((prev) => prev.filter((c) => c.id !== tempId));
                                            setItem((prev) => (prev ? {
                                                ...prev,
                                                commentCount: Math.max((prev.commentCount || 1) - 1, 0)
                                            } : prev));
                                            toast.error("Couldn't post comment.");
                                        })
                                        .finally(() => setPostingComment(false));
                                }}
                            >
                                <input
                                    placeholder="Add a comment..."
                                    value={newComment}
                                    onChange={(e) => setNewComment(e.target.value)}
                                />
                                <button type="submit" className="tp-sendbtn" title="Send" disabled={postingComment}>

                                    Send
                                </button>
                            </form>
                        </div>
                    </div>
                </section>
            </div>

            <div className="tp-back">
                <button onClick={() => navigate(-1)}>← Back</button>
            </div>
        </div>
    );
}