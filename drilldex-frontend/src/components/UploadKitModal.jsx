import {useEffect, useState} from "react";
import {IoClose, IoAlbums} from "react-icons/io5";
import DropField from "./DropField.jsx";
import "./UploadModal.css";
import toast from "react-hot-toast";
import {uploadKitFromState} from "../lib/api";

/* ========== Sanitizers (your functions) ========== */
// allow free deletion; only enforce max while typing
const onlyInt = (v, {max = 999} = {}) => {
    let s = String(v ?? "").replace(/[^\d]/g, "");
    if (s === "") return "";
    if (s.length > 1) s = s.replace(/^0+/, "");
    let n = parseInt(s, 10);
    if (Number.isNaN(n)) return "";
    if (n > max) n = max;
    return String(n);
};

// clamp to min/max when the user leaves the field (or on submit)
const clampInt = (v, {min = 0, max = 999} = {}) => {
    const s = String(v ?? "").replace(/[^\d]/g, "");
    if (s === "") return "";
    let n = parseInt(s, 10);
    if (Number.isNaN(n)) return "";
    if (n < min) n = min;
    if (n > max) n = max;
    return String(n);
};

const onlyDecimal = (v, {min = 0, max = 999999, decimals = 2} = {}) => {
    if (typeof v !== "string") v = String(v ?? "");
    v = v.replace(/[^0-9.,]/g, "").replace(/,/g, ".");
    if (v === ".") return v;

    const firstDot = v.indexOf(".");
    if (firstDot !== -1) {
        v = v.slice(0, firstDot + 1) + v.slice(firstDot + 1).replace(/\./g, "");
    }

    let [intPart = "", fracPart = ""] = v.split(".");
    intPart = intPart.replace(/^0+(?=\d)/, "");
    if (intPart === "") intPart = "0";
    if (fracPart) fracPart = fracPart.slice(0, decimals);

    const endsWithDot = v.endsWith(".") && fracPart === "";
    if (endsWithDot) {
        const clampedInt = Math.min(Math.max(Number(intPart || "0"), min), max);
        return `${clampedInt}.`;
    }

    const normalized = fracPart ? `${intPart}.${fracPart}` : intPart;
    if (normalized === "" || normalized === "0") return normalized;

    const num = Number(normalized);
    if (Number.isNaN(num)) return "";
    const clamped = Math.min(Math.max(num, min), max);
    if (decimals === 0) return String(Math.trunc(clamped));
    const [ci, cd = ""] = String(clamped).split(".");
    return cd ? `${ci}.${cd.slice(0, decimals)}` : ci;
};

