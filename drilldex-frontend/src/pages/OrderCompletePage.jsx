import React, {useEffect, useMemo, useState} from "react";
import {Link, useSearchParams} from "react-router-dom";

import api from "../lib/api";
import toast from "react-hot-toast";
import "./OrderCompletePage.css";

const fmtUSD = (n) =>
    new Intl.NumberFormat("en-US", {style: "currency", currency: "USD"}).format(n ?? 0);

function normalizeApiPath(u) {
    if (!u) return u;
    if (/^https?:\/\//i.test(u)) return u;
    u = u.replace(/^\//, "");
    if (u.startsWith("api/")) u = u.slice(4);
    return u;
}

async function downloadViaApi(url, fallbackName) {
    const toastId = toast.loading("Downloading…");
    try {
        const resp = await api.get(url, {responseType: "blob"});
        const type = resp.headers["content-type"] || "application/octet-stream";
        const blob = new Blob([resp.data], {type});
        const href = URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = href;
        a.download = fallbackName || "download";
        document.body.appendChild(a);
        a.click();
        a.remove();
        URL.revokeObjectURL(href);
    } catch (e) {
        console.error("Download failed:", e);
        toast.error("Sorry, the download failed. Please try again.");
    } finally {
        toast.dismiss(toastId);
    }
}

export default function OrderCompletePage() {
    const [searchParams] = useSearchParams();
    const orderId = searchParams.get("orderId");
    const [order, setOrder] = useState(null);
    const isLoggedIn = !!localStorage.getItem("auth");

    // Always run the effect unconditionally
    useEffect(() => {
        if (!orderId) return;
        api.get(`/purchases/order/${orderId}`)
            .then(res => setOrder(res.data))
            .catch(err => {
                console.error("Failed to fetch order:", err);
                toast.error("Failed to load order details.");
            });
    }, [orderId]);

    // Always call useMemo unconditionally
    const downloads = useMemo(() => {
        if (!order?.purchases) return [];
        return order.purchases.map(p => ({
            ok: true,
            type: p.type,
            title: p.title,
            thumb: p.img || "/placeholder-cover.jpg",
            purchaseId: p.purchaseId,
            licenseHref: normalizeApiPath(p.licenseUrl || `purchases/${p.purchaseId}/license`),
            zipHref: normalizeApiPath(
                p.type === "pack"
                    ? `purchases/packs/${p.purchaseId}/download`
                    : p.type === "kit"
                        ? `purchases/kits/${p.purchaseId}/download`
                        : null
            ),
            audioHref: p.type === "beat" ? normalizeApiPath(`purchases/beats/${p.purchaseId}/download`) : null,
            pricePaid: p.pricePaid || p.price,
            error: null
        }));
    }, [order]);

    if (!order) {
        return (
            <div className="ordercomplete ordercomplete--empty">
                <div className="oc-card">
                    <div className="oc-success">
                        <div className="oc-success__icon">!</div>
                        <h1 className="oc-success__title">No order found</h1>
                    </div>
                    <p className="oc-orderid">We couldn’t find your order details.</p>
                    <div className="oc-divider"/>
                    <div className="oc-dlrow">
                        <Link to="/checkout" className="oc-btn oc-btn--primary">Back to Checkout</Link>
                        <Link to="/" className="oc-btn">Home</Link>
                    </div>
                </div>
            </div>
        );
    }

    const subtotal = downloads
        .map(d => d.pricePaid ?? 0)
        .reduce((sum, p) => sum + p, 0);
    const tax = 0; // or compute tax if you want
    const total = subtotal + tax;

    return (
        <div className="ordercomplete">
            <div className="oc__inner">
                <aside className="oc-card">
                    <div className="oc-success">
                        <div className="oc-success__icon">✓</div>
                        <h1 className="oc-success__title">Thank you for your purchase!</h1>
                    </div>
                    <p className="oc-orderid"><strong>Order ID:</strong> {order.orderId}</p>
                    <p className="oc-orderid">
                        Your order has been processed. You can now download and view your items.
                    </p>

                    <div className="oc-divider"/>

                    <div className="oc-totals">
                        <div className="oc-totals__label">Subtotal</div>
                        <div className="oc-totals__value">{fmtUSD(subtotal)}</div>
                        <div className="oc-totals__label">Tax</div>
                        <div className="oc-totals__value">{fmtUSD(tax)}</div>
                    </div>
                    <div className="oc-totals__grand">
                        <div className="label">Total</div>
                        <div className="value">{fmtUSD(total)}</div>
                    </div>

                    <div className="oc-divider"/>

                    <div className="oc-downloads">
                        <div className="oc-dlrow">
                            <Link to="/" className="oc-chip">Back to Home</Link>
                            {isLoggedIn && (
                                <Link to="/library" className="oc-chip oc-chip--primary">Go to My Library</Link>
                            )}
                        </div>
                    </div>
                </aside>

                <section className="oc-card oc-card--right">
                    <div className="oc-righthead">
                        <h2>Your items</h2>
                    </div>

                    <div className="oc-purchases">
                        {downloads.map((r, i) => (
                            <div key={i} className="oc-item" aria-live="polite">
                                <div className="oc-item__cover">
                                    <img src={r.thumb} alt=""/>
                                </div>
                                <div className="oc-item__meta">
                                    <div className="oc-item__title">
                                        <strong>{r.title}</strong>
                                        <span className="oc-badge">{r.type}</span>
                                    </div>
                                    <div className="oc-item__sub">Purchase ID: {r.purchaseId}</div>
                                </div>
                                <div className="oc-item__actions">
                                    {r.licenseHref && !["kit", "subscription", "promotion"].includes(r.type) && (
                                        <button
                                            className="oc-btn oc-btn--primary"
                                            onClick={() =>
                                                downloadViaApi(r.licenseHref, `license-${r.purchaseId}.pdf`)
                                            }
                                        >
                                            License PDF
                                        </button>
                                    )}
                                    {r.audioHref &&
                                        <button className="oc-btn" onClick={() => downloadViaApi(r.audioHref)}>Download
                                            Audio</button>}
                                    {r.zipHref && <button className="oc-btn"
                                                          onClick={() => downloadViaApi(r.zipHref, `${r.type}-${r.purchaseId}.zip`)}>Download
                                        ZIP</button>}
                                </div>
                            </div>
                        ))}
                    </div>
                </section>
            </div>

            <div className="oc-back">
                <Link to="/">← Back</Link>
            </div>
        </div>
    );
}