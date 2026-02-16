import { useEffect, useState } from "react";
import "./edit-item-modal.css";
import api from "../lib/api";
import toast from "react-hot-toast";

export default function EditItemModal({
                                          open,
                                          item,
                                          onClose,
                                          onSave,
                                          onSaved,
                                          onRemoveChild,
                                      }) {

    const kind = item?.kind ?? "";
    const isPack = kind === "Pack";
    const isBeat = kind === "Beat";
    const isKit = kind === "Kit";
    const [loadingChildren, setLoadingChildren] = useState(false);
    const [childErr, setChildErr] = useState("");
    const [title, setTitle] = useState("");
    const [price, setPrice] = useState("");
    const [genre, setGenre] = useState("");
    const [bpm, setBpm] = useState("");
    const [tags, setTags] = useState("");
    const [children, setChildren] = useState([]);
    const [licenses, setLicenses] = useState([]);
    const [stemsInfo, setStemsInfo] = useState({ hasStems: false, name: "" });

    const fireSaved = (serverData, payload) => {
        if (typeof onSaved === "function") onSaved(serverData, payload);
        else if (typeof onSave === "function") onSave(serverData, payload);
    };

    const updateLicense = (id, patch) =>
        setLicenses(prev => prev.map(l => (l.id === id ? { ...l, ...patch } : l)));

    const toTagString = (t) => Array.isArray(t) ? t.join(", ") : (t ?? "");

    useEffect(() => {
        if (!open || !item?.id) return;

        const base =
            item.kind === "Beat" ? "/me/beats" :
                item.kind === "Pack" ? "/me/packs" :
                    item.kind === "Kit"  ? "/me/kits"  : null;

        if (!base) return;

        (async () => {
            try {
                const { data } = await api.get(`${base}/${item.id}`);
                // Accept either an array of tags or a comma string:
                const t = Array.isArray(data?.tags) ? data.tags.join(", ") : (data?.tags ?? "");
                setTags(t);
                // (Optional) also refresh genre/bpm if detail has them
                if (typeof data?.genre === "string") setGenre(data.genre);
                if (typeof data?.bpm !== "undefined" && data?.bpm !== null) setBpm(String(data.bpm || ""));
            } catch {
                // leave whatever we already had; no toast needed
            }
        })();
    }, [open, item?.id, item?.kind]);

    // --- put this at the top of your component file or in utils/tags.js ---
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

    const onlyInt = (v, { max = 999 } = {}) => {
        let s = String(v ?? "").replace(/[^\d]/g, "");
        if (s === "") return "";
        if (s.length > 1) s = s.replace(/^0+/, ""); // remove leading zeros
        let n = parseInt(s, 10);
        if (Number.isNaN(n)) return "";
        if (n > max) n = max;
        return String(n);
    };

// clamp integer when user leaves field (e.g. BPM)
    const clampInt = (v, { min = 0, max = 999 } = {}) => {
        const s = String(v ?? "").replace(/[^\d]/g, "");
        if (s === "") return "";
        let n = parseInt(s, 10);
        if (Number.isNaN(n)) return "";
        if (n < min) n = min;
        if (n > max) n = max;
        return String(n);
    };

// allow decimals for price (with optional clamping)
    const onlyDecimal = (v, { min = 0, max = 999999, decimals = 2 } = {}) => {
        if (typeof v !== "string") v = String(v ?? "");
        v = v.replace(/[^0-9.,]/g, "").replace(/,/g, ".");

        if (v === ".") return v;

        const firstDot = v.indexOf(".");
        if (firstDot !== -1) {
            v =
                v.slice(0, firstDot + 1) +
                v.slice(firstDot + 1).replace(/\./g, ""); // remove extra dots
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

        const [ci, cd = ""] = String(clamped).split(".");
        return cd ? `${ci}.${cd.slice(0, decimals)}` : ci;
    };

// only alphabetic words/phrases (for genre)
    const onlyAlphaPhrase = (v, { allowPunct = true, maxLen = 60 } = {}) => {
        const re = allowPunct
            ? /[^a-zA-ZÀ-ÿ\s'&/()-]/g
            : /[^a-zA-ZÀ-ÿ\s]/g;
        return v.replace(re, "").slice(0, maxLen);
    };

    const safeTitle = (v, { maxLen=80 } = {}) =>
        v.replace(/[<>]/g, "").slice(0, maxLen);

    useEffect(() => {
        setTitle(item?.title ?? "");
        setPrice(item?.price ?? "");
        setGenre(item?.genre ?? "");
        setBpm(item?.bpm ?? "");

        // 👇 always prefill tags from item; supports array or string
        setTags(toTagString(item?.tags));

        setChildren(Array.isArray(item?.children) ? item.children : []);
    }, [item]);

    useEffect(() => {
        if (open) {
            document.body.classList.add('modal-open');
        } else {
            document.body.classList.remove('modal-open');
        }
        return () => document.body.classList.remove('modal-open');
    }, [open]);

    useEffect(() => {
        if (!open || !item) return;
        if (item.kind !== "Beat" && item.kind !== "Pack") { setLicenses([]); return; }

        (async () => {
            try {
                const path = item.kind === "Beat"
                    ? `/me/beats/${item.id}/licenses`
                    : `/me/packs/${item.id}/licenses`;
                const { data } = await api.get(path);
                setLicenses(Array.isArray(data) ? data : []);
            } catch {
                setLicenses([]);
                toast.error("Failed to load licenses");
            }
        })();
    }, [open, item?.id, item?.kind]);



    const handleSubmit = async (e) => {
        e.preventDefault();

        // --- Normalize inputs ---
        const nextTitle = (title || "").trim();
        const nextTags  = (tags  || "").trim();

        // Build the base payload. Always send *something* for tags.
        const base = {
            title: nextTitle || item.title,
            tags:  nextTags  || toTagString(item.tags),
        };

        // Beat-only fields
        const beatBits = isBeat
            ? {
                genre: (genre || "").trim() || item.genre || "",
                bpm: Number.isFinite(+bpm) ? +bpm : (item.bpm || 0),
            }
            : {};

        // Kit-only fields
        const kitBits = isKit
            ? {
                price: Number.isFinite(+price) ? +price : (item.price || 0),
            }
            : {};

        const payload = { ...base, ...beatBits, ...kitBits };

        // 1) Save metadata
         // 1) Save metadata (PATCH the correct resource)
             try {
               let metaPath = null;
               let metaBody = null;

                   if (isBeat) {
                     metaPath = `/me/beats/${item.id}`;
                     metaBody = {
                           title: safeTitle(title || item.title || ""),
                           genre: (genre || "").trim() || item.genre || "",
                           bpm: Number.isFinite(+bpm) ? +bpm : (item.bpm || 0),
                           tags: (tags || "").trim() || toTagString(item.tags) || "",
                         };
                   } else if (isPack) {
                     metaPath = `/me/packs/${item.id}`;
                     metaBody = {
                           // BACKEND expects `name` for packs
                           name: safeTitle(title || item.title || ""),
                           tags: (tags || "").trim() || toTagString(item.tags) || "",
                         };
                   } else if (isKit) {
                     metaPath = `/me/kits/${item.id}`;
                     metaBody = {
                           // kits also use `name`
                           name: safeTitle(title || item.title || ""),
                           price: Number.isFinite(+price) ? +price : (item.price || 0),
                           tags: (tags || "").trim() || toTagString(item.tags) || "",
                         };
                   }

                   let saved = null;
               if (metaPath && metaBody) {
                     const { data } = await api.patch(metaPath, metaBody);
                     saved = data;
                   }
               fireSaved(saved, metaBody || {});
             } catch (e) {
               toast.error("Failed to save details");
               return; // keep modal open
             }

        // 2) Save licenses for Beat/Pack
        if (isBeat || isPack) {
            const path = isBeat
                ? `/me/beats/${item.id}/licenses`
                : `/me/packs/${item.id}/licenses`;

            const body = licenses.map(l => ({
                id: l.id,
                enabled: !!l.enabled,
                price: Number.isFinite(+l.price) ? +l.price : 0,
            }));

            try {
                await api.patch(path, body);
            } catch {
                toast.error("Failed to save license prices");
                return; // keep modal open so user can retry
            }
        }

        onClose?.();
        toast.success("Saved!");
    };

    async function loadChildren(target) {
        console.log("Target passed into loadChildren:", target);
        console.log("target.kind:", target.kind);
        if (!target) return;

        if (target.kind === "Pack") {
            const { data } = await api.get(`/me/packs/${target.id}/contents`);


            setChildren(Array.isArray(data) ? data : []);
        } else if (target.kind === "Kit") {
            const { data } = await api.get(`/me/kits/${target.id}/contents`);
            console.log("Kit contents loaded:", data);

            const fileUrls = Array.isArray(data?.fileUrls) ? data.fileUrls : [];

            const rows = fileUrls.map((url, i) => ({
                index: i,
                title: url.split("/").pop() || `File ${i + 1}`,
                subtitle: url,
                url,
                coverUrl: data.coverUrl || null,
                previewUrl: data.previewUrl || null,
            }));

            setChildren(rows);
        } else {
            setChildren([]); // Beat or anything else
        }
    }

    useEffect(() => {
        if (!open || !item || !(isPack || isKit)) return;
        (async () => {
            try {
                setLoadingChildren(true);
                setChildErr("");
                await loadChildren(item);
            } catch {
                setChildErr("Failed to load contents.");
            } finally {
                setLoadingChildren(false);
            }
        })();
    }, [open, item?.id, item?.kind]);

    useEffect(() => {
        if (!open || !(isBeat || isPack) || !item?.id) return;

        const base = isBeat ? "/me/beats" : "/me/packs";

        (async () => {
            try {
                const { data } = await api.get(`${base}/${item.id}`);
                if (data?.hasStems) {
                    const name =
                        data.stemsPath?.split("/").filter(Boolean).pop() + ".zip" ||
                        "stems.zip";
                    setStemsInfo({ hasStems: true, name });
                } else {
                    setStemsInfo({ hasStems: false, name: "" });
                }
            } catch (err) {
                console.warn("Failed to load stems info:", err);
                setStemsInfo({ hasStems: false, name: "" });
            }
        })();
    }, [open, item?.id, item?.kind]);
// remove one child
    async function removeChild(child) {
        if (!child) return;

        // optimistic UI
        const prev = children;
        const next =
            kind === "Pack"
                ? children.filter((x) => x.id !== child.id)
                : children
                    .filter((x) => x.index !== child.index)
                    .map((x, i) => ({ ...x, index: i })); // reindex kit files locally
        setChildren(next);

        try {
            if (kind === "Pack") {
                await api.delete(`/me/packs/${item.id}/beats/${child.id}`);
            } else if (kind === "Kit") {
                await api.delete(`/me/kits/${item.id}/files/${child.index}`);
            }
            // if you still want to keep your external callback:
            await onRemoveChild?.(item, child);
        } catch (e) {
            // revert on failure
            setChildren(prev);
        }
    }



    const handleRemoveChild = async (child) => {
        setChildren((prev) => prev.filter((c) => c.id !== child.id));
        try {
            await onRemoveChild?.(item, child);
        } catch {
            setChildren((prev) => {
                const next = [...prev, child];
                return next.sort((a, b) => (a.index ?? a.id) - (b.index ?? b.id));
            });
        }
    };

    if (!open || !item) return null;

    return (
        <div className="editItemModal__backdrop" onClick={onClose} role="dialog" aria-modal="true">
            <div className="editItemModal" onClick={(e) => e.stopPropagation()}>
                <div className="editItemModal__header">
                    <h3>Edit {item.kind}</h3>
                    <button className="editItemModal__close" onClick={onClose} aria-label="Close">×</button>
                </div>

                <div className="editItemModal__grid">
                    {(isPack || isBeat || isKit) && (
                        <div className="editItemModal__contents">
                            {/* ===== Licenses (Beat/Pack) ===== */}
                            {(isBeat || isPack) && (
                                <div className="editItemModal__licenses">
                                    <div className="editItemModal__sectionHead">
                                        <h4>Licenses</h4>
                                        <span className="editItemModal__count">{licenses.length}</span>
                                    </div>

                                    {licenses.length === 0 ? (
                                        <div className="editItemModal__empty">No licenses found.</div>
                                    ) : (
                                        <div className="licenseGrid">
                                            {licenses.map((lic) => {
                                                // Optional: nicer display names for your enum types
                                                const NAME_MAP = {
                                                    MP3: "MP3 Lease",
                                                    WAV: "WAV Lease",
                                                    PREMIUM: "Premium (WAV + Stems)",
                                                    EXCLUSIVE: "Exclusive Rights",
                                                };
                                                const title = NAME_MAP[lic.type?.toUpperCase?.()] || lic.type || "License";
                                                const currency = "$"; // or "€" if you prefer

                                                return (
                                                    <div key={lic.id} className={`licenseCard ${lic.enabled ? "is-on" : ""}`}>
                                                        <div className="licenseHead">
                                                            <div className="licenseTitle">
                                                                {/* if you imported the icon elsewhere, you can include it here */}
                                                                {/* <IoPricetagsOutline /> */}
                                                                <span>{title}</span>
                                                            </div>

                                                            <label className="switch">
                                                                <input
                                                                    type="checkbox"
                                                                    checked={!!lic.enabled}
                                                                    onChange={(e) => updateLicense(lic.id, { enabled: e.target.checked })}
                                                                />
                                                                <span className="lpSlider" />
                                                            </label>
                                                        </div>

                                                        {lic.enabled && (
                                                            <div className="priceRow">
                                                                <span className="currency">{currency}</span>
                                                                <input
                                                                    className="priceInput"
                                                                    type="number"
                                                                    step="0.01"
                                                                    min="0"
                                                                    placeholder="Enter price"
                                                                    value={lic.price ?? ""}
                                                                    onChange={(e) => updateLicense(lic.id, { price: e.target.value })}
                                                                    aria-label={`${title} price`}
                                                                />
                                                            </div>
                                                        )}
                                                    </div>
                                                );
                                            })}
                                        </div>
                                    )}
                                    {/* ====== Upload Stems (if required) ====== */}
                                    {(isBeat || isPack) && licenses.some(l => ["PREMIUM", "EXCLUSIVE"].includes(l.type) && l.enabled) && (
                                        <div className="editItemModal__stems">
                                            <div className="editItemModal__sectionHead">
                                                <h4>Stems (optional upload)</h4>
                                            </div>
                                            <div className="stems-upload-row">
                                                <label htmlFor="stemsUpload" className="upload-btn">
                                                    Choose File
                                                </label>
                                                <input
                                                    id="stemsUpload"
                                                    type="file"
                                                    accept=".zip"
                                                    onChange={async (e) => {
                                                        const file = e.target.files?.[0];
                                                        if (!file) return;

                                                        const formData = new FormData();
                                                        formData.append("stems", file);

                                                        const toastId = toast.loading("Uploading stems… 0%");
                                                        let processingTimer = null;
                                                        let showedProcessing = false;

                                                        try {
                                                            const path = isBeat
                                                                ? `/me/beats/${item.id}/stems`
                                                                : `/me/packs/${item.id}/stems`;

                                                            if (stemsInfo?.hasStems) {
                                                                try {
                                                                    await api.delete(path);
                                                                } catch (delErr) {
                                                                    console.warn("Failed to delete previous stems:", delErr);
                                                                }
                                                            }

                                                            await api.post(path, formData, {
                                                                headers: { "Content-Type": "multipart/form-data" },
                                                                onUploadProgress: (evt) => {
                                                                    if (!evt.total) return;
                                                                    const pct = Math.round((evt.loaded * 100) / evt.total);

                                                                    if (pct < 100) {
                                                                        toast.loading(`Uploading stems… ${pct}%`, { id: toastId });
                                                                    } else if (!showedProcessing) {
                                                                        showedProcessing = true;
                                                                        if (processingTimer) clearTimeout(processingTimer);
                                                                        processingTimer = setTimeout(() => {
                                                                            toast.loading("Processing stems on server…", { id: toastId });
                                                                        }, 400);
                                                                    }
                                                                },
                                                            });

                                                            if (processingTimer) clearTimeout(processingTimer);
                                                            toast.dismiss(toastId);
                                                            toast.success("Stems uploaded and processed!");
                                                            setStemsInfo({ hasStems: true, name: file.name });
                                                        } catch (err) {
                                                            console.error(err);
                                                            if (processingTimer) clearTimeout(processingTimer);
                                                            toast.dismiss(toastId);
                                                            toast.error("Failed to upload stems");
                                                        }
                                                    }}
                                                />

                                                {stemsInfo?.hasStems && (
                                                    <span className="stems-filename">
      {stemsInfo.name} <span className="stems-status">Uploaded</span>
    </span>
                                                )}
                                            </div>
                                            <p className="editItemModal__note">
                                                Upload a ZIP containing your stems. These will be provided to buyers with Premium or Exclusive licenses.
                                            </p>
                                        </div>
                                    )}
                                </div>
                            )}

                            {/* Contents only for Packs & Kits */}
                            {(isPack || isKit) && (
                                <>
                            <div className="editItemModal__sectionHead">
                                <h4>Contents</h4>
                                <span className="editItemModal__count">{children.length}</span>
                            </div>

                            {children.length === 0 ? (
                                <div className="editItemModal__empty">No items inside this {item.kind.toLowerCase()} yet.</div>
                            ) : (
                                <ul className="editItemModal__list">
                                    {children.map((child) => (
                                        <li key={child.id ?? child.index} className="editItemModal__row">
                                            <div className="editItemModal__rowLeft">
                                                {child.coverUrl && (
                                                    <div className="editItemModal__thumb">
                                                        <img src={child.coverUrl} alt="" />
                                                    </div>
                                                )}
                                                <div className="editItemModal__rowMeta">
                                                    <div className="editItemModal__rowTitle">{child.title}</div>
                                                    {child.subtitle && (
                                                        <div className="editItemModal__rowSub">{child.subtitle}</div>
                                                    )}
                                                </div>
                                            </div>
                                            <button
                                                className="editItemModal__remove"
                                                type="button"
                                                onClick={() => removeChild(child)}
                                                aria-label={`Remove ${child.title}`}
                                                title="Remove"
                                            >
                                                ✕
                                            </button>
                                        </li>
                                    ))}
                                </ul>
                            )}
                                </>
                            )}
                        </div>
                    )}

                    {/* LEFT – Metadata */}
                    <form className="editItemModal__form" onSubmit={handleSubmit}>
                        <label>
                            <span>Title</span>
                            <input
                                value={title}
                                onChange={(e) => setTitle(safeTitle(e.target.value))}
                                placeholder="Title"
                            />
                        </label>

                        {!isBeat && !isPack && (
                            <label>
                                <span>Price</span>
                                <input
                                    type="text"
                                    value={price}
                                    onChange={(e) =>
                                        setPrice(
                                            onlyDecimal(e.target.value, { min: 0, max: 999999, decimals: 2 })
                                        )
                                    }
                                    onBlur={(e) => {
                                        // normalize on blur
                                        const num = Number(e.target.value);
                                        setPrice(Number.isFinite(num) ? num.toFixed(2) : "");
                                    }}
                                    placeholder="0.00"
                                    inputMode="decimal"
                                />
                            </label>
                        )}

                        {isBeat && (
                            <label>
                                <span>Genre</span>
                                <input
                                    value={genre}
                                    onChange={(e) =>
                                        setGenre(onlyAlphaPhrase(e.target.value, { maxLen: 40 }))
                                    }
                                    placeholder="e.g. Drill"
                                    maxLength={40}
                                />
                            </label>
                        )}

                        {isBeat && (
                            <label>
                                <span>BPM</span>
                                <input
                                    type="text"
                                    value={bpm}
                                    onChange={(e) => setBpm(onlyInt(e.target.value, { max: 300 }))}
                                    onBlur={(e) => setBpm(clampInt(e.target.value, { min: 30, max: 300 }))}
                                    placeholder="140"
                                    inputMode="numeric"
                                    pattern="[0-9]*"
                                />
                            </label>
                        )}

                        <label>
                            <span>Tags</span>
                            <input
                                value={tags}
                                onChange={(e) => setTags(sanitizeTags(e.target.value))}
                                placeholder="comma,separated,tags"
                            />
                        </label>

                        <div className="editItemModal__actions">
                            <button className="btn btn--ghost" type="button" onClick={onClose}>Cancel</button>
                            <button className="btn btn--primary" type="button" onClick={handleSubmit}>Save</button>
                        </div>
                    </form>



                </div>
            </div>
        </div>
    );
}