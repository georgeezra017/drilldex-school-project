// src/pages/admin/AdminBeatsReview.jsx
import {Fragment, useEffect, useRef, useState} from "react";
import "./admin.css";
import {
    IoCheckmark, IoClose, IoSearch, IoWarningOutline, IoRefresh, IoFilter,
    IoPlay, IoPause, IoChevronDown, IoChevronForward
} from "react-icons/io5";
import api from "../../lib/api";
import toast from "react-hot-toast"

/* -------- helpers -------- */
const API_ORIGIN = new URL(api.defaults.baseURL).origin;
const S3_PUBLIC_BASE = import.meta.env.VITE_S3_PUBLIC_BASE?.replace(/\/+$/, ""); // trim trailing /

function toFileUrl(input) {
    if (!input) return "";

    // already absolute (https://, http://, data:, blob:, etc.)
    if (/^[a-z]+:\/\//i.test(input)) return input;

    // clean up
    let p = String(input).replace(/\\/g, "/").trim().replace(/^\/+/, "");

    // If we have an S3 public base configured, prefer serving keys from S3
    // Works for keys like "app-prod/audio/...", "covers/...", "audio/...", etc.
    if (S3_PUBLIC_BASE) {
        return `${S3_PUBLIC_BASE}/${p}`;
    }

    // Fallback to local /uploads/... (dev / legacy)
    // keep from /uploads/ if present
    const idx = p.indexOf("uploads/");
    if (idx >= 0) {
        p = p.substring(idx); // -> "uploads/..."
    } else {
        // normalize into uploads/
        if (/^(audio|covers|kits|previews)\//.test(p)) p = `uploads/${p}`;
        else if (!/^uploads\//.test(p)) p = `uploads/${p}`;
    }

    return `${API_ORIGIN}/${p}`;
}

function normalizeTags(t) {
    if (Array.isArray(t)) return t;
    if (!t) return [];
    return String(t).split(",").map(s => s.trim()).filter(Boolean);
}

function fmtTime(sec = 0) {
    const m = Math.floor(sec / 60);
    const s = String(Math.floor(sec % 60)).padStart(2, "0");
    return `${m}:${s}`;
}

/* -------- API layer (beats + packs + kits) -------- */
async function apiListModerationRows({q = "", status = "pending"}) {
    const [beatsRes, packsRes, kitsRes] = await Promise.allSettled([
        api.get("/admin/beats/pending"),
        api.get("/admin/packs/pending"),
        api.get("/admin/kits/pending"),
    ]);

    // Helper: detect pack/kit asset paths even when absolute S3 URLs are used
    const isKitOrPackPath = (p) => {
        if (!p) return false;
        const s = String(p)
            .replace(/^https?:\/\/[^/]+/i, "") // strip origin
            .replace(/^\/+/, "")
            .toLowerCase();
        // matches: kits/... , packs/... , uploads/app-prod/kits/... , uploads/app-prod/packs/...
        return /(^|\/)(uploads\/)?(app-prod\/)?(kits|packs)\//.test(s);
    };

    // --- BEATS (filter out anything that clearly comes from kits/ or packs/) ---
    const beatRows = beatsRes.status === "fulfilled"
        ? (beatsRes.value.data || [])
            .map(b => {
                const srcRaw = b.previewUrl || b.audioFilePath || "";
                return {
                    kind: "BEAT",
                    id: b.id,
                    title: b.title ?? "(untitled)",
                    artistName: b.artistName ?? "Unknown",
                    durationSec: b.durationInSeconds ?? 0,
                    price: Number(b.price ?? 0),
                    status: "pending",
                    submittedAt: b.createdAt ?? b.featuredAt ?? Date.now(),
                    coverUrl: toFileUrl(b.albumCoverUrl || b.coverImagePath),
                    tags: normalizeTags(b.tags),
                    previewUrl: toFileUrl(b.previewUrl || b.audioFilePath),
                    _srcRaw: srcRaw,  // keep raw for filtering
                };
            })
            .filter(r => !isKitOrPackPath(r._srcRaw))
            .map(({_srcRaw, ...r}) => r)
        : [];

    // --- PACKS (already one row per pack) ---
    const packRows = packsRes.status === "fulfilled"
        ? (packsRes.value.data || []).map(p => ({
            kind: "PACK",
            id: p.id,
            title: p.title ?? "(untitled)",
            artistName: p.ownerName ?? "Unknown",
            durationSec: 0,
            price: Number(p.displayPrice ?? p.price ?? 0),
            status: "pending",
            submittedAt: p.createdAt ?? Date.now(),
            coverUrl: toFileUrl(p.coverUrl || p.coverImagePath),
            tags: normalizeTags(p.tags),
            previewUrl: "",            // packs don’t autoplay here
            beatsCount: p.beatsCount ?? undefined,
        }))
        : [];

    // --- KITS (group by kit id so you only get one row per kit) ---
    const kitsRaw = kitsRes.status === "fulfilled" ? (kitsRes.value.data || []) : [];
    const kitsGrouped = new Map();
    for (const it of kitsRaw) {
        const kitId = it.id ?? it.kitId ?? it.kit?.id;
        if (!kitId) continue;

        if (!kitsGrouped.has(kitId)) {
            kitsGrouped.set(kitId, {
                kind: "KIT",
                id: kitId,
                title: it.title ?? it.name ?? it.kit?.title ?? "(untitled)",
                artistName: it.ownerName ?? it.kit?.ownerName ?? it.owner?.displayName ?? "Unknown",
                durationSec: 0,
                price: Number(it.price ?? it.kit?.price ?? 0),
                status: "pending",
                submittedAt: it.createdAt ?? it.kit?.createdAt ?? Date.now(),
                coverUrl: toFileUrl(it.coverUrl || it.coverImagePath || it.kit?.coverUrl),
                tags: normalizeTags(it.tags ?? it.kit?.tags),
                previewUrl: toFileUrl(it.previewUrl || it.previewAudioPath || it.kit?.previewUrl),
                filesCount: Number.isFinite(+it.filesCount) ? +it.filesCount : undefined,

                _files: [], // stash file paths only for expand panel
            });
        }

        // If backend accidentally returns file-level kit rows, keep file paths only for expand UI
        const filePath = it.file || it.filePath || it.path || null;
        if (filePath) {
            kitsGrouped.get(kitId)._files.push(filePath);
        }
    }

    const kitRows = Array.from(kitsGrouped.values()).map(k => ({
        ...k,
        filesCount:
            typeof k.filesCount === "number"
                ? k.filesCount
                : (k._files?.length ?? undefined),
        detail: Array.isArray(k._files) && k._files.length
            ? {files: k._files.map(toFileUrl)}
            : undefined,
    }));

    // Merge & filter/search/sort
    let rows = [...beatRows, ...packRows, ...kitRows];

    const term = q.trim().toLowerCase();
    if (term) {
        rows = rows.filter(r =>
            r.title.toLowerCase().includes(term) ||
            r.artistName.toLowerCase().includes(term)
        );
    }

    // defensive de-dupe (by kind+id)
    const seen = new Set();
    rows = rows.filter(r => {
        const key = `${r.kind}:${r.id}`;
        if (seen.has(key)) return false;
        seen.add(key);
        return true;
    });

    rows.sort((a, b) => new Date(b.submittedAt) - new Date(a.submittedAt));
    return {rows};
}

async function apiApprove(kind, id) {
    if (kind === "PACK") return api.post(`/admin/packs/${id}/approve`);
    if (kind === "KIT") return api.post(`/admin/kits/${id}/approve`);
    return api.post(`/admin/beats/${id}/approve`);
}

async function apiReject(kind, id, reason) {
    const payload = {reason}; // backend uses it to notify owner
    if (kind === "PACK") return api.post(`/admin/packs/${id}/reject`, payload);
    if (kind === "KIT") return api.post(`/admin/kits/${id}/reject`, payload);
    return api.post(`/admin/beats/${id}/reject`, payload);
}

async function apiPackDetail(id) {
    const {data} = await api.get(`/admin/packs/${id}`);
    return data; // include beats[]
}

async function apiPackVerify(id) {
    const {data} = await api.get(`/admin/packs/${id}/verify`);
    return data;
}

async function apiKitDetail(id) {
    const {data} = await api.get(`/admin/kits/${id}`);
    return data; // { id, files: [public-or-key strings], coverUrl, ... }
}

/* -------- Component -------- */
export default function AdminBeatsReview() {
    const [q, setQ] = useState("");
    const [status, setStatus] = useState("pending");
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState("");
    const [rows, setRows] = useState([]);
    const [selected, setSelected] = useState(new Set());
    const [rejectOpen, setRejectOpen] = useState(false);
    const [rejectTargets, setRejectTargets] = useState([]); // [{id, kind, title, ownerName?}]
    const [rejectReason, setRejectReason] = useState("");
    const [rejectSending, setRejectSending] = useState(false);
    const [playingId, setPlayingId] = useState(null);
    const [expanded, setExpanded] = useState(null);   // expanded PACK id
    const [verifyMap, setVerifyMap] = useState({});   // packId -> verify result
    const audioRef = useRef(null);

    useEffect(() => {
        let alive = true;
        (async () => {
            setLoading(true);
            setError("");
            try {
                const {rows} = await apiListModerationRows({q, status});
                if (!alive) return;
                setRows(rows);
                setSelected(new Set());
            } catch (e) {
                setError("Failed to load moderation items.");
            } finally {
                setLoading(false);
            }
        })();
        return () => {
            alive = false;
        };
    }, [q, status]);

    useEffect(() => {
        const el = audioRef.current;
        if (!el) return;
        const onEnded = () => setPlayingId(null);
        const onError = () => setPlayingId(null);
        el.addEventListener("ended", onEnded);
        el.addEventListener("error", onError);
        return () => {
            el.removeEventListener("ended", onEnded);
            el.removeEventListener("error", onError);
        };
    }, []);

    const allChecked = rows.length > 0 && rows.every(r => selected.has(r.id));
    const someChecked = !allChecked && selected.size > 0;

    function toggleOne(id) {
        const next = new Set(selected);
        next.has(id) ? next.delete(id) : next.add(id);
        setSelected(next);
    }

    function toggleAll() {
        if (allChecked) setSelected(new Set());
        else setSelected(new Set(rows.map(r => r.id)));
    }

    const handlePlayToggle = async (row) => {
        if (row.kind === "PACK") return; // packs play via the expanded list
        const el = audioRef.current;
        if (!el) return;

        let src = "";
        try {
            if (row.kind === "BEAT") {
                // Admin-only master; fallback handled below
                const {data} = await api.get(`/admin/beats/${row.id}/master-url`);
                src = data?.url || "";
            } else if (row.kind === "KIT") {

                const {data} = await api.get(`/admin/kits/${row.id}/master-url`);
                src = data?.url || "";
            }
        } catch {
            // last resort: whatever the row carried
            src = row.previewUrl || "";
        }
        if (!src) return;

        if (playingId === row.id) {
            el.pause();
            setPlayingId(null);
            return;
        }

        el.src = src;
        el.play()
            .then(() => setPlayingId(row.id))
            .catch(() => setPlayingId(null));
    };

    const makePlayIdKit = (kitId, idx) => `kit:${kitId}:${idx}`;

    async function expandItem(row) {
        if (row.kind === "PACK") return expandPack(row);
        if (row.kind === "KIT") return expandKit(row);
    }

    async function expandKit(row) {
        if (expanded === row.id) {
            setExpanded(null);
            return;
        }
        setExpanded(row.id);

        if (!row.detail) {
            try {
                // fetch detail (kits don’t have a verify call like packs)
                const detail = await apiKitDetail(row.id);

                const filesRaw = Array.isArray(detail.files) ? detail.files.slice() : [];
                const mappedDetail = {
                    ...detail,
                    // prefer server-provided list; keep RAW keys for /master-url and map pretty URLs for display
                    filesRaw,
                    files: filesRaw.map(toFileUrl),
                };

                setRows(prev =>
                    prev.map(r =>
                        r.id === row.id && r.kind === "KIT"
                            ? {
                                ...r,
                                detail: mappedDetail,
                                filesCount: Array.isArray(mappedDetail.files) ? mappedDetail.files.length : 0
                            }
                            : r
                    )
                );
            } catch { /* ignore */
            }
        }
    }

    const playKitFile = async (kitId, fileUrl, idx) => {
        const el = audioRef.current;
        if (!el) return;

        const current = makePlayIdKit(kitId, idx);
        if (playingId === current) {
            el.pause();
            setPlayingId(null);
            return;
        }

        let src = "";
        try {
            // ask backend to presign THIS file; backend accepts url or key
            const {data} = await api.get(`/admin/kits/${kitId}/master-url`, {
                params: {file: fileUrl},
            });
            src = data?.url || "";
        } catch {
            // fallback only if your bucket allows public read
            src = fileUrl || "";
        }

        if (!src) {
            setPlayingId(null);
            return;
        }

        el.src = src;
        el.play()
            .then(() => setPlayingId(current))
            .catch(() => setPlayingId(null));
    };

    async function expandPack(row) {
        if (expanded === row.id) {
            setExpanded(null);
            return;
        }
        setExpanded(row.id);
        if (!row.detail) {
            try {
                const [detail, ver] = await Promise.all([apiPackDetail(row.id), apiPackVerify(row.id)]);
                const mappedDetail = {
                    ...detail,
                    beats: (detail.beats || []).map(b => ({
                        ...b,
                        // prefer server-provided URLs; fall back to legacy names if present
                        previewUrl: toFileUrl(b.audioUrl || b.previewUrl || b.audioFilePath),
                        coverUrl: toFileUrl(b.coverUrl || b.albumCoverUrl || b.coverImagePath),
                    })),
                };
                setRows(prev => prev.map(r => r.id === row.id ? {...r, detail: mappedDetail} : r));
                setVerifyMap(m => ({...m, [row.id]: ver}));
            } catch {/* ignore */
            }
        }
    }

    async function approve(ids = [...selected]) {
        const prev = rows.slice();
        setRows(rs => rs.map(r => ids.includes(r.id) ? {...r, status: "approved"} : r));
        try {
            for (const id of ids) {
                const item = prev.find(r => r.id === id);
                await apiApprove(item.kind, id);
            }
            setSelected(new Set());
        } catch {
            setRows(prev);
            toast.error("Approve failed, rolled back.");
        }
    }

    async function reject(ids = [...selected]) {
        const prev = rows.slice();
        setRows(rs => rs.map(r => ids.includes(r.id) ? {...r, status: "rejected"} : r));
        try {
            for (const id of ids) {
                const item = prev.find(r => r.id === id);
                await apiReject(item.kind, id);
            }
            setSelected(new Set());
        } catch {
            setRows(prev);
            toast.error("Reject failed, rolled back.");
        }
    }

    const pid = (packId, beatId) => `pack:${packId}:${beatId}`;

    const playPackBeat = async (packId, beat) => {
        const el = audioRef.current;
        if (!el) return;

        const current = pid(packId, beat.id);

        if (playingId === current) {
            el.pause();
            setPlayingId(null);
            return;
        }

        let src = "";
        try {
            const {data} = await api.get(`/admin/beats/${beat.id}/master-url`);
            src = data?.url || "";
        } catch {
            src = beat.previewUrl || "";
        }
        if (!src) {
            setPlayingId(null);
            return;
        }

        el.src = src;                    // ← set the source!
        el.play()
            .then(() => setPlayingId(current))
            .catch(() => setPlayingId(null));
    };

    return (
        <div className="admin">
            <audio ref={audioRef} preload="none"/>

            <header className="admin__top">
                <h1>Review Submissions</h1>
                <div className="admin__actions">
                    <div className="admin__search">
                        <IoSearch aria-hidden/>
                        <input
                            value={q}
                            onChange={e => setQ(e.target.value)}
                            placeholder="Search title or user…"
                            aria-label="Search"
                        />
                    </div>

                    <div className="admin__filters">
                        <IoFilter aria-hidden/>
                        <select value={status} onChange={e => setStatus(e.target.value)}>
                            <option value="pending">Pending</option>
                            <option value="all">All</option>
                        </select>
                    </div>

                    <button className="admin__refresh" onClick={() => setQ(q => q)} title="Refresh">
                        <IoRefresh/>
                    </button>
                </div>
            </header>

            <section className="admin__bulkbar" aria-live="polite">
                <span>{selected.size} selected</span>
                <div className="admin__bulkbtns">
                    <button disabled={selected.size === 0} className="btn btn--approve" onClick={() => approve()}>
                        <IoCheckmark/> Approve
                    </button>
                    <button
                        disabled={selected.size === 0}
                        className="btn btn--reject"
                        onClick={() => {
                            const targets = rows.filter(r => selected.has(r.id)).map(r => ({
                                id: r.id, kind: r.kind, title: r.title
                            }));
                            setRejectTargets(targets);
                            setRejectOpen(true);
                        }}
                    >
                        <IoClose/> Reject
                    </button>
                </div>
            </section>

            {error && <div className="admin__error"><IoWarningOutline/> {error}</div>}

            <div className="admin__tablewrap">
                <table className="admin__table">
                    <thead>
                    <tr>
                        <th>
                            <input
                                type="checkbox"
                                checked={allChecked}
                                ref={el => {
                                    if (el) el.indeterminate = someChecked;
                                }}
                                onChange={toggleAll}
                            />
                        </th>
                        <th>Play</th>
                        <th>Title</th>
                        <th>User</th>
                        <th>Tags</th>
                        <th>Kind</th>
                        <th>Duration</th>
                        <th>Price</th>
                        <th>Status</th>
                        <th>Submitted</th>
                        <th>Actions</th>
                    </tr>
                    </thead>
                    <tbody>
                    {loading ? (
                        <tr>
                            <td colSpan="11" className="admin__loading">Loading…</td>
                        </tr>
                    ) : rows.length === 0 ? (
                        <tr>
                            <td colSpan="11" className="admin__empty">No items.</td>
                        </tr>
                    ) : rows.map(r => (
                        <>
                            <tr key={`${r.kind}-${r.id}`} className={`is-${r.status}`}>
                                <td>
                                    <input type="checkbox" checked={selected.has(r.id)}
                                           onChange={() => toggleOne(r.id)}/>
                                </td>
                                <td className="cell-play">
                                    <button
                                        className={`playbtn ${playingId === r.id ? "is-playing" : ""}`}
                                        onClick={() => handlePlayToggle(r)}
                                        title={r.kind === "PACK" ? "No preview" : (playingId === r.id ? "Pause" : "Play")}
                                        disabled={r.kind === "PACK"} // ← only disable for packs
                                    >
                                        {playingId === r.id ? <IoPause/> : <IoPlay/>}
                                    </button>
                                </td>
                                <td className="cell-title">
                                    <div className="cell-title__wrap">
                                        {r.coverUrl && <img className="thumb" src={r.coverUrl} alt="" aria-hidden/>}
                                        <div className="tcol">
                                            <div className="t1">
                                                {r.title}
                                                {(r.kind === "PACK" || r.kind === "KIT") && (
                                                    <button
                                                        className="icon-btn ml-8"
                                                        onClick={() => expandItem(r)}   // ← changed
                                                        title="Show contents"
                                                    >
                                                        {expanded === r.id ? <IoChevronDown/> : <IoChevronForward/>}
                                                    </button>
                                                )}
                                            </div>
                                            <div className="t2">
                                                {r.kind === "PACK"
                                                    ? `${r.beatsCount ?? "?"} items`
                                                    : r.kind === "KIT"
                                                        ? `${(r.filesCount ?? "?")} items`
                                                        : (r.previewUrl || "—")}
                                            </div>
                                        </div>
                                    </div>
                                </td>
                                <td>{r.artistName}</td>
                                <td className="cell-tags">
                                    {(r.tags || []).map(t => <span key={t} className="tag">{t}</span>)}
                                </td>
                                <td>
                  <span className={`badge badge--${
                      r.kind === "PACK" ? "pack" : (r.kind === "KIT" ? "kit" : "beat")
                  }`}>
                    {r.kind}
                  </span>
                                </td>
                                <td>{r.kind === "BEAT" ? fmtTime(r.durationSec) : "—"}</td>
                                <td>${Number(r.price ?? 0).toFixed(2)}</td>
                                <td><span className={`badge badge--${r.status}`}>{r.status}</span></td>
                                <td>{new Date(r.submittedAt).toLocaleString()}</td>
                                <td className="cell-actions">
                                    <button className="icon-btn approve" title="Approve"
                                            onClick={() => approve([r.id])}><IoCheckmark/></button>
                                    <button
                                        className="icon-btn reject"
                                        title="Reject"
                                        onClick={() => {
                                            setRejectTargets([{id: r.id, kind: r.kind, title: r.title}]);
                                            setRejectOpen(true);
                                        }}
                                    >
                                        <IoClose/>
                                    </button>
                                </td>
                            </tr>

                            {/* PACK expanded row */}
                            {r.kind === "PACK" && expanded === r.id && r.detail && (
                                <tr className="row-expand">
                                    <td/>
                                    <td colSpan="10">
                                        <div className="expand__wrap">
                                            <h4>Pack Contents</h4>
                                            <ul className="list-beats">
                                                {(r.detail.beats || []).map(b => {
                                                    const isPlaying = playingId === pid(r.id, b.id);
                                                    return (
                                                        <li key={b.id} className="pack-beat-row">
                                                            <button
                                                                className={`playbtn sub ${isPlaying ? "is-playing" : ""}`}
                                                                onClick={() => playPackBeat(r.id, b)}
                                                                title={isPlaying ? "Pause" : "Play"}
                                                            >
                                                                {isPlaying ? <IoPause/> : <IoPlay/>}
                                                            </button>
                                                            <span
                                                                className="beat-title">{b.title || "(untitled)"}</span>
                                                            <span className="beat-meta">
                  {b.bpm ? `${b.bpm} BPM • ` : ""}
                                                                {b.durationInSeconds ? fmtTime(b.durationInSeconds) : ""}
                </span>
                                                        </li>
                                                    );
                                                })}
                                            </ul>
                                            {verifyMap[r.id] && (
                                                <div className={`verify ${verifyMap[r.id].ok ? "ok" : "warn"}`}>
                                                    {verifyMap[r.id].ok ? "No issues found." : (
                                                        <>
                                                            <strong>Issues:</strong>
                                                            <ul>{verifyMap[r.id].issues.map((it, i) => <li
                                                                key={i}>{it}</li>)}</ul>
                                                        </>
                                                    )}
                                                </div>
                                            )}
                                        </div>
                                    </td>
                                </tr>
                            )}

                            {/* KIT expanded row */}
                            {r.kind === "KIT" && expanded === r.id && r.detail && (
                                <tr className="row-expand">
                                    <td/>
                                    <td colSpan="10">
                                        <div className="expand__wrap">
                                            <h4>Kit Files</h4>
                                            <ul className="list-beats">
                                                {(r.detail.files || []).map((fileUrl, i) => {
                                                    const isPlaying = playingId === makePlayIdKit(r.id, i);
                                                    const name = (fileUrl || "").split("/").pop() || `File ${i + 1}`;
                                                    return (
                                                        <li key={i} className="pack-beat-row">
                                                            <button
                                                                className={`playbtn sub ${isPlaying ? "is-playing" : ""}`}
                                                                onClick={() => playKitFile(r.id, fileUrl, i)}
                                                                title={isPlaying ? "Pause" : "Play"}
                                                            >
                                                                {isPlaying ? <IoPause/> : <IoPlay/>}
                                                            </button>
                                                            <span className="beat-title">{name}</span>
                                                        </li>
                                                    );
                                                })}
                                            </ul>
                                        </div>
                                    </td>
                                </tr>
                            )}

                        </>
                    ))}
                    </tbody>
                </table>
            </div>
            {rejectOpen && (
                <div className="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="reject-title">
                    <div className="modal">
                        <h3 id="reject-title">Reject {rejectTargets.length > 1 ? `${rejectTargets.length} items` : rejectTargets[0]?.title || "item"}</h3>
                        <p className="mt-8">Tell the creator why this was rejected. This message will be sent to their
                            inbox.</p>
                        <textarea
                            className="modal-textarea"
                            placeholder="Reason (required)"
                            value={rejectReason}
                            onChange={(e) => setRejectReason(e.target.value)}
                            rows={5}
                            autoFocus
                        />
                        <div className="modal-actions">
                            <button className="btn" onClick={() => {
                                setRejectOpen(false);
                                setRejectReason("");
                                setRejectTargets([]);
                            }} disabled={rejectSending}>
                                Cancel
                            </button>
                            <button
                                className="btn btn--reject"
                                onClick={async () => {
                                    if (!rejectReason.trim()) return; // simple required guard
                                    setRejectSending(true);
                                    const ids = rejectTargets.map(t => t.id);
                                    const prev = rows.slice();
                                    // optimistic
                                    setRows(rs => rs.map(r => ids.includes(r.id) ? ({...r, status: "rejected"}) : r));
                                    try {
                                        for (const t of rejectTargets) {
                                            await apiReject(t.kind, t.id, rejectReason.trim());
                                        }
                                        setSelected(new Set());
                                        setRejectOpen(false);
                                        setRejectReason("");
                                        setRejectTargets([]);
                                    } catch {
                                        // rollback
                                        setRows(prev);
                                        toast.error("Reject failed, rolled back.");
                                    } finally {
                                        setRejectSending(false);
                                    }
                                }}
                                disabled={rejectSending || !rejectReason.trim()}
                            >
                                {rejectSending ? "Sending…" : "Reject & Notify"}
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
}
