// src/components/LicenseModal.jsx
import {useEffect, useMemo, useState} from "react";
import api from "../lib/api";
import "./LicenseModal.css";

/**
 * Props:
 * - isOpen: boolean
 * - track: { id: number, title?: string, artist?: string }   // <- NEW (BrowsePage already passes this)
 * - onClose: () => void
 * - onSelect: (payload) => void
 *    payload = { beatId, license, action?: "addToCart" | "buyNow" }
 */
export default function LicenseModal({isOpen, track, onClose, onSelect}) {
    // catalog for UI metadata (backend sends type + price)
    const CATALOG = useMemo(
        () => ({
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
        }),
        []
    );

    const [licenses, setLicenses] = useState([]); // normalized for UI
    const [selectedId, setSelectedId] = useState(null);
    const [loading, setLoading] = useState(false);
    const [err, setErr] = useState("");

    // ESC to close
    useEffect(() => {
        if (!isOpen) return;
        const onKey = (e) => e.key === "Escape" && onClose();
        window.addEventListener("keydown", onKey);
        return () => window.removeEventListener("keydown", onKey);
    }, [isOpen, onClose]);

    // Fetch licenses when opening or when track changes
    useEffect(() => {
        if (!isOpen || !track?.id) return;
        let cancel = false;

        (async () => {
            setLoading(true);
            setErr("");
            try {
                // Backend returns: [{ id, type: "MP3"|"WAV"|..., price }]
                const isPack = String(track?.kind || "").toUpperCase() === "PACK";
                const url = isPack
                    ? `/packs/${track.id}/licenses`
                    : `/beats/${track.id}/licenses`;
                const {data} = await api.get(url);
                // Map to UI shape using the catalog
                const normalized = (data || [])
                    .map((row) => {
                        const t = String(row.type || "").toUpperCase();
                        const meta = CATALOG[t];
                        if (!meta) return null; // skip unknown types
                        return {
                            // Keep backend id around in case you need it later
                            backendId: row.id,
                            id: meta.id,
                            type: t,
                            name: meta.name,
                            files: meta.files,
                            features: meta.features,
                            price: Number(row.price ?? 0), // BigDecimal -> number
                        };
                    })
                    .filter(Boolean)
                    // Optional: preferred order
                    .sort((a, b) => {
                        const order = ["MP3", "WAV", "PREMIUM", "EXCLUSIVE"];
                        return order.indexOf(a.type) - order.indexOf(b.type);
                    });

                if (!cancel) {
                    setLicenses(normalized);
                    setSelectedId(normalized[0]?.id ?? null);
                }
            } catch (e) {
                if (!cancel) setErr("Failed to load licenses");
            } finally {
                if (!cancel) setLoading(false);
            }
        })();

        return () => {
            cancel = true;
        };
    }, [isOpen, track?.id, CATALOG]);

    if (!isOpen) return null;

    const current =
        licenses.find((l) => l.id === selectedId) || (licenses.length ? licenses[0] : null);

    return (
        <div className="licmodal">
            {/* Backdrop */}
            <div className="licmodal__overlay" onClick={onClose}/>

            {/* Panel */}
            <div
                className="licmodal__panel"
                role="dialog"
                aria-modal="true"
                aria-labelledby="licmodal-title"
                onClick={(e) => e.stopPropagation()}
            >
                {/* Header */}
                <div className="licmodal__head">
                    <h2 id="licmodal-title" className="licmodal__title">
                        Choose License{track?.title ? ` – ${track.title}` : ""}
                    </h2>
                    <button className="licmodal__close" onClick={onClose} aria-label="Close">
                        ×
                    </button>
                </div>

                {/* Loading / Error / Empty */}
                {loading && <div className="licmodal__loading">Loading licenses…</div>}
                {!loading && err && <div className="licmodal__error">{err}</div>}
                {!loading && !err && licenses.length === 0 && (
                    <div className="licmodal__empty">No licenses available for this item.</div>
                )}

                {/* Body */}
                {!loading && !err && licenses.length > 0 && (
                    <div className="licmodal__body">
                        {/* Left: license list */}
                        <div className="licmodal__left">
                            {licenses.map((l) => (
                                <button
                                    key={l.id}
                                    className={`liccard ${selectedId === l.id ? "is-active" : ""}`}
                                    onClick={() => setSelectedId(l.id)}
                                >
                                    <div className="liccard__top">
                                        <div className="liccard__name">{l.name}</div>
                                        <div className="liccard__price">${l.price.toFixed(2)}</div>
                                    </div>
                                    <div className="liccard__files">
                                        {l.files.map((f) => (
                                            <span key={f} className="chip chip--file">
                        {f}
                      </span>
                                        ))}
                                    </div>
                                </button>
                            ))}
                        </div>

                        {/* Right: usage terms for selected license */}
                        <div className="licmodal__right">
                            <div className="licmodal__sectionTitle">Usage Terms</div>

                            {current && (
                                <>
                                    <div className="licmodal__subhead">
                                        {current.name}{" "}
                                        <span className="licmodal__subprice">(${current.price.toFixed(2)})</span>
                                    </div>

                                    <ul className="liclist">
                                        {current.features.map((f, i) => (
                                            <li key={i} className="liclist__item">
                                                <CheckIcon/>
                                                <span>{f}</span>
                                            </li>
                                        ))}
                                    </ul>

                                    <div className="licmodal__receives">
                                        You’ll receive:
                                        <div className="licmodal__files">
                                            {current.files.map((f) => (
                                                <span key={f} className="chip chip--file">
                          {f}
                        </span>
                                            ))}
                                        </div>
                                    </div>
                                </>
                            )}
                        </div>
                    </div>
                )}

                {/* Footer */}
                {!loading && !err && current && (
                    <div className="licmodal__foot">
                        <div className="licmodal__total">
                            Total <strong>${current.price.toFixed(2)}</strong>
                        </div>
                        <div className="licmodal__actions">
                            <button
                                className="btn btn--ghost"
                                onClick={() => {
                                    const kind = String(track?.kind || "BEAT").toUpperCase();

                                    if (kind === "BEAT") {
                                        // Beat behavior stays the same
                                        onSelect?.({
                                            beatId: track?.id,
                                            license: current,
                                            licenseType: current?.type,
                                            action: "addToCart",
                                        });
                                    } else if (kind === "PACK") {
                                        // Pack needs packId + license info
                                        onSelect?.({
                                            packId: track?.id,
                                            license: current,
                                            licenseType: current?.type,
                                            action: "addToCart",
                                        });
                                    }

                                    onClose();
                                }}
                            >
                                Add to Cart
                            </button>
                            <button
                                className="btn btn--primary"
                                onClick={() => {
                                    const kind = String(track?.kind || "BEAT").toUpperCase();

                                    if (kind === "BEAT") {
                                        onSelect?.({
                                            beatId: track?.id,
                                            license: current,
                                            licenseType: current?.type,
                                            action: "buyNow",
                                        });
                                    } else if (kind === "PACK") {
                                        onSelect?.({
                                            packId: track?.id,
                                            license: current,
                                            licenseType: current?.type,
                                            action: "buyNow",
                                        });
                                    }

                                    onClose();
                                }}
                            >
                                Buy Now
                            </button>
                        </div>
                    </div>
                )}
            </div>
        </div>
    );
}

function CheckIcon() {
    return (
        <svg
            className="licicon"
            viewBox="0 0 24 24"
            width="18"
            height="18"
            fill="none"
            stroke="currentColor"
            strokeWidth="2.2"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
        >
            <path d="M20 6L9 17l-5-5"/>
        </svg>
    );
}