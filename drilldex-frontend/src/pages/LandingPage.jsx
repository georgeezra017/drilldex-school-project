
import {useEffect, useState, useRef} from "react";
import "./landing.css";
import DiscoverDrillStyles from "../components/DiscoverDrillStyles";
import "../components/discover-drill-styles.css";
import LicenseModal from "../components/LicenseModal.jsx";
import api from "../lib/api";
import toast from "react-hot-toast";
import {useNavigate} from "react-router-dom";
import ShareModal from "../components/ShareModal.jsx";
import {queueBeatsList, queuePack} from "../lib/playerQueue";

import {
    IoEllipsisHorizontal,
    IoShareOutline,
    IoCartOutline,
    IoListOutline,
    IoOpenOutline,
    IoSearch,
    IoPersonOutline
} from "react-icons/io5";
import {useCart} from "../state/cart.jsx";
import UploaderLink from "../components/UploaderLink.jsx";

const CDN_BASE = import.meta.env.VITE_S3_PUBLIC_BASE?.replace(/\/+$/, "") || "";

const PACK_KEY = (id) => `pack:${id}`;
const KIT_KEY = (id) => `kit:${id}`;
const BEAT_KEY = (id) => `beat:${id}`;


const TAB_MAP = {
    New: "new",
    Popular: "popular",
};

function formatDuration(seconds) {
    if (!seconds) return "0:00";
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    return `${m}:${s.toString().padStart(2, "0")}`;
}


