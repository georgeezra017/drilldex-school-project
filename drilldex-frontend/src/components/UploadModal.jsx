import { useEffect, useState, useRef, useCallback } from "react";
import {
    IoClose,
    IoMusicalNotes,
    IoAlbums,
    IoCloudUploadOutline,
} from "react-icons/io5";
import "./UploadModal.css";
import LicensePricing from "./LicensePricing.jsx";
import toast from "react-hot-toast";
import DropField from "../components/DropField.jsx";
import { uploadBeatFromState, uploadPackFromState } from "../lib/api";


const initialBeat = () => ({
    audioFile: null,
    title: "",
    cover: null,
    bpm: "",
    genre: "",
    tags: "",
    price: "",
    stemsZip: null,
    licenses: {
        mp3:       { enabled: false, price: "" },
        wav:       { enabled: false, price: "" },
        premium:   { enabled: false, price: "" },
        exclusive: { enabled: false, price: "" },
    },
});

const initialPack = () => ({
    files: [],
    zip: null,
    stemsZip: null,
    name: "",
    cover: null,
    description: "",
    tags: "",
    price: "",
    licenses: {
        mp3: { enabled: false, price: "" },
        wav: { enabled: false, price: "" },
        premium: { enabled: false, price: "" },
        exclusive: { enabled: false, price: "" },
    },
});

// ---- input sanitizers ----
// allow free deletion; only enforce max while typing
const onlyInt = (v, { max = 999 } = {}) => {
    let s = String(v ?? "").replace(/[^\d]/g, "");
    if (s === "") return "";                 // let user clear the field
    if (s.length > 1) s = s.replace(/^0+/, ""); // remove leading zeros (keep single 0 if needed)
    let n = parseInt(s, 10);
    if (Number.isNaN(n)) return "";
    if (n > max) n = max;                    // cap to max live
    return String(n);
};

// clamp to min/max when the user leaves the field (or on submit)
const clampInt = (v, { min = 0, max = 999 } = {}) => {
    const s = String(v ?? "").replace(/[^\d]/g, "");
    if (s === "") return "";                 // keep empty if they left it blank
    let n = parseInt(s, 10);
    if (Number.isNaN(n)) return "";
    if (n < min) n = min;
    if (n > max) n = max;
    return String(n);
};

const onlyDecimal = (v, { min = 0, max = 999999, decimals = 2 } = {}) => {
    if (typeof v !== "string") v = String(v ?? "");

    // keep digits, comma, dot; normalize comma->dot
    v = v.replace(/[^0-9.,]/g, "").replace(/,/g, ".");

    // if user just typed a single dot, allow it
    if (v === ".") return v;

    // collapse multiple dots to a single one (keep the first)
    const firstDot = v.indexOf(".");
    if (firstDot !== -1) {
        v =
            v.slice(0, firstDot + 1) +
            v
                .slice(firstDot + 1)
                .replace(/\./g, ""); // remove any additional dots
    }

    // split integer / fractional
    let [intPart = "", fracPart = ""] = v.split(".");

    // strip leading zeros in integer part but keep one if it’s followed by fraction
    intPart = intPart.replace(/^0+(?=\d)/, "");
    if (intPart === "") intPart = "0";

    // limit fractional digits
    if (fracPart) fracPart = fracPart.slice(0, decimals);

    const endsWithDot = v.endsWith(".") && fracPart === "";

    // while typing a trailing dot, don’t clamp yet—just reflect
    if (endsWithDot) {
        // still respect min/max on the integer portion
        const clampedInt = Math.min(Math.max(Number(intPart || "0"), min), max);
        return `${clampedInt}.`;
    }

    // build normalized string
    const normalized = fracPart ? `${intPart}.${fracPart}` : intPart;

    // allow empty, "0", or "0." (handled above) while typing
    if (normalized === "" || normalized === "0") return normalized;

    // clamp numeric value
    const num = Number(normalized);
    if (Number.isNaN(num)) return "";

    const clamped = Math.min(Math.max(num, min), max);
    // preserve up to `decimals` fractional digits already typed
    if (decimals === 0) return String(Math.trunc(clamped));

    const [ci, cd = ""] = String(clamped).split(".");
    return cd ? `${ci}.${cd.slice(0, decimals)}` : ci;
};

