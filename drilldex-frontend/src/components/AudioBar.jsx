// src/components/AudioBar.jsx
import {useEffect, useMemo, useRef, useState} from "react";
import toast from "react-hot-toast";
import LicenseModal from "../components/LicenseModal.jsx";
import {useCart} from "../state/cart";
import {
    IoPlay, IoPause, IoShuffle, IoRepeat, IoPlayBackOutline, IoPlayForwardOutline, IoShare, IoCartOutline,
    IoVolumeMute, IoVolumeLow, IoVolumeMedium, IoVolumeHigh, IoListOutline,
} from "react-icons/io5";
import {FcLikePlaceholder, FcLike} from "react-icons/fc";
import api from "../lib/api";
import ShareModal from "../components/ShareModal.jsx";


import "../pages/landing.css";

// Minimal local IconButton (uses your .iconbtn styles)
function IconButton({children, onClick, label, className = ""}) {
    return (
        <button className={`iconbtn ${className}`} onClick={onClick} title={label} aria-label={label}>
            {children}
        </button>
    );
}

const API_ORIGIN = new URL(api.defaults.baseURL).origin;

function toFileUrl(input) {
    if (!input) return "";
    let s = String(input).replace(/\\/g, "/").trim();
    const abs = s.match(/https?:\/\/[^\s]+/i);
    if (abs) return abs[0];
    if (/^https?:\/\//i.test(s)) return s;
    const i = s.lastIndexOf("/uploads/");
    let path = i >= 0 ? s.slice(i) : s;
    if (!path.startsWith("/uploads/")) {
        if (/^(covers|audio|previews|kits)\//i.test(path)) {
            path = `/uploads/${path}`;
        } else {
            return "";
        }
    }
    path = path.replace(/\/{2,}/g, "/");
    return `${API_ORIGIN}${path}`;
}


export default function AudioBar() {
    // --- Core state (self-contained so it works app-wide) ---
    const [queue, setQueue] = useState([]);        // [{ id, title, artistName, audioUrl, albumCoverUrl, ... }]
    const playLockRef = useRef(false);
    const [currentIndex, setCurrentIndex] = useState(0);
    const current = useMemo(() => queue[currentIndex] || null, [queue, currentIndex]);
    // const [isRepeat, setIsRepeat] = useState(false);
    // const isRepeatRef = useRef(isRepeat);
    const [repeatMode, setRepeatMode] = useState("off"); // "off" | "all" | "one"
    const repeatModeRef = useRef(repeatMode);
    const playNextRef = useRef(() => {
    });
    const [showQueue, setShowQueue] = useState(false);
    const [isPlaying, setIsPlaying] = useState(false);
    const [isShuffle, setIsShuffle] = useState(false);

    const [isLiked, setIsLiked] = useState(false);
    const [showMore, setShowMore] = useState(false);
    const {add, clear} = useCart();
    const [showLicenseModal, setShowLicenseModal] = useState(false);
    const [selectedTrack, setSelectedTrack] = useState(null); // { kind: "Beat"|"Pack"|"Kit", id, title, artist, img }
    const [progress, setProgress] = useState({current: 0, duration: 0});
    const [showShareModal, setShowShareModal] = useState(false);
    const [sharePayload, setSharePayload] = useState(null); // { title, subtitle, url, img }

    const [shareTrack, setShareTrack] = useState(null); // { id | slug, title, artistName, cover }
    const [volume, setVolume] = useState(1);
    const [prevVolume, setPrevVolume] = useState(1);
    const isMuted = volume === 0;

    const audioRef = useRef(null);
    const sourceKeyRef = useRef("");
    const queueRef = useRef([]);
    const idxRef = useRef(0);

    const lenRef = useRef(0);
    const isShuffleRef = useRef(false);

    const lastQueueSigRef = useRef("");   // e.g. "12,45,9"
    const lastBumpedKeyRef = useRef("");// e.g. "12,45,9|2"
    const PACK_PREFIX = "pack:";
    const getPackIdFromSourceKey = (k = "") =>
        (typeof k === "string" && k.startsWith(PACK_PREFIX)) ? k.slice(PACK_PREFIX.length) : null;

    const KIT_PREFIX = "kit:";
    const getKitIdFromSourceKey = (k = "") =>
        (typeof k === "string" && k.startsWith(KIT_PREFIX)) ? k.slice(KIT_PREFIX.length) : null;

    const lastPackBumpSigRef = useRef(""); // e.g. "42|id1,id2,id3" so one bump per (packId + list signature)
    const lastKitBumpSigRef = useRef(""); // e.g. "17|id1,id2"

    const LS_KEY = "drilldex:playlist";
    const [playlist, setPlaylist] = useState(() => {
        try {
            return JSON.parse(localStorage.getItem(LS_KEY) || "[]");
        } catch {
            return [];
        }
    });
    useEffect(() => {
        localStorage.setItem(LS_KEY, JSON.stringify(playlist));
    }, [playlist]);

    const sigOf = (list = []) =>
        (Array.isArray(list) ? list.map(t => t?.id).filter(Boolean).join(",") : "");


// Cache /preview-playlist responses by packId with TTL
    const packPreviewCacheRef = useRef(new Map());
// shape: Map(packId, { fetchedAt: number, map: Map<string,idUrl> })
    const PACK_TTL_MS = 2 * 60 * 1000;

    async function loadPackPreviewMap(packId, {force = false} = {}) {
        const now = Date.now();
        const hit = packPreviewCacheRef.current.get(String(packId));
        if (!force && hit && (now - hit.fetchedAt) < PACK_TTL_MS) return hit.map;

        try {
            const {data} = await api.get(`/packs/${packId}/preview-playlist`);
            const m = new Map(
                (Array.isArray(data) ? data : []).map(t => [
                    String(t.id),
                    (t.previewUrl || t.audioUrl || "").trim(),
                ])
            );
            packPreviewCacheRef.current.set(String(packId), {fetchedAt: now, map: m});
            return m;
        } catch (err) {
            console.warn("[AudioBar] loadPackPreviewMap failed", packId, err);
            return new Map();
        }
    }

    const bumpAt = (list, index) => {
        if (!Array.isArray(list) || !list.length) return;
        if (!Number.isInteger(index) || index < 0 || index >= list.length) return;

        const row = list[index];
        const id = row?.id;
        if (!id) return;

        const sig = sigOf(list);

        // If we’re inside a pack, DO NOT bump the beat.
        const packId = row?.packId || getPackIdFromSourceKey(sourceKeyRef.current);
        const kitId = row?.kitId || getKitIdFromSourceKey(sourceKeyRef.current);
        if (packId || kitId) {
            const key = `${packId ? "pack" : "kit"}|${sig}|${index}`;
            if (lastBumpedKeyRef.current === key) return;
            lastBumpedKeyRef.current = key;
            lastQueueSigRef.current = sig

            // Still fire an event for UI/analytics if you want
            document.dispatchEvent(new CustomEvent("audio:track-start", {
                detail: {index, trackId: id, list, packId, kitId}
            }));
            return;
        }

        const key = `${sig}|${index}`;
        if (lastBumpedKeyRef.current === key) return; // already bumped this start

        api.post(`/beats/${id}/play`).catch(() => {
        });
        lastBumpedKeyRef.current = key;
        lastQueueSigRef.current = sig;

        // (optional) notify listeners
        document.dispatchEvent(new CustomEvent("audio:track-start", {
            detail: {index, trackId: id, list}
        }));
    };

    async function fetchPackSummary(packId) {
        try {
            const {data} = await api.get(`/packs/${packId}`);
            return {
                id: data?.id ?? packId,
                title: data?.title || "Untitled Pack",
                img: data?.coverUrl || data?.albumCoverUrl || data?.coverImagePath || "",
            };
        } catch {
            return {id: packId, title: "Pack", img: ""};
        }
    }

    async function fetchKitSummary(kitId) {
        try {
            const {data} = await api.get(`/kits/${kitId}`);
            return {
                id: data?.id ?? kitId,
                title: data?.title || "Untitled Kit",
                artist: data?.artistName || data?.ownerName || data?.artist || "Unknown",
                img: toFileUrl(data?.coverUrl || data?.albumCoverUrl || data?.coverImagePath || data?.cover || ""
                ),
                price: deriveKitPrice(data)
            };
        } catch {
            return {id: kitId, title: "Kit", artist: "Unknown", img: "", price: 0};
        }
    }

    useEffect(() => {
        const onPlayList = async (e) => {
            const {list = [], index = 0, sourceKey = ""} = e.detail || {};
            if (!Array.isArray(list) || !list.length) return;

            const sig = list.map(t => t?.id).filter(Boolean).join(",");
            const sameSig = sig && sig === lastQueueSigRef.current;
            const sameOwner = String(sourceKey || "") === sourceKeyRef.current;
            if (sameSig && sameOwner && currentIndex === index) return;

            sourceKeyRef.current = String(sourceKey || "");
            lastQueueSigRef.current = sig;

            // --- If it's a PACK, re-fetch fresh URLs for the whole pack ---
            const packIdFromKey = getPackIdFromSourceKey(sourceKeyRef.current);
            if (packIdFromKey) {
                const freshMap = await loadPackPreviewMap(packIdFromKey, {force: true});

                const normalized = list.map(it => ({
                    ...it,
                    packId: packIdFromKey, // <— stamp so we can refresh later from saved playlist
                    audioUrl: freshMap.get(String(it.id)) || "",
                }));

                setQueue(normalized);
                setCurrentIndex(Math.max(0, Math.min(index, normalized.length - 1)));

                // bump pack once per (packId + queue signature)
                const packSig = `${packIdFromKey}|${sig}`;
                if (lastPackBumpSigRef.current !== packSig) {
                    api.post(`/packs/${packIdFromKey}/play`).catch(() => {
                    });
                    lastPackBumpSigRef.current = packSig;
                }

                setTimeout(() => emitState(), 0);
                return;
            }

            // --- If it's a KIT, stamp kitId and bump once per (kitId + list signature) ---
            const kitIdFromKey = getKitIdFromSourceKey(sourceKeyRef.current);
            if (kitIdFromKey) {
                const normalized = list.map(it => ({
                    ...it,
                    kitId: kitIdFromKey,     // stamp so bumpAt can detect "kit context"
                    // keep incoming audioUrl; your kit preview URLs are already in the list
                }));

                setQueue(normalized);
                setCurrentIndex(Math.max(0, Math.min(index, normalized.length - 1)));

                const kitSig = `${kitIdFromKey}|${sig}`;
                if (lastKitBumpSigRef.current !== kitSig) {
                    api.post(`/kits/${kitIdFromKey}/play`).catch(() => {
                    });
                    lastKitBumpSigRef.current = kitSig;
                }

                setTimeout(() => emitState(), 0);
                return;
            }

            // --- Non-pack: keep incoming urls (we'll refresh per-beat on demand) ---
            setQueue(list.map(t => ({
                ...t,
                audioUrl: (typeof t.audioUrl === "string" ? t.audioUrl : ""),
            })));
            setCurrentIndex(Math.max(0, Math.min(index, list.length - 1)));
            setTimeout(() => emitState(), 0);
        };

        const onGetState = () => emitState();
        document.addEventListener("audio:play-list", onPlayList);
        document.addEventListener("audio:get-state", onGetState);
        return () => {
            document.removeEventListener("audio:play-list", onPlayList);
            document.removeEventListener("audio:get-state", onGetState);
        };
    }, []);

    useEffect(() => {
        const a = audioRef.current;
        if (!a) return;

        const onResume = () => {
            if (a.paused) {
                a.play().catch((err) => {
                    console.error("[AudioBar] Failed to resume playback:", err);
                });
            }
        };

        document.addEventListener("audio:resume", onResume);

        return () => {
            document.removeEventListener("audio:resume", onResume);
        };
    }, []);


    useEffect(() => {
        queueRef.current = queue;
    }, [queue]);
    useEffect(() => {
        idxRef.current = currentIndex;
    }, [currentIndex]);
    useEffect(() => {
        lenRef.current = queue.length;
    }, [queue.length]);
    useEffect(() => {
        isShuffleRef.current = isShuffle;
    }, [isShuffle]);
    useEffect(() => {
        console.log("[DBG] repeatMode =", repeatMode);
    }, [repeatMode]);


    // --- Volume helpers ---
    const applyVolume = (v) => {
        const a = audioRef.current;
        if (!a) return;
        a.volume = v;
        a.muted = v === 0;
    };

    function toSafeAudioUrl(url) {
        if (typeof url !== "string") return "";
        return url.includes("#") ? url.replace(/#/g, "%23") : url;
    }

    useEffect(() => {
        playNextRef.current = playNext;
    });
    // useEffect(() => { isRepeatRef.current = isRepeat; }, [isRepeat]);
    useEffect(() => {
        repeatModeRef.current = repeatMode;
    }, [repeatMode]);

    // Fetch & cache a preview url for a beat id
    async function fetchPreviewUrl(beatId) {
        try {
            const {data} = await api.get(`/beats/${beatId}/preview-url`);
            return (data?.url || data?.previewUrl || "").trim();
        } catch (e) {
            console.warn("[AudioBar] fetchPreviewUrl failed", beatId, e);
            return "";
        }
    }

    // === Playlist bus (persistent) ===
    useEffect(() => {
        const onAdd = (e) => {
            // accepts single item or {items:[...]}
            const items = Array.isArray(e.detail?.items) ? e.detail.items
                : e.detail ? [e.detail] : [];
            if (!items.length) return;
            setPlaylist(prev => {
                const seen = new Set(prev.map(x => String(x.id)));
                const next = [...prev];
                for (const it of items) {
                    const id = String(it?.id ?? "");
                    if (!id || seen.has(id)) continue;
                    seen.add(id);
                    next.push({
                        id: it.id,
                        title: it.title,
                        artistName: it.artistName || "Unknown",
                        albumCoverUrl: it.albumCoverUrl || "",
                        audioUrl: it.audioUrl || "",
                        durationInSeconds: it.durationInSeconds || 0,
                    });
                }
                return next;
            });
        };
        const onRemoveIndex = (e) => {
            const i = Number(e.detail?.index);
            setPlaylist(p => (Number.isInteger(i) && i >= 0 && i < p.length)
                ? [...p.slice(0, i), ...p.slice(i + 1)]
                : p);
        };
        const onClear = () => setPlaylist([]);
        document.addEventListener("playlist:add", onAdd);
        document.addEventListener("playlist:remove-index", onRemoveIndex);
        document.addEventListener("playlist:clear", onClear);
        return () => {
            document.removeEventListener("playlist:add", onAdd);
            document.removeEventListener("playlist:remove-index", onRemoveIndex);
            document.removeEventListener("playlist:clear", onClear);
        };
    }, []);

    async function ensureAudioUrlAt(index) {
        const list = queueRef.current || [];
        const row = list[index];
        if (!row) return "";

        // Prefer packId stamped on item; fallback to sourceKey when present
        const packId = row.packId || getPackIdFromSourceKey(sourceKeyRef.current);

        if (packId) {
            // refresh /preview-playlist (uses TTL; force only when retrying on error)
            const map = await loadPackPreviewMap(packId, {force: false});

            // update the entire queue with any fresh URLs we received
            setQueue(q => q.map(it => {
                if (it.packId !== packId) return it;
                const u = map.get(String(it.id)) || "";
                return u ? {...it, audioUrl: u} : it;
            }));

            return map.get(String(row.id)) || "";
        }

        // Non-pack beat: fetch individual fresh URL if missing
        let url = (typeof row.audioUrl === "string" ? row.audioUrl.trim() : "");
        if (!url) {
            url = await fetchPreviewUrl(row.id);
            if (url) {
                setQueue(q => {
                    const next = [...q];
                    if (next[index]) next[index] = {...next[index], audioUrl: url};
                    return next;
                });
            }
        }
        return url;
    }

    function emitState(extra = {}) {
        const detail = {
            playing: isPlaying,
            index: currentIndex,
            trackId: current?.id ?? null,
            sourceKey: sourceKeyRef.current,
            list: queue,
            shuffle: isShuffle,
            repeat: repeatMode,           // "off" | "all" | "one
            current: current ? {
                id: current.id,
                title: current.title,
                artistName: current.artistName,
                albumCoverUrl: current.albumCoverUrl,
                audioUrl: current.audioUrl,
            } : null,
            progress,
            ...extra,
        };
        document.dispatchEvent(new CustomEvent("audio:state", {detail}));
    }

    useEffect(() => {
        if (!showMore) return;
        const onDoc = (e) => {
            // Close only if click is outside BOTH the menu and the button
            if (!e.target.closest?.(".player-more") && !e.target.closest?.(".more-btn")) {
                setShowMore(false);
            }
        };
        const onEsc = (e) => e.key === "Escape" && setShowMore(false);
        document.addEventListener("mousedown", onDoc);
        document.addEventListener("keydown", onEsc);
        return () => {
            document.removeEventListener("mousedown", onDoc);
            document.removeEventListener("keydown", onEsc);
        };
    }, [showMore]);

    // close the Playlist panel on outside click / Esc
    useEffect(() => {
        if (!showQueue) return;
        const onDoc = (e) => {
            if (!e.target.closest?.(".player-queue") && !e.target.closest?.(".queue-btn")) {
                setShowQueue(false);
            }
        };
        const onEsc = (e) => e.key === "Escape" && setShowQueue(false);
        document.addEventListener("mousedown", onDoc);
        document.addEventListener("keydown", onEsc);
        return () => {
            document.removeEventListener("mousedown", onDoc);
            document.removeEventListener("keydown", onEsc);
        };
    }, [showQueue]);

    const changeVolume = (v) => {
        const clamped = Math.max(0, Math.min(1, v));
        setVolume(clamped);
        applyVolume(clamped);
    };
    const toggleMute = () => {
        if (isMuted) {
            changeVolume(prevVolume || 0.8);
        } else {
            setPrevVolume(volume);
            changeVolume(0);
        }
    };
    const VolumeIcon = ({v}) => {
        if (v === 0) return <IoVolumeMute size={24}/>;
        if (v < 0.34) return <IoVolumeLow size={24}/>;
        if (v < 0.67) return <IoVolumeMedium size={24}/>;
        return <IoVolumeHigh size={24}/>;
    };

    // --- Transport ---
    const togglePlay = () => {
        const a = audioRef.current;
        if (!current || !a) return;
        if (a.paused) a.play().catch(() => {
        });
        else a.pause();
    };
    const toggleShuffle = () => setIsShuffle((p) => {
        const next = !p;
        emitState({shuffle: next});
        return next;
    });
    // const toggleRepeat = () => setIsRepeat((p) => {
    //     const next = !p;
    //     emitState({repeat: next ? "all" : "off"});
    //     return next;
    // });

    const toggleRepeat = () => {
        const next = repeatMode === "off" ? "all" : repeatMode === "all" ? "one" : "off";
        repeatModeRef.current = next;
        setRepeatMode(next);
        emitState({repeat: next});
    };

    useEffect(() => {
        emitState();
    }, [currentIndex, queue.length]);


    function likeKindAndId(current, sourceKey) {
        if (!current) return null;

        // if we’re inside a pack/kit queue, like that container
        const packId = current.packId || (typeof sourceKey === "string" && sourceKey.startsWith("pack:") ? sourceKey.slice(5) : null);
        const kitId = current.kitId || (typeof sourceKey === "string" && sourceKey.startsWith("kit:") ? sourceKey.slice(4) : null);

        if (kitId) return {kind: "kit", id: kitId};
        if (packId) return {kind: "pack", id: packId};
        return {kind: "beat", id: current.id};
    }

    async function apiFetchLiked(kind, id) {
        try {
            const {data} = await api.get(`/${kind}s/${id}/liked`);
            // accept { liked: true } or plain true/false
            return Boolean(data?.liked ?? data);
        } catch {
            return false;
        }
    }

    async function apiSetLiked(kind, id, liked) {
        try {
            if (liked) {
                await api.post(`/${kind}s/${id}/like`);
            } else {
                await api.delete(`/${kind}s/${id}/like`);
            }
            return true;
        } catch {
            return false;
        }
    }

    useEffect(() => {
        let cancelled = false;
        (async () => {
            if (!current) {
                setIsLiked(false);
                return;
            }
            const target = likeKindAndId(current, sourceKeyRef.current);
            if (!target) {
                setIsLiked(false);
                return;
            }
            const liked = await apiFetchLiked(target.kind, target.id);
            if (!cancelled) setIsLiked(liked);
        })();
        return () => {
            cancelled = true;
        };
    }, [current]);

    const playNext = ({fromEnded = false} = {}) => {
        const len = lenRef.current;
        const idx = idxRef.current;
        const shuffle = isShuffleRef.current;
        const mode = repeatModeRef.current;

        if (!len) {
            console.log("[DBG] playNext → empty");
            return;
        }

        let to = null;

        if (shuffle && len > 1) {
            let r = Math.floor(Math.random() * len);
            if (r === idx) r = (r + 1) % len;
            to = r;
        } else {
            const atLast = idx >= len - 1;
            if (!atLast) to = idx + 1;
            else to = fromEnded ? (mode === "all" ? 0 : null) : 0;
        }

        if (to === null) {
            console.log("[DBG] playNext → stop (repeat=off at end)", {idx, len, mode, fromEnded});
            const a = audioRef.current;
            if (a) a.pause();
            setIsPlaying(false);
            emitState({playing: false});
            return;
        }

        console.log("[DBG] playNext → forcePlayIndex", {idx, to, len, mode, shuffle, fromEnded});
        forcePlayIndex(to);
    };


    function parseMoney(val) {
        if (val == null) return null;
        if (typeof val === "number") return Number.isFinite(val) ? val : null;
        const s = String(val).trim();
        if (!s) return null;
        // Handle cents explicitly like "2500" when it’s clearly cents
        // (you can remove this if you never return cents in `price`)
        // Otherwise, strip currency symbols and thousands separators.
        const cleaned = s
            .replace(/[, ]/g, "")      // "2,500.00" -> "2500.00" | "25 00" -> "2500"
            .replace(/[^\d.]/g, "");   // "$25.00" -> "25.00"
        const n = Number(cleaned);
        return Number.isFinite(n) ? n : null;
    }

    function deriveKitPrice(k = {}) {
        // Prefer explicit cents fields if your API ever exposes them
        const centsCandidates = [
            k.priceCents, k.amountCents, k.displayPriceCents, k.salePriceCents,
            k?.pricing?.priceCents, k?.pricing?.salePriceCents,
        ].map(v => (Number.isFinite(Number(v)) ? Number(v) : null)).filter(v => v != null);
        if (centsCandidates.length) return centsCandidates[0] / 100;

        const candidates = [
            k.price,
            k.salePrice,
            k.displayPrice,
            k.amount,
            k?.pricing?.price,
            k?.pricing?.salePrice,
        ];
        for (const v of candidates) {
            const n = parseMoney(v);
            if (n != null && n >= 0) return n;
        }
        return 0;
    }

    const playPrev = () => {
        const len = lenRef.current;
        const idx = idxRef.current;
        if (!len) {
            console.log("[AudioBar] playPrev: empty queue");
            return;
        }

        const a = audioRef.current;
        if (a && a.currentTime > 2) {
            console.log("[AudioBar] playPrev: restart current");
            a.currentTime = 0;
            return;
        }
        const to = (idx - 1 + len) % len;
        console.log("[AudioBar] playPrev: linear", {from: idx, to});
        setCurrentIndex(to);
    };


    const onTimeUpdate = () => {
        const a = audioRef.current;
        if (!a) return;
        const next = {current: a.currentTime || 0, duration: a.duration || 0};
        setProgress(next);
        // don’t spam; browsers throttle dispatches while scrolling anyway
        emitState({progress: next});
    };

    const onSeek = (e) => {
        const a = audioRef.current;
        if (!a || !progress.duration) return;
        const rect = e.currentTarget.getBoundingClientRect();
        const ratio = Math.min(1, Math.max(0, (e.clientX - rect.left) / rect.width));
        a.currentTime = ratio * progress.duration;
    };

    // Global control bus: any component can dispatch these to control the bar
    useEffect(() => {
        const onToggle = () => togglePlay();
        const onNext = () => playNext();
        const onPrev = () => playPrev();
        const onShuffle = () => toggleShuffle();

        const onRepeat = (e) => {
            const mode = e.detail?.mode;            // "off" | "one" | "all"
            if (mode === "off" || mode === "one" || mode === "all") {
                repeatModeRef.current = mode;
                setRepeatMode(mode);
                emitState({repeat: mode});
            }
        };

        const onSeekPct = (e) => {
            const pct = Math.max(0, Math.min(1, Number(e.detail?.pct)));
            const a = audioRef.current;
            if (a && isFinite(a.duration)) a.currentTime = pct * a.duration;
        };

        // queue append
        const onAppend = (e) => {
            const items = Array.isArray(e.detail?.items) ? e.detail.items : [];
            if (!items.length) return;
            setQueue((q) => {
                const next = [...q, ...items];
                emitState({list: next});
                return next;
            });
        };

        const onPause = () => {
            const a = audioRef.current;
            if (a && !a.paused) a.pause();
        };
        const onResume = () => {
            const a = audioRef.current;
            if (a && a.paused) a.play().catch(() => {
            });
        };
        const onPlayIndex = (e) => {
            const idx = Number(e.detail?.index);
            if (Number.isInteger(idx) && idx >= 0 && idx < queue.length) setCurrentIndex(idx);
        };

        document.addEventListener("audio:toggle", onToggle);
        document.addEventListener("audio:next", onNext);
        document.addEventListener("audio:prev", onPrev);
        document.addEventListener("audio:toggle-shuffle", onShuffle);
        document.addEventListener("audio:set-repeat", onRepeat);
        document.addEventListener("audio:seek-pct", onSeekPct);
        document.addEventListener("audio:queue-append", onAppend);
        document.addEventListener("audio:pause", onPause);
        document.addEventListener("audio:resume", onResume);
        document.addEventListener("audio:play-index", onPlayIndex);

        return () => {
            document.removeEventListener("audio:toggle", onToggle);
            document.removeEventListener("audio:next", onNext);
            document.removeEventListener("audio:prev", onPrev);
            document.removeEventListener("audio:toggle-shuffle", onShuffle);
            document.removeEventListener("audio:set-repeat", onRepeat);
            document.removeEventListener("audio:seek-pct", onSeekPct);
            document.removeEventListener("audio:queue-append", onAppend);
            document.removeEventListener("audio:pause", onPause);
            document.removeEventListener("audio:resume", onResume);
            document.removeEventListener("audio:play-index", onPlayIndex);
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [repeatMode, isShuffle, queue, currentIndex]);

    const handleShare = async () => {
        if (!current) return;

        const packId = current.packId || getPackIdFromSourceKey(sourceKeyRef.current);
        const kitId = current.kitId || getKitIdFromSourceKey(sourceKeyRef.current);

        // Pack: fetch once to get slug
        if (packId) {
            try {
                const {data} = await api.get(`/packs/${packId}`);
                setShareTrack({
                    kind: "pack",
                    id: data?.id ?? packId,
                    slug: data?.slug ?? null,
                    title: data?.title || current.title || "Untitled Pack",
                    artistName: data?.artistName || data?.ownerName || current.artistName || "Unknown",
                    cover: toFileUrl(
                        data?.coverUrl || data?.albumCoverUrl || data?.coverImagePath || current.albumCoverUrl || ""
                    ),
                });
            } catch {
                setShareTrack({
                    kind: "pack",
                    id: packId,
                    slug: null,
                    title: current.title || "Untitled Pack",
                    artistName: current.artistName || "Unknown",
                    cover: current.albumCoverUrl || "",
                });
            }
            setShowShareModal(true);
            return;
        }

        // Kit: fetch once to get slug
        if (kitId) {
            try {
                const {data} = await api.get(`/kits/${kitId}`);
                setShareTrack({
                    kind: "kit",
                    id: data?.id ?? kitId,
                    slug: data?.slug ?? null,
                    title: data?.title || current.title || "Untitled Kit",
                    artistName: data?.artistName || data?.ownerName || current.artistName || "Unknown",
                    cover: toFileUrl(
                        data?.coverUrl || data?.albumCoverUrl || data?.coverImagePath || current.albumCoverUrl || ""
                    ),
                });
            } catch {
                setShareTrack({
                    kind: "kit",
                    id: kitId,
                    slug: null,
                    title: current.title || "Untitled Kit",
                    artistName: current.artistName || "Unknown",
                    cover: current.albumCoverUrl || "",
                });
            }
            setShowShareModal(true);
            return;
        }

        // Beat: no fetch — use what we already have
        setShareTrack({
            kind: "beat",
            id: current.id,
            slug: current.slug || null,
            title: current.title || "Untitled",
            artistName: current.artistName || "Unknown",
            cover: current.albumCoverUrl || "",
        });

        setShowShareModal(true);
    };

    const toggleLike = async () => {
        if (!current) return;
        const target = likeKindAndId(current, sourceKeyRef.current);
        if (!target) return;

        const next = !isLiked;
        setIsLiked(next); // optimistic
        const ok = await apiSetLiked(target.kind, target.id, next);
        if (!ok) {
            setIsLiked(!next); // revert on failure
            toast.error("Please sign up to like this item");
        } else {
            toast.success(next ? "Successfully liked" : "Successfully unliked");
        }
    };

    const handleCartClick = async () => {
        if (!current) return;

        // Context: is this track inside a pack or kit?
        const packId = current.packId || getPackIdFromSourceKey(sourceKeyRef.current);
        const kitId = current.kitId || getKitIdFromSourceKey(sourceKeyRef.current);

        if (kitId) {
            // Kits: add immediately (same behavior as BrowsePage)
            const k = await fetchKitSummary(kitId);
            add({
                id: `kit-${k.id}`,
                type: "kit",
                kitId: k.id,
                title: k.title,
                artist: k.artist || "Unknown",
                img: k.img || toFileUrl(current.albumCoverUrl) || "",
                price: Number.isFinite(k.price) ? k.price : 0,
                qty: 1,
            });
            toast.success("Kit added to cart");
            return;
        }

        if (packId) {
            // Packs: open LicenseModal for the WHOLE PACK
            const p = await fetchPackSummary(packId);
            setSelectedTrack({
                id: p.id,
                title: p.title,
                img: p.img,
                kind: "Pack",
                artist: current.artistName || "Unknown",
            });
            setShowLicenseModal(true);
            return;
        }

        // Beat: open LicenseModal for the current beat
        setSelectedTrack({
            id: current.id,
            title: current.title,
            img: current.albumCoverUrl || "",
            artist: current.artistName || "Unknown",
            kind: "Beat",
        });
        setShowLicenseModal(true);
    };

    // remove item from queue and keep index consistent
    function removeFromQueue(idx) {
        setQueue((q) => {
            const next = q.filter((_, i) => i !== idx);
            // move currentIndex back if we removed an item before it
            setCurrentIndex((cur) => {
                if (idx < cur) return Math.max(0, cur - 1);
                // if we removed the currently playing item, clamp to last
                if (idx === cur) return Math.min(cur, next.length - 1);
                return cur;
            });
            emitState({list: next});
            return next;
        });
    }

    // const forcePlayIndex = (startIdx) => {
    //     const a = audioRef.current;
    //     const list = queueRef.current || [];
    //     const len = list.length;
    //
    //     console.groupCollapsed("[DBG] forcePlayIndex()");
    //     console.log("request", {startIdx, len});
    //
    //     if (!a || !len) {
    //         console.log("→ no audio element or empty list");
    //         console.groupEnd();
    //         return;
    //     }
    //
    //     // Try startIdx, then scan forward (wrap) for the next playable URL
    //     for (let hop = 0; hop < len; hop++) {
    //         const cand = (startIdx + hop) % len;
    //         const item = list[cand];
    //         const url = (item?.audioUrl || "").trim();
    //
    //         console.log("safeUrl", toSafeAudioUrl(url));
    //
    //         if (!url) {
    //             console.log("skip: empty audioUrl");
    //             continue;
    //         }
    //
    //         try {
    //             a.pause();
    //         } catch {
    //         }
    //
    //         // Fully clear previous source to avoid post-ended quirks
    //         try {
    //             a.removeAttribute("src");
    //             a.load();
    //         } catch {
    //         }
    //
    //         // Swap source
    //         a.src = url;
    //         setCurrentIndex(cand);
    //         idxRef.current = cand;
    //
    //         // Attempt playback
    //         try {
    //             a.play().then(() => {
    //                 console.log("✓ playing", {cand});
    //                 console.groupEnd();
    //             }).catch((err) => {
    //                 console.warn("play() rejected on cand:", cand, err);
    //             });
    //             return;
    //         } catch (err) {
    //             console.warn("play() threw on cand:", cand, err);
    //             // fall through to next cand
    //         }
    //     }
    //
    //     // If we got here, none of the URLs were playable
    //     console.warn("✗ no playable items in queue; stopping");
    //     setIsPlaying(false);
    //     emitState({playing: false});
    //     console.groupEnd();
    // };

// keep this if you want light throttling for forced refreshes
    const lastRetryRef = useRef({ index: -1, stamp: 0 });
    const isTransitioningRef = useRef(false); // ✅ Now using useRef for reactivity-safe locking

    const forcePlayIndex = async (startIdx) => {
        const a = audioRef.current;
        const list = queueRef.current || [];
        const len = list.length;
        if (!a || !len) return;

        if (isTransitioningRef.current) {
            console.warn("[AudioBar] Skipping forcePlayIndex — already transitioning");
            return;
        }

        isTransitioningRef.current = true; // 🔒 Lock

        const norm = (i) => ((i % len) + len) % len;

        const tryPlayAt = async (i, { forceRefresh = false } = {}) => {
            // 🔒 Prevent overlapping play attempts
            if (playLockRef.current) {
                console.warn(`[AudioBar] Blocked tryPlayAt(${i}) — another play is in progress`);
                return false;
            }

            const a = audioRef.current;
            const row = queueRef.current[i];
            if (!a || !row) return false;

            playLockRef.current = true; // lock start

            try {
                // 🧠 Prevent excessive refetching of the same index
                if (forceRefresh) {
                    const now = Date.now();
                    if (
                        lastRetryRef.current.index !== i ||
                        now - lastRetryRef.current.stamp > 1500
                    ) {
                        lastRetryRef.current = { index: i, stamp: now };
                        const packId = row.packId || getPackIdFromSourceKey(sourceKeyRef.current);
                        if (packId) {
                            console.log(`[AudioBar] Forcing fresh preview URLs for pack ${packId}`);
                            await loadPackPreviewMap(packId, { force: true });
                        }
                    }
                }

                // 🎧 Get or refresh the preview URL
                let url = (row.audioUrl || "").trim();
                if (!url || forceRefresh) {
                    url = await ensureAudioUrlAt(i, { force: true });
                }

                if (!url) {
                    console.warn(`[AudioBar] Missing preview URL for track ${row?.title} (id=${row?.id})`);
                    return false;
                }

                // 🧹 Cleanly reset the audio element
                try { a.pause(); } catch {}
                try { a.removeAttribute("src"); a.load(); } catch {}

                setCurrentIndex(i);
                idxRef.current = i;

                a.src = toSafeAudioUrl(url);

                a.load();

                setProgress({ current: 0, duration: 0 }); // keep progress synced

                // 🕒 Small delay prevents "play() interrupted by new load" errors
                await new Promise((resolve) => setTimeout(resolve, 80));

                if (a.readyState < 2) {
                    await new Promise((resolve) => {
                        const onLoaded = () => {
                            a.removeEventListener("loadedmetadata", onLoaded);
                            resolve();
                        };
                        a.addEventListener("loadedmetadata", onLoaded);
                    });
                }

                await a.play();
                bumpAt(queueRef.current, i);

                // 🔥 Preload next track
                const nextIdx = norm(i + 1);
                ensureAudioUrlAt(nextIdx).catch(() => {});

                console.log(`[AudioBar] Now playing: ${row?.title} (id=${row?.id})`);
                return true;
            } catch (err) {
                console.warn(
                    `[AudioBar] Playback failed at index ${i} (${row?.title}), refresh=${forceRefresh}`,
                    err
                );

                // 🔁 Retry once with a fresh preview URL
                if (!forceRefresh) {
                    console.log(`[AudioBar] Retrying ${row?.title} with fresh preview URL...`);
                    return await tryPlayAt(i, { forceRefresh: true });
                }

                return false;
            } finally {
                playLockRef.current = false; // 🔓 Unlock safely
            }
        };

        try {
            const i0 = norm(startIdx);

            if (await tryPlayAt(i0)) return;
            if (await tryPlayAt(i0, { forceRefresh: true })) return;

            const i1 = norm(i0 + 1);
            if (await tryPlayAt(i1)) return;
            if (await tryPlayAt(i1, { forceRefresh: true })) return;

            console.warn("[AudioBar] Could not play any track — stopping playback");
            setIsPlaying(false);
            emitState({ playing: false });
        } finally {
            isTransitioningRef.current = false; // 🔓 Always unlock
        }
    };

    // --- Wire up audio element events ---
    useEffect(() => {
        const a = audioRef.current;
        if (!a) return;

        const onPlay = () => {
            setIsPlaying(true);
            emitState({playing: true});
        };
        const onPause = () => {
            setIsPlaying(false);
            emitState({playing: false});
        };

        // const onEnded = () => {
        //     const a       = audioRef.current;
        //     const mode    = repeatModeRef.current;   // "off" | "all" | "one"
        //     const idx     = idxRef.current;
        //     const len     = lenRef.current;
        //     const shuffle = isShuffleRef.current;
        //     const atLast  = idx >= len - 1;
        //
        //     console.log("[AudioBar] onEnded", { idx, len, mode, atLast, shuffle });
        //     if (!len) return;
        //
        //     // repeat current track
        //     if (mode === "one") {
        //         console.log("[AudioBar] repeat=one → restart current");
        //         if (a) { a.currentTime = 0; a.play().catch(() => {}); }
        //         return;
        //     }
        //
        //     let next = null;
        //
        //     if (shuffle && len > 1) {
        //         let r = Math.floor(Math.random() * len);
        //         if (r === idx) r = (r + 1) % len;
        //         next = r;
        //     } else if (!atLast) {
        //         next = idx + 1;
        //     } else if (mode === "all") {
        //         next = 0; // repeat the pack
        //     }
        //
        //     if (next !== null) {
        //         console.log("[AudioBar] advance →", next);
        //         forcePlayIndex(next);
        //         return;
        //     }
        //
        //     console.log("[AudioBar] repeat=off at last → stop");
        //     if (a) a.pause();
        //     setIsPlaying(false);
        //     emitState({ playing: false });
        // };

        const onEnded = () => {
            const a = audioRef.current;
            const mode = repeatModeRef.current;   // "off" | "all" | "one"
            const idx = idxRef.current;
            const len = lenRef.current;
            const shuffle = isShuffleRef.current;
            const atLast = idx >= len - 1;

            console.groupCollapsed("[DBG] onEnded()");
            console.log("state", {idx, len, atLast, shuffle, mode});
            if (a) {
                console.log("audio", {
                    ended: a.ended,
                    readyState: a.readyState,
                    networkState: a.networkState,
                    src: a.currentSrc || a.src,
                    currentTime: a.currentTime,
                    duration: a.duration,
                });
            }

            if (!len) {
                console.log("→ empty queue, abort");
                console.groupEnd();
                return;
            }

            if (mode === "one") {
                console.log("→ repeat-one: restart current track");
                if (a) {
                    a.currentTime = 0;
                    a.play().catch(() => {
                    });
                }
                console.groupEnd();
                return;
            }

            if (shuffle && len > 1) {
                let r = Math.floor(Math.random() * len);
                if (r === idx) r = (r + 1) % len;
                console.log("→ shuffle advance", {to: r});
                forcePlayIndex(r);
                console.groupEnd();
                return;
            }

            if (!atLast) {
                console.log("→ linear advance", {to: idx + 1});
                forcePlayIndex(idx + 1);
                console.groupEnd();
                return;
            }

            if (mode === "all") {
                console.log("→ repeat-all wrap to 0");
                // Option A: go straight to first track
                // forcePlayIndex(0);

                // Option B: reuse playNext logic and see its logs
                console.log("→ delegating to playNext({ fromEnded: true })");
                playNext({fromEnded: true});

                console.groupEnd();
                return;
            }

            console.log("→ stop (repeat=off at last)");
            if (a) a.pause();
            setIsPlaying(false);
            emitState({playing: false});
            console.groupEnd();
        };


        const onLoadedMeta = () => {
            setProgress((p) => {
                const next = {...p, duration: isFinite(a.duration) ? a.duration : 0};
                emitState({progress: next});
                return next;
            });
        };

        a.addEventListener("play", onPlay);
        a.addEventListener("pause", onPause);
        a.addEventListener("ended", onEnded);
        a.addEventListener("loadedmetadata", onLoadedMeta);

        applyVolume(volume);

        return () => {
            a.removeEventListener("play", onPlay);
            a.removeEventListener("pause", onPause);
            a.removeEventListener("ended", onEnded);
            a.removeEventListener("loadedmetadata", onLoadedMeta);
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    // Load & auto-play when the current track changes
    // useEffect(() => {
    //     const a = audioRef.current;
    //     if (!a || !current) return;
    //      const safeUrl = toSafeAudioUrl(current.audioUrl);
    //      if (a.src !== safeUrl) {
    //            a.src = safeUrl;
    //         a.load();
    //         setProgress({current: 0, duration: 0});
    //     }
    //     a.play().catch(() => {
    //     });
    // }, [current]);

    // useEffect(() => {
    //     const a = audioRef.current;
    //     if (!a || !current) return;
    //
    //     const run = async () => {
    //         // make sure current has a url
    //         let url = (current.audioUrl || "").trim();
    //         if (!url) url = await ensureAudioUrlAt(idxRef.current);
    //         if (!url) return; // nothing to play
    //
    //          if (a.src !== url) {
    //                console.log("[AUDIO] src ←", url);
    //                a.src = url;
    //             a.load();
    //             setProgress({ current: 0, duration: 0 });
    //         }
    //         a.play().catch(() => {});
    //     };
    //
    //     run();
    // }, [current]);

    useEffect(() => {
        const a = audioRef.current;
        if (!a || !current) return;

        const run = async () => {
            // make sure current has a url
            let url = (current.audioUrl || "").trim();
            if (!url) url = await ensureAudioUrlAt(idxRef.current);
            if (!url) return; // nothing to play

            if (a.src !== url) {
                console.log("[AUDIO] src ←", url);
                a.src = url;
                a.load();
                setProgress({current: 0, duration: 0});
            }

            try {
                await a.play();
                // 🚀 bump play *after* the track actually starts
                bumpAt(queueRef.current, idxRef.current);
            } catch {
                // ignore autoplay rejection, etc.
            }
        };

        run();
    }, [current]);

    // Keyboard shortcuts
    useEffect(() => {
        const onKey = (e) => {
            const tag = (e.target && e.target.tagName) || "";
            if (tag === "INPUT" || tag === "TEXTAREA") return;
            if (e.code === "Space") {
                e.preventDefault();
                togglePlay();
            }
            if (e.code === "ArrowRight") playNext();
            if (e.code === "ArrowLeft") playPrev();
        };
        window.addEventListener("keydown", onKey);
        return () => window.removeEventListener("keydown", onKey);
    }, [queue, current, progress.duration]);


    return (
        <footer className="player">
            {/* Top progress line – full width */}
            <div className="progress" onClick={onSeek}>
                <div
                    className="progress__bar"
                    style={{width: progress.duration ? `${(progress.current / progress.duration) * 100}%` : "0%"}}
                />
            </div>

            <div className="player__inner">
                {/* Left: cover + meta */}
                <div className="player__left">
                    <div className="cover">
                        {current?.albumCoverUrl && (
                            <img src={current.albumCoverUrl} alt={current?.title || "cover"}/>
                        )}
                    </div>
                    <div className="meta">
                        <div className="audiobar__title-wrapper">
                            <div className="scrolling-title">
                                <div className="meta__title" title={current?.title || ""}>
                                    {current?.title || "Nothing playing"}
                                </div>
                                <div className="meta__title" aria-hidden="true">
                                    {current?.title || "Nothing playing"}
                                </div>
                            </div>
                            </div>
                            <div className="meta__artist" title={current?.artistName || ""}>
                                {current?.artistName || "Select a beat"}
                            </div>
                        </div>
                    </div>

                    {/* Center: transport */}
                <div className="player__center">
                    <IconButton label="Shuffle" onClick={toggleShuffle}>
                        <IoShuffle size={24} color={isShuffle ? "#009e94" : "inherit"}/>
                    </IconButton>

                    <IconButton label="Prev" onClick={playPrev}>
                        <IoPlayBackOutline size={30}/>
                    </IconButton>

                    <IconButton label={isPlaying ? "Pause" : "Play"} onClick={togglePlay}>
                        {isPlaying ? <IoPause size={30}/> : <IoPlay size={30}/>}
                    </IconButton>

                    <IconButton label="Next" onClick={playNext}>
                        <IoPlayForwardOutline size={30}/>
                    </IconButton>

                    <IconButton label="Repeat" onClick={toggleRepeat}>
                        {/*<IoRepeat size={24} color={isRepeat ? "#009e94" : "inherit"}/>*/}
                        <span
                            className={`repeat-btn ${repeatMode !== "off" ? "is-on" : ""} ${repeatMode === "one" ? "is-one" : ""}`}>
    <IoRepeat size={24}/>
                            {repeatMode === "one" && <span className="repeat-badge">1</span>}
                          </span>
                    </IconButton>
                </div>

                {/* Right: volume + actions */}
                <div className="player__right">
                    <div className="player__right">

                        <button
                            className="iconbtn more-btn"
                            aria-label="More options"
                            aria-haspopup="menu"
                            aria-expanded={showMore}
                            onClick={(e) => {
                                e.stopPropagation();
                                setShowMore((s) => !s);      // <-- actually toggles the sheet
                            }}
                        >
                            <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor" aria-hidden="true">
                                <circle cx="5" cy="12" r="2"/>
                                <circle cx="12" cy="12" r="2"/>
                                <circle cx="19" cy="12" r="2"/>
                            </svg>
                        </button>

                    </div>

                    <div className="volume">
                        <IconButton label={isMuted ? "Unmute" : "Mute"} onClick={toggleMute}>
                            <VolumeIcon v={volume}/>
                        </IconButton>

                        <input
                            className="volume__slider"
                            type="range"
                            min="0"
                            max="1"
                            step="0.01"
                            value={volume}
                            onChange={(e) => changeVolume(parseFloat(e.target.value))}
                            aria-label="Volume"
                        />
                    </div>

                    <IconButton label="Cart" onClick={handleCartClick}>
                        <IoCartOutline size={22}/>
                    </IconButton>

                    <IconButton label="Playlist" className="queue-btn" onClick={() => setShowQueue((s) => !s)}>
                        <IoListOutline size={22}/>
                    </IconButton>


                    <IconButton label="Like" onClick={toggleLike}>
                        {isLiked ? <FcLike size={22}/> : <FcLikePlaceholder size={22}/>}
                    </IconButton>

                    <IconButton label="Share" onClick={handleShare}>
                        <IoShare size={22}/>
                    </IconButton>
                </div>
            </div>

            {showMore && (
                <div className="player-more" role="menu" aria-label="Player actions">
                    <button
                        className="player-more__item"
                        onClick={() => {
                            handleAddToCart();
                            setShowMore(false);
                        }}
                    >
                        <IoCartOutline size={18}/>
                        <span>Add to cart</span>
                    </button>

                    <button
                        className="player-more__item"
                        onClick={async () => {
                            await toggleLike();
                            setShowMore(false);
                        }}
                    >
                        {isLiked ? <FcLike size={18}/> : <FcLikePlaceholder size={18}/>}
                        <span>{isLiked ? "Unlike" : "Like"}</span>
                    </button>

                    <button
                        className="player-more__item"
                        onClick={() => {
                            handleShare();
                            setShowMore(false);
                        }}
                    >
                        <IoShare size={18}/>
                        <span>Share</span>
                    </button>

                    <button
                        className="player-more__item"
                        onClick={() => {
                            toggleMute();
                            setShowMore(false);
                        }}
                    >
                        {isMuted ? (
                            <>
                                <IoVolumeMute size={18}/>
                                <span>Unmute</span>
                            </>
                        ) : (
                            <>
                                <IoVolumeHigh size={18}/>
                                <span>Mute</span>
                            </>
                        )}
                    </button>
                </div>
            )}
            {showQueue && (
                <div className="player-queue" role="dialog" aria-label="Playback queue">
                    <div className="player-queue__head">
                        <strong className="player-queue__title">Playlist</strong>
                        <div className="player-queue__actions">
                            <button
                                className="player-queue__btn player-queue__btn--clear"
                                onClick={() => document.dispatchEvent(new CustomEvent("playlist:clear"))}
                            >
                                Clear
                            </button>
                            <button
                                className="player-queue__btn player-queue__btn--close"
                                onClick={() => setShowQueue(false)}
                                aria-label="Close queue"
                            >
                                ✕
                            </button>
                        </div>
                    </div>

                    <div className="player-queue__scroll">
                        <ul className="player-queue__list">
                            {playlist.length === 0 && (
                                <li className="player-queue__empty">Your playlist is empty.</li>
                            )}

                            {playlist.map((t, i) => {
                                const isCur = queue[currentIndex]?.id === t.id;
                                return (
                                    <li key={`pl-${t.id ?? i}`}
                                        className={`player-queue__item ${isCur ? "is-current" : ""}`}>
                                        <button
                                            className="player-queue__play"
                                            onClick={() => {
                                                document.dispatchEvent(new CustomEvent("audio:play-list", {
                                                    detail: {list: playlist, index: i, sourceKey: "playlist"}
                                                }));
                                                setShowQueue(false);
                                            }}
                                            title={isCur ? "Currently playing" : "Play this track"}
                                        >
                                            {isCur ? "▶︎" : "►"}
                                        </button>

                                        {t.albumCoverUrl
                                            ? <img className="player-queue__cover" src={t.albumCoverUrl} alt=""/>
                                            : <div className="player-queue__cover ph" aria-hidden="true"/>}

                                        <div className="player-queue__meta">
                                            <div className="player-queue__title"
                                                 title={t.title || ""}>{t.title || "Untitled"}</div>
                                            <div className="player-queue__artist"
                                                 title={t.artistName || ""}>{t.artistName || ""}</div>
                                        </div>

                                        <button
                                            className="player-queue__remove"
                                            aria-label="Remove from playlist"
                                            title="Remove from playlist"
                                            onClick={() =>
                                                document.dispatchEvent(new CustomEvent("playlist:remove-index", {detail: {index: i}}))
                                            }
                                        >
                                            ✕
                                        </button>
                                    </li>
                                );
                            })}
                        </ul>
                    </div>
                </div>
            )}

            <audio
                ref={audioRef}
                preload="metadata"
                crossOrigin="anonymous"
                onTimeUpdate={onTimeUpdate}
                playsInline
                onError={async (e) => {
                    const a = e.currentTarget;
                    console.error("[AUDIO ERROR]", {
                        currentSrc: a.currentSrc,
                        networkState: a.networkState,
                        error: a.error && {code: a.error.code, message: a.error.message}
                    });

                    const idx = idxRef.current;
                    const row = (queueRef.current || [])[idx];
                    if (!row) return;

                    const now = Date.now();
                    const alreadyRetried = (lastRetryRef.current.index === idx) &&
                        (now - lastRetryRef.current.stamp < 2000);
                    if (alreadyRetried) return;

                    lastRetryRef.current = {index: idx, stamp: now};

                    const packId = row.packId || getPackIdFromSourceKey(sourceKeyRef.current);
                    if (packId) {
                        await loadPackPreviewMap(packId, {force: true});
                        await ensureAudioUrlAt(idx);
                    } else {
                        const fresh = await fetchPreviewUrl(row.id);
                        if (fresh) {
                            setQueue(q => {
                                const next = [...q];
                                if (next[idx]) next[idx] = {...next[idx], audioUrl: fresh};
                                return next;
                            });
                        }
                    }


                    forcePlayIndex(idx);
                }}
            />
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
                            id: `pack-${selectedTrack.id}-${license.id}`,
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
                        // BEAT: needs licenseType for backend /purchases/beat
                        item = {
                            id: `beat-${beatId || selectedTrack.id}-${license.type}`,
                            type: "beat",
                            beatId: beatId || selectedTrack.id,
                            title: selectedTrack.title,
                            artist: selectedTrack.artist || "Unknown",
                            img: selectedTrack.img,
                            licenseType: license.type,
                            licenseName: license.name,
                            price: Number(license.price || 0),
                            qty: 1,
                        };
                    }

                    if (action === "addToCart") {
                        add(item);
                        toast.success("Added to cart");
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
                open={showShareModal}
                track={shareTrack}
                onClose={() => {
                    setShowShareModal(false);
                    setShareTrack(null);
                }}
            />
        </footer>
    );
}