// Make any backend path usable by <img>/<audio>
const toFileUrl = (p) => {
    if (!p) return "";
    if (/^https?:\/\//i.test(p)) return p;               // already absolute
    if (p.startsWith("/uploads/https://") || p.startsWith("/uploads/http://")) {
        return p.replace(/^\/uploads\//, "");               // fix double-prefix bug
    }
    if (p.startsWith("/uploads/")) return p;              // served by backend static
    const clean = p.replace(/^\/+/, "");
    return CDN_BASE ? `${CDN_BASE}/${clean}` : `/uploads/${clean}`;
};


/**
 * Drilldex Landing Page (JSX + vanilla CSS, Vite-ready)
 * - Floating header
 * - Featured as horizontal cards w/ hover play
 * - New & Popular as list rows
 * - Full-width audio bar with top progress line
 */

export default function LandingPage() {

    const likeInFlight = useRef(new Set());

    function getBeatFromState(id) {
        return (
            featured.find(x => x.id === id) ||
            latest.find(x => x.id === id) ||
            popular.find(x => x.id === id) ||
            null
        );
    }


    const [shareOpen, setShareOpen] = useState(false);
    const [shareTrack, setShareTrack] = useState(null);

    function onShare(track) {
        setShareTrack({
            id: track.id,
            slug: track.slug,
            title: track.title,
            artistName: track.artistName,
            albumCoverUrl: track.albumCoverUrl,
        });
        setShareOpen(true);
    }
    const [intent, setIntent] = useState(null);
    const [isLiked, setIsLiked] = useState(false);
    const toggleLike = () => setIsLiked(prev => !prev);
    const [showLicenseModal, setShowLicenseModal] = useState(false);
    const [selectedTrack, setSelectedTrack] = useState(null);
    const [openMenuFor, setOpenMenuFor] = useState(null);
    const [featured, setFeatured] = useState([]);
    const [latest, setLatest] = useState([]);
    const [popular, setPopular] = useState([]);
    const navigate = useNavigate();
    const [featuredPacks, setFeaturedPacks] = useState([]);
    const [featuredKits, setFeaturedKits] = useState([]);
    const playedOnce = useRef(new Set());
    const {add, clear} = useCart();
    const [nowKey, setNowKey] = useState(null);
    const [isPlaying, setIsPlaying] = useState(false);
    const [heroQuery, setHeroQuery] = useState("");
    const splitTags = (t) => (t ? String(t).split(",").map(s => s.trim()).filter(Boolean) : []);
    const beatSlugOrId = (b) => b?.slug || b?.id;
    const [player, setPlayer] = useState({ playing:false, sourceKey:"", trackId:null });
    const [intentKey, setIntentKey] = useState(null);

    // somewhere top-level in the component
    const mapBeat = (x) => {
        const priceFromLic = derivePriceIfPossible(x);
        return {
            id: x.id,
            slug: x.slug,
            title: x.title,
            artistName: x.artistName,
            ownerId: x.ownerId, // ← ADD THIS
            albumCoverUrl: toFileUrl(x.coverUrl || x.albumCoverUrl),
            genre: x.genre,
            bpm: x.bpm,
            durationInSeconds: x.durationInSeconds,
            tags: splitTags(x.tags),
            likeCount: x.likeCount ?? 0,
            commentCount: x.commentCount ?? 0,
            playCount: x.playCount ?? 0,
            price: Number.isFinite(priceFromLic) ? priceFromLic : Number(x.price || 0),
            liked: Boolean(x.liked ?? x.isLiked ?? x.userHasLiked ?? false),
        };
    };

// Helper: normalize one beat-like item for the playlist bus
    function asPlaylistItem(row = {}) {
        return {
            id: row.id,
            title: row.title,
            artistName: row.artistName || row.creatorName || "Unknown",
            albumCoverUrl: row.albumCoverUrl || row.coverUrl || "",
            audioUrl: row.previewUrl || "",                // ok if empty; player can fetch on play
            durationInSeconds: row.durationInSeconds || 0,
        };
    }

    async function addToPlaylist(item) {
        const kind =
            item._kind || item.kind ||
            (item.tracks ? "PACK" : item.previewUrl ? "BEAT" : item.coverUrl ? "BEAT" : "BEAT");

        // BEAT → add a single entry
        if (kind === "BEAT") {
            document.dispatchEvent(new CustomEvent("playlist:add", { detail: asPlaylistItem(item) }));
            toast.success("Added to playlist");
            return;
        }

        // PACK → fetch previews then add many
        if (kind === "PACK") {
            const t = toast.loading("Adding pack previews…");
            try {
                // you already have this in your page code:
                // const list = await getPackPlaylist(packId, packCoverUrl)
                const list = await getPackPlaylist(item.id, item.coverUrl);
                document.dispatchEvent(new CustomEvent("playlist:add", { detail: { items: list } }));
                toast.success("Pack added to playlist", { id: t });
            } catch {
                toast.error("Couldn't add pack", { id: t });
            }
            return;
        }

        // Kits aren’t musical playlists → don’t add
        if (kind === "KIT") {
            toast("Kits aren’t playlist items.", { icon: "ℹ️" });
            return;
        }

        // Fallback: treat as single track
        document.dispatchEvent(new CustomEvent("playlist:add", { detail: asPlaylistItem(item) }));
        toast.success("Added to playlist");
    }

    async function toggleLikeBeat(b) {
        const beatId = b?.id;
        if (!beatId) return;

        if (likeInFlight.current.has(beatId)) return;
        likeInFlight.current.add(beatId);

        try {
            const s = getBeatFromState(beatId) || b || {};
            const wasLiked = !!s.liked;
            const wasCount = Number.isFinite(s.likeCount) ? s.likeCount : 0;

            if (wasLiked) {
                // ✅ UNLIKE: optimistic decrement immediately (no flicker)
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

            // Local says "not liked".
            // First check if server already has it liked (post-refresh mismatch),
            // and if so, do immediate UNLIKE without any +1.
            try {
                const {data} = await api.get(`/beats/${beatId}`);
                const serverLiked = !!(data?.liked ?? data?.isLiked ?? data?.userHasLiked);
                const serverCount = Number.isFinite(data?.likeCount) ? data.likeCount : wasCount;

                if (serverLiked) {
                    // ✅ Server thinks it's liked → user intent is UNLIKE now.
                    const dec = Math.max(0, serverCount - 1);
                    patchBeatEverywhere(beatId, {liked: false, likeCount: dec});

                    const resp = await api.delete(`/beats/${beatId}/like`);
                    const dto = resp?.data || {};
                    patchBeatEverywhere(beatId, {
                        liked: !!(dto.liked ?? dto.isLiked ?? dto.userHasLiked ?? false),
                        likeCount: Number.isFinite(dto.likeCount) ? dto.likeCount : dec,
                    });
                    return;
                }
            } catch {
                // If GET fails, just proceed to POST path below (no optimistic +1).
            }

            // ✅ LIKE: do NOT optimistic-increment; wait for server to avoid 1→2→1 flicker
            const resp = await api.post(`/beats/${beatId}/like`);
            const dto = resp?.data || {};
            const finalLiked = !!(dto.liked ?? dto.isLiked ?? dto.userHasLiked ?? true);
            const finalCount = Number.isFinite(dto.likeCount)
                ? dto.likeCount
                : wasCount + (finalLiked ? 1 : 0);

            patchBeatEverywhere(beatId, {liked: finalLiked, likeCount: finalCount});

        } catch (err) {
            // revert to whatever we currently have in state (defensive)
            const s = getBeatFromState(beatId) || {};
            patchBeatEverywhere(beatId, {
                liked: !!s.liked,
                likeCount: Number.isFinite(s.likeCount) ? s.likeCount : 0,
            });

            const status = err?.response?.status;
            if (status === 401) toast.error("Please sign in to like beats.");
            else toast.error("Couldn't update like. Please try again.");
        } finally {
            likeInFlight.current.delete(beatId);
        }
    }

    // ---- license → min price helpers ----
    const minPriceFromLicenses = (licenses = []) => {
        const nums = licenses
            .filter(l => l == null ? false : (l.enabled ?? true))
            .map(l => Number(l.price || 0))
            .filter(n => Number.isFinite(n) && n >= 0);
        if (!nums.length) return 0;
        return Math.min(...nums);
    };

// When an entity carries licenses inline already (some endpoints do)
    const derivePriceIfPossible = (row) => {
        if (Array.isArray(row?.licenses) && row.licenses.length) {
            return minPriceFromLicenses(row.licenses);
        }
        return null; // not present, fetch later
    };


    function goToTrackComments(b) {
        const seg = beatSlugOrId(b);
        if (!seg) return;
        // Use an anchor the Track page can read (#comments). If your Track page listens for ?comments=1, swap accordingly.
        navigate(`/track/${seg}#comments`);
    }

    // update existing mappers:
    const mapPackOrKit = (x) => {
        const priceFromLic = derivePriceIfPossible(x);

        let totalDuration = "0:00";
        if (x.type?.toLowerCase()?.includes("kit")) {
            // kits → use totalDurationSec
            totalDuration = formatDuration(x.totalDurationSec || 0);
        } else {
            // packs → prefer backend string
            totalDuration = x.totalDuration || formatDuration(x.totalDurationSec || 0);
        }

        // compute track count
        const trackCount = x.trackCount != null
            ? x.trackCount
            : (x.samples || 0) + (x.presets || 0) + (x.loops || 0);

        return {
            id: x.id,
            slug: x.slug,
            title: x.title,
            ownerId: x.ownerId || x.creatorId,
            creatorName:
                x.creatorName ||
                x.artistName ||
                x.producerName ||
                x.uploader ||
                x.ownerDisplayName ||
                x.ownerName ||
                "Unknown",
            coverUrl: toFileUrl(x.coverUrl || x.imageUrl || x.coverImagePath),
            price: Number.isFinite(priceFromLic) ? priceFromLic : Number(x.price || 0),
            tags: splitTags(x.tags),
            type: x.type || "Kit",
            totalDurationSec: x.totalDurationSec || 0,
            trackCount,
            totalDuration, // always a string like "6:59"

        };
    };

    useEffect(() => {
        window.scrollTo(0, 0);
    }, []);


    const onHeroSearch = (e) => {
        e.preventDefault();
        const q = heroQuery.trim();
        if (!q) return;
        navigate(`/search?q=${encodeURIComponent(q)}`);
    };


    const onBuy = (track) => {
        setSelectedTrack(track);
        setShowLicenseModal(true);
    };

    const previewCache = useRef(new Map());

    // useEffect(() => {
    //     const onPlayList = (e) => {
    //         const srcKey = e.detail?.sourceKey; // e.g. "pack:123", "kit:45"
    //         if (srcKey) incrementPlayBySourceKey(srcKey);
    //     };
    //     document.addEventListener("audio:play-list", onPlayList);
    //     return () => document.removeEventListener("audio:play-list", onPlayList);
    // }, []);
    //
    // useEffect(() => {
    //     const onPlayList = (e) => {
    //         const srcKey = e.detail?.sourceKey; // "beat:..", "pack:..", "kit:.."
    //         if (!srcKey) return;
    //         const [type] = String(srcKey).split(":");
    //         if (type === "kit") incrementPlayBySourceKey(srcKey); // kits only
    //     };
    //     document.addEventListener("audio:play-list", onPlayList);
    //     return () => document.removeEventListener("audio:play-list", onPlayList);
    // }, []);

    function goToTrack(track) {
        const seg = track?.slug || track?.id;
        if (!seg) return;
        navigate(`/track/${seg}`);
    }

    function goToKit(kit) {
        const seg = kit?.slug || kit?.id;
        if (!seg) return console.warn("Missing kit slug/id");
        navigate(`/kit/${seg}`);
    }

    function goToPack(pack) {
        const seg = pack?.slug || pack?.id;
        if (!seg) return console.warn("Missing pack slug/id");
        navigate(`/pack/${seg}`);
    }

    // --- BUY HELPERS ---
// open license modal for a PACK
    const openPackBuy = (pack) => {
        setSelectedTrack({
            id: pack.id,
            title: pack.title,
            artistName: pack.creatorName || pack.artistName || "Unknown",
            albumCoverUrl: pack.coverUrl,
            kind: "PACK",                     // tells LicenseModal to use /packs/{id}/licenses
        });
        setShowLicenseModal(true);
    };

// add a KIT directly to cart
    const addKitToCart = (kit) => {
        add({
            id: `kit-${kit.id}`,
            type: "kit",
            kitId: kit.id,
            title: kit.title,
            artist: kit.creatorName || kit.artistName || "Unknown",
            img: kit.coverUrl,
            price: Number(kit.price || 0),
            qty: 1,
        });
    };

// add a PACK with selected license to cart (used by modal onSelect)
    const addPackWithLicenseToCart = (pack, license) => {
        add({
            id: `pack-${pack.id}-${license.id}`,
            type: "pack",
            packId: pack.id,
            title: pack.title,
            artist: pack.artistName || "Unknown",
            img: pack.albumCoverUrl || pack.coverUrl,
            licenseType: license.type || license.id,
            licenseId: license.id,
            licenseName: license.name,
            price: Number(license.price || 0),
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

// Build a "beat-like" list for the global player
    const mapPreviewItemsToPlaylist = (arr, fallbackCover) =>
        (Array.isArray(arr) ? arr : []).map((t, i) => ({
            id: t.id ?? i,
            title: t.title,
            artistName: t.artistName || "Unknown",
            albumCoverUrl: toFileUrl(t.coverUrl || fallbackCover),
            audioUrl: toFileUrl(t.previewUrl),
            durationInSeconds: t.durationInSeconds || 0,
            // fields your player may ignore:
            genre: "",
            bpm: 0,
            tags: "",
            price: 0,
        }));

    const getPreviewUrl = async (id) => {
        if (previewCache.current.has(id)) return previewCache.current.get(id);
        const {data} = await api.get(`/beats/${id}/preview-url`); // -> { url, expiresInSec }
        const url = data?.url;
        if (url) previewCache.current.set(id, url);
        return url;
    };



    useEffect(() => {
        const onState = (e) => {
            const d = e.detail || {};

            // treat "list empty + no trackId" as a transient snapshot we should ignore
            const emptySnapshot =
                Array.isArray(d.list) && d.list.length === 0 && (d.trackId == null);

            setPlayer(prev => {
                if (emptySnapshot) {
                    // Don't let an empty snapshot flip playing=false or clear trackId.
                    // Still accept a sourceKey if the player wants to tell us which list
                    // is being prepared.
                    return {
                        playing: prev.playing,
                        sourceKey: d.sourceKey ?? prev.sourceKey,
                        trackId: prev.trackId
                    };
                }

                return {
                    playing: (typeof d.playing === "boolean") ? d.playing : prev.playing,
                    sourceKey: d.sourceKey ?? prev.sourceKey,
                    trackId: d.trackId ?? d.current?.id ?? prev.trackId
                };
            });

            // optional: clear pending only when we see a non-empty, matching state
            if (intentKey && d.sourceKey === intentKey && !emptySnapshot) {
                setIntentKey(null);
            }
        };

        document.addEventListener("audio:state", onState);
        document.dispatchEvent(new CustomEvent("audio:get-state"));
        return () => document.removeEventListener("audio:state", onState);
    }, [intentKey]);


    async function getPackPlaylist(packId, packCoverUrl) {
        const {data} = await api.get(`/packs/${packId}/preview-playlist`);
        return mapPreviewItemsToPlaylist(data, packCoverUrl);
    }

    async function getKitPlaylist(kitId, kitCoverUrl) {
        const {data} = await api.get(`/kits/${kitId}/preview-playlist`);
        return mapPreviewItemsToPlaylist(data, kitCoverUrl);
    }

    async function playPackCard(pack) {
        const key = PACK_KEY(pack.id);

        if (player.sourceKey === key) {
            document.dispatchEvent(new CustomEvent(player.playing ? "audio:pause" : "audio:resume"));
            return;
        }

        setIntentKey(key);
        await queuePack(pack.id, pack.coverUrl, key);
    }

    async function playKitCard(kit) {
        const key = KIT_KEY(kit.id);

        if (player.sourceKey === key) {
            document.dispatchEvent(new CustomEvent(player.playing ? "audio:pause" : "audio:resume"));
            return;
        }

        setIntentKey(key);
        const toastId = toast.loading("Loading kit previews…");
        try {
            const list = await getKitPlaylist(kit.id, kit.coverUrl);
            if (!list.length) {
                toast.error("No previews found for this kit.", { id: toastId });
                return;
            }

            document.dispatchEvent(new CustomEvent("audio:play-list", {
                detail: { list, index: 0, sourceKey: key }
            }));

            toast.dismiss(toastId);
        } catch (err) {
            const msg = err?.response?.data?.error || "Couldn't load kit previews.";
            toast.error(msg, { id: toastId });
        }
    }

    async function playKitCard(kit) {
        const key = KIT_KEY(kit.id);

         if (player.sourceKey === key) {
               document.dispatchEvent(new CustomEvent(player.playing ? "audio:pause" : "audio:resume"));
               return;
             }

        const toastId = toast.loading("Loading kit previews…");
        try {
            const list = await getKitPlaylist(kit.id, kit.coverUrl);
            if (!list.length) {
                toast.error("No previews found for this kit.", {id: toastId});
                return;
            }

            document.dispatchEvent(new CustomEvent("audio:play-list", {
                detail: {list, index: 0, sourceKey: key}
            }));
            setNowKey(key);
            setIsPlaying(true);

            // No success toast — just clear the loading one.
            toast.dismiss(toastId);
        } catch (err) {
            const msg = err?.response?.data?.error || "Couldn't load kit previews.";
            toast.error(msg, {id: toastId});
        }
    }

    // De-dupe: only count one play per item per page session

// singular -> endpoint plural (safety if you change names later)
    const endpointMap = {beat: "beats", pack: "packs", kit: "kits"};

    function patchBeatEverywhere(beatId, patch) {
        setFeatured(prev => prev.map(b => b.id === beatId ? {...b, ...patch} : b));
        setLatest(prev => prev.map(b => b.id === beatId ? {...b, ...patch} : b));
        setPopular(prev => prev.map(b => b.id === beatId ? {...b, ...patch} : b));
    }



    function incrementPlayBySourceKey(sourceKey) {
        if (!sourceKey) return;
        const [type, id] = String(sourceKey).split(":");
        // Kits only — beats/packs are handled by AudioBar
        if (type === "kit") incrementPlay(type, id);
    }

    async function playBeatFromList(listName, index) {
        const merged = [...featured, ...latest, ...popular];
        const base =
            listName === "featured" ? 0 :
                listName === "latest" ? featured.length :
                    featured.length + latest.length;

        const absoluteIndex = base + index;
        const target = merged[absoluteIndex];
        if (!target) return;

        const key = BEAT_KEY(target.id);

        // If this card is already active, just toggle pause/resume
         if (player.sourceKey === key) {
               document.dispatchEvent(new CustomEvent(player.playing ? "audio:pause" : "audio:resume"));
               return;
             }
        setIntentKey(key);
         await queueBeatsList(merged, absoluteIndex, key, { freshForIndex: true });
    }

    const priceLoaded = useRef(new Set());


// generic license fetcher
    async function fetchMinPrice(kind, id) {
        if (!id || priceLoaded.current.has(`${kind}:${id}`)) return null;
        priceLoaded.current.add(`${kind}:${id}`);

        try {
            let url = `/${kind}/${id}/licenses`; // beats|packs|kits
            const {data} = await api.get(url);
            const minPrice = minPriceFromLicenses(Array.isArray(data) ? data : []);
            return Number.isFinite(minPrice) ? minPrice : 0;
        } catch {
            return null;
        }
    }

// batch hydrate for a list
    async function hydratePricesFor(kind, list, patchFn) {
        const targets = (list || [])
            .filter(x => !priceLoaded.current.has(`${kind}:${x.id}`))
            // fetch for anything that didn't carry licenses inline
            .filter(x => !(Array.isArray(x.licenses) && x.licenses.length));

        if (!targets.length) return;

        const results = await Promise.allSettled(
            targets.map(x => fetchMinPrice(kind, x.id))
        );

        results.forEach((res, idx) => {
            const row = targets[idx];
            if (res.status === "fulfilled" && Number.isFinite(res.value) && res.value >= 0) {
                patchFn(row.id, {price: res.value});
            }
        });
    }

    // NEW: add to cart builder
    const addTrackWithLicenseToCart = (track, license) => {
        add({
            id: `beat-${track.id}-${license.id}`,   // unique per beat+license
            type: "beat",
            beatId: track.id,
            title: track.title,
            artist: track.artistName,
            img: track.albumCoverUrl,
            licenseType: license.type || license.id,
            licenseId: license.id,
            licenseName: license.name,
            price: license.price,
            qty: 1,
        });
    };




    useEffect(() => {
        const onDoc = (e) => {
            // if any menu is open and the click is outside any .moremenu__panel or .more-actions-btn, close it
            const isPanel = e.target.closest?.(".moremenu__panel");
            const isBtn = e.target.closest?.(".more-actions-btn");
            if (!isPanel && !isBtn) setOpenMenuFor(null);
        };
        const onEsc = (e) => e.key === "Escape" && setOpenMenuFor(null);

        document.addEventListener("click", onDoc);
        document.addEventListener("keydown", onEsc);
        return () => {
            document.removeEventListener("click", onDoc);
            document.removeEventListener("keydown", onEsc);
        };
    }, []);

    // patchers for packs/kits like you have for beats
    function patchPackEverywhere(packId, patch) {
        setFeaturedPacks(prev => prev.map(p => p.id === packId ? {...p, ...patch} : p));
    }

    function patchKitEverywhere(kitId, patch) {
        setFeaturedKits(prev => prev.map(k => k.id === kitId ? {...k, ...patch} : k));
    }


    useEffect(() => {
        let alive = true;

        (async () => {
            try {
                const [feat, neu, pop, packsRes, kitsRes] = await Promise.allSettled([
                    api.get("/beats/featured?limit=20"),
                    api.get("/beats/new?limit=10"),
                    api.get("/beats/popular?limit=10"),
                    api.get("/packs/featured?limit=20"),
                    api.get("/kits/featured?limit=20"),
                ]);

                if (!alive) return;

                // beats (unwrap items)
                const featuredBeats = feat.status === "fulfilled" && Array.isArray(feat.value.data?.items)
                    ? feat.value.data.items.map(mapBeat)
                    : [];
                const newBeats = neu.status === "fulfilled" && Array.isArray(neu.value.data?.items)
                    ? neu.value.data.items.map(mapBeat)
                    : [];
                const popularBeats = pop.status === "fulfilled" && Array.isArray(pop.value.data?.items)
                    ? pop.value.data.items.map(mapBeat)
                    : [];

                setFeatured(featuredBeats);
                setLatest(newBeats);
                setPopular(popularBeats);

                // packs/kits
                const packs = packsRes.status === "fulfilled" && Array.isArray(packsRes.value.data?.items)
                    ? packsRes.value.data.items.map(mapPackOrKit)
                    : [];
                const kits = kitsRes.status === "fulfilled" && Array.isArray(kitsRes.value.data?.items)
                    ? kitsRes.value.data.items.map(mapPackOrKit)
                    : [];

                setFeaturedPacks(packs);
                setFeaturedKits(kits);

                hydratePricesFor("beats", featuredBeats, (id, patch) => patchBeatEverywhere(id, patch));
                hydratePricesFor("beats", newBeats, (id, patch) => patchBeatEverywhere(id, patch));
                hydratePricesFor("beats", popularBeats, (id, patch) => patchBeatEverywhere(id, patch));

                hydratePricesFor("packs", packs, (id, patch) => patchPackEverywhere(id, patch));
                hydratePricesFor("kits", kits, (id, patch) => patchKitEverywhere(id, patch));
            } catch (e) {
                console.error("Landing fetch failed:", e);
            }
        })();

        return () => {
            alive = false
        };
    }, []);


    return (
        <div className="page">
            {/* Header */}


            {/* Main */}
            <main className="main">
                {/*<section className="section">*/}
                {/*    <h1>Discover beats</h1>*/}
                {/*    <p className="subtitle">Featured, New and Popular on Drilldex</p>*/}
                {/*</section>*/}

                <section className="hero">
                    <div className="hero__inner">
                        <h1 className="hero__title">THE #1 DRILL MARKETPLACE</h1>
                        <p className="hero__sub">Discover, buy, and sell drill beats from top producers</p>

                        <form className="hero__search" onSubmit={onHeroSearch} role="search" aria-label="Search beats">
                            <IoSearch className="hero__searchIcon" aria-hidden="true"/>
                            <input
                                className="hero__searchInput"
                                type="search"
                                placeholder="Search beats, styles, tags…"
                                value={heroQuery}
                                onChange={(e) => setHeroQuery(e.target.value)}
                            />
                            <button className="hero__searchBtn" type="submit">Search</button>
                        </form>

                        <div className="hero__tags">
                            {["#chicagodrill", "#dutchdrill", "#frenchdrill", "#ukdrill", "#nydrill", "#afrodrill"].map(t => (
                                <button
                                    key={t}
                                    type="button"
                                    className="hero__tag"
                                    onClick={() => navigate(`/search?q=${encodeURIComponent(t.replace(/^#/, ""))}`)}
                                >
                                    {t}
                                </button>
                            ))}
                        </div>
                    </div>
                </section>

                <DiscoverDrillStyles
                    styles={[
                        {
                            title: "UK Drill",
                            image: "https://thefader-res.cloudinary.com/private_images/w_1440,c_limit,f_auto,q_auto:best/sl2_sgoazh/uk-rapper-sl-tropical-drill-first-interview.jpg",
                            slug: "uk-drill"
                        },
                        {
                            title: "NY Drill",
                            image: "https://i1.sndcdn.com/artworks-rOgmsTz3H0Csn0dU-cRmjcw-t500x500.jpg",
                            slug: "ny-drill"
                        },
                        {
                            title: "Chicago Drill",
                            image: "https://images.squarespace-cdn.com/content/v1/520ed800e4b0229123208764/1629321489423-UTT08M0SY06LVRQJR5ZC/chief-keef-650-430-a-compressed.jpeg",
                            slug: "chicago-drill"
                        },
                        {
                            title: "Dutch Drill",
                            image: "https://r.testifier.nl/Acbs8526SDKI/resizing_type:fill/width:3840/height:2560/plain/https://s3-newsifier.ams3.digitaloceanspaces.com/puna.nl/images/2023-02/Schermafbeelding-2021-03-19-om-12.30.09.jpg@webp",
                            slug: "dutch-drill"
                        },
                        {
                            title: "French Drill",
                            image: "https://image-cdn-ak.spotifycdn.com/image/ab67706c0000da84487ba0bd7a5e985519a0af3e",
                            slug: "french-drill"
                        },
                        {
                            title: "Afro Drill",
                            image: "https://digimillennials.com/wp-content/uploads/2023/02/PsychoYP-705x900.jpeg",
                            slug: "afro-drill"
                        },
                        {
                            title: "Canadian Drill",
                            image: "https://i1.sndcdn.com/avatars-bSh6a5hmAxczRpHc-Ra5Ryg-t1080x1080.jpg",
                            slug: "canadian-drill"
                        },
                        {
                            title: "Australian Drill",
                            image: "https://preview.redd.it/can-yall-recommend-some-drill-rappers-that-mask-up-with-v0-uavsf57ml1re1.jpg?width=640&crop=smart&auto=webp&s=87f5b8d7d18ffccbc00376e66a9f3a2576b276db",
                            slug: "australian-drill"
                        },
                        {
                            title: "Irish Drill",
                            image: "https://i.ytimg.com/vi/EwgwOz8nQMI/maxresdefault.jpg",
                            slug: "irish-drill"
                        },
                        {
                            title: "German Drill",
                            image: "https://i1.sndcdn.com/artworks-JbWoOO4iuEfazgEf-j2tzdg-t500x500.jpg",
                            slug: "german-drill"
                        },
                        {
                            title: "Spanish Drill",
                            image: "https://wallpapers.com/images/hd/uk-drill-ziak-xtyra4o3qkfo64xf.jpg",
                            slug: "spanish-drill"
                        },
                        {
                            title: "Italian Drill",
                            image: "https://i.pinimg.com/736x/d6/0f/62/d60f627318f70336d98bc860827e3640.jpg",
                            slug: "italian-drill"
                        },
                        {
                            title: "Brazilian Drill",
                            image: "https://i.guim.co.uk/img/media/545e5215e80e4bd7895cf955e5a48f6ee0cec778/0_198_4200_2520/master/4200.jpg?width=1200&height=900&quality=85&auto=format&fit=crop&s=6753d3a739adc1b53014a0e3a9eaefad",
                            slug: "brazilian-drill"
                        }
                    ]}
                    tags={[
                        "#dark",
                        "#gritty",
                        "#melodicdrill",
                        "#ukdrill",
                        "#nydrill",
                        "#chicagodrill",
                        "#afrodrill",

                    ]}
                    onStyleClick={(style) => console.log("style clicked:", style)}
                    onTagClick={(tag) => console.log("tag clicked:", tag)}
                />

                {/* Featured as cards */}
                <FeaturedSection
                    items={featured}
                    onPlay={(i) => playBeatFromList("featured", i)}
                    onBuy={onBuy}
                    player={player}
                    intentKey={intentKey}
                    openMenuFor={openMenuFor}
                    setOpenMenuFor={setOpenMenuFor}
                    onShare={onShare}
                    onGoToTrack={goToTrack}
                    onAddToPlaylist={addToPlaylist}
                    onToggleLike={toggleLikeBeat}
                />
                <FeaturedPacksSection
                    items={featuredPacks}
                    onPlay={playPackCard}
                    onBuy={openPackBuy}
                    player={player}
                    intentKey={intentKey}
                    openMenuFor={openMenuFor}
                    setOpenMenuFor={setOpenMenuFor}
                    onShare={(p) => onShare({
                        id: p.id,
                        slug: p.slug,
                        title: p.title,
                        artistName: p.creatorName || p.artistName,
                        albumCoverUrl: p.coverUrl,
                    })}
                    onGoToPack={goToPack}
                    onAddToPlaylist={(p) => addToPlaylist({ ...p, _kind: "PACK" })}
                />

                <FeaturedKitsSection
                    items={featuredKits}
                    onPlay={playKitCard}
                    onBuy={addKitToCart}
                    player={player}

                    intentKey={intentKey}
                    openMenuFor={openMenuFor}
                    setOpenMenuFor={setOpenMenuFor}
                    onShare={(k) => onShare({
                        id: k.id,
                        slug: k.slug,
                        title: k.title,
                        artistName: k.creatorName || k.artistName,
                        albumCoverUrl: k.coverUrl,
                    })}
                    onGoToKit={goToKit}
                />

                {/* New + Popular side‑by‑side */}
                <section className="section">
                    <div className="section-columns">
                        <ListSection
                            title="New"
                            items={latest}
                            onPlay={(i) => playBeatFromList("latest", i)}
                            limit={5}
                            showSeeAll
                            openMenuFor={openMenuFor}
                            setOpenMenuFor={setOpenMenuFor}
                            onShare={onShare}
                            onGoToTrack={goToTrack}
                            onAddToPlaylist={addToPlaylist}
                            onBuy={onBuy}
                            onToggleLike={toggleLikeBeat}
                            onGoToComments={goToTrackComments}
                        />
                        <ListSection
                            title="Popular"
                            items={popular}
                            onPlay={(i) => playBeatFromList("popular", i)}
                            limit={5}
                            showSeeAll
                            openMenuFor={openMenuFor}
                            setOpenMenuFor={setOpenMenuFor}
                            onShare={onShare}
                            onGoToTrack={goToTrack}
                            onAddToPlaylist={addToPlaylist}
                            onBuy={onBuy}
                            onToggleLike={toggleLikeBeat}
                            onGoToComments={goToTrackComments}
                        />
                    </div>
                </section>
            </main>
            <LicenseModal
                isOpen={showLicenseModal}
                track={selectedTrack}
                onClose={() => {
                    setShowLicenseModal(false);
                    setSelectedTrack(null);
                }}
                onSelect={({license, action}) => {
                    if (!selectedTrack) return;

                    let item;

                    if (selectedTrack.kind === "PACK") {
                        // build a PACK line (updated)
                        item = {
                            id: `pack-${selectedTrack.id}-${license.id}`, // unique per pack + license
                            type: "pack",
                            packId: selectedTrack.id,
                            title: selectedTrack.title,
                            artist: selectedTrack.artistName || selectedTrack.creatorName || "Unknown",
                            img: selectedTrack.cover || selectedTrack.albumCoverUrl || selectedTrack.img || "",
                            licenseType: license.type || license.id,
                            licenseId: license.id,
                            licenseName: license.name,
                            price: Number(license.price || 0),
                            qty: 1,
                        };
                    } else {
                        // default = BEAT line
                        item = {
                            id: `beat-${selectedTrack.id}-${license.type}`,
                            type: "beat",                 // <-- missing before
                            beatId: selectedTrack.id,
                            title: selectedTrack.title,
                            img: selectedTrack.cover || selectedTrack.albumCoverUrl || selectedTrack.img || "",
                            licenseType: license.type,    // backend needs this
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


function FeaturedSection({
                             items = [],
                             onPlay,
                             onBuy,
                             player,
                             intentKey,
                             openMenuFor,
                             setOpenMenuFor,
                             onShare,
                             onGoToTrack,
                             onAddToPlaylist,
                         }) {
    const hasItems = Array.isArray(items) && items.length > 0;
    const navigate = useNavigate();

    return (
        <section className="section">
            <div className="section__header">
                <h2 className="section__title">Featured Beats</h2>
                {hasItems && (
                    <button
                        className="section__link"
                        onClick={() => navigate("/browse?type=beats&featured=1")}
                    >
                        View all
                    </button>
                )}
            </div>

            {hasItems ? (
                <ul className="featured-grid">
                    {items.slice(0, 5).map((b, i) => {
                        const key = BEAT_KEY(b.id);
                        const active = player?.sourceKey === key;
                        const showPause = active && player?.playing && player?.trackId != null;
                        const pending = intentKey === key && !active;
                        const menuId = `feat-beat-${b.id}`;
                        const menuOpen = openMenuFor === menuId;

                        return (
                            <li key={`featured-${b.id}`} className={`beat-card ${menuOpen ? "is-menu-open" : ""}`}>
                                {/* Thumbnail (play only) */}
                                <div
                                    className="beat-card__thumb"
                                    onClick={() => onPlay && onPlay(i)}
                                    role="button"
                                    aria-label={`${active && player?.playing ? "Pause" : "Play"} ${b.title} by ${b.artistName}`}                                >
                                    <img src={b.albumCoverUrl} alt={b.title}/>
                                    <div className="beat-card__overlay">
                                        {showPause ? (
                                            <svg viewBox="0 0 24 24" aria-hidden="true">
                                                <path d="M6 5h4v14H6zM14 5h4v14h-4z" />
                                            </svg>
                                        ) : pending ? (
                                            <svg viewBox="0 0 24 24" aria-hidden="true" className="spin">
                                                <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="2" fill="none"
                                                        strokeLinecap="round" strokeDasharray="60" strokeDashoffset="30" />
                                            </svg>
                                        ) : (
                                            <svg viewBox="0 0 24 24" aria-hidden="true">
                                                <path d="M8 5v14l11-7z" />
                                            </svg>
                                        )}
                                    </div>
                                </div>

                                {/* Info (no play) */}
                                <div className="beat-card__info" onClick={(e) => e.stopPropagation()}>
                                    <div className="beat-card__title" title={b.title}>{b.title}</div>
                                    <div className="beat-card__artist" title={b.artistName}>
                                        {b.artistName && b.ownerId ? (
                                            <UploaderLink userId={b.ownerId}>
                                                {b.artistName}
                                            </UploaderLink>
                                        ) : (
                                            b.artistName
                                        )}
                                    </div>
                                    <div className="beat__sub">
                                        {b.genre} · {b.bpm} BPM · {formatDuration(b.durationInSeconds)}
                                    </div>
                                    {b.tags?.length > 0 && (
                                        <div className="card-tags">
                                            {b.tags.slice(0, 3).map(t => <span key={t} className="tag">{t}</span>)}
                                            {b.tags.length > 3 &&
                                                <span className="tag tag--more">+{b.tags.length - 3}</span>}
                                        </div>
                                    )}
                                    {/* Kebab */}
                                    <div className="moremenu">
                                        <button
                                            className="more-actions-btn"
                                            onClick={(e) => {
                                                e.stopPropagation();
                                                setOpenMenuFor(openMenuFor === menuId ? null : menuId);
                                            }}
                                            aria-label="More actions"
                                        >
                                            <IoEllipsisHorizontal size={18}/>
                                        </button>

                                        {openMenuFor === menuId && (
                                            <div className="moremenu__panel" role="menu"
                                                 onClick={(e) => e.stopPropagation()}>
                                                <button className="moremenu__item" onClick={() => {
                                                    onShare?.(b);
                                                    setOpenMenuFor(null);
                                                }}>
                                                    <IoShareOutline size={18}/> <span>Share</span>
                                                </button>
                                                <button className="moremenu__item" onClick={() => {
                                                    onGoToTrack?.(b);
                                                    setOpenMenuFor(null);
                                                }}>
                                                    <IoOpenOutline size={18}/> <span>Go to track</span>
                                                </button>
                                                {b.ownerId && (
                                                    <button
                                                        className="moremenu__item"
                                                        onClick={() => navigate(`/profile/${b.ownerId}`)}
                                                    >
                                                        <IoPersonOutline size={18} /> Go to Artist Profile
                                                    </button>
                                                )}

                                                <button
                                                    className="moremenu__item"
                                                    onClick={() => {
                                                        onBuy?.(b);
                                                        setOpenMenuFor(null);
                                                    }}
                                                >
                                                    <IoCartOutline size={18}/> <span>Buy License</span>
                                                </button>
                                                <button className="moremenu__item" onClick={() => {
                                                    onAddToPlaylist?.(b);
                                                    setOpenMenuFor(null);
                                                }}>
                                                    <IoListOutline size={18}/> <span>Add to playlist</span>
                                                </button>
                                            </div>
                                        )}
                                    </div>

                                </div>
                                {onBuy && (
                                    <div className="beat-card__buy">
                                        <button
                                            className="price-btn price-btn--full"
                                            onClick={(e) => {
                                                e.stopPropagation();
                                                onBuy(b);
                                            }}
                                            aria-label={`Buy ${b.title}`}
                                        >
                                            <IoCartOutline size={18} style={{marginRight: 6}}/>
                                            {Number.isFinite(b.price) ? `$${Number(b.price || 0).toFixed(2)}` : "Buy"}
                                        </button>
                                    </div>
                                )}
                            </li>
                        );
                    })}
                </ul>
            ) : (
                <div className="list__empty" role="status" aria-live="polite">
                    Feature the first beat.
                </div>
            )}
        </section>
    );
}

function FeaturedPacksSection({
                                  items = [],
                                  onPlay,
                                  onBuy,
                                  player,
                                  intentKey,
                                  openMenuFor,
                                  setOpenMenuFor,
                                  onShare,
                                  onGoToPack,
                                  onAddToPlaylist,
                              }) {
    const hasItems = Array.isArray(items) && items.length > 0;
    const navigate = useNavigate();

    return (
        <section className="section">
            <div className="section__header">
                <h2 className="section__title">Featured Packs</h2>
                {hasItems && (
                    <button
                        className="section__link"
                        onClick={() => navigate("/browse?type=packs&featured=1")}
                    >
                        View all
                    </button>
                )}
            </div>

            {hasItems ? (
                <ul className="featured-grid">
                    {items.slice(0, 5).map((p) => {
                        const key = PACK_KEY(p.id);
                        const active = player?.sourceKey === key;
                        const showPause = active && player?.playing && player?.trackId != null;
                        const pending = intentKey === key && !active;
                        const menuId = `feat-pack-${p.id}`;

                        return (
                            <li
                                key={`featured-pack-${p.id}`}
                                className={`beat-card ${openMenuFor === menuId ? "is-menu-open" : ""}`}
                            >
                                {/* Thumb (play only) */}
                                <div
                                    className="beat-card__thumb"
                                    onClick={() => onPlay && onPlay(p)}
                                    role="button"
                                    aria-label={`Play ${p.title} by ${p.creatorName || p.artistName}`}
                                >
                                    <img src={p.coverUrl} alt={p.title}/>
                                    <div className="beat-card__overlay">
                                        {showPause ? (
                                            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 5h4v14H6zM14 5h4v14h-4z"/></svg>
                                        ) : pending ? (
                                            <svg viewBox="0 0 24 24" aria-hidden="true" className="spin">
                                                <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="2" fill="none"
                                                        strokeLinecap="round" strokeDasharray="60" strokeDashoffset="30" />
                                            </svg>
                                        ) : (
                                            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5v14l11-7z"/></svg>
                                        )}
                                    </div>
                                </div>

                                {/* Info (no play) */}
                                <div className="beat-card__info" onClick={(e) => e.stopPropagation()}>
                                    <div className="beat-card__title" title={p.title}>{p.title}</div>
                                    <div className="beat-card__artist" title={p.creatorName || p.artistName}>
                                        <UploaderLink userId={p.ownerId}>
                                            {p.creatorName || p.artistName}
                                        </UploaderLink>
                                    </div>

                                    <div className="beat__sub">
                                        {p.trackCount} track{p.trackCount !== 1 ? "s" : ""} · {p.totalDuration}
                                    </div>

                                    {p.tags?.length > 0 && (
                                        <div className="card-tags">
                                            {p.tags.slice(0, 3).map(t => <span key={t} className="tag">{t}</span>)}
                                            {p.tags.length > 3 &&
                                                <span className="tag tag--more">+{p.tags.length - 3}</span>}
                                        </div>
                                    )}

                                    {/* Kebab + panel (anchored) */}
                                    <div className="moremenu" onClick={(e) => e.stopPropagation()}>
                                        <button
                                            className="more-actions-btn"
                                            onClick={() =>
                                                setOpenMenuFor(openMenuFor === menuId ? null : menuId)
                                            }
                                            aria-label="More actions"
                                        >
                                            <IoEllipsisHorizontal size={18}/>
                                        </button>

                                        {openMenuFor === menuId && (
                                            <div className="moremenu__panel" role="menu">
                                                <button className="moremenu__item" onClick={() => {
                                                    onShare?.(p);
                                                    setOpenMenuFor(null);
                                                }}>
                                                    <IoShareOutline size={18}/> <span>Share</span>
                                                </button>
                                                {p.ownerId && (
                                                    <button
                                                        className="moremenu__item"
                                                        onClick={() => navigate(`/profile/${p.ownerId}`)}
                                                    >
                                                        <IoPersonOutline size={18} /> Go to Artist Profile
                                                    </button>
                                                )}
                                                <button className="moremenu__item" onClick={() => {
                                                    onGoToPack?.(p);
                                                    setOpenMenuFor(null);
                                                }}>
                                                    <IoOpenOutline size={18}/> <span>Go to pack</span>
                                                </button>
                                                <button className="moremenu__item" onClick={() => {
                                                    onBuy?.(p);
                                                    setOpenMenuFor(null);
                                                }}>
                                                    <IoCartOutline size={18}/> <span>Buy (choose license)</span>
                                                </button>
                                                <button className="moremenu__item" onClick={() => {
                                                    onAddToPlaylist?.(p);
                                                    setOpenMenuFor(null);
                                                }}>
                                                    <IoListOutline size={18}/> <span>Add to playlist</span>
                                                </button>
                                            </div>
                                        )}
                                    </div>
                                </div>


                                {onBuy && (
                                    <div className="beat-card__buy">
                                        <button
                                            className="price-btn price-btn--full"
                                            onClick={(e) => {
                                                e.stopPropagation();
                                                onBuy(p);
                                            }}
                                            aria-label={`Buy ${p.title}`}
                                        >
                                            <IoCartOutline size={18} style={{marginRight: 6}}/>
                                            {Number.isFinite(p.price) ? `$${Number(p.price || 0).toFixed(2)}` : "Buy"}
                                        </button>
                                    </div>
                                )}
                            </li>
                        );
                    })}
                </ul>
            ) : (
                <div className="list__empty" role="status" aria-live="polite">
                    Feature the first pack.
                </div>
            )}
        </section>
    );
}

function FeaturedKitsSection({
                                 items = [],
                                 onPlay,
                                 onBuy,
                                 player,
                                 intentKey,
                                 openMenuFor,
                                 setOpenMenuFor,
                                 onShare,
                                 onGoToKit,
                             }) {
    const hasItems = Array.isArray(items) && items.length > 0;
    const navigate = useNavigate();

    return (
        <section className="section">
            <div className="section__header">
                <h2 className="section__title">Featured Kits</h2>
                {hasItems && (
                    <button
                        className="section__link"
                        onClick={() => navigate("/browse?type=kits&featured=1")}
                    >
                        View all
                    </button>
                )}
            </div>

            {hasItems ? (
                <ul className="featured-grid">
                    {items.slice(0, 5).map((k) => {
                        const key = KIT_KEY(k.id);
                        const active = player?.sourceKey === key;
                        const showPause = active && player?.playing && player?.trackId != null;
                        const pending = intentKey === key && !active;
                        const menuId = `feat-kit-${k.id}`;

                        return (
                            <li
                                key={`featured-kit-${k.id}`}
                                className={`beat-card ${openMenuFor === menuId ? "is-menu-open" : ""}`}
                            >
                                {/* Thumb (play only) */}
                                <div
                                    className="beat-card__thumb"
                                    onClick={() => onPlay && onPlay(k)}
                                    role="button"
                                    aria-label={`Play ${k.title} by ${k.creatorName || k.artistName}`}
                                >
                                    <img src={k.coverUrl} alt={k.title}/>
                                    <div className="beat-card__overlay">
                                        {showPause ? (
                                            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 5h4v14H6zM14 5h4v14h-4z"/></svg>
                                        ) : pending ? (
                                            <svg viewBox="0 0 24 24" aria-hidden="true" className="spin">
                                                <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="2" fill="none"
                                                        strokeLinecap="round" strokeDasharray="60" strokeDashoffset="30" />
                                            </svg>
                                        ) : (
                                            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5v14l11-7z"/></svg>
                                        )}
                                    </div>
                                </div>

                                {/* Info (no play) */}
                                <div className="beat-card__info" onClick={(e) => e.stopPropagation()}>
                                    <div className="beat-card__title" title={k.title}>{k.title}</div>
                                    <div className="beat-card__artist" title={k.creatorName || k.artistName}>
                                        <UploaderLink userId={k.ownerId}>
                                            {k.creatorName || k.artistName}
                                        </UploaderLink>
                                    </div>

                                    <div className="beat__sub">
                                        {k.type} · {formatDuration(k.totalDurationSec)}
                                    </div>

                                    {k.tags?.length > 0 && (
                                        <div className="card-tags">
                                            {k.tags.slice(0, 3).map(t => <span key={t} className="tag">{t}</span>)}
                                            {k.tags.length > 3 &&
                                                <span className="tag tag--more">+{k.tags.length - 3}</span>}
                                        </div>
                                    )}

                                    {/* Kebab + anchored panel */}
                                    <div className="moremenu" onClick={(e) => e.stopPropagation()}>
                                        <button
                                            className="more-actions-btn"
                                            onClick={() =>
                                                setOpenMenuFor(openMenuFor === menuId ? null : menuId)
                                            }
                                            aria-label="More actions"
                                        >
                                            <IoEllipsisHorizontal size={18}/>
                                        </button>

                                        {openMenuFor === menuId && (
                                            <div className="moremenu__panel" role="menu">
                                                <button
                                                    className="moremenu__item"
                                                    onClick={() => {
                                                        onShare && onShare(k);
                                                        setOpenMenuFor(null);
                                                    }}
                                                >
                                                    <IoShareOutline size={18}/> <span>Share</span>
                                                </button>
                                                {k.ownerId && (
                                                    <button
                                                        className="moremenu__item"
                                                        onClick={() => navigate(`/profile/${k.ownerId}`)}
                                                    >
                                                        <IoPersonOutline size={18} /> Go to Artist Profile
                                                    </button>
                                                )}

                                                <button
                                                    className="moremenu__item"
                                                    onClick={() => {
                                                        onGoToKit && onGoToKit(k);
                                                        setOpenMenuFor(null);
                                                    }}
                                                >
                                                    <IoOpenOutline size={18}/> <span>Go to kit</span>
                                                </button>

                                                <button
                                                    className="moremenu__item"
                                                    onClick={() => {
                                                        onBuy && onBuy(k); // add straight to cart
                                                        setOpenMenuFor(null);
                                                    }}
                                                >
                                                    <IoCartOutline size={18}/> <span>Add to cart</span>
                                                </button>
                                            </div>
                                        )}
                                    </div>

                                </div>
                                {onBuy && (
                                    <div className="beat-card__buy">
                                        <button
                                            className="price-btn price-btn--full"
                                            onClick={(e) => {
                                                e.stopPropagation();
                                                onBuy(k);
                                            }}
                                            aria-label={`Add ${k.title} to cart`}
                                        >
                                            <IoCartOutline size={18} style={{marginRight: 6}}/>
                                            {Number.isFinite(k.price) ? `$${Number(k.price || 0).toFixed(2)}` : "Buy"}
                                        </button>
                                    </div>
                                )}
                            </li>
                        );
                    })}
                </ul>
            ) : (
                <div className="list__empty" role="status" aria-live="polite">
                    Feature the first kit.
                </div>
            )}
        </section>
    );
}

function ListSection({
                         title, items, onPlay, showSeeAll = false,
                         openMenuFor, setOpenMenuFor,
                         onShare, onGoToTrack, onAddToCart, onAddToPlaylist, onBuy,
                         onToggleLike, onGoToComments,
                     }) {
    const hasItems = Array.isArray(items) && items.length > 0;
    const navigate = useNavigate();


    return (
        <section className="section">
            <div className="section__header">
                <h2 className="section__title">{title}</h2>
                {showSeeAll && hasItems && (
                    <button
                        className="section__link"
                        onClick={() =>
                            navigate(`/charts?tab=${encodeURIComponent(TAB_MAP[title] ?? title.toLowerCase())}`)
                        }
                    >
                        View all
                    </button>
                )}
            </div>

            {hasItems ? (
                <ul className="list list--grid">
                    {items.slice(0, 5).map((b, i) => (
                        <li
                            key={`${title}-${b.id}`}
                            className={`list__row listcard ${openMenuFor === b.id ? 'is-menu-open' : ''}`}
                            onClick={() => onPlay(i)}
                            role="button"
                            aria-label={`Play ${b.title} by ${b.artistName}`}
                        >
                            {/* LEFT: image + tags */}
                            <div className="list__left">
                                <div className="list__cover">
                                    {b.albumCoverUrl && <img src={b.albumCoverUrl} alt={b.title} />}
                                </div>
                            </div>

                            {/* MIDDLE */}
                            <div className="list__meta">
                                <div className="list__title">{b.title}</div>

                                <div className="list__sub">
                                    <UploaderLink userId={b.ownerId || b.artistId}>
                                        {b.artistName}
                                    </UploaderLink>
                                </div>

                                <div className="list__light">
                                    {b.genre} · {b.bpm} BPM · {Math.round(b.durationInSeconds / 60)}m {b.durationInSeconds % 60}s
                                </div>

                                {/* ✅ Moved tags here */}
                                {Array.isArray(b.tags) && b.tags.length > 0 && (
                                    <div className="list__tags">
                                        {b.tags.slice(0, 4).map((t) => (
                                            <span key={t} className="tag">{t}</span>
                                        ))}
                                        {b.tags.length > 4 && (
                                            <span className="tag tag--more">+{b.tags.length - 4}</span>
                                        )}
                                    </div>
                                )}
                            </div>

                            {/* RIGHT */}
                            <div className="list__stats">
                                <button
                                    className="more-actions-btn"
                                    onClick={(e) => {
                                        e.stopPropagation();
                                        setOpenMenuFor(openMenuFor === b.id ? null : b.id);
                                    }}
                                    onMouseDown={(e) => e.stopPropagation()}
                                    aria-label="More actions"
                                >
                                    <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor">
                                        <circle cx="5" cy="12" r="2"/>
                                        <circle cx="12" cy="12" r="2"/>
                                        <circle cx="19" cy="12" r="2"/>
                                    </svg>
                                </button>

                                {openMenuFor === b.id && (
                                    <div className="moremenu__panel" role="menu" onClick={(e) => e.stopPropagation()}>
                                        <button className="moremenu__item" onClick={() => onShare(b)}>
                                            <IoShareOutline size={18}/> Share
                                        </button>
                                        <button className="moremenu__item" onClick={() => onGoToTrack(b)}>
                                            <IoOpenOutline size={18}/> Go to track
                                        </button>
                                        <button
                                            className="moremenu__item"
                                            onClick={(e) => {
                                                e.stopPropagation();
                                                onBuy(b);
                                                setOpenMenuFor(null);
                                            }}
                                        >
                                            <IoCartOutline size={18}/> Buy License
                                        </button>
                                        {b.ownerId && (
                                            <button
                                                className="moremenu__item"
                                                onClick={() => navigate(`/profile/${b.ownerId}`)}
                                            >
                                                <IoPersonOutline size={18} /> Go to Artist Profile
                                            </button>
                                        )}
                                        <button className="moremenu__item" onClick={() => onAddToPlaylist(b)}>
                                            <IoListOutline size={18}/> Add to playlist
                                        </button>
                                    </div>
                                )}

                                <button
                                    className={`pill pill--clickable ${b.liked ? "is-active" : ""}`}
                                    onClick={(e) => {
                                        e.stopPropagation();
                                        onToggleLike?.(b);
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
                                        onGoToComments?.(b);
                                    }}
                                    aria-label="Open comments"
                                    title="Open comments"
                                >
                                    <svg viewBox="0 0 24 24" aria-hidden="true">
                                        <path
                                            d="M21 15a4 4 0 0 1-4 4H8l-5 3V7a4 4 0 0 1 4-4h10a4 4 0 0 1 4 4v8z"
                                            fill="none" stroke="currentColor" strokeLinecap="round"
                                            strokeLinejoin="round" strokeWidth="2"
                                        />
                                    </svg>
                                    {b.commentCount}
                                </button>

                                <button
                                    className="price-btn"
                                    onClick={(e) => {
                                        e.stopPropagation();
                                        onBuy(b);
                                    }}
                                >
                                    <IoCartOutline size={18} style={{marginRight: "6px"}}/>
                                    ${Number(b.price || 0).toFixed(2)}
                                </button>
                            </div>
                        </li>
                    ))}
                </ul>
            ) : (
                <div className="list__empty" role="status" aria-live="polite">
                    No {String(title || "beats").toLowerCase()} yet.
                </div>
            )}
        </section>
    );
}

function IconButton({children, onClick, label}) {
    return (
        <button className="iconbtn" onClick={onClick} title={label} aria-label={label}>
            {children}
        </button>
    );
}
