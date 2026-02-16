import { useEffect, useRef } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import api from "../lib/api";
import { useCart } from "../state/cart";
import toast from "react-hot-toast";
import "./paymentconfirm.css";

export default function PaymentConfirm() {
    const [searchParams] = useSearchParams();
    const navigate = useNavigate();
    const { items, clear } = useCart();
    const effectRan = useRef(false);

    useEffect(() => {
        if (effectRan.current) return;
        effectRan.current = true;

        const provider = "test";
        const orderId = null;
        const sessionId = searchParams.get("sessionId") || searchParams.get("token");

        const toNumberId = (val) => {
            if (val == null) return undefined;
            if (typeof val === "number" && !Number.isNaN(val)) return val;
            const m = String(val).match(/\d+/);
            return m ? Number(m[0]) : undefined;
        };

        const resolveLicenseType = (item) => {
            const raw =
                item?.licenseType ??
                item?.license?.type ??
                item?.licenseId ??
                item?.license?.id;
            return raw ? String(raw).toUpperCase() : undefined;
        };

        async function confirmPayment() {
            console.log("[PaymentConfirm] Confirming payment...", { sessionId, provider });

            const normalizedItems = items.map((i) => ({
                type: i.type,
                beatId: toNumberId(i.beatId ?? i._raw?.beatId ?? i._raw?.id ?? i.id),
                packId: toNumberId(i.packId ?? i._raw?.packId ?? i._raw?.id ?? i.id),
                kitId: toNumberId(i.kitId ?? i._raw?.kitId ?? i._raw?.id ?? i.id),
                licenseType: resolveLicenseType(i),
                title: i.title,
                artist: i.artist,
                price: i.price,
                qty: i.qty || 1,
                subscriptionId: undefined,
                billingCycle: i.type === "subscription" ? i.billingCycle : undefined,
                planId: i.type === "subscription" ? i.planId : undefined,
                tier: i.type === "promotion" ? i.tier : undefined,
                days: i.type === "promotion" ? i.days : undefined,
            }));

            console.log("[PaymentConfirm] Sending normalized items to backend:", normalizedItems);
            console.log("[PaymentConfirm] provider:", provider, "sessionId:", sessionId, "orderId:", orderId);

            // No artificial delay for local test payments

            try {
                const { data } = await api.post("/checkout/confirm", {
                    provider,
                    sessionId,   // still needed for one-time purchases
                    orderId,
                    items: normalizedItems,
                });

                clear();
                toast.success("Purchase completed!");
                navigate(`/order/complete?orderId=${data.orderId}`);
            } catch (err) {
                const serverMsg =
                    err?.response?.data?.message ||
                    err?.response?.data?.error ||
                    (typeof err?.response?.data === "string" ? err.response.data : null);
                console.error("[PaymentConfirm] Payment confirmation failed:", err);
                toast.error(serverMsg || "Payment could not be confirmed");
                navigate("/checkout");
            }
        }

        confirmPayment();
    }, [searchParams, navigate, clear]);

    return (
        <div className="ykjwbsy12">
            Confirming payment… please wait.
        </div>
    );
}
