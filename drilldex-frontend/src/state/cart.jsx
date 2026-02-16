// src/state/cart.jsx
import {createContext, useContext, useEffect, useMemo, useState} from "react";
import toast from "react-hot-toast";
const CartCtx = createContext(null);

const STORAGE_KEY = "cart.v1";

/* -------------------- Normalization helpers -------------------- */

function detectType(raw) {
    if (raw?.type === "subscription") return "subscription";
    if (raw?.type === "promotion") return "promotion";
    if (raw?.type) return raw.type;
    if (raw?.beatId != null || raw?.audioFilePath) return "beat";
    if (raw?.packId != null || Array.isArray(raw?.beats)) return "pack";
    if (raw?.kitId != null || Array.isArray(raw?.filePaths)) return "kit";
    // last resort: beats usually carry license info
    if (raw?.licenseType || raw?.license?.type || raw?.selectedLicense?.type) return "beat";
    return null;
}

function lineKeyFor(t, {beatId, packId, kitId, licenseType, promo, planId}) {
    if (t === "subscription") {
        return `subscription:${planId ?? "unknown"}`;
    }

    if (t === "promotion") {
        const tt = (promo?.targetType || "unknown").toUpperCase();
        const id = promo?.targetId ?? "NA";
        const tr = (promo?.tier || "standard").toLowerCase();
        const d = promo?.days ?? "NA";
        // stable key = one line per target/tier/days
        return `promo:${tt}:${id}:${tr}:${d}`;
    }

    if (t === "beat") return `beat:${beatId}:${licenseType || "NA"}`;
    if (t === "pack") return `pack:${packId}`;
    if (t === "kit") return `kit:${kitId}`;
    return `item:${Math.random().toString(36).slice(2)}`;
}

function normalizeItem(raw) {
    const merged = { ...raw, ...raw._raw };

// Detect type after merging
    const t = detectType(merged);
    if (!t) throw new Error("Cannot determine item type for cart item");

// Entity IDs
    const beatId = t === "beat" ? (merged.beatId ?? merged.id ?? merged?.beat?.id) : undefined;
    const packId = t === "pack" ? (merged.packId ?? merged.id ?? merged?.pack?.id) : undefined;
    const kitId = t === "kit" ? (merged.kitId ?? merged.id ?? merged?.kit?.id) : undefined;

// Title / image from merged
    const title = merged.title || merged.name || merged?.beat?.title || merged?.pack?.title || merged?.kit?.title || "Untitled";
    const img = merged.img || merged.cover || merged.coverImagePath || merged?.images?.[0] || merged?.coverUrl;

// License
    const license =
        merged.license ||
        merged.selectedLicense ||
        (merged.licenseType || merged.licenseId
            ? {
                type: merged.licenseType ?? merged.licenseId,
                name: merged.licenseName,
                price: Number(merged.price ?? 0),
            }
            : undefined);

    const licenseType = license?.type;


    // --- Promotion: keep targetType/targetId/tier/days (and entity ids if present) ---
    if (t === "subscription") {
        const planId = raw.planId ?? "unknown";
        const canonical = {
            id: lineKeyFor("subscription", {planId}),
            type: "subscription",
            planId,
            planName: raw.planName ?? "Unknown Plan",
            billingCycle: raw.billingCycle || "monthly",
            trialDays: raw.trialDays || 0,
            price: Number(raw.price ?? 0),
            qty: 1,
            img: raw.img || "/logo.png",
            title: raw.title || `${raw.planName ?? "Subscription"} (${raw.billingCycle ?? "monthly"})`,
            _raw: raw,
        };
        return canonical;
    }


    if (t === "promotion") {
        const targetType =
            raw.targetType || raw.promotion?.targetType || raw._raw?.targetType || null;
        const targetId =
            raw.targetId ?? raw.promotion?.targetId ?? raw._raw?.targetId ?? null;
        const tier = (raw.tier || raw.promotion?.tier || raw._raw?.tier || "standard").toLowerCase();
        const days = Number.isFinite(raw.days) ? raw.days : Number(raw.promotion?.days ?? raw._raw?.days) || 1;

        // Try infer entity ids too (handy later in Checkout)
        const lowerTT = targetType?.toLowerCase();
        const beatId = lowerTT === "beat" ? (raw.beatId ?? raw._raw?.beatId ?? targetId) : undefined;
        const packId = lowerTT === "pack" ? (raw.packId ?? raw._raw?.packId ?? targetId) : undefined;
        const kitId = lowerTT === "kit" ? (raw.kitId ?? raw._raw?.kitId ?? targetId) : undefined;

        const title = raw.title || "Promotion";
        const img = raw.img || raw.cover || raw.coverImagePath || raw.coverUrl;
        const unitPrice = Number(raw.price ?? 0);

        const canonical = {
            id: lineKeyFor("promotion", {promo: {targetType, targetId, tier, days}}),
            type: "promotion",
            title,
            img,
            price: unitPrice,
            qty: Math.max(1, Number(raw.qty ?? 1)),

            // keep both flat and nested (belt & suspenders)
            targetType: lowerTT,       // "beat" | "pack" | "kit"
            targetId,
            tier,
            days,
            promotion: {targetType: lowerTT, targetId, tier, days},

            // optional entity ids for convenience
            beatId,
            packId,
            kitId,

            _raw: raw,
        };
        return canonical;
    }

    // price: if beat, prefer license price; fall back to raw.price
    const unitPrice =
        t === "beat" ? Number(license?.price ?? raw.price ?? 0) : Number(raw.price ?? 0);

    const canonical = {
        id: lineKeyFor(t, { beatId, packId, kitId, licenseType }),
        type: t,
        beatId,
        packId,
        kitId,
        title,
        img,
        price: (t === "beat" || t === "pack")
            ? Number(license?.price ?? 0)  // always license price for beats/packs
            : Number(merged.price ?? 0),  // DB price for kits or other types
        licenseType,
        license,
        qty: Math.max(1, Number(merged.qty ?? 1)),
        _raw: merged, // keep merged _raw for debugging
    };

    return canonical;
}

