import {useMemo, useState, useEffect} from "react";
import {
    IoPlaySharp,
    IoEllipsisHorizontal,
    IoLogoInstagram,
    IoLogoYoutube,
    IoLogoFacebook,
    IoLogoSoundcloud,
    IoLogoTiktok,
    IoHeartOutline,
    IoChatbubbleOutline,
    IoCartOutline
} from "react-icons/io5";
import ShareModal from "../components/ShareModal.jsx";
import {FaXTwitter} from "react-icons/fa6";
import LicenseModal from "../components/LicenseModal.jsx";
import {useCart} from "../state/cart";
import {FaUserCircle} from "react-icons/fa";
import "./ProfilePage.css";
import {useNavigate} from "react-router-dom";
import api from "../lib/api";
import {IoShareOutline, IoOpenOutline, IoDownloadOutline, IoListOutline} from "react-icons/io5";
import {useParams, Navigate} from "react-router-dom";
import {useAuth} from "../state/auth.jsx";
import {useRef} from "react";
import {derivePriceIfPossible, hydratePrices} from "../lib/pricing";
import toast from "react-hot-toast";
import UploaderLink from "../components/UploaderLink.jsx";
import useIsMobile from "../hooks/useIsMobile.js";
import {queueBeatsList, queueSingleBeat, BEAT_KEY} from "../lib/playerQueue";

const BOARD_LABEL = {featured: "Featured", trending: "Trending", popular: "Popular", new: "New"};