// letters + spaces + common separators (no digits)
const onlyAlphaPhrase = (v, {allowPunct = true, maxLen = 60} = {}) => {
    const re = allowPunct
        ? /[^a-zA-ZÀ-ÿ\s'&/()-]/g
        : /[^a-zA-ZÀ-ÿ\s]/g;
    return v.replace(re, "").slice(0, maxLen);
};

// title can be fairly free-text, but remove control/angle brackets
const safeTitle = (v, {maxLen = 80} = {}) =>
    v.replace(/[<>]/g, "").slice(0, maxLen);

// tags: letters/numbers/spaces/hyphens per tag, comma separated
const sanitizeTags = (v, {maxTags = 12, tagMaxLen = 24} = {}) => {
    if (typeof v !== "string") v = String(v ?? "");
    v = v.replace(/[^A-Za-z0-9, ]+/g, "");
    const endsWithComma = v.endsWith(",");
    let parts = v.split(",").map((p) =>
        p.trim().replace(/\s+/g, " ").slice(0, tagMaxLen)
    );
    const seen = new Set();
    const cleaned = [];
    for (const p of parts) {
        if (!p) continue;
        const key = p.toLowerCase();
        if (seen.has(key)) continue;
        seen.add(key);
        cleaned.push(p);
        if (cleaned.length >= maxTags) break;
    }
    let result = cleaned.join(", ");
    if (endsWithComma && cleaned.length < maxTags) result += ", ";
    return result;
};
/* ================================================= */

const KIT_TYPES = [
    "Drum Kit",
    "Loop Kit",
    "MIDI Kit",
    "Construction Kit",
    "FX Kit",
    "One-Shots",
];

export default function UploadKitModal({isOpen, onClose, onSubmit}) {
    const [kit, setKit] = useState({
        type: "Drum Kit",
        name: "",
        cover: null,
        files: [],     // kept for backward-compat, but not used anymore
        zip: null,     // ← NEW
        description: "",
        bpmMin: "",
        bpmMax: "",
        key: "",
        price: "",
        tags: "",
    });
    const [submitting, setSubmitting] = useState(false);

    // Accept maps for DropField
    const ACCEPT_IMG = {"image/*": [".png", ".jpg", ".jpeg", ".webp"]};

    const ACCEPT_ZIP = {
        "application/zip": [".zip"],
        "application/x-zip-compressed": [".zip"],
        "multipart/x-zip": [".zip"],
        // some browsers report generic type:
        "application/octet-stream": [".zip"],
    };


    useEffect(() => {
        if (!isOpen) return;
        const onKey = (e) => e.key === "Escape" && onClose?.();
        window.addEventListener("keydown", onKey);
        return () => window.removeEventListener("keydown", onKey);
    }, [isOpen, onClose]);

    // lock scroll while open
    useEffect(() => {
        if (!isOpen) return;
        const scrollY = window.scrollY;
        document.body.dataset.scrollY = String(scrollY);
        document.body.style.position = "fixed";
        document.body.style.top = `-${scrollY}px`;
        document.body.style.width = "100%";
        document.body.style.overflow = "hidden";
        return () => {
            const y = Number(document.body.dataset.scrollY || 0);
            document.body.style.position = "";
            document.body.style.top = "";
            document.body.style.width = "";
            document.body.style.overflow = "";
            delete document.body.dataset.scrollY;
            window.scrollTo(0, y);
        };
    }, [isOpen]);

    useEffect(() => {
        if (isOpen) setKit((k) => ({...k, type: k.type || "Drum Kit"}));
    }, [isOpen]);

    if (!isOpen) return null;

    const set = (patch) => setKit((k) => ({...k, ...patch}));

    const isLoopLike = kit.type === "Loop Kit";
    const needsKey = kit.type === "Loop Kit" || kit.type === "Construction Kit";

     const canSubmit =
           kit.type &&
           kit.name.trim() &&
           kit.cover &&
           !!kit.zip &&                                // ← must have a zip
           kit.description.trim() &&
           kit.price.trim() &&
           kit.tags.trim() &&
           (!isLoopLike || (kit.bpmMin.trim() && kit.bpmMax.trim())) &&
           (!needsKey || kit.key.trim()) &&
           !submitting;

// show toasts + call API
    async function handleUploadClick() {
        if (!canSubmit || submitting) return;

        try {
            setSubmitting(true);
            const res = await uploadKitFromState(kit);
            toast.success(res?.message || "Kit uploaded and awaiting admin approval.");
            onClose?.(); // close only after success
        } catch (err) {
            const msg =
                err?.message ||
                err?.response?.data?.error ||
                "Upload failed. Please try again.";
            toast.error(msg);
        } finally {
            setSubmitting(false);
        }
    }

    return (
        <div className="uplmodal" role="dialog" aria-modal="true" aria-labelledby="uplmodal-title">
            <div className="uplmodal__backdrop" onClick={onClose}/>
            <div className="uplmodal__panel" onClick={(e) => e.stopPropagation()}>
                {/* Head */}
                <div className="uplmodal__head">
                    <h2 id="uplmodal-title" className="uplmodal__title">
            <span style={{display: "inline-flex", alignItems: "center", gap: 8}}>
              <IoAlbums/> Upload Kit
            </span>
                    </h2>
                    <button className="uplmodal__close" onClick={onClose} aria-label="Close">
                        <IoClose size={20}/>
                    </button>
                </div>

                {/* Body */}
                <div className="uplmodal__body">
                    <form
                        className="uplform"
                        onSubmit={(e) => {
                            e.preventDefault();
                            if (canSubmit) onSubmit?.(kit);
                        }}
                    >
                        <div className="uplgrid">
                            {/* Kit type */}
                            <div className="uplfield">
                                <label>Kit Type</label>
                                <select
                                    className="uplinput"
                                    value={kit.type}
                                    onChange={(e) => set({type: e.target.value})}
                                >
                                    {KIT_TYPES.map((t) => (
                                        <option key={t} value={t}>
                                            {t}
                                        </option>
                                    ))}
                                </select>
                            </div>

                            <div className="uplfield">
                                <label>Kit Name</label>
                                <input
                                    className="uplinput"
                                    value={kit.name}
                                    onChange={(e) => set({name: safeTitle(e.target.value, {maxLen: 80})})}
                                    placeholder="e.g., Drill Essentials Vol. 1"
                                    maxLength={80}
                                />
                            </div>

                            {/* Cover */}
                            <div className="uplfield">
                                <DropField
                                    label="Cover Image"
                                    value={kit.cover}
                                    onChange={(file) => set({cover: file})}
                                    accept={ACCEPT_IMG}
                                    maxSize={15 * 1024 * 1024}
                                    placeholder="Click to select or drop image (PNG, JPG, WEBP)"
                                />
                            </div>

                            {/* Files */}
                            <div className="uplfield span-2">
                                <DropField
                                    label="Kit Contents (.zip only)"
                                    value={kit.zip}
                                    onChange={(file) => {
                                        if (!file) return set({zip: null});
                                        if (!/\.zip$/i.test(file.name)) {
                                            toast.error("Please select a .zip file");
                                            return;
                                        }
                                        set({zip: file});
                                    }}
                                    accept={ACCEPT_ZIP}
                                    multiple={false}
                                    maxSize={2 * 1024 * 1024 * 1024} // 2GB
                                    placeholder="Click to select or drop a .zip file"
                                />
                            </div>

                            {/* Type specific */}
                            {isLoopLike && (
                                <>
                                    <div className="uplfield">
                                        <label>BPM Min</label>
                                        <input
                                            className="uplinput"
                                            inputMode="numeric"
                                            placeholder="120"
                                            value={kit.bpmMin}
                                            onChange={(e) => set({bpmMin: onlyInt(e.target.value, {max: 300})})}
                                            onBlur={(e) => set({bpmMin: clampInt(e.target.value, {min: 30, max: 300})})}
                                        />
                                    </div>
                                    <div className="uplfield">
                                        <label>BPM Max</label>
                                        <input
                                            className="uplinput"
                                            inputMode="numeric"
                                            placeholder="160"
                                            value={kit.bpmMax}
                                            onChange={(e) => set({bpmMax: onlyInt(e.target.value, {max: 300})})}
                                            onBlur={(e) => set({bpmMax: clampInt(e.target.value, {min: 30, max: 300})})}
                                        />
                                    </div>
                                </>
                            )}

                            {needsKey && (
                                <div className="uplfield">
                                    <label>Musical Key</label>
                                    <input
                                        className="uplinput"
                                        placeholder="e.g., Am, F#m, C"
                                        value={kit.key}
                                        onChange={(e) => set({
                                            key: onlyAlphaPhrase(e.target.value, {
                                                allowPunct: true,
                                                maxLen: 6
                                            })
                                        })}
                                    />
                                </div>
                            )}

                            {/* Description */}
                            <div className="uplfield span-2">
                                <label>Description</label>
                                <textarea
                                    className="uplinput"
                                    rows={4}
                                    placeholder="What’s inside the kit? (format, count, styles)"
                                    value={kit.description}
                                    onChange={(e) => set({description: e.target.value.replace(/[<>]/g, "")})}
                                />
                            </div>

                            {/* Price & Tags */}
                            <div className="uplfield">
                                <label>Price ($)</label>
                                <input
                                    className="uplinput"
                                    inputMode="decimal"
                                    placeholder="24.99"
                                    value={kit.price}
                                    onChange={(e) => set({
                                        price: onlyDecimal(e.target.value, {
                                            min: 0,
                                            max: 999999,
                                            decimals: 2
                                        })
                                    })}
                                />
                            </div>

                            <div className="uplfield">
                                <label>Tags</label>
                                <input
                                    className="uplinput"
                                    placeholder="drill, 808s, snares, loops"
                                    value={kit.tags}
                                    onChange={(e) => set({tags: sanitizeTags(e.target.value)})}
                                />
                            </div>
                        </div>
                    </form>
                </div>

                {/* Footer */}
                <div className="uplmodal__foot">

                    <div className="uplmodal__foot">
                        <button className="uplbtn uplbtn--ghost" onClick={onClose} type="button">
                            Cancel
                        </button>
                        <button
                            className="uplbtn uplbtn--primary"
                            type="button"
                            disabled={!canSubmit}
                            onClick={handleUploadClick}
                        >
                            {submitting ? "Uploading…" : "Upload Kit"}
                        </button>
                    </div>
                </div>
            </div>
        </div>
    );
}