// letters + spaces + common separators (no digits)
const onlyAlphaPhrase = (v, { allowPunct=true, maxLen=60 } = {}) => {
    const re = allowPunct
        ? /[^a-zA-ZÀ-ÿ\s'&/()-]/g   // keep letters incl. accents + spaces + a few separators
        : /[^a-zA-ZÀ-ÿ\s]/g;
    return v.replace(re, "").slice(0, maxLen);
};

// title can be fairly free-text, but remove control/angle brackets
const safeTitle = (v, { maxLen=80 } = {}) =>
    v.replace(/[<>]/g, "").slice(0, maxLen);

// tags: letters/numbers/spaces/hyphens per tag, comma separated
const sanitizeTags = (v, { maxTags = 12, tagMaxLen = 24 } = {}) => {
    if (typeof v !== "string") v = String(v ?? "");
    // allow only letters, numbers, comma, and space
    v = v.replace(/[^A-Za-z0-9, ]+/g, "");

    // If the string ends with a comma, keep track so we don’t lose it
    const endsWithComma = v.endsWith(",");

    // split on commas → trim, collapse spaces, enforce max length
    let parts = v.split(",").map(p =>
        p
            .trim()
            .replace(/\s+/g, " ")
            .slice(0, tagMaxLen)
    );

    // remove empties & duplicates
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

    // restore trailing comma if user was in the middle of typing
    if (endsWithComma && cleaned.length < maxTags) {
        result += ", ";
    }

    return result;
};




export default function UploadModal({
                                        isOpen,
                                        onClose,
                                    }) {
    const [tab, setTab] = useState("beat"); // "beat" | "pack"
    const [beat, setBeat] = useState(initialBeat);
    const [pack, setPack] = useState(initialPack);
    const [submittingBeat, setSubmittingBeat] = useState(false);
    const [submittingPack, setSubmittingPack] = useState(false);

    function handleClose() {
        // reset forms immediately
        setBeat(initialBeat());
        setPack(initialPack());
        onClose?.();
    }

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



    useEffect(() => { if (isOpen) setTab("beat"); }, [isOpen]);

    useEffect(() => {
        if (!isOpen) {
            setBeat(initialBeat());
            setPack(initialPack());
        }
    }, [isOpen]);





    // Close on ESC
    useEffect(() => {
        if (!isOpen) return;
        const onKey = (e) => e.key === "Escape" && onClose?.();
        window.addEventListener("keydown", onKey);
        return () => window.removeEventListener("keydown", onKey);
    }, [isOpen, onClose]);

    // Reset tab when reopened
    useEffect(() => {
        if (isOpen) setTab("beat");
    }, [isOpen]);

    if (!isOpen) return null;


    const hasValidBeatLicense = Object.values(beat.licenses).some(
        (l) =>
            l.enabled &&
            String(l.price).trim() &&
            !isNaN(Number(l.price)) &&
            Number(l.price) > 0
    );


    const canSubmitBeat =
        beat.audioFile &&
        beat.title.trim() &&
        beat.cover &&
        beat.bpm.trim() &&
        beat.genre.trim() &&
        beat.tags.trim() &&
        hasValidBeatLicense;

    const hasValidPackLicense = Object.values(pack.licenses).some(
        (l) =>
            l.enabled &&
            String(l.price).trim() &&
            !isNaN(Number(l.price)) &&
            Number(l.price) > 0
    );

    const canSubmitPack =
        !!pack.zip &&
        pack.name.trim() &&
        pack.cover &&
        (pack.description || "").trim() &&
        (pack.tags || "").trim() &&
        hasValidPackLicense;

    async function handleSubmitBeat() {
        if (!canSubmitBeat || submittingBeat) return;

        const premiumOrExclusiveEnabled =
            beat.licenses.premium.enabled || beat.licenses.exclusive.enabled;

        if (premiumOrExclusiveEnabled && !beat.stemsZip) {
            toast.error("Please upload your stems ZIP when offering Premium or Exclusive licenses.");
            return;
        }

        try {
            setSubmittingBeat(true);
            const res = await uploadBeatFromState(beat);
            toast.success(res?.message || "Beat uploaded!");
            handleClose(); // resets + closes modal
        } catch (err) {
            const msg = err?.response?.data?.error || err?.message || "Upload failed";
            toast.error(msg);
        } finally {
            setSubmittingBeat(false);
        }
    }



    async function handleSubmitPack() {
        if (!canSubmitPack || submittingPack) return;

        const premiumOrExclusiveEnabled =
            pack.licenses.premium.enabled || pack.licenses.exclusive.enabled;

        if (premiumOrExclusiveEnabled && !pack.zip) {
            toast.error("Please upload your stems ZIP when offering Premium or Exclusive pack licenses.");
            return;
        }

        try {
            setSubmittingPack(true);
            const res = await uploadPackFromState(pack);
            toast.success(res?.message || "Pack uploaded!");
            handleClose();
        } catch (err) {
            const msg = err?.response?.data?.error || err?.message || "Upload failed";
            toast.error(msg);
        } finally {
            setSubmittingPack(false);
        }
    }

    return (
        <div className="uplmodal" role="dialog" aria-modal="true" aria-labelledby="uplmodal-title">
            <div className="uplmodal__backdrop" onClick={handleClose} />

            <div className="uplmodal__panel" onClick={(e) => e.stopPropagation()}>
                {/* Header */}
                <div className="uplmodal__head">
                    <h2 id="uplmodal-title" className="uplmodal__title">Upload</h2>
                    <button className="uplmodal__close" onClick={handleClose} aria-label="Close">
                        <IoClose size={20} />
                    </button>
                </div>

                {/* Tabs */}
                <div className="uplmodal__tabs" role="tablist" aria-label="Upload type">
                    <button
                        role="tab"
                        aria-selected={tab === "beat"}
                        className={`upltab ${tab === "beat" ? "is-active" : ""}`}
                        onClick={() => {
                            if (tab !== "beat") setPack(initialPack()); // leaving pack
                            setTab("beat");
                        }}
                    >
                        <IoMusicalNotes /> Single Beat
                    </button>
                    <button
                        role="tab"
                        aria-selected={tab === "pack"}
                        className={`upltab ${tab === "pack" ? "is-active" : ""}`}
                        onClick={() => {
                            if (tab !== "pack") setBeat(initialBeat()); // leaving beat
                            setTab("pack");
                        }}
                    >
                        <IoAlbums /> Pack of Beats
                    </button>
                </div>

                {/* Body switches */}
                <div className="uplmodal__body">
                    {tab === "beat" ? (
                        <BeatForm state={beat} setState={setBeat} />
                    ) : (
                        <PackForm state={pack} setState={setPack} />
                    )}
                </div>

                {/* Footer */}
                <div className="uplmodal__foot">
                    <button className="uplbtn uplbtn--ghost" onClick={onClose}>Cancel</button>

                    {tab === "beat" ? (
                        <button
                            className="uplbtn uplbtn--primary"
                            disabled={!canSubmitBeat || submittingBeat}
                            onClick={handleSubmitBeat}
                        >
                            {submittingBeat ? "Uploading…" : "Upload Beat"}
                        </button>
                    ) : (
                        <button
                            className="uplbtn uplbtn--primary"
                            disabled={!canSubmitPack || submittingPack}
                            onClick={handleSubmitPack}
                        >
                            {submittingPack ? "Uploading…" : "Upload Pack"}
                        </button>
                    )}
                </div>
            </div>
        </div>
    );
}

/* ---------- Beat Form ---------- */
function BeatForm({ state, setState }) {

    return (
        <form className="uplform" onSubmit={(e) => e.preventDefault()}>
            <div className="uplgrid">

                {/*<div className="uplfield">*/}
                {/*    <label>Audio File (MP3/WAV)</label>*/}
                {/*    <div className="dropzone" onClick={() => audioInputRef.current?.click()}>*/}
                {/*        <IoCloudUploadOutline />*/}
                {/*        <span>{state.audioFile ? state.audioFile.name : "Click to select or drop file"}</span>*/}
                {/*        <input*/}
                {/*            ref={audioInputRef}*/}
                {/*            type="file"*/}
                {/*            accept=".mp3,.wav"*/}
                {/*            onChange={(e) => setState((s) => ({ ...s, audioFile: e.target.files?.[0] || null }))}*/}
                {/*            hidden*/}
                {/*        />*/}
                {/*    </div>*/}
                {/*</div>*/}
                <DropField
                    label="Audio File (MP3/WAV)"
                    value={state.audioFile}
                    onChange={(file) => setState(s => ({ ...s, audioFile: file }))}
                    accept={{ "audio/mpeg": [".mp3"], "audio/wav": [".wav"] }}
                    multiple={false}
                    maxSize={100 * 1024 * 1024} // 100MB if you like
                    placeholder="Click to select or drop file"
                />

                {/* Cover */}
                {/*<div className="uplfield">*/}
                {/*    <label>Cover Image</label>*/}
                {/*    <div className="dropzone" onClick={() => coverInputRef.current?.click()}>*/}
                {/*        <IoCloudUploadOutline />*/}
                {/*        <span>{state.cover ? state.cover.name : "Click to select image"}</span>*/}
                {/*        <input*/}
                {/*            ref={coverInputRef}*/}
                {/*            type="file"*/}
                {/*            accept="image/*"*/}
                {/*            onChange={(e) => setState((s) => ({ ...s, cover: e.target.files?.[0] || null }))}*/}
                {/*            hidden*/}
                {/*        />*/}
                {/*    </div>*/}
                {/*</div>*/}

                <DropField
                    label="Cover Image"
                    value={state.cover}
                    onChange={(file) => setState(s => ({ ...s, cover: file }))}
                    accept={{ "image/*": [] }}
                    multiple={false}
                    maxSize={10 * 1024 * 1024} // e.g. 10MB
                    placeholder="Click to select or drop image"
                />

                <div className="uplfield">
                    <label>Title</label>
                    <input
                        className="uplinput"
                        value={state.title}
                        onChange={(e) =>
                            setState(s => ({ ...s, title: safeTitle(e.target.value) }))
                        }
                        placeholder="e.g., Midnight Drill"
                        maxLength={80}
                        />
                </div>


                <div className="uplfield two">
                    <div>
                        <label>BPM</label>
                        <input
                            className="uplinput"
                            value={state.bpm}
                            onChange={(e) =>
                                setState(s => ({ ...s, bpm: onlyInt(e.target.value, { min: 30, max: 300 }) }))
                            }
                            placeholder="140"
                            inputMode="numeric"
                            pattern="[0-9]*"
                        />
                    </div>
                    <div>
                        <label>Genre</label>
                        <input
                            className="uplinput"
                            value={state.genre}
                            onChange={(e) =>
                                setState(s => ({ ...s, genre: onlyAlphaPhrase(e.target.value, { maxLen: 40 }) }))
                            }
                            placeholder="UK Drill"
                            maxLength={40}
                        />
                    </div>

                </div>

                <div className="uplfield two">

                    <div>
                        <label>Tags</label>
                        <input
                            className="uplinput"
                            value={state.tags}
                            onChange={(e) =>
                                setState(s => ({ ...s, tags: sanitizeTags(e.target.value) }))
                            }
                            placeholder="dark, fast, drill"
                        />
                    </div>
                </div>


            </div>
            <div className="uplfield">
                {(state.licenses.premium.enabled || state.licenses.exclusive.enabled) && (
                    <div className="uplfield" style={{ marginTop: 20 }}>
                        <DropField
                            label="Stems ZIP"
                            value={state.stemsZip}
                            onChange={(file) => setState((s) => ({ ...s, stemsZip: file }))}
                            accept={{
                                "application/zip": [".zip"],
                                "application/x-zip-compressed": [".zip"],
                                "application/octet-stream": [".zip"],
                            }}
                            multiple={false}
                            maxSize={1000 * 1024 * 1024} // 1GB limit
                            placeholder="Click to select or drop a ZIP with your stems"
                        />
                        <p className="muted" style={{ marginTop: 6 }}>
                            Upload a ZIP containing your separated track stems (WAV, MP3, M4A, etc.).
                            This is <strong>required</strong> if you offer <strong>Premium</strong> or <strong>Exclusive</strong> licenses.
                        </p>
                    </div>
                )}
                <label style={{
                    "margin-top":"12px"}}>Licenses & Pricing
                </label>

                <LicensePricing
                    value={state.licenses}
                    onChange={(licenses) => setState((s) => ({ ...s, licenses }))}
                    currency="$"
                />

                <p className="muted" style={{ marginTop: 8 }}>
                    Toggle which licenses you want to offer and set your own price for each.
                </p>
            </div>
        </form>
    );
}

/* ---------- Pack Form ---------- */
function PackForm({ state, setState }) {
    const handleFilesOrZip = useCallback((selection) => {
        const arr = Array.isArray(selection) ? selection : [selection].filter(Boolean);
        if (!arr.length) {
            setState(s => ({ ...s, files: [], zip: null }));
            return;
        }

        const hasZip   = arr.some(f => /\.zip$/i.test(f.name) || (f.type && f.type.toLowerCase().includes("zip")));
        const allAudio = arr.every(f => /\.(mp3|wav)$/i.test(f.name));

        // only a single zip OR one/many mp3/wav — not both, not mixed
        if (hasZip && arr.length > 1) {
            toast.error("Pick a single .zip OR multiple .mp3/.wav files, not both.");
            return;
        }
        if (hasZip && !/\.zip$/i.test(arr[0].name)) {
            toast.error("Pick a single .zip OR multiple .mp3/.wav files, not both.");
            return;
        }
        if (!hasZip) {
            toast.error("Only a single .zip file is allowed.");
            return;
        }

        if (hasZip) {
            setState(s => ({ ...s, zip: arr[0], files: [] }));
        } else {
            setState(s => ({ ...s, files: arr, zip: null }));
        }
    }, [setState]);

    return (
        <form className="uplform" onSubmit={(e) => e.preventDefault()}>
            <div className="uplgrid">
                {/* Multiple files */}
                <div className="uplfield">
                    <label>Pack Name</label>
                    <input
                        className="uplinput"
                        value={state.name}
                        onChange={(e) =>
                            setState(s => ({ ...s, name: safeTitle(e.target.value, { maxLen: 80 }) }))
                        }
                        placeholder="e.g., Drill Essentials Vol. 1"
                        maxLength={80}
                    />
                </div>

                <div className="uplfield">
                    <label>Description</label>
                    <textarea
                        className="uplinput"
                        rows={4}
                        value={state.description}
                        onChange={(e) =>
                            setState(s => ({ ...s, description: e.target.value.replace(/[<>]/g, "") }))
                        }
                        placeholder="Describe what's inside the pack…"
                    />
                </div>

                {/*<div className="uplfield">*/}
                {/*    <label>Audio Files or ZIP</label>*/}
                {/*    <div className="dropzone" onClick={() => filesInputRef.current?.click()}>*/}
                {/*        <IoCloudUploadOutline />*/}
                {/*        <span>*/}
                {/*            {state.files.length*/}
                {/*                ? `${state.files.length} file(s) selected`*/}
                {/*                : "Click to select multiple files or a .zip"}*/}
                {/*        </span>*/}
                {/*        <input*/}
                {/*            ref={filesInputRef}*/}
                {/*            type="file"*/}
                {/*            accept=".mp3,.wav,.zip"*/}
                {/*            multiple*/}
                {/*            onChange={(e) => setState((s) => ({ ...s, files: Array.from(e.target.files || []) }))}*/}
                {/*            hidden*/}
                {/*        />*/}
                {/*    </div>*/}
                {/*</div>*/}

                <DropField
                    label="ZIP (required)"
                    value={state.zip}
                    onChange={(file) => setState(s => ({ ...s, zip: file, files: [] }))} // clear files
                    accept={{
                        "application/zip": [".zip"],
                        "application/x-zip-compressed": [".zip"],
                        "application/octet-stream": [".zip"],
                    }}
                    multiple={false}
                    maxSize={1_000 * 1024 * 1024} // 1GB (adjust if needed)
                    placeholder="Click to select or drop a .zip"
                />



                {/*<div className="uplfield">*/}
                {/*    <label>Cover Image</label>*/}
                {/*    <div className="dropzone" onClick={() => coverInputRef.current?.click()}>*/}
                {/*        <IoCloudUploadOutline />*/}
                {/*        <span>{state.cover ? state.cover.name : "Click to select image"}</span>*/}
                {/*        <input*/}
                {/*            ref={coverInputRef}*/}
                {/*            type="file"*/}
                {/*            accept="image/*"*/}
                {/*            onChange={(e) => setState((s) => ({ ...s, cover: e.target.files?.[0] || null }))}*/}
                {/*            hidden*/}
                {/*        />*/}
                {/*    </div>*/}
                {/*</div>*/}

                <DropField
                    label="Cover Image"
                    value={state.cover}
                    onChange={(file) => setState(s => ({ ...s, cover: file }))}
                    accept={{ "image/*": [] }}
                    multiple={false}
                    maxSize={10 * 1024 * 1024}
                    placeholder="Click to select or drop image"
                />

                <div className="uplfield two">
                    <div>
                        <label>Tags</label>
                        <input
                            className="uplinput"
                            value={state.tags}
                            onChange={(e) =>
                                setState(s => ({ ...s, tags: sanitizeTags(e.target.value) }))
                            }
                            placeholder="drill, bundle, stems"
                        />
                    </div>
                </div>
            </div>
            <div className="uplfield">
                {(state.licenses.premium.enabled || state.licenses.exclusive.enabled) && (
                    <div className="uplfield" style={{ marginTop: 20 }}>
                        <DropField
                            label="Stems ZIP"
                            value={state.stemsZip}
                            onChange={(file) => setState((s) => ({ ...s, stemsZip: file }))}
                            accept={{
                                "application/zip": [".zip"],
                                "application/x-zip-compressed": [".zip"],
                                "application/octet-stream": [".zip"],
                            }}
                            multiple={false}
                            maxSize={1000 * 1024 * 1024} // 1GB limit
                            placeholder="Click to select or drop a ZIP with your stems"
                        />
                        <p className="muted" style={{ marginTop: 6 }}>
                            Upload a ZIP containing your separated track stems (WAV, MP3, M4A, etc.).
                            This is <strong>required</strong> if you offer <strong>Premium</strong> or <strong>Exclusive</strong> licenses.
                        </p>
                    </div>
                )}
                <label style={{
                    "margin-top":"12px"}}>Licenses & Pricing</label>
                <LicensePricing
                    value={state.licenses}
                    onChange={(licenses) => setState((s) => ({ ...s, licenses }))}
                    currency="$"
                />
                <p className="muted" style={{ marginTop: 8 }}>
                    Toggle which licenses you want to offer and set your own price for each.
                </p>
            </div>
        </form>
    );
}