export default function ProfilePage() {
    const {me} = useAuth();
    const navigate = useNavigate();
    const [tab, setTab] = useState("beats"); // beats | kits | packs | about
    const [openMenuFor, setOpenMenuFor] = useState(null);
    const [sortBy, setSortBy] = useState("all");
    const [profile, setProfile] = useState(null);
    const {id: routeId} = useParams();
    const isOwnProfile = routeId === "me" || (profile && me?.id === profile.id);
    const [playIntent, setPlayIntent] = useState(null);
    const [pageByTab, setPageByTab] = useState({beats: 0, kits: 0, packs: 0});
    const [totalPagesByTab, setTotalPagesByTab] = useState({beats: 1, kits: 1, packs: 1});
    const [hasMoreByTab, setHasMoreByTab] = useState({beats: false, kits: false, packs: false});
    const pageSize = 50;
    const fmtNum = (n) => new Intl.NumberFormat().format(Number(n || 0));
    const [totals, setTotals] = useState({plays: 0, tracks: 0});
    const emptyData = {sections: {featured: [], new: [], popular: [], trending: []}, all: []};
    const [beatsData, setBeatsData] = useState(emptyData);
    const [kitsData, setKitsData] = useState(emptyData);
    const [packsData, setPacksData] = useState(emptyData);
    const [shareOpen, setShareOpen] = useState(false);
    const [shareData, setShareData] = useState(null);
    const [loading, setLoading] = useState(true);
    const [licenseOpen, setLicenseOpen] = useState(false);
    const [licenseData, setLicenseData] = useState(null);
    const {add, clear} = useCart();
    const [isFollowing, setIsFollowing] = useState(false);
    const [followLoading, setFollowLoading] = useState(false);
    const [player, setPlayer] = useState({
        playing: false,
        sourceKey: "",
        trackId: null,
    });

    useEffect(() => {
        window.scrollTo({top: 0, left: 0, behavior: "auto"});
    }, [location.pathname]);

    function fmtTime(sec) {
        const m = Math.floor(sec / 60);
        const s = String(sec % 60).padStart(2, "0");
        return `${m}m ${s}s`;
    }

    const getKind = (resource) => {
        switch (resource) {
            case "beats":
                return "BEAT";
            case "kits":
                return "KIT";
            case "packs":
                return "PACK";
            default:
                return "UNKNOWN";
        }
    };

    const fmtMMSS = (sec) => {
        const s = Math.max(0, Number(sec || 0));
        const m = Math.floor(s / 60);
        const r = String(Math.floor(s % 60)).padStart(2, "0");
        return `${m}:${r}`;
    };

    const CDN_BASE = import.meta.env.VITE_S3_PUBLIC_BASE?.replace(/\/+$/, "") || "";
    const toFileUrl = (p) => {
        if (!p) return null;

        // ✅ Fix broken case: "/uploads/https://..."
        if (p.startsWith("/uploads/https://") || p.startsWith("/uploads/http://")) {
            p = p.replace(/^\/uploads\//, "");
        }

        // ✅ If already a full URL, return as-is
        if (p.startsWith("http://") || p.startsWith("https://")) return p;

        // ✅ Otherwise, treat as relative path
        const clean = p.replace(/^\/+/, "");
        return CDN_BASE ? `${CDN_BASE}/${clean}` : `/uploads/${clean}`;
    };

    useEffect(() => {
        const needs = (beatsData.all || []).filter(r => r.price == null && !r._hydratedPrice);
        if (!needs.length) return;

        let cancelled = false;
        const patchAll = (id, patchObj) => {
            if (cancelled) return;
            setBeatsData(prev => {
                const patchList = (list) => list.map(r => r.id === id ? {...r, ...patchObj, _hydratedPrice: true} : r);
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
            await hydratePrices("beats", needs, (id, p) => patchAll(id, p));
        })();

        return () => {
            cancelled = true;
        };
    }, [beatsData.all]);

    useEffect(() => {
        const needs = (packsData.all || []).filter(r => r.price == null && !r._hydratedPrice);
        if (!needs.length) return;

        let cancelled = false;
        const patchAll = (id, patchObj) => {
            if (cancelled) return;
            setPacksData(prev => {
                const patchList = (list) => list.map(r => r.id === id ? {...r, ...patchObj, _hydratedPrice: true} : r);
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
            await hydratePrices("packs", needs, (id, p) => patchAll(id, p));
        })();

        return () => {
            cancelled = true;
        };
    }, [packsData.all]);

    const toRow = (x, board, kind) => {
        const isBeatOrPack = kind === "BEAT" || kind === "PACK";
        const inline = isBeatOrPack ? derivePriceIfPossible(x) : null;

        // packs send tags as array; kits often send comma string
        const tags =
            Array.isArray(x.tags)
                ? x.tags
                : String(x.tags || "").split(",").map(t => t.trim()).filter(Boolean);

        return {
            id: x.id,
            slug: x.slug || String(x.id),
            board,
            kind,
            title: x.title,
            artist: x.artistName || x.artist || x.ownerName || x.ownerDisplayName || "",
            genre: x.genre,
            bpm: x.bpm,
            keySig: x.key || x.keySig, // optional
            duration: Number(x.durationInSeconds || 0),
            img: toFileUrl(x.coverUrl || x.albumCoverUrl || x.coverImagePath) || null,
            tags: tags.slice(0, 6),

            // pricing
            price: isBeatOrPack ? (inline ?? null)
                : (Number.isFinite(Number(x.price)) ? Number(x.price) : 0),


            playCount: Number(x.playCount || 0),
            likes: Number(x.likeCount ?? 0),
            liked: !!x.liked,
            comments: Number(x.commentCount ?? 0),

            featured: !!(x.featured || x.featuredAt || x.featuredFrom),
            isNew: !!(x.isNew ?? x.new),
            createdAt: x.createdAt ? Date.parse(x.createdAt) : (x.id || 0),
            year: x.year ?? (x.createdAt ? new Date(x.createdAt).getFullYear() : null),

            // ---- PACK extras (from backend) ----
            beatsCount: Number.isFinite(x.beatsCount) ? x.beatsCount : undefined,
            totalDurationSec:
                Number.isFinite(x.totalDurationSec) ? x.totalDurationSec : undefined,

            // ---- KIT extras (from backend) ----
            // e.g. "Drum Kit"
            type: x.type || undefined,
            samples: Number.isFinite(x.samples) ? x.samples : undefined,
            loops: Number.isFinite(x.loops) ? x.loops : undefined,
            presets: Number.isFinite(x.presets) ? x.presets : undefined,
        };
    };


    async function onToggleLike(trackId, isLiked) {
        try {
            const method = isLiked ? "delete" : "post";
            await api[method](`/beats/${trackId}/like`);

            // Update frontend
            setBeatsData(prev => {
                const toggle = (arr) => arr.map(t => {
                    if (t.id !== trackId) return t;
                    return {
                        ...t,
                        liked: !isLiked,
                        likes: t.likes + (isLiked ? -1 : 1)
                    };
                });
                return {
                    sections: {
                        featured: toggle(prev.sections.featured),
                        new: toggle(prev.sections.new),
                        popular: toggle(prev.sections.popular),
                        trending: toggle(prev.sections.trending),
                    },
                    all: toggle(prev.all),
                };
            });
        } catch (err) {
            const status = err?.response?.status;
            if (status === 401) toast.error("Please sign in to like beats.");
            else toast.error("Couldn't update like. Please try again.");
        }
    }

// --- helpers ---
    const dedupeById = (arr) => {
        const seen = new Set();
        return arr.filter(it => (seen.has(it.id) ? false : (seen.add(it.id), true)));
    };
    const trendingScore = (x) => (x.likes || 0) + (x.playCount || 0) * 0.5;

    async function toggleFollow() {
        console.log("Follow button clicked");
        if (!profile?.id || followLoading) return;

        setFollowLoading(true);

        try {
            let newCount = profile.followers;
            if (isFollowing) {
                const {data} = await api.delete(`/follow/${profile.id}`);
                setIsFollowing(false);
                newCount = data;
            } else {
                const {data} = await api.post(`/follow/${profile.id}`);
                setIsFollowing(true);
                newCount = data;
            }

            // 👇 update profile with the new follower count
            setProfile(prev => ({
                ...prev,
                followers: newCount
            }));
        } catch (err) {
            console.error("Failed to toggle follow", err);
        } finally {
            setFollowLoading(false);
        }
    }

    const toArr = (res) => {
        // Support both axios-style responses and already-unwrapped payloads
        const payload = res?.data ?? res;

        if (Array.isArray(payload)) return payload;
        if (Array.isArray(payload?.items)) return payload.items;
        if (Array.isArray(payload?.results)) return payload.results;

        // Some endpoints might nest one level deeper
        if (payload && typeof payload === "object") {
            for (const k of Object.keys(payload)) {
                const v = payload[k];
                if (Array.isArray(v)) return v;
                if (v && typeof v === "object" && Array.isArray(v.items)) return v.items;
            }
        }
        return [];
    };

    const buildState = (featured, news, popular, trending, anyRows = []) => ({
        sections: {featured, new: news, popular, trending},
        // include EVERYTHING (boards + full list), then dedupe
        all: dedupeById([
            ...featured,
            ...trending,
            ...popular,
            ...news,
            ...anyRows,
        ]),
    });

    function onShare(track) {
        setShareData({
            id: track.id,
            slug: track.slug || track.id,
            title: track.title,
            artistName: track.artist || "",
            albumCoverUrl: track.img || "",
        });
        setShareOpen(true);
    }

    const displayName = (u) =>
        u?.displayName || u?.username || u?.name || u?.email || `User #${u?.id}`;

    const onMessage = () => {
        if (!profile?.id) return;
        navigate(`/chat/${profile.id}`, {
            state: {recipientName: displayName(profile)},
        });
    };

    const goToNextPage = () => {
        setPageByTab(prev => ({...prev, [tab]: prev[tab] + 1}));
    };

    const goToPrevPage = () => {
        setPageByTab(prev => ({...prev, [tab]: Math.max(0, prev[tab] - 1)}));
    };

    useEffect(() => {
        setPageByTab(prev => ({...prev, [tab]: 0}));
    }, [tab]);

// --- data load (load only the active tab's resource) ---
// Add these states at the top of your component

    useEffect(() => {
        let alive = true;
        const controller = new AbortController();
        const cfg = {signal: controller.signal};

        const loadResource = async (resource, setter) => {
            const base = routeId === "me" ? "/me" : `/users/${routeId}`;
            const currentPage = pageByTab[tab];
            const pageSize = 50;

            try {
                // Fetch boards + approved/all in parallel
                const [featRes, newRes, popRes, trendRes, approvedRes] = await Promise.all([
                    api.get(`${base}/${resource}/featured`, {params: {limit: pageSize, page: currentPage}}),
                    api.get(`${base}/${resource}/new`, {params: {limit: pageSize, page: currentPage}}),
                    api.get(`${base}/${resource}/popular`, {params: {limit: pageSize, page: currentPage}}),
                    api.get(`${base}/${resource}/trending`, {params: {limit: pageSize, page: currentPage}}),
                    fetchAllForUser(base, resource, {pageSize, page: currentPage}) // fallback list
                ]);

                const mapRow = (x, board) => ({
                    ...toRow(x, board, getKind(resource)),
                    assignedTags: new Set(board ? [board] : [])
                });

                const allMap = new Map();
                [featRes, newRes, popRes, trendRes].forEach((res, idx) => {
                    const tag = ["featured", "new", "popular", "trending"][idx];
                    toArr(res).forEach(x => {
                        if (!allMap.has(x.id)) allMap.set(x.id, mapRow(x, tag));
                        else allMap.get(x.id).assignedTags.add(tag);
                    });
                });

                // Add approved / fallback items
                toArr(approvedRes).forEach(x => {
                    if (!allMap.has(x.id)) allMap.set(x.id, mapRow(x, null));
                });

                const allRows = Array.from(allMap.values());

                // Set state immediately
                setter({
                    sections: {
                        featured: allRows.filter(r => r.assignedTags.has("featured")),
                        new: allRows.filter(r => r.assignedTags.has("new")),
                        popular: allRows.filter(r => r.assignedTags.has("popular")),
                        trending: allRows.filter(r => r.assignedTags.has("trending")),
                    },
                    all: allRows
                });

                // Then hydrate prices asynchronously for beats/packs
                if (["beats", "packs"].includes(resource)) {
                    const needs = allRows.filter(r => r.price == null && !r._hydratedPrice);
                    if (needs.length) {
                        await hydratePrices(resource, needs, (id, patchObj) => {
                            setter(prev => {
                                const patchList = list => list.map(r => r.id === id ? {
                                    ...r, ...patchObj,
                                    _hydratedPrice: true
                                } : r);
                                return {
                                    sections: {
                                        featured: patchList(prev.sections.featured),
                                        new: patchList(prev.sections.new),
                                        popular: patchList(prev.sections.popular),
                                        trending: patchList(prev.sections.trending)
                                    },
                                    all: patchList(prev.all)
                                };
                            });
                        });
                    }
                }

            } catch (err) {
                console.error(`Failed to load ${resource}:`, err);
                if (alive) setter(emptyData);
            }
        };

        (async () => {
            setLoading(true);
            if (tab === "beats") await loadResource("beats", setBeatsData);
            if (tab === "kits") await loadResource("kits", setKitsData);
            if (tab === "packs") await loadResource("packs", setPacksData);
            if (alive) setLoading(false);
        })();

        return () => {
            alive = false;
            controller.abort();
        };
    }, [routeId, tab, pageByTab]);

    const menuRef = useRef(null);

    async function fetchAllForUser(base, resource, cfg, {pageSize = 100} = {}) {
        const approvedPath = `${base}/${resource}/approved`;
        const fallbackPath = `${base}/${resource}`;

        // 1) Try paginated /approved first
        try {
            let page = 0;
            const out = [];
            while (true) {
                const res = await api.get(approvedPath, {
                    ...cfg,
                    params: {limit: pageSize, page},
                });
                const chunk = toArr(res); // let toArr handle axios or plain payload
                if (!chunk.length) break;
                out.push(...chunk);
                if (chunk.length < pageSize) break; // last page
                page += 1;
            }
            if (out.length) return out;
        } catch (err) {
            const status = err?.response?.status;
            if (status && status !== 404) {
                console.warn(`[Profile] ${approvedPath} failed with ${status}`, err);
            }
        }

        // 2) Fallback: /users/:id/:resource — typically NOT paginated. Fetch once.
        try {
            const res = await api.get(fallbackPath, {...cfg, params: {limit: pageSize}});
            return toArr(res);
        } catch (err) {
            const status = err?.response?.status;
            if (status && status !== 404) {
                console.warn(`[Profile] ${fallbackPath} failed with ${status}`, err);
            }
        }

        return [];
    }

    useEffect(() => {
        function handleClickOutside(event) {
            if (menuRef.current && !menuRef.current.contains(event.target)) {
                setOpenMenuFor(null);
            }
        }

        function handleEsc(event) {
            if (event.key === "Escape") {
                setOpenMenuFor(null);
            }
        }

        document.addEventListener("mousedown", handleClickOutside);
        document.addEventListener("keydown", handleEsc);
        return () => {
            document.removeEventListener("mousedown", handleClickOutside);
            document.removeEventListener("keydown", handleEsc);
        };
    }, []);

    const addKitToCart = (kit) => {
        add({
            type: "kit",
            kitId: kit.id,
            title: kit.title,
            img: kit.img || kit.coverUrl || "",
            price: Number(kit.price || 0),
            qty: 1,
        });
    };


    useEffect(() => {
        let alive = true;
        const controller = new AbortController();
        const cfg = {signal: controller.signal};

        (async () => {
            try {
                const path = routeId === "me" ? "/me" : `/users/${routeId}`;
                const {data} = await api.get(path, cfg);

                if (routeId !== "me") {
                    try {
                        const {data: followData} = await api.get(`/follow/${routeId}/is-following`);
                        if (alive) setIsFollowing(followData === true);
                    } catch (e) {
                        console.warn("Could not check follow status", e);
                    }
                }

                if (!alive) return;

                const d = data || {};
                const av = d.avatarUrl || d.profilePicture || d.profilePicturePath || d.avatar || null;
                const bn = d.bannerUrl || d.bannerImage || d.bannerImagePath || d.banner || null;

                setProfile({
                    id: d.id || d.userId || null,
                    name: d.displayName || d.name || d.username || d.artistName || "",
                    role: d.tagline || d.title || d.role || "",
                    followers: d.followersCount ?? d.followerCount ?? d.followers ?? 0,
                    socials: {
                        instagram: d.instagram || d.instagramUrl || "",
                        twitter: d.twitter || d.twitterUrl || d.x || "",
                        youtube: d.youtube || d.youtubeUrl || "",
                        facebook: d.facebook || d.facebookUrl || "",
                        soundcloud: d.soundcloud || d.soundcloudUrl || "",
                        tiktok: d.tiktok || d.tiktokUrl || "",
                    },
                    avatar: av ? toFileUrl(av) : null,
                    banner: bn ? toFileUrl(bn) : null,
                    about: typeof d.bio === "string" ? d.bio : (d.about || ""),
                });

                setTotals({
                    plays: Number(d.totalPlays ?? d.totals?.plays ?? 0),
                    tracks: Number(d.trackCount ?? d.totals?.tracks ?? 0),
                });
            } catch (e) {
                console.error("Profile fetch failed:", e);
                setProfile((p) => p ?? {name: "", role: "", followers: 0, socials: {}});
                setTotals(prev => ({...prev, plays: prev.plays ?? 0, tracks: prev.tracks ?? 0}));
            }
        })();

        return () => {
            alive = false;
            controller.abort();
        };
    }, [routeId]);

    // --- mini player state (same pattern as Landing) ---
    const PACK_KEY = (id) => `pack:${id}`;
    const KIT_KEY = (id) => `kit:${id}`;
    const BEAT_KEY = (id) => `beat:${id}`;

    const playedOnce = useRef(new Set());
    const previewCache = useRef(new Map());

// keep in sync with global audio bar
    useEffect(() => {
        const onState = (e) => {
            const d = e.detail || {};
            setPlayer({
                playing: !!d.playing,
                sourceKey: d.sourceKey || "",
                trackId: d.trackId ?? d.current?.id ?? null, // fallback if old audio bar
            });

            // Clear playIntent once the global state matches what we optimistically showed
            setPlayIntent((prev) => {
                if (!prev) return null;
                // If the same source confirmed its playing state, clear
                if (prev.key === d.sourceKey && typeof d.playing === "boolean" && prev.playing === !!d.playing) return null;
                // For beats, trackId also identifies the source (beat:<id>)
                if (prev.key?.startsWith("beat:") && d.trackId != null && prev.key === `beat:${d.trackId}`) return null;
                return prev;
            });
        };

        document.addEventListener("audio:state", onState);
        document.dispatchEvent(new CustomEvent("audio:get-state"));
        return () => document.removeEventListener("audio:state", onState);
    }, []);

    function renderPageNumbers(currentPage, totalPages, setPageForTab) {
        const pageButtons = [];
        const maxPagesToShow = 7;
        const ellipsis = <span key="ellipsis" className="tp-ellipsis">…</span>;

        const createBtn = (p) => (
            <button
                key={p}
                onClick={() => setPageForTab(p)}
                className={`tp-pagebtn ${p === currentPage ? "active" : ""}`}
            >
                {p + 1}
            </button>
        );

        if (totalPages <= maxPagesToShow) {
            for (let i = 0; i < totalPages; i++) pageButtons.push(createBtn(i));
        } else {
            pageButtons.push(createBtn(0)); // first page

            if (currentPage > 3) pageButtons.push(ellipsis);

            const start = Math.max(1, currentPage - 1);
            const end = Math.min(totalPages - 2, currentPage + 1);
            for (let i = start; i <= end; i++) pageButtons.push(createBtn(i));

            if (currentPage < totalPages - 4) pageButtons.push(ellipsis);

            pageButtons.push(createBtn(totalPages - 1)); // last page
        }

        return pageButtons;
    }

// presigned beat preview
    async function getPreviewUrl(beatId) {
        if (previewCache.current.has(beatId)) return previewCache.current.get(beatId);
        const {data} = await api.get(`/beats/${beatId}/preview-url`); // -> { url }
        const url = data?.url;
        if (url) previewCache.current.set(beatId, url);
        return url;
    }

// kit/pack preview playlists (same shape you used on Landing)
    const mapPreviewItemsToPlaylist = (arr, cover) =>
        (Array.isArray(arr) ? arr : []).map((t, i) => ({
            id: t.id ?? i,
            title: t.title,
            artistName: t.artistName || "Unknown",
            albumCoverUrl: toFileUrl(t.coverUrl || cover),
            audioUrl: toFileUrl(t.previewUrl),
            durationInSeconds: t.durationInSeconds || 0,
        }));

    async function getPackPlaylist(packId, cover) {
        const {data} = await api.get(`/packs/${packId}/preview-playlist`);
        return mapPreviewItemsToPlaylist(data, cover);
    }

    async function getKitPlaylist(kitId, cover) {
        const {data} = await api.get(`/kits/${kitId}/preview-playlist`);
        return mapPreviewItemsToPlaylist(data, cover);
    }

    function hasLiveQueue(key) {
        return player.sourceKey === key && player.trackId != null;
    }

    // Beats: play or toggle a single beat row
    async function playBeat(tr) {
        const key = `profile:${tr.id}`; // <-- use consistent prefix here!

        // Simple resume/pause toggle
        if (player.sourceKey === key) {
            document.dispatchEvent(new CustomEvent(player.playing ? "audio:pause" : "audio:resume"));
            return;
        }

        // Build a list of all tracks (if needed)
        const fullList = sortedBeats.map((row) => ({
            id: row.id,
            title: row.title || "Untitled",
            artistName: row.artist || profile?.name || "Unknown",
            albumCoverUrl: row.img || profile?.avatar || "",
            audioUrl: row.audioUrl || "", // may be empty
            durationInSeconds: row.duration || 0,
        }));

        const index = fullList.findIndex((item) => item.id === tr.id);
        if (index === -1) return;

        // Hydrate preview URLs on demand
        if (!fullList[index].audioUrl) {
            try {
                const previewUrl = await getPreviewUrl(tr.id);
                fullList[index].audioUrl = previewUrl;
            } catch {
                console.warn("Failed to load preview URL");
                return;
            }
        }

        // Dispatch to AudioBar
        document.dispatchEvent(new CustomEvent("audio:play-list", {
            detail: {
                list: fullList,
                index,
                sourceKey: key,
            }
        }));
    }

// Kits: play/toggle kit preview playlist
    async function playKit(k) {
        const key = KIT_KEY(k.id);

        if (player.sourceKey === key) {
            setPlayIntent({key, playing: !player.playing});
            document.dispatchEvent(new CustomEvent(player.playing ? "audio:pause" : "audio:resume"));
            return;
        }

        setPlayIntent({key, playing: true});

        const list = await getKitPlaylist(k.id, k.img || k.coverUrl);
        if (!list.length) return;

        document.dispatchEvent(new CustomEvent("audio:play-list", {
            detail: {list, index: 0, sourceKey: key}
        }));

        // incrementPlay("kit", k.id);
    }

// Packs: play/toggle pack preview playlist
    async function playPack(p) {
        const key = PACK_KEY(p.id);

        if (player.sourceKey === key) {
            setPlayIntent({key, playing: !player.playing});
            document.dispatchEvent(new CustomEvent(player.playing ? "audio:pause" : "audio:resume"));
            return;
        }

        setPlayIntent({key, playing: true});

        const list = await getPackPlaylist(p.id, p.img || p.coverUrl);
        if (!list.length) return;

        document.dispatchEvent(new CustomEvent("audio:play-list", {
            detail: {list, index: 0, sourceKey: key}
        }));

        // incrementPlay("pack", p.id);
    }


// --- sorting (per tab) ---
    const makeSorter = (data) => {
        const TAG_PRIORITY = {
            featured: 4,
            popular: 3,
            trending: 2,
            new: 1,
            "": 0,
        };

        switch (sortBy) {
            case "all": {
                const full = dedupeById([
                    ...data.sections.featured,
                    ...data.sections.popular,
                    ...data.sections.trending,
                    ...data.sections.new,
                    ...data.all,
                ]);

                return full.slice().sort((a, b) => {
                    const aTag = TAG_PRIORITY[a.board] || 0;
                    const bTag = TAG_PRIORITY[b.board] || 0;
                    if (bTag !== aTag) return bTag - aTag;

                    const scoreA = (a.likes || 0) * 10 + (a.playCount || 0);
                    const scoreB = (b.likes || 0) * 10 + (b.playCount || 0);
                    if (scoreB !== scoreA) return scoreB - scoreA;

                    return Number(b.createdAt || 0) - Number(a.createdAt || 0);
                });
            }
            case "featured":
                return data.sections.featured;
            case "trending":
                return data.sections.trending;
            case "popular":
                return data.sections.popular;
            case "new":
                return data.sections.new;
            case "priceAsc":
                return dedupeById(data.all).slice().sort((a, b) => (a.price ?? 0) - (b.price ?? 0));
            case "priceDesc":
                return dedupeById(data.all).slice().sort((a, b) => (b.price ?? 0) - (a.price ?? 0));
            default:
                return data.all;
        }
    };


    const sortedBeats = useMemo(() => makeSorter(beatsData), [beatsData, sortBy]);
    const sortedKits = useMemo(() => makeSorter(kitsData), [kitsData, sortBy]);
    const sortedPacks = useMemo(() => makeSorter(packsData), [packsData, sortBy]);

    const activeData = useMemo(() => (
        tab === "beats" ? beatsData :
            tab === "kits" ? kitsData :
                packsData
    ), [tab, beatsData, kitsData, packsData]);

    const sortedTracks = sortedBeats;


    const onGoToItem = (item) => {
        if (!item) return;
        if (item.kind === "KIT" && item.slug) {
            navigate(`/kit/${item.slug}`);
        } else if (item.kind === "PACK" && item.slug) {
            navigate(`/pack/${item.slug}`);
        } else {
            console.warn("Missing kind or slug on item:", item);
        }
    };

    const membership = useMemo(() => ({
        featured: new Set(activeData.sections.featured.map(x => x.id)),
        trending: new Set(activeData.sections.trending.map(x => x.id)),
        popular: new Set(activeData.sections.popular.map(x => x.id)),
        new: new Set(activeData.sections.new.map(x => x.id)),
    }), [activeData]);

    const badgeFor = (id) => {
        // If user picked a category, show THAT badge when the item belongs to it
        if (["featured", "popular", "trending", "new"].includes(sortBy)) {
            if (membership[sortBy]?.has(id)) return sortBy;
        }
        // For "All" or price sorts, fall back to a sensible priority
        if (membership.featured.has(id)) return "featured";
        if (membership.popular.has(id)) return "popular";
        if (membership.trending.has(id)) return "trending";
        if (membership.new.has(id)) return "new";
        return null;
    };


    const isMobile = useIsMobile();

    const getSmartTitle = (title) => {
        return title || "";
    };


    return (
        <main className="profile">
            {/* Banner */}
            <div className="profile__banner">
                {profile?.banner ? (
                    <img src={profile.banner} alt=""/>
                ) : (
                    <div className="profile__bannerPh" aria-label="No banner"/>
                )}
            </div>

            <div className="profile__wrap">
                {/* Header row: avatar + meta + actions */}
                <header className="profile__header">
                    <div className="profile__avatarWrap">
                        {profile?.avatar ? (
                            <img
                                className="profile__avatar"
                                src={profile.avatar}
                                alt={profile?.name || "Artist"}
                            />
                        ) : (
                            <FaUserCircle className="profile__avatar profile__avatar--placeholder"/>
                        )}
                    </div>

                    <div className="profile__meta">
                        <h1 className="profile__name">
                            <UploaderLink userId={profile?.id} noUnderline>
                                {profile?.name || "Artist"}
                            </UploaderLink>
                        </h1>


                        <div className="profile__statsRow">
                            {typeof profile?.followers === "number" && (
                                <span className="profile__statChip">
      <strong>{fmtNum(profile.followers)}</strong> Followers
    </span>
                            )}
                            <span className="profile__statChip">
    <strong>{fmtNum(totals.plays)}</strong> Plays
  </span>
                            <span className="profile__statChip">
    <strong>{fmtNum(totals.tracks)}</strong> Tracks
  </span>
                        </div>

                        <div className="profile__socials" aria-label="Social links">
                            {profile?.socials?.instagram && (
                                <a href={profile.socials.instagram} target="_blank" rel="noreferrer"
                                   aria-label="Instagram" className="profile__social">
                                    <IoLogoInstagram size={20}/>
                                </a>
                            )}
                            {profile?.socials?.twitter && (
                                <a href={profile.socials.twitter} target="_blank" rel="noreferrer" aria-label="Twitter"
                                   className="profile__social">
                                    <FaXTwitter size={20}/>
                                </a>
                            )}
                            {profile?.socials?.youtube && (
                                <a href={profile.socials.youtube} target="_blank" rel="noreferrer" aria-label="YouTube"
                                   className="profile__social">
                                    <IoLogoYoutube size={20}/>
                                </a>
                            )}
                            {profile?.socials?.facebook && (
                                <a href={profile.socials.facebook} target="_blank" rel="noreferrer"
                                   aria-label="Facebook" className="profile__social">
                                    <IoLogoFacebook size={20}/>
                                </a>
                            )}
                            {profile?.socials?.soundcloud && (
                                <a href={profile.socials.soundcloud} target="_blank" rel="noreferrer"
                                   aria-label="SoundCloud" className="profile__social">
                                    <IoLogoSoundcloud size={20}/>
                                </a>
                            )}
                            {profile?.socials?.tiktok && (
                                <a href={profile.socials.tiktok} target="_blank" rel="noreferrer" aria-label="TikTok"
                                   className="profile__social">
                                    <IoLogoTiktok size={20}/>
                                </a>
                            )}
                        </div>
                    </div>

                    {!isOwnProfile && (
                        <div className="profile__actions">
                            <button className="profile__btn profile__btn--ghost"
                                    onClick={onMessage}
                                    disabled={!profile?.id}
                                    title={profile?.id ? `Message ${displayName(profile)}` : "No user"}
                            >Message
                            </button>
                            <button
                                className={`profile__btn ${isFollowing ? "profile__btn--ghost" : "profile__btn--primary"}`}
                                onClick={toggleFollow}
                                disabled={followLoading}
                            >
                                {isFollowing ? "Following" : "Follow"}
                            </button>
                        </div>
                    )}
                </header>

                {/* Tabs */}
                <nav className="profile__tabs" role="tablist" aria-label="Profile sections">
                    {[
                        {id: "beats", label: "Beats"},
                        {id: "kits", label: "Kits"},
                        {id: "packs", label: "Packs"},
                        {id: "about", label: "About"},
                    ].map((t) => (
                        <button
                            key={t.id}
                            role="tab"
                            aria-selected={tab === t.id}
                            className={`profile__tab ${tab === t.id ? "is-active" : ""}`}
                            onClick={() => setTab(t.id)}
                        >
                            {t.label}
                        </button>
                    ))}
                </nav>

                {/* Panels */}
                <section className="profile__panel">
                    {tab === "beats" && (
                        <>
                            <div className="profile__filterBar">
                                <label className="profile__sort">
                                    <span>Sort by</span>
                                    <select
                                        value={sortBy}
                                        onChange={(e) => setSortBy(e.target.value)}
                                        aria-label="Sort beats"
                                        disabled={loading}
                                    >
                                        <option value="all">All</option>
                                        <option value="featured">Featured</option>
                                        <option value="trending">Trending</option>
                                        <option value="popular">Popular</option>
                                        <option value="new">New</option>
                                        <option value="priceAsc">Price ↑</option>
                                        <option value="priceDesc">Price ↓</option>
                                    </select>
                                </label>
                            </div>

                            {!loading && sortedTracks.length === 0 ? (
                                <div className="list__empty" role="status" aria-live="polite">
                                    No beats uploaded yet
                                </div>
                            ) : (
                                <ul className="profileCharts__feed">
                                    {sortedTracks.map((tr, idx) => {
                                        const rowKey = `beat:${tr.id}`;
                                        return (
                                            <li
                                                key={rowKey}
                                                className={`profileChartrow ${tr.board === "featured" ? "profileChartrow--featured" : ""}`}
                                            >
                                                <span className="profileRank">{idx + 1}</span>

                                                {/*<div className="profileCoverWrap">*/}
                                                {/*    {tr.img ? (*/}
                                                {/*        <img src={tr.img} alt={tr.title}/>*/}
                                                {/*    ) : (*/}
                                                {/*        <div className="profileCoverWrap__ph" aria-label="No cover"/>*/}
                                                {/*    )}*/}
                                                {/*</div>*/}

                                                <div className="profileCoverWrap">
                                                    {tr.img ? <img src={tr.img} alt={tr.title}/> :
                                                        <div className="profileCoverWrap__ph" aria-label="No cover"/>}
                                                    <button
                                                        className="coverWrap__playbtn"
                                                        aria-label="Play/Pause"
                                                        onClick={(e) => {
                                                            e.stopPropagation();
                                                            playBeat(tr);
                                                        }}
                                                    >
                                                        {(() => {
                                                            const key = BEAT_KEY(tr.id);
                                                            const showPause = playIntent?.key === key
                                                                ? playIntent.playing
                                                                : (player.playing && (player.trackId === tr.id || player.sourceKey === key));

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
                                                    </button>
                                                </div>

                                                <div className="profileMeta">
                                                    <div className="profileTitleRow">
                                                        <div className="profileTitle" title={tr.title}>
                                                            {getSmartTitle(tr.title)}
                                                        </div>
                                                        <div className="boardpillRow">
                                                            {(() => {
                                                                const b = badgeFor(tr.id);
                                                                return b ? (
                                                                    <span className={`boardpill boardpill--${b}`}>
          {BOARD_LABEL[b] || b}
        </span>
                                                                ) : null;
                                                            })()}
                                                        </div>
                                                    </div>

                                                    <div className="profileSub">
                                                        {tr.genre ? `${tr.genre} • ` : ""}
                                                        {tr.bpm} BPM
                                                        {tr.keySig ? ` • ${tr.keySig}` : ""}
                                                        {" • "}
                                                        {fmtTime(tr.duration)}
                                                    </div>

                                                    <div className="profileTags">
                                                        {tr.tags.map((t) => (
                                                            <span key={`${tr.id}-tag-${t}`} className="tag">{t}</span>
                                                        ))}
                                                    </div>
                                                </div>

                                                {/* right actions */}
                                                <div className="profileStats">
                                                    <div className="moremenu__wrap">
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
                                                                <svg viewBox="0 0 24 24" width="20" height="20"
                                                                     fill="currentColor" aria-hidden>
                                                                    <circle cx="5" cy="12" r="2"/>
                                                                    <circle cx="12" cy="12" r="2"/>
                                                                    <circle cx="19" cy="12" r="2"/>
                                                                </svg>
                                                            </button>

                                                            {openMenuFor === rowKey && (
                                                                <div
                                                                    ref={menuRef}
                                                                    className="moremenu__panel"
                                                                    role="menu"
                                                                    onMouseDown={(e) => e.stopPropagation()}
                                                                    onClick={(e) => e.stopPropagation()}
                                                                >
                                                                    <button className="moremenu__item" onClick={() => {
                                                                        onShare(tr);
                                                                        setOpenMenuFor(null);
                                                                    }}>
                                                                        <IoShareOutline size={18}/> <span>Share</span>
                                                                    </button>
                                                                    <button
                                                                        className="moremenu__item"
                                                                        onClick={() => {
                                                                            navigate(`/track/${tr.slug || tr.id}`);
                                                                            setOpenMenuFor(null);
                                                                        }}
                                                                    >
                                                                        <IoOpenOutline size={18}/>
                                                                        <span>Go to track</span>
                                                                    </button>

                                                                    <button className="moremenu__item"
                                                                            onClick={() => setOpenMenuFor(null)}>
                                                                        <IoListOutline size={18}/>
                                                                        <span>Add to playlist</span>
                                                                    </button>
                                                                </div>
                                                            )}
                                                        </div>
                                                    </div>

                                                    <button
                                                        className={`pill pill--clickable ${tr.liked ? "is-active" : ""}`}
                                                        onClick={(e) => {
                                                            e.stopPropagation();
                                                            onToggleLike(tr.id, tr.liked);
                                                        }}
                                                        aria-pressed={!!tr.liked}
                                                        aria-label={tr.liked ? "Unlike" : "Like"}
                                                        title={tr.liked ? "Unlike" : "Like"}
                                                    >
                                                        <svg viewBox="0 0 24 24" aria-hidden="true" width="20"
                                                             height="20">
                                                            <path
                                                                className="icon-heart"
                                                                d="M4.318 6.318a4.5 4.5 0 016.364 0L12 7.636l1.318-1.318a4.5 4.5 0 116.364 6.364L12 21.364 4.318 12.682a4.5 4.5 0 010-6.364z"
                                                            />
                                                        </svg>
                                                        {tr.likes}
                                                    </button>
                                                    <button
                                                        className="pill"
                                                        onClick={() => navigate(`/track/${tr.slug || tr.id}#comments`)}
                                                        aria-label="Go to comments"
                                                        title="Go to comments"
                                                    >
                                                        <IoChatbubbleOutline/> {tr.comments}
                                                    </button>
                                                    <button
                                                        className="price-btn"
                                                        onClick={(e) => {
                                                            e.stopPropagation(); // prevent bubbling to row click
                                                            setLicenseData(tr);  // tr = track, pack, or kit
                                                            setLicenseOpen(true);
                                                        }}
                                                    >
                                                        <IoCartOutline size={18} style={{marginRight: 6}}/>
                                                        {tr.price == null ? "Buy" : `$${Number(tr.price).toFixed(2)}`}
                                                    </button>
                                                </div>
                                            </li>
                                        );
                                    })}
                                </ul>
                            )}
                        </>
                    )}

                    {tab === "kits" && (
                        <>
                            <div className="profile__filterBar">
                                <label className="profile__sort">
                                    <span>Sort by</span>
                                    <select
                                        value={sortBy}
                                        onChange={(e) => setSortBy(e.target.value)}
                                        aria-label="Sort kits"
                                        disabled={loading}
                                    >
                                        <option value="all">All</option>
                                        <option value="popular">Popular</option>
                                        <option value="featured">Featured</option>
                                        <option value="trending">Trending</option>
                                        <option value="new">New</option>
                                        <option value="priceAsc">Price ↑</option>
                                        <option value="priceDesc">Price ↓</option>
                                    </select>
                                </label>
                            </div>

                            {(!loading && (!sortedKits || sortedKits.length === 0)) ? (
                                <div className="list__empty">No kits uploaded yet</div>
                            ) : (
                                <ul className="profile__albums">
                                    {sortedKits.map((k) => {
                                        const rowKey = `kit:${k.id}`;
                                        const isOpen = openMenuFor === rowKey;

                                        return (
                                            <li
                                                key={`kit:${k.id}`}
                                                className={`albumCard ${k.board === "featured" ? "albumCard--featured" : ""}`}
                                            >
                                                {/*<div className="albumCover">*/}
                                                {/*    {k.img ? (*/}
                                                {/*        <img src={k.img} alt={k.title}/>*/}
                                                {/*    ) : (*/}
                                                {/*        <div className="profileCoverWrap__ph" aria-label="No cover"/>*/}
                                                {/*    )}*/}
                                                {/*</div>*/}

                                                <div className="albumCover">
                                                    {k.img ? <img src={k.img} alt={k.title}/> :
                                                        <div className="profileCoverWrap__ph" aria-label="No cover"/>}
                                                    <button
                                                        className="coverWrap__playbtn"
                                                        aria-label="Play/Pause preview"
                                                        onClick={(e) => {
                                                            e.stopPropagation();
                                                            playKit(k);
                                                        }}
                                                    >
                                                        {(() => {
                                                            const key = KIT_KEY(k.id);
                                                            const showPause = playIntent?.key === key
                                                                ? playIntent.playing
                                                                : (player.playing && player.sourceKey === key);

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
                                                    </button>
                                                </div>


                                                <div className="albumMeta">
                                                    <div className="albumTitleRow">
                                                        <div className="albumTitle" title={k.title}>
                                                            {getSmartTitle(k.title)}
                                                        </div>
                                                        {k.board && (
                                                            <span className={`profilePill boardpill--${k.board}`}>
                    {{
                        featured: "Featured",
                        trending: "Trending",
                        popular: "Popular",
                        new: "New",
                    }[k.board] || k.board}
                  </span>
                                                        )}
                                                        <div className="moremenu__wrapper">
                                                            <button
                                                                className="more-actions-btn card__more-btn"
                                                                onClick={(e) => {
                                                                    e.stopPropagation();
                                                                    setOpenMenuFor(openMenuFor === rowKey ? null : rowKey);
                                                                }}
                                                                onMouseDown={(e) => e.stopPropagation()}
                                                                aria-label="More actions"
                                                            >
                                                                <svg viewBox="0 0 24 24" width="20" height="20"
                                                                     fill="currentColor" aria-hidden>
                                                                    <circle cx="5" cy="12" r="2"/>
                                                                    <circle cx="12" cy="12" r="2"/>
                                                                    <circle cx="19" cy="12" r="2"/>
                                                                </svg>
                                                            </button>
                                                        </div>

                                                        {isOpen && (
                                                            <div
                                                                ref={menuRef}
                                                                className="moremenu__panel1"
                                                                role="menu"
                                                                onMouseDown={(e) => e.stopPropagation()}
                                                                onClick={(e) => e.stopPropagation()}
                                                            >
                                                                <button
                                                                    className="moremenu__item"
                                                                    onClick={() => {
                                                                        onShare(k); // Pass current kit
                                                                        setOpenMenuFor(null);
                                                                    }}
                                                                >
                                                                    <IoShareOutline size={18}/> <span>Share</span>
                                                                </button>

                                                                <button
                                                                    className="moremenu__item"
                                                                    onClick={() => {
                                                                        onGoToItem(k);
                                                                        setOpenMenuFor(null);
                                                                    }}
                                                                >
                                                                    <IoOpenOutline size={18}/>
                                                                    <span>Go to kit</span>
                                                                </button>

                                                                <button
                                                                    className="moremenu__item"
                                                                    onClick={() => {
                                                                        onAddToPlaylist(k); // add to playlist logic
                                                                        setOpenMenuFor(null);
                                                                    }}
                                                                >
                                                                    <IoListOutline size={18}/>
                                                                    <span>Add to playlist</span>
                                                                </button>
                                                            </div>
                                                        )}
                                                    </div>


                                                    {/* optional subline; keep or swap for whatever you track on kits */}
                                                    <div className="albumSub">
                                                        {k.type || k.subtitle || ""}
                                                        {Number.isFinite(k.totalDurationSec) ? ` · ${fmtMMSS(k.totalDurationSec)}` : ""}
                                                    </div>

                                                    {Array.isArray(k.tags) && k.tags.length > 0 && (
                                                        <div className="profileTags">
                                                            {k.tags.slice(0, 3).map((t, i) => (
                                                                <span key={`${k.id}-tag-${i}`}
                                                                      className="tag">{t}</span>
                                                            ))}
                                                        </div>
                                                    )}
                                                </div>

                                                <div className="albumFooter">
                                                    <button
                                                        className="price-btn"
                                                        onClick={(e) => {
                                                            e.stopPropagation();
                                                            addKitToCart(k);
                                                        }}
                                                    >
                                                        <IoCartOutline size={18} style={{marginRight: 6}}/>
                                                        ${Number(k.price ?? 0).toFixed(2)}
                                                    </button>
                                                </div>
                                            </li>
                                        );
                                    })}
                                </ul>
                            )}
                        </>
                    )}

                    {tab === "packs" && (
                        (!loading && (!sortedPacks || sortedPacks.length === 0)) ? (
                            <div className="list__empty">No packs uploaded yet</div>
                        ) : (
                            <ul className="profile__albums">
                                {sortedPacks.map((al) => {
                                    const rowKey = `pack:${al.id}`;
                                    const isOpen = openMenuFor === rowKey;

                                    return (
                                        <li
                                            key={`album:${al.id}`}
                                            className={`albumCard ${al.board === "featured" ? "albumCard--featured" : ""} ${isOpen ? "is-menu-open" : ""}`}

                                        >
                                            {/*<div className="albumCover">*/}
                                            {/*    {al.img ? (*/}
                                            {/*        <img src={al.img} alt={al.title}/>*/}
                                            {/*    ) : (*/}
                                            {/*        <div className="profileCoverWrap__ph" aria-label="No cover"/>*/}
                                            {/*    )}*/}
                                            {/*</div>*/}

                                            <div className="albumCover">
                                                {al.img ? <img src={al.img} alt={al.title}/> :
                                                    <div className="profileCoverWrap__ph" aria-label="No cover"/>}
                                                <button
                                                    className="coverWrap__playbtn"
                                                    aria-label="Play/Pause preview"
                                                    onClick={(e) => {
                                                        e.stopPropagation();
                                                        playPack(al);
                                                    }}
                                                >
                                                    {(() => {
                                                        const key = PACK_KEY(al.id);
                                                        const showPause = playIntent?.key === key
                                                            ? playIntent.playing
                                                            : (player.playing && player.sourceKey === key);

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
                                                </button>
                                            </div>

                                            <div className="albumMeta">
                                                <div className="albumTitleRow">
                                                    <div className="albumTitle" title={al.title}>
                                                        {getSmartTitle(al.title)}
                                                    </div>
                                                    {al.board && (
                                                        <span className={`profilePill boardpill--${al.board}`}>
                  {{
                      featured: "Featured",
                      trending: "Trending",
                      popular: "Popular",
                      new: "New",
                  }[al.board] || al.board}
                </span>
                                                    )}

                                                    <div className="moremenu__wrapper">
                                                        <button
                                                            className="more-actions-btn card__more-btn"
                                                            onClick={(e) => {
                                                                e.stopPropagation();
                                                                setOpenMenuFor(openMenuFor === rowKey ? null : rowKey);
                                                            }}
                                                            onMouseDown={(e) => e.stopPropagation()}
                                                            aria-label="More actions"
                                                        >
                                                            <svg viewBox="0 0 24 24" width="20" height="20"
                                                                 fill="currentColor" aria-hidden>
                                                                <circle cx="5" cy="12" r="2"/>
                                                                <circle cx="12" cy="12" r="2"/>
                                                                <circle cx="19" cy="12" r="2"/>
                                                            </svg>
                                                        </button>
                                                    </div>

                                                    {isOpen && (
                                                        <div
                                                            ref={menuRef}
                                                            className="moremenu__panel1"
                                                            role="menu"
                                                            onMouseDown={(e) => e.stopPropagation()}
                                                            onClick={(e) => e.stopPropagation()}
                                                        >
                                                            <button
                                                                className="moremenu__item"
                                                                onClick={() => {
                                                                    onShare(al); // Pass current kit
                                                                    setOpenMenuFor(null);
                                                                }}
                                                            >
                                                                <IoShareOutline size={18}/> <span>Share</span>
                                                            </button>

                                                            <button
                                                                className="moremenu__item"
                                                                onClick={() => {
                                                                    onGoToItem(al);
                                                                    setOpenMenuFor(null);
                                                                }}
                                                            >
                                                                <IoOpenOutline size={18}/>
                                                                <span>Go to pack</span>
                                                            </button>

                                                            <button
                                                                className="moremenu__item"
                                                                onClick={() => {
                                                                    onAddToPlaylist(al); // add to playlist logic
                                                                    setOpenMenuFor(null);
                                                                }}
                                                            >
                                                                <IoListOutline size={18}/> <span>Add to playlist</span>
                                                            </button>
                                                        </div>
                                                    )}
                                                </div>

                                                <div className="albumSub">
                                                    {Number.isFinite(Number(al.beatsCount))
                                                        ? `${Number(al.beatsCount)} ${Number(al.beatsCount) === 1 ? "track" : "tracks"}`
                                                        : ""}
                                                    {Number.isFinite(Number(al.totalDurationSec))
                                                        ? ` · ${fmtMMSS(Number(al.totalDurationSec))}`
                                                        : ""}
                                                </div>

                                                {Array.isArray(al.tags) && al.tags.length > 0 && (
                                                    <div className="profileTags">
                                                        {al.tags.map((t, i) => (
                                                            <span key={`${al.id}-tag-${i}`} className="tag">{t}</span>
                                                        ))}
                                                    </div>
                                                )}

                                            </div>

                                            <div className="albumFooter">
                                                <button
                                                    className="price-btn"
                                                    onClick={(e) => {
                                                        e.stopPropagation();
                                                        setLicenseData({...al, kind: "PACK"});
                                                        setLicenseOpen(true);
                                                    }}
                                                >
                                                    <IoCartOutline size={18} style={{marginRight: 6}}/>
                                                    {al.price == null ? "Buy" : `$${Number(al.price).toFixed(2)}`}
                                                </button>

                                            </div>
                                        </li>
                                    );
                                })}
                            </ul>
                        )
                    )}

                    {tab === "about" && (
                        <div className="about">
                            <h2 className="about__title">About</h2>
                            <p className="about__text">
                                {(profile?.about && profile.about.trim())
                                    ? profile.about
                                    : "No bio yet. Talk about your drill style, typical BPM range, key vibes, and collab info."}
                            </p>
                        </div>
                    )}
                </section>
            </div>

            <div className="tp-pagination-bar">
                <button
                    onClick={goToPrevPage}
                    disabled={pageByTab[tab] === 0}
                    className="tp-pagebtn tp-backbtn"
                >
                    ←
                </button>

                <div className="tp-pagination-scroll">
                    {renderPageNumbers(
                        pageByTab[tab],
                        totalPagesByTab[tab],
                        (p) => setPageByTab(prev => ({...prev, [tab]: p}))
                    )}
                </div>

                <button
                    onClick={goToNextPage}
                    disabled={!hasMoreByTab[tab]}
                    className="tp-pagebtn tp-nextbtn"
                >
                    →
                </button>
            </div>

            <ShareModal
                open={shareOpen}
                track={shareData}
                onClose={() => {
                    setShareOpen(false);
                    setShareData(null);
                }}
            />
            <LicenseModal
                isOpen={licenseOpen}
                track={licenseData}
                onClose={() => {
                    setLicenseOpen(false);
                    setLicenseData(null);
                }}
                onSelect={({beatId, license, action}) => {
                    if (!licenseData) return;

                    const isPack = licenseData.kind === "PACK";

                    const item = {
                        id: isPack
                            ? `pack-${licenseData.id}-${license.type}`
                            : `${beatId}:${license.type}`,
                        type: isPack ? "pack" : "beat",
                        beatId: isPack ? undefined : beatId,
                        packId: isPack ? licenseData.id : undefined,
                        title: licenseData.title,
                        artist: licenseData.artistName || licenseData.artist || "Unknown",
                        img: licenseData.img || licenseData.cover || "",
                        licenseId: license.backendId,
                        licenseName: license.name,
                        licenseType: license.type,
                        price: Number(license.price || 0),
                        qty: 1,
                    };

                    if (action === "addToCart") {
                        add(item);
                    } else if (action === "buyNow") {
                        clear();
                        add(item);
                        window.location.href = "/checkout";
                    }

                    setLicenseOpen(false);
                    setLicenseData(null);
                }}
            />
        </main>

    );
}