/* -------------------- Provider -------------------- */

export function CartProvider({children}) {
    // 1) Load + migrate once
    const [items, setItems] = useState(() => {
        try {
            const raw = localStorage.getItem(STORAGE_KEY);
            if (!raw) return [];
            const arr = JSON.parse(raw);
            // migrate legacy shapes to canonical
            const migrated = arr
                .map((x) => {
                    try {
                        return normalizeItem(x);
                    } catch {
                        return null;
                    }
                })
                .filter(Boolean);
            return migrated;
        } catch {
            return [];
        }
    });

    // 2) Persist on change
    useEffect(() => {
        try {
            localStorage.setItem(STORAGE_KEY, JSON.stringify(items));
        } catch {
        }
    }, [items]);

    // 3) Cart ops
    const add = (rawItem) =>
        setItems((prev) => {
            console.group("[cart] add() called");
            console.log("Raw item received:", rawItem);

            let item;
            try {
                item = normalizeItem(rawItem);
            } catch (e) {
                console.warn("[cart] add() dropped unrecognizable item:", rawItem, e);
                return prev;
            }

            const hasSubscription = prev.some((i) => i.type === "subscription");
            const hasOneTime = prev.some((i) => i.type !== "subscription");
            if (item.type === "subscription" && hasOneTime) {
                toast.error("Subscriptions cannot be added together with other items. Please checkout separately.");
                return prev; // do not add
            }
            if (item.type !== "subscription" && hasSubscription) {
                toast.error("You cannot add other items while a subscription is in the cart. Please checkout separately.");
                return prev; // do not add
            }

            // Merge by line id (same beat + same license = same line)
            const idx = prev.findIndex((p) => p.id === item.id);

            if (idx !== -1) {
                // Prevent duplicates for all types
                toast.error(`You already added this ${item.type} to the cart`);
                return prev; // do not add duplicate
            }
            console.log("[cart] Item being added to state:", item);

// Item is not in the cart, add it
            return [...prev, item];
        });

    const updateQty = (lineId, qty) =>
        setItems((prev) =>
            prev.map((i) => (i.id === lineId ? {...i, qty: Math.max(1, Number(qty) || 1)} : i))
        );

    const remove = (lineId) => setItems((prev) => prev.filter((i) => i.id !== lineId));
    const clear = () => setItems([]);

    // 4) Derived
    const count = useMemo(() => items.reduce((n, i) => n + (i.qty ?? 1), 0), [items]);
    const subtotal = useMemo(
        () => items.reduce((sum, i) => sum + (Number(i.price) || 0) * (i.qty ?? 1), 0),
        [items]
    );

    const value = useMemo(
        () => ({items, add, updateQty, remove, clear, count, subtotal}),
        [items, count, subtotal]
    );

    return <CartCtx.Provider value={value}>{children}</CartCtx.Provider>;
}

export function useCart() {
    const ctx = useContext(CartCtx);
    if (!ctx) throw new Error("useCart must be used within CartProvider");
    return ctx;
}
