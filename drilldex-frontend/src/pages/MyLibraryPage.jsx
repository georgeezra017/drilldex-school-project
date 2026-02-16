// src/pages/MyLibraryPage.jsx
import React, {useEffect, useMemo, useRef, useState, useCallback} from "react";
import {Link} from "react-router-dom";
import "./MyLibraryPage.css";
import api from "../lib/api";
import toast from "react-hot-toast";
import { useLocation } from "react-router-dom";
import {FiPlay, FiPause, FiFileText, FiDownload, FiArchive, FiSearch, FiX, FiRefreshCw} from "react-icons/fi";
import ConfirmCancelModal from "../components/ConfirmCancelModal.jsx";

/* -------- helpers (mirrors OrderCompletePage) ------------------------ */
const fmtUSD = (n) =>
    new Intl.NumberFormat("en-US", {style: "currency", currency: "USD"}).format(n ?? 0);

const API_BASE = (api?.defaults?.baseURL || "/api").replace(/\/+$/, "");

function toApiUrl(u) {
    if (!u) return u;
    if (/^https?:\/\//i.test(u)) return u;
    u = String(u).replace(/^\//, "");
    if (u.startsWith("api/")) u = u.slice(4);
    return `${API_BASE}/${u}`;
}

function getFilenameFromCD(cd) {
    if (!cd) return null;
    const star = /filename\*=(?:UTF-8''|)([^;]+)/i.exec(cd);
    if (star && star[1]) return decodeURIComponent(star[1].replace(/"/g, "").trim());
    const plain = /filename="?([^"]+)"?/i.exec(cd);
    if (plain && plain[1]) return plain[1].trim();
    return null;
}

function normalizeApiPath(u) {
    if (!u) return u;
    if (/^https?:\/\//i.test(u)) return u; // absolute URLs untouched
    u = u.replace(/^\//, "");
    if (u.startsWith("api/")) u = u.slice(4);
    return u;
}

async function downloadViaApi(url, fallbackName = "download.bin") {
    const toastId = toast.loading("Downloading…");
    try {
        const resp = await api.get(url, { responseType: "blob" });
        const type = resp.headers["content-type"] || "application/octet-stream";
        const cd = resp.headers["content-disposition"];
        const filename = getFilenameFromCD(cd) || fallbackName;

        const blob = new Blob([resp.data], { type });
        const href = URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = href;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        a.remove();
        URL.revokeObjectURL(href);

        toast.success("Download complete!", { id: toastId });
    } catch (e) {
        console.error("Download failed:", e);
        toast.error("Sorry, the download failed. Please try again.", { id: toastId });
    }
}

/* Expected backend rows:
[
  { purchaseId, type: "beat"|"pack"|"kit", title, img, pricePaid?, purchasedAt?,
    licenseUrl?, zipUrl? }
]
*/

export default function MyLibraryPage() {
    const [loading, setLoading] = useState(true);
    const [rows, setRows] = useState([]);
    const [tab, setTab] = useState("all"); // all | beat | pack | kit
    const [q, setQ] = useState("");
    const [selected, setSelected] = useState(null);
    const [cancelTarget, setCancelTarget] = useState(null);
    const [playingKey, setPlayingKey] = useState(null); // e.g. "beat:123" or "content:123:0"
    const [isPlaying, setIsPlaying] = useState(false);
    const blobUrlCache = useRef(new Map());   // key => objectURL
    const currentObjectUrl = useRef(null);
    const [contents, setContents] = useState({});
    const [lastPlayedKey, setLastPlayedKey] = useState(null);
    const location = useLocation();
    const beatStreamUrl = useCallback(
        (purchaseId) => toApiUrl(`purchases/beats/${purchaseId}/stream`),
        []
    );

    useEffect(() => {
        if (location.state?.tab) {
            setTab(location.state.tab);
        }
    }, [location.state]);


    const itemStreamUrl = useCallback((purchaseId, type, keyOrUrl) => {
        // If API already supplies a URL, normalize and use it
        if (keyOrUrl && (/^https?:\/\//i.test(keyOrUrl) || String(keyOrUrl).startsWith("/api/"))) {
            const clean = String(keyOrUrl).replace(/^\/api\/?/, "");
            return toApiUrl(clean);
        }
        // Otherwise treat it as an S3 key your backend expects in ?key=
        const base = type === "pack" ? "packs" : "kits";
        return toApiUrl(`purchases/${base}/${purchaseId}/stream?key=${encodeURIComponent(keyOrUrl || "")}`);
    }, []);

    useEffect(() => {
        return () => {
            // revoke all cached object URLs to avoid leaks
            for (const url of blobUrlCache.current.values()) {
                try {
                    URL.revokeObjectURL(url);
                } catch {
                }
            }
            blobUrlCache.current.clear();
        };
    }, []);

    const onPause = () => {
        document.dispatchEvent(new CustomEvent("audio:pause"));
    };

    useEffect(() => {
        let mounted = true;
        (async () => {
            try {
                const [purchaseResp, promoResp, subResp] = await Promise.all([
                    api.get("/purchases/mine"),
                    api.get("/promotions/mine"),
                    api.get("/billing/subscriptions/mine"),
                ]);

                if (!mounted) return;

                const purchaseMapped = (purchaseResp.data || []).map((p) => {
                    const type = (p.type || "").toLowerCase();
                    const purchaseId = p.purchaseId ?? p.id;
                    const licenseHref = normalizeApiPath(
                        p.licenseUrl || (purchaseId ? `purchases/${purchaseId}/license` : null)
                    );

                    let zipHref = normalizeApiPath(p.zipUrl || null);
                    if (!zipHref && purchaseId && type === "pack") {
                        zipHref = `purchases/packs/${purchaseId}/download`;
                    }
                    if (!zipHref && purchaseId && type === "kit") {
                        zipHref = `purchases/kits/${purchaseId}/download`;
                    }

                    const audioHref =
                        type === "beat" && purchaseId
                            ? normalizeApiPath(`purchases/beats/${purchaseId}/download`)
                            : null;

                    const streamHref = type === "beat" ? beatStreamUrl(purchaseId) : null;

                    return {
                        ok: true,
                        type,
                        title: p.title || "Untitled",
                        thumb: p.img || "/placeholder-cover.jpg",
                        purchaseId,
                        pricePaid: p.pricePaid ?? null,
                        purchasedAt: p.purchasedAtIso || p.createdAtIso || p.purchasedAt || null,
                        licenseHref,
                        zipHref,
                        audioHref,
                        streamHref,
                    };
                });

                const promoMapped = (promoResp.data || []).map((p) => ({
                    type: "promotion",
                    purchaseId: p.id,
                    title: p.title,
                    thumb: p.thumb || "/placeholder-cover.jpg",
                    pricePaid: null,
                    purchasedAt: p.startedAt,
                    licenseHref: null,
                    zipHref: null,
                    audioHref: null,
                    streamHref: null,
                    tier: p.tier,
                    status: (p.status || "").toLowerCase(),
                    endedAt: p.endsAt || null,
                }));

                const subMapped = (subResp.data || []).map((s) => ({
                    type: "subscription",
                    purchaseId: s.id,
                    title:
                        s.planLabel
                            ? s.planLabel.charAt(0).toUpperCase() + s.planLabel.slice(1)
                            : s.planName
                                ? s.planName.charAt(0).toUpperCase() + s.planName.slice(1) + " Monthly"
                                : "Subscription",
                    thumb: "/logo.png",
                    pricePaid: null,
                    purchasedAt: s.startedAt || s.createdAt || s.updatedAt,
                    licenseHref: null,
                    zipHref: null,
                    audioHref: null,
                    streamHref: null,
                    planName: s.planName ?? s.plan ?? null,
                    status: (s.status || "").toLowerCase(),
                    endedAt: s.endedAt || null,
                    currentPeriodEnd: s.currentPeriodEnd ?? null,
                }));

                const allItems = [...purchaseMapped, ...promoMapped, ...subMapped];


                const sortPriority = {
                    subscription: 0,
                    promotion: 1,
                    beat: 2,
                    pack: 2,
                    kit: 2,
                };

                allItems.sort((a, b) => {
                    const pA = sortPriority[a.type] ?? 99;
                    const pB = sortPriority[b.type] ?? 99;
                    return pA - pB;
                });

                setRows(allItems);

                if (!selected && allItems.length) setSelected(allItems[0]);

            } catch (e) {
                console.error(e);
                toast.error("Failed to load your library.");
            } finally {
                if (mounted) setLoading(false);
            }
        })();
        return () => {
            mounted = false;
        };
    }, []);

    // Lazy load tracks for selected pack/kit
    useEffect(() => {
        (async () => {
            if (!selected) return;
            if (selected.type !== "pack" && selected.type !== "kit") return;
            if (contents[selected.purchaseId]) return;

            try {
                const base = selected.type === "pack" ? "packs" : "kits";
                const {data} = await api.get(
                    normalizeApiPath(`purchases/${base}/${selected.purchaseId}/tracks`)
                );
                const mapped = (data || []).map((t, i) => ({
                    title: t.name || t.title || (t.key || t.s3Key || t.path || '').split('/').pop() || `Track ${i + 1}`,
                    duration: t.duration ?? null,
                    streamUrl: t.streamUrl
                        ? toApiUrl(String(t.streamUrl).replace(/^\/api\/?/, ""))
                        : itemStreamUrl(selected.purchaseId, selected.type, t.key || t.s3Key || t.path),
                    key: t.key || t.s3Key || t.path || null,
                    _raw: t,
                }));
                setContents((prev) => ({...prev, [selected.purchaseId]: mapped}));
            } catch (e) {
                console.error(e);
                toast.error(`Couldn't load ${selected.type} contents.`);
            }
        })();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [selected?.purchaseId, selected?.type]);

    const filtered = useMemo(() => {
        const ql = q.trim().toLowerCase();
        return rows.filter((r) => {
            if (tab !== "all") {
                if (tab === "services") {
                    if (r.type !== "promotion" && r.type !== "subscription") return false;
                } else {
                    if (r.type !== tab) return false;
                }
            }
            if (!ql) return true;
            return (
                r.title.toLowerCase().includes(ql) ||
                (r.type && r.type.toLowerCase().includes(ql)) ||
                ("" + r.purchaseId).includes(ql)
            );
        });
    }, [rows, tab, q]);

    const playThroughBar = async (streamUrl, meta = {}) => {
        try {
            toast.loading("Loading audio…", {id: "lib-audio-load"});
            // Fetch with Authorization header (axios interceptor)
            const resp = await api.get(streamUrl, {responseType: "blob"});
            const objectUrl = URL.createObjectURL(resp.data);

            const track = {
                id: meta.key || `${meta.type || "item"}:${meta.purchaseId || "0"}`,
                title: meta.title || "Untitled",
                artistName: meta.artist || "",
                albumCoverUrl: meta.cover || "",
                audioUrl: objectUrl,
            };

            document.dispatchEvent(
                new CustomEvent("audio:play-list", {detail: {list: [track], index: 0}})
            );

            // (optional local UI hint)
            setPlayingKey(track.id);
            setIsPlaying(true);
        } catch (e) {
            console.error(e);
            toast.error("Playback failed.");
        } finally {
            toast.dismiss("lib-audio-load");
        }
    };

    /* ---------------------- playback handlers ---------------------- */

    const stopPlayback = () => {
        const a = audioRef.current;
        if (!a) return;
        a.pause();
        setIsPlaying(false);
        setPlayingKey(null);
    };

    const playUrl = async (url, key) => {
        try {
            const a = audioRef.current;
            if (!a) return;

            // toggle pause if same key and currently playing
            if (playingKey === key && !a.paused) {
                a.pause();
                setIsPlaying(false);
                return;
            }

            if (playingKey !== key) {
                // use cached object URL if available
                let objectUrl = blobUrlCache.current.get(key);
                if (!objectUrl) {
                    toast.loading("Loading audio…", {id: "lib-audio-load"});
                    const resp = await api.get(url, {responseType: "blob"}); // <-- Authorization header included
                    objectUrl = URL.createObjectURL(resp.data);
                    blobUrlCache.current.set(key, objectUrl);
                    toast.dismiss("lib-audio-load");
                }

                if (currentObjectUrl.current && currentObjectUrl.current !== objectUrl) {
                    try {
                        URL.revokeObjectURL(currentObjectUrl.current);
                    } catch {
                    }
                }
                a.src = objectUrl;
                currentObjectUrl.current = objectUrl;
            }

            await a.play();
            setPlayingKey(key);
            setIsPlaying(true);
        } catch (e) {
            toast.dismiss("lib-audio-load");
            console.error(e);
            toast.error("Playback failed.");
        }
    };

    useEffect(() => {
        const handleAudioState = (e) => {
            const { detail } = e;
            const { playing, trackId } = detail || {};

            setIsPlaying(!!playing);
            setPlayingKey(trackId ?? null);

            if (trackId) {
                setLastPlayedKey(trackId); // ✅ Remember last played even if paused
            }

            console.debug("DEBUG audio:state event", trackId, playing);
        };

        document.addEventListener("audio:state", handleAudioState);
        return () => document.removeEventListener("audio:state", handleAudioState);
    }, []);

    const onPlayBeat = async () => {
        const key = `beat:${selected.purchaseId}`;

        // If it's the same track and paused, just resume
        if (playingKey === key && !isPlaying) {
            console.log("DEBUG: Same beat paused, resuming playback");
            document.dispatchEvent(new CustomEvent("audio:resume"));
            return;
        }

        // If it's already playing, pause
        if (playingKey === key && isPlaying) {
            console.log("DEBUG: Same beat playing, pausing playback");
            document.dispatchEvent(new CustomEvent("audio:pause"));
            return;
        }

        // Otherwise it's a new beat, load and play
        try {
            toast.loading("Loading audio…", { id: "beat-load" });

            const resp = await api.get(`purchases/beats/${selected.purchaseId}/stream`, {
                responseType: "blob"
            });

            const objectUrl = URL.createObjectURL(resp.data);

            const track = {
                id: key,
                title: selected.title || "Untitled",
                artistName: "",
                albumCoverUrl: selected.thumb,
                audioUrl: objectUrl,
                metaKey: key,
            };

            document.dispatchEvent(new CustomEvent("audio:play-list", {
                detail: {
                    list: [track],
                    index: 0,
                },
            }));

            setPlayingKey(key);
            setIsPlaying(true);
        } catch (e) {
            console.error(e);
            toast.error("Could not load audio");
        } finally {
            toast.dismiss("beat-load");
        }
    };

    // const onPlayContent = (purchaseId, idx) => {
    //     const items = contents[purchaseId] || [];
    //     if (!items.length) return;
    //
    //     const parent = rows.find(r => r.purchaseId === purchaseId);
    //     const albumCoverUrl = parent?.thumb ?? "";
    //
    //     const trackList = items.map((item, i) => ({
    //         id: `content:${purchaseId}:${i}`,
    //         title: item.title,
    //         artistName: "",
    //         albumCoverUrl,
    //         audioUrl: item.streamUrl,
    //         metaKey: `content:${purchaseId}:${i}`, // required for audio:state tracking
    //     }));
    //
    //     document.dispatchEvent(new CustomEvent("audio:play-list", {
    //         detail: {
    //             list: trackList,
    //             index: idx,
    //         },
    //     }));
    //
    //     setPlayingKey(`content:${purchaseId}:${idx}`);
    //     setIsPlaying(true);
    // };

    const onPlaySingleContentItem = async (purchaseId, index) => {
        const items = contents[purchaseId] || [];
        const item = items[index];
        if (!item?.streamUrl) return;

        const key = `content:${purchaseId}:${index}`;

        if (playingKey === key && isPlaying) {
            document.dispatchEvent(new CustomEvent("audio:pause"));
            return;
        }

        if (playingKey === key && !isPlaying) {
            document.dispatchEvent(new CustomEvent("audio:resume"));
            return;
        }

        toast.loading("Loading track…", { id: "content-load" });

        try {
            const parent = rows.find(r => r.purchaseId === purchaseId);
            const albumCoverUrl = parent?.thumb ?? "";

            // Fetch the single track with secure auth
            const res = await api.get(item.streamUrl, { responseType: "blob" });
            const blobUrl = URL.createObjectURL(res.data);

            const track = {
                id: key,
                title: item.title,
                artistName: "",
                albumCoverUrl,
                audioUrl: blobUrl,
                metaKey: key,
            };

            // 👇 ONLY play this one track (not the whole list)
            document.dispatchEvent(new CustomEvent("audio:play-list", {
                detail: {
                    list: [track],
                    index: 0,
                },
            }));

            setPlayingKey(key);
            setIsPlaying(true);
        } catch (e) {
            console.error(e);
            toast.error("Could not load track.");
        } finally {
            toast.dismiss("content-load");
        }
    };

    const onPlayContent = async (purchaseId, idx) => {
        const items = contents[purchaseId] || [];
        if (!items.length) return;

        const key = `content:${purchaseId}:${idx}`;

        if (playingKey === key && isPlaying) {
            document.dispatchEvent(new CustomEvent("audio:pause"));
            return;
        }

        if (playingKey === key && !isPlaying) {
            document.dispatchEvent(new CustomEvent("audio:resume"));
            return;
        }

        toast.loading("Loading tracks…", { id: "packkit-load" });

        try {
            const parent = rows.find(r => r.purchaseId === purchaseId);
            const albumCoverUrl = parent?.thumb ?? "";

            // Preload all tracks in the pack using blob URLs
            const blobTracks = await Promise.all(
                items.map(async (item, i) => {
                    try {
                        const res = await api.get(item.streamUrl, { responseType: "blob" });
                        const blobUrl = URL.createObjectURL(res.data);
                        return {
                            id: `content:${purchaseId}:${i}`,
                            title: item.title,
                            artistName: "",
                            albumCoverUrl,
                            audioUrl: blobUrl,
                            metaKey: `content:${purchaseId}:${i}`,
                        };
                    } catch (e) {
                        console.warn(`Track ${i} failed to load`, e);
                        return null;
                    }
                })
            );

            const filteredTracks = blobTracks.filter(Boolean);
            if (!filteredTracks.length) {
                toast.error("No playable tracks in this pack.");
                return;
            }

            document.dispatchEvent(new CustomEvent("audio:play-list", {
                detail: {
                    list: filteredTracks,
                    index: idx,
                },
            }));

            setPlayingKey(`content:${purchaseId}:${idx}`);
            setIsPlaying(true);
        } catch (err) {
            console.error(err);
            toast.error("Failed to load pack.");
        } finally {
            toast.dismiss("packkit-load");
        }
    };

    const isSelectedPlaying = (() => {
        if (!selected) {
            console.log("DEBUG isSelectedPlaying: selected is null");
            return false;
        }

        const key =
            selected.type === "beat"
                ? `beat:${selected.purchaseId}`
                : selected.type === "pack" || selected.type === "kit"
                    ? `content:${selected.purchaseId}:0`
                    : null;

        const result = isPlaying && playingKey === key;
        console.log("DEBUG isSelectedPlaying:", { playingKey, key, isPlaying, result });
        return result;
    })();

    const isItemActive = (purchaseId, idx) =>
        playingKey === `content:${purchaseId}:${idx}` && isPlaying;

    return (
        <div className="librarypage">
            <div className="lp__inner">
                {/* global hidden audio element */}


                {/* LEFT: details for selected item */}
                <aside className="lp-card lp-left">
                    {selected ? (
                        <>
                            <div className="lp-cover">
                                <img src={selected.thumb} alt=""/>
                                {(selected.type === "beat" || selected.type === "pack" || selected.type === "kit") && (
                                    <button
                                        className="lp-play"
                                        title={isSelectedPlaying ? "Pause" : "Play"}
                                        aria-label={isSelectedPlaying ? "Pause" : "Play"}
                                        onClick={() => {
                                            const expectedKey =
                                                selected.type === "beat"
                                                    ? `beat:${selected.purchaseId}`
                                                    : selected.type === "pack" || selected.type === "kit"
                                                        ? `content:${selected.purchaseId}:0`
                                                        : null;

                                            if (!expectedKey) return;

                                            // ✅ If currently playing, pause it
                                            if (playingKey === expectedKey && isPlaying) {
                                                console.log("🔇 Pausing track");
                                                document.dispatchEvent(new CustomEvent("audio:pause"));
                                                return;
                                            }

                                            // ✅ If paused but this was the last played track, resume it
                                            if (lastPlayedKey === expectedKey && !isPlaying) {
                                                console.log("▶️ Resuming track");
                                                document.dispatchEvent(new CustomEvent("audio:resume"));
                                                return;
                                            }

                                            console.log("🎵 New track selected, starting playback");

                                            if (selected.type === "beat") {
                                                onPlayBeat();
                                            } else {
                                                const items = contents[selected.purchaseId] || [];
                                                if (items.length) {
                                                    onPlayContent(selected.purchaseId, 0);
                                                } else {
                                                    toast("Loading contents…");
                                                }
                                            }
                                        }}
                                    >
                                        {isSelectedPlaying ? <FiPause size={28} /> : <FiPlay size={28} />}
                                    </button>
                                )}
                            </div>

                            <div className="lp-titleblock">
                                <h1 className="lp-title">{selected.title}</h1>
                                <div className="lp-artistline">
                <span className="lp-artist">
                    {selected.type === "subscription"
                        ? "Subscription"
                        : selected.type === "promotion"
                            ? "Promotion"
                            : "Purchased item"}
                </span>
                                    <div className="oc-success__icon">✓</div>
                                </div>
                            </div>

                            <div className="lp-stats">
                                <div>
                                    <div className="lp-statnum">
                                        {selected.type === "promotion"
                                            ? "Promotion"
                                            : selected.type === "subscription"
                                                ? "Subscription"
                                                : selected.type}
                                    </div>
                                    <div className="lp-statlabel">
                                        {selected.type === "subscription"
                                            ? (selected.planName?.charAt(0).toUpperCase() + selected.planName?.slice(1) || "Unknown")
                                            : selected.type === "promotion"
                                                ? (selected.tier
                                                    ? selected.tier.charAt(0).toUpperCase() + selected.tier.slice(1)
                                                    : "Unknown")
                                                : "Type"}
                                    </div>
                                </div>
                                <div>
                                    <div className="lp-statnum">
                                        {selected.type === "promotion" || selected.type === "subscription"
                                            ? "Ends in"
                                            : "Paid"}
                                    </div>
                                    <div className="lp-statlabel">
                                        {selected.type === "promotion"
                                            ? (() => {
                                                const endsAt = new Date(selected.endedAt || selected.endsAt);
                                                const now = new Date();
                                                const diffMs = endsAt - now;
                                                const diffDays = Math.max(Math.ceil(diffMs / (1000 * 60 * 60 * 24)), 0);
                                                return `${diffDays} day${diffDays !== 1 ? "s" : ""}`;
                                            })()
                                            : selected.type === "subscription"
                                                ? (() => {
                                                    const endsAt = new Date(selected.currentPeriodEnd);
                                                    const now = new Date();
                                                    const diffMs = endsAt - now;
                                                    const diffDays = Math.max(Math.ceil(diffMs / (1000 * 60 * 60 * 24)), 0);
                                                    return `Renews in ${diffDays} day${diffDays !== 1 ? "s" : ""}`;
                                                })()
                                                : selected.pricePaid != null
                                                    ? fmtUSD(selected.pricePaid)
                                                    : "—"}
                                    </div>
                                </div>
                            </div>

                            <div className="lp-info">
                                <div className="lp-infotitle">Details</div>
                                <dl className="lp-infolist">
                                    <dt>Purchase ID</dt>
                                    <dd>{selected.purchaseId}</dd>

                                    {(selected.type === "promotion" || selected.type === "subscription") && (
                                        <>
                                            <dt>Started</dt>
                                            <dd>
                                                {selected.purchasedAt
                                                    ? new Date(selected.purchasedAt).toLocaleString()
                                                    : "—"}
                                            </dd>

                                            {selected.endedAt && (
                                                <>
                                                    <dt>Ends</dt>
                                                    <dd>{new Date(selected.endedAt).toLocaleString()}</dd>
                                                </>
                                            )}

                                            <dt>Status</dt>
                                            <dd>
                                                {selected.status === "canceled" ? (
                                                    <span className="lp-badge expired">Canceled</span>
                                                ) : selected.status === "paused" ? (
                                                    <span className="lp-badge paused">Paused</span>
                                                ) : (
                                                    <span className="lp-badge active">Active</span>
                                                )}
                                            </dd>
                                        </>
                                    )}

                                    {(selected.type === "beat" || selected.type === "pack" || selected.type === "kit") && (
                                        <>
                                            <dt>Purchased</dt>
                                            <dd>
                                                {selected.purchasedAt
                                                    ? new Date(selected.purchasedAt).toLocaleString()
                                                    : "—"}
                                            </dd>
                                        </>
                                    )}
                                </dl>
                            </div>

                            {(selected.type === "beat" || selected.type === "pack" || selected.type === "kit") && (
                                <>
                                    <div className="lp-divider"/>

                                    <div className="lp-actions-row">
                                        {selected.licenseHref && (
                                            <button
                                                className="lp-action"
                                                onClick={() =>
                                                    downloadViaApi(selected.licenseHref, `license-${selected.purchaseId}.pdf`)
                                                }
                                            >
                            <span className="lp-action-icon" aria-hidden="true">
                                <FiFileText/>
                            </span>
                                                <span className="lp-action-num">License</span>
                                            </button>
                                        )}

                                        {selected.type === "beat" && selected.audioHref && (
                                            <button
                                                className="lp-action"
                                                onClick={() => downloadViaApi(selected.audioHref)}
                                            >
                            <span className="lp-action-icon" aria-hidden="true">
                                <FiDownload/>
                            </span>
                                                <span className="lp-action-num">Audio</span>
                                            </button>
                                        )}

                                        {(selected.type === "pack" || selected.type === "kit") && selected.zipHref && (
                                            <button
                                                className="lp-action"
                                                onClick={() =>
                                                    downloadViaApi(selected.zipHref, `${selected.type}-${selected.purchaseId}.zip`)
                                                }
                                            >
                            <span className="lp-action-icon" aria-hidden="true">
                                <FiArchive/>
                            </span>
                                                <span className="lp-action-num">ZIP</span>
                                            </button>
                                        )}
                                    </div>
                                </>
                            )}

                            {(selected.type === "subscription" || selected.type === "promotion") && (
                                <>
                                    <div className="lp-divider" />

                                    <div className="lp-actions-row">
                                        {selected.status?.toLowerCase() === "active" && (
                                            <button
                                                className="lp-action"
                                                onClick={() =>
                                                    setCancelTarget({
                                                        type: selected.type,
                                                        purchaseId: selected.purchaseId,
                                                    })
                                                }
                                            >
  <span className="lp-action-icon" aria-hidden="true">
    <FiX />
  </span>
                                                <span className="lp-action-num">
    Cancel {selected.type === "subscription" ? "Subscription" : "Promotion"}
  </span>
                                            </button>
                                        )}

                                        {selected.status !== "active" && (
                                            <button
                                                className="lp-action"
                                                onClick={async () => {
                                                    if (selected.type === "subscription") {
                                                        try {
                                                            await api.post(`/billing/subscriptions/${selected.purchaseId}/resume`);
                                                            toast.success("Subscription renewed successfully.");
                                                            window.location.reload();
                                                        } catch (e) {
                                                            console.error(e);
                                                            toast.error("Failed to renew subscription.");
                                                        }
                                                    } else if (selected.type === "promotion") {
                                                        toast("To renew this promotion, please go to the promotions page.");
                                                        window.location.href = "/promos";
                                                    }
                                                }}
                                            >
        <span className="lp-action-icon" aria-hidden="true">
            <FiRefreshCw />
        </span>
                                                <span className="lp-action-num">
            Renew {selected.type === "subscription" ? "Subscription" : "Promotion"}
        </span>
                                            </button>
                                        )}
                                    </div>

                                    <div className="lp-divider" />
                                </>
                            )}

                            {(selected.type === "pack" || selected.type === "kit") && (
                                <div className="lp-contents">
                                    <div className="lp-infotitle">Contents</div>

                                    {!contents[selected.purchaseId] && (
                                        <div className="lp-empty sm">Loading contents…</div>
                                    )}

                                    {contents[selected.purchaseId] &&
                                        contents[selected.purchaseId].length === 0 && (
                                            <div className="lp-empty sm">No items.</div>
                                        )}

                                    {contents[selected.purchaseId] &&
                                        contents[selected.purchaseId].length > 0 && (
                                            <ul className="lp-tracklist">
                                                {contents[selected.purchaseId].map((t, i) => (
                                                    <li key={i} className="lp-track">
                                                        <button
                                                            className={`lp-trackplay ${
                                                                isItemActive(selected.purchaseId, i) ? "is-active" : ""
                                                            }`}
                                                            onClick={() => onPlaySingleContentItem(selected.purchaseId, i)}
                                                            aria-label={
                                                                isItemActive(selected.purchaseId, i) ? "Pause" : "Play"
                                                            }
                                                        >
                                                            {isItemActive(selected.purchaseId, i) ? (
                                                                <FiPause/>
                                                            ) : (
                                                                <FiPlay/>
                                                            )}
                                                        </button>
                                                        <div className="lp-trackmeta">
                                                            <div className="lp-tracktitle">{t.title}</div>
                                                            {t.duration &&
                                                                <div className="lp-tracksub">{t.duration}</div>}
                                                        </div>
                                                    </li>
                                                ))}
                                            </ul>
                                        )}
                                </div>
                            )}

                            <div className="lp-tags">
                                <div className="lp-tagrow">
                                    <span className="lp-tag">Owned</span>
                                    <span className="lp-tag">{selected.type}</span>
                                    {selected.pricePaid != null && (
                                        <span className="lp-tag">{fmtUSD(selected.pricePaid)}</span>
                                    )}
                                </div>
                            </div>
                        </>
                    ) : (
                        <div className="lp-empty">{loading ? "Loading..." : "Select an item from your library."}</div>
                    )}
                </aside>

                {/* RIGHT: list + filters */}
                <section className="lp-card lp-right">
                    <div className="lp-righthead">
                        <h2>My Library</h2>
                        <div className="lp-filters">
                            <div className="lp-tabs">
                                {["all", "beat", "pack", "kit", "services"].map((t) => (
                                    <button
                                        key={t}
                                        className={`lp-tab ${tab === t ? "is-active" : ""}`}
                                        onClick={() => setTab(t)}
                                    >
                                        {t === "beat"
                                            ? "Beats"
                                            : t === "services"
                                                ? "Services"
                                                : t[0].toUpperCase() + t.slice(1)}
                                    </button>
                                ))}
                            </div>
                            <div className="lp-searchwrap">
                                <input
                                    className="lp-search"
                                    placeholder="Search your library…"
                                    value={q}
                                    onChange={(e) => setQ(e.target.value)}
                                    aria-label="Search your library"
                                />
                                <FiSearch className="lp-searchicon" aria-hidden="true"/>
                            </div>
                        </div>
                    </div>

                    <div className="lp-list">
                        {filtered.map((r) => (
                            <div
                                key={`${r.type}-${r.purchaseId}`}
                                className={`lp-row ${selected?.purchaseId === r.purchaseId ? "is-selected" : ""}`}
                                onClick={() => setSelected(r)}
                            >
                                <div className="lp-row-left">
                                    <div className="lp-thumb">
                                        <img src={r.thumb} alt=""/>
                                    </div>
                                    <div className="lp-row-meta">
                                        <div className="lp-row-title">{r.title}</div>
                                        <div className="lp-row-sub">
                                            <span className="lp-chip">{r.type}</span>
                                            {r.pricePaid != null &&
                                                <span className="lp-chip">{fmtUSD(r.pricePaid)}</span>}
                                            <span className="lp-chip">#{r.purchaseId}</span>
                                        </div>
                                    </div>
                                </div>

                                <div className="lp-row-actions" onClick={(e) => e.stopPropagation()}>
                                    {/*{r.type === "promotion" && r.status === "active" && (*/}
                                    {/*    <button*/}
                                    {/*        className="oc-btn"*/}
                                    {/*        onClick={() => {*/}
                                    {/*            setCancelTarget({*/}
                                    {/*                type: "promotion",*/}
                                    {/*                purchaseId: r.purchaseId,*/}
                                    {/*            });*/}
                                    {/*        }}*/}
                                    {/*    >*/}
                                    {/*        <FiX /> <span>Cancel Promotion</span>*/}
                                    {/*    </button>*/}
                                    {/*)}*/}

                                    {r.type === "promotion" && r.status === "canceled" && (
                                        <button
                                            className="oc-btn"
                                            onClick={() => {
                                                toast("To renew this promotion, please go to the promotions page.");
                                                window.location.href = "/promos";
                                            }}
                                        >
                                            <FiRefreshCw /> <span>Renew Promotion</span>
                                        </button>
                                    )}



                                    {r.type === "subscription" && r.status === "active" && (
                                        <button
                                            className="oc-btn"
                                            onClick={() =>
                                                setCancelTarget({
                                                    type: "subscription",
                                                    purchaseId: r.purchaseId,
                                                })
                                            }
                                        >
                                            <FiX /> <span>Cancel Subscription</span>
                                        </button>
                                    )}

                                    {/*{r.type === "subscription" && r.status !== "active" && r.status !== "expired" && (*/}
                                    {/*    <button*/}
                                    {/*        className="oc-btn"*/}
                                    {/*        onClick={async () => {*/}
                                    {/*            try {*/}
                                    {/*                await api.post(`/billing/subscriptions/${r.purchaseId}/resume`);*/}
                                    {/*                toast.success("Subscription renewed.");*/}
                                    {/*                window.location.reload();*/}
                                    {/*            } catch (e) {*/}
                                    {/*                console.error(e);*/}
                                    {/*                toast.error("Failed to renew subscription.");*/}
                                    {/*            }*/}
                                    {/*        }}*/}
                                    {/*    >*/}
                                    {/*        <FiRefreshCw /> <span>Renew Subscription</span>*/}
                                    {/*    </button>*/}
                                    {/*)}*/}
                                    {r.licenseHref && (
                                        <button
                                            className="oc-btn oc-btn--primary"
                                            onClick={() => downloadViaApi(r.licenseHref, `license-${r.purchaseId}.pdf`)}
                                        >
                                            <FiFileText/> <span>License PDF</span>
                                        </button>
                                    )}
                                    {r.type === "beat" && r.audioHref && (
                                        <button className="oc-btn" onClick={() => downloadViaApi(r.audioHref)}>
                                            <FiDownload/> <span>Download Audio</span>
                                        </button>
                                    )}
                                    {(r.type === "pack" || r.type === "kit") && r.zipHref && (
                                        <button
                                            className="oc-btn"
                                            onClick={() => downloadViaApi(r.zipHref, `${r.type}-${r.purchaseId}.zip`)}
                                        >
                                            <FiArchive/> <span>Download ZIP</span>
                                        </button>
                                    )}
                                </div>
                            </div>
                        ))}

                        {!loading && filtered.length === 0 && <div className="lp-empty">No items found.</div>}
                    </div>

                    <div className="lp-back">
                        <Link to="/">← Back</Link>
                    </div>
                </section>
            </div>
            <ConfirmCancelModal
                open={!!cancelTarget}
                type={cancelTarget?.type}
                onClose={() => setCancelTarget(null)}
                onConfirm={async () => {
                    try {
                        if (cancelTarget.type === "subscription") {
                            await api.post(`/billing/subscriptions/${cancelTarget.purchaseId}/cancel`);
                        } else {
                            await api.post(`/promotions/${cancelTarget.purchaseId}/cancel`);
                        }
                        toast.success("Canceled successfully.");
                        window.location.reload();
                    } catch (e) {
                        console.error(e);
                        toast.error("Failed to cancel.");
                    } finally {
                        setCancelTarget(null);
                    }
                }}
            />
        </div>
    );
}