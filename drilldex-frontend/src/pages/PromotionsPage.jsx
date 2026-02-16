import { useEffect, useMemo, useState } from "react";
import "./promotions.css";
import api from "../lib/api";
import { toast } from "react-hot-toast";
import { useCart } from "../state/cart";

const CDN_BASE = import.meta.env.VITE_S3_PUBLIC_BASE?.replace(/\/+$/, "") || "";
const toFileUrl = (p) => {
    if (!p) return "";
    if (/^https?:\/\//i.test(p)) return p;
    if (p.startsWith("/uploads/https://") || p.startsWith("/uploads/http://")) {
        return p.replace(/^\/uploads\//, "");
    }
    if (p.startsWith("/uploads/")) return p;
    const clean = p.replace(/^\/+/, "");
    return CDN_BASE ? `${CDN_BASE}/${clean}` : `/uploads/${clean}`;
};

// --- license → min price helpers ---
function minPriceFromLicenses(licenses = []) {
    const nums = licenses
        .filter(l => l && (l.enabled ?? true))
        .map(l => Number(l.price || 0))
        .filter(n => Number.isFinite(n) && n >= 0);
    return nums.length ? Math.min(...nums) : null;
}

const priceLoaded = new Set();

async function fetchMinPrice(kind, id) {
    // kind: "beats" | "packs"
    if (!id) return null;
    const key = `${kind}:${id}`;
    if (priceLoaded.has(key)) return null;
    priceLoaded.add(key);
    try {
        const { data } = await api.get(`/${kind}/${id}/licenses`);
        return minPriceFromLicenses(Array.isArray(data) ? data : []) ?? null;
    } catch {
        return null;
    }
}

function getPlanDiscountMultiplier(plan) {
    if (!plan) return 1.0;
    switch(plan.toLowerCase()) {
        case "growth": return 0.9; // 10% off
        case "pro": return 0.8;    // 20% off
        default: return 1.0;       // Free or unknown
    }
}

function getPlanDiscountLabel(plan) {
    switch(plan?.toLowerCase()) {
        case "growth": return "10% off";
        case "pro": return "20% off";
        default: return null;
    }
}

async function hydratePrices(kind, list, patchFn) {
    // list: array of items in state (beats/packs)
    const targets = (list || []).filter(x => !priceLoaded.has(`${kind}:${x.id}`));
    if (!targets.length) return;

    const results = await Promise.allSettled(
        targets.map(x => fetchMinPrice(kind, x.id))
    );

    results.forEach((res, idx) => {
        const row = targets[idx];
        if (res.status === "fulfilled" && Number.isFinite(res.value)) {
            patchFn(row.id, { price: res.value, priceKind: "from" });
        }
    });
}


/** Pricing in USD per item per day */
const PRICING = {
    standard: {
        label: "Standard",
        rate: 1.5,
        desc: [
            "Featured at the top of Drilldex pages",
            "Good visibility for your beats, packs, or kits"
        ]
    },
    premium:  {
        label: "Premium",
        rate: 3.0,
        desc: [
            "Everything in Standard",
            "Shared on Drilldex social media channels (Instagram, Twitter/X, TikTok, etc.)",
            "If unsold, you'll get a rerun and $10 promo credits"
        ]
    },
    spotlight:{
        label: "Spotlight",
        rate: 6.0,
        desc: [
            "Everything in Premium",
            "Included in Drilldex marketing campaigns (newsletters, social media, email campaigns, etc.)",
            "If unsold, you'll get a rerun and $25 promo credits",
            "Maximum exposure inside and outside Drilldex"
        ]
    },
};
const DURATIONS = [3, 7, 14, 30];

const GUARANTEED_IMPRESSIONS_PER_DOLLAR = {
    standard: 83,    // ~$12 CPM @ $1.50/day → ~125/day
    premium: 100,    // ~$10 CPM @ $3.00/day → 300/day
    spotlight: 125,  // ~$8 CPM  @ $6.00/day → 750/day
};

const TIER_MULT = { standard: 1.0, premium: 1.6, spotlight: 2.2 };

function useUserInventory() {
    const [beats, setBeats] = useState([]);
    const [packs, setPacks] = useState([]);
    const [kits,  setKits]  = useState([]);
    const [loading, setLoading] = useState(true);
    const [promoCredits, setPromoCredits] = useState(0); // new
    const [referralCredits, setReferralCredits] = useState(0);

    const [pages, setPages] = useState({ beats: 0, packs: 0, kits: 0 });
    const [totalPages, setTotalPages] = useState({ beats: 1, packs: 1, kits: 1 });



    const LIMITS = { beats: 50, packs: 50, kits: 50 };

    const fetchPage = async (tab, page) => {
        try {
            const { data } = await api.get(`/me/${tab}?page=${page}&limit=${LIMITS[tab]}`);
            const itemsRaw = Array.isArray(data.items) ? data.items : [];

            const mapCard = (x) => ({
                id: x.id,
                title: x.title || x.name,
                cover: toFileUrl(x.coverUrl || x.albumCoverUrl || x.coverImagePath),
                price: Number(x.price || 0),
                priceKind: "fixed",
            });

            const setter = { beats: setBeats, packs: setPacks, kits: setKits }[tab];
            setter(itemsRaw.map(mapCard));

            // hydrate prices for beats/packs only
            if (tab !== "kits") {
                await hydratePrices(tab, itemsRaw, (id, patch) =>
                    setter(prev => prev.map(it => it.id === id ? { ...it, ...patch } : it))
                );
            }

            setTotalPages(prev => ({
                ...prev,
                [tab]: Math.ceil((data.totalItems ?? itemsRaw.length) / LIMITS[tab])
            }));
        } catch (err) {
            console.error(`Failed to fetch ${tab} page ${page}`, err);
            toast.error(`Couldn’t load ${tab} page.`);
        }
    };

    const goToPage = (tab, newPage) => {
        if (newPage < 0 || newPage >= totalPages[tab]) return;
        setPages(prev => ({ ...prev, [tab]: newPage }));
        fetchPage(tab, newPage);
    };

    useEffect(() => {
        let alive = true;
        setLoading(true);

        (async () => {
            await Promise.all([
                fetchPage("beats", 0),
                fetchPage("packs", 0),
                fetchPage("kits", 0),
            ]);


            if (!alive) return;

            // fetch promo credits
            try {
                const { data } = await api.get("/me"); // or /me/promoCredits if you have dedicated endpoint
                setPromoCredits(data.promoCredits ?? 0);
                setReferralCredits(data.referralCredits ?? 0);
            } catch (err) {
                console.error("Failed to fetch promo credits", err);
            }

            alive && setLoading(false);
        })();



        return () => { alive = false; };
    }, []);

    return { beats, packs, kits, loading, pages, totalPages, goToPage, promoCredits, referralCredits };
}

export default function PromotionsPage() {
    const { beats, packs, kits, loading, pages, totalPages, goToPage, promoCredits, referralCredits } = useUserInventory();
    const {add, clear} = useCart();
    const [enable, setEnable] = useState({ beats: true, packs: false, kits: false });
    const [selected, setSelected] = useState({ beats: new Set(), packs: new Set(), kits: new Set() });
    const [tier, setTier] = useState("premium");
    const [days, setDays] = useState(14);
    const [submitting, setSubmitting] = useState(false);
    const [subscription, setSubscription] = useState(null);


    useEffect(() => {
        let alive = true;

        (async () => {
            try {
                const { data } = await api.get("/me");
                if (!alive) return;

                setSubscription(data.subscription ?? null);
            } catch (err) {
                console.error("Failed to fetch /me for subscription", err);
            }
        })();

        return () => { alive = false; };
    }, []);

    const toggleEnable = (key) =>
        setEnable((s) => {
            const next = { ...s, [key]: !s[key] };
            if (!next[key]) {
                setSelected((sel) => ({ ...sel, [key]: new Set() }));
            }
            return next;
        });
    const togglePick = (group, id) =>
        setSelected((s) => {
            const next = new Set(s[group]);
            if (next.has(id)) next.delete(id);
            else next.add(id);
            return { ...s, [group]: next };
        });

    // Pricing & simple reach estimate
    const { totalItems, totalPrice, reach, discountLabel, finalPrice, usedPromoCredits, discountedItemPrices } = useMemo(() => {
        const count =
            (enable.beats ? selected.beats.size : 0) +
            (enable.packs ? selected.packs.size : 0) +
            (enable.kits ? selected.kits.size : 0);

        if (count === 0) {
            return {
                totalItems: 0,
                totalPrice: 0,
                reach: 0,
                discountLabel: null,
                finalPrice: 0,
                usedPromoCredits: { promo: 0, referral: 0 },
                discountedItemPrices: [],
            };
        }

        const rawItemPrice = PRICING[tier].rate * days;
        const subscriptionMultiplier = getPlanDiscountMultiplier(subscription?.planName);

        let remainingPromo = promoCredits ?? 0;
        let remainingReferral = referralCredits ?? 0;

        const discountedItemPrices = [];

        for (let i = 0; i < count; i++) {
            let price = rawItemPrice;

            const promoApplied = Math.min(price, remainingPromo);
            price -= promoApplied;
            remainingPromo -= promoApplied;

            const referralApplied = Math.min(price, remainingReferral);
            price -= referralApplied;
            remainingReferral -= referralApplied;

            // Apply subscription multiplier
            price = +(price * subscriptionMultiplier).toFixed(2);

            discountedItemPrices.push(price);
        }

        const totalRawPrice = discountedItemPrices.reduce((a, b) => a + b, 0);
        const perDollar = GUARANTEED_IMPRESSIONS_PER_DOLLAR[tier] ?? 200;
        const guaranteed = Math.round(perDollar * totalRawPrice);
        const discountLabel = getPlanDiscountLabel(subscription?.planName);

        return {
            totalItems: count,
            totalPrice: +(rawItemPrice * count).toFixed(2),
            reach: guaranteed,
            discountLabel,
            finalPrice: totalRawPrice,
            usedPromoCredits: {
                promo: (promoCredits ?? 0) - remainingPromo,
                referral: (referralCredits ?? 0) - remainingReferral
            },
            discountedItemPrices,
        };
    }, [selected, enable, days, tier, subscription, promoCredits, referralCredits]);

// ---- helpers (put these above startPromotion) ----
    async function featureOne(basePath, id, payload) {
        // ordered attempts per basePath
        const tries = {
            "/beats": [
                `${basePath}/${id}/feature/start`,
                `${basePath}/${id}/feature`,
            ],
            "/packs": [
                `${basePath}/${id}/feature`,
                `${basePath}/${id}/feature/start`,
            ],
            "/kits": [
                `${basePath}/${id}/feature/start`,
                `${basePath}/${id}/feature`,
            ],
        }[basePath] ?? [
            `${basePath}/${id}/feature/start`,
            `${basePath}/${id}/feature`,
        ];

        let lastErr;

        for (const url of tries) {
            try {
                return await api.post(url, payload);
            } catch (e) {
                const s = e?.response?.status;
                if (s === 401) { toast.error("You need to sign in."); throw e; }
                if (s === 403) { toast.error("You don’t have permission to feature this item."); throw e; }
                if (s !== 404) { throw e; } // only keep looping on 404 (endpoint missing)
                lastErr = e; // try next endpoint
            }
        }

        // All attempts 404'd — tailor the hint
        if (basePath === "/packs") {
            toast.error("Missing packs feature endpoint. Expected POST /api/packs/{id}/feature.");
        } else if (basePath === "/kits") {
            toast.error("Missing kits feature endpoint. Add POST /api/kits/{id}/feature or /feature/start.");
        } else {
            toast.error("Feature endpoint not found.");
        }
        throw lastErr;
    }


// ---- replace your existing startPromotion with this ----
    async function startPromotion() {
        const wantBeats = enable.beats && selected.beats.size > 0;
        const wantPacks = enable.packs && selected.packs.size > 0;
        const wantKits  = enable.kits && selected.kits.size > 0;

        console.log("=== startPromotion called ===");
        console.log("Selected tabs:", { wantBeats, wantPacks, wantKits });
        console.log("Selected sets:", selected);

        if (!wantBeats && !wantPacks && !wantKits) {
            toast("Pick at least one item to promote.");
            return;
        }

        // Flatten selected items in the same order as discountedItemPrices
        const allSelected = [
            ...beats.filter(b => selected.beats.has(b.id)),
            ...packs.filter(p => selected.packs.has(p.id)),
            ...kits.filter(k => selected.kits.has(k.id)),
        ];

        console.log("Flattened allSelected items (before credits):", allSelected);
        console.log("discountedItemPrices from useMemo:", discountedItemPrices);

        if (!allSelected.length) {
            toast("No items selected.");
            return;
        }


        const lines = allSelected.map((it, idx) => {
            const price = Number(discountedItemPrices[idx] ?? 0);

            const line = {
                id: `promo-${it.type}-${it.id}-${tier}-${days}`,
                type: "promotion",
                title: `${it.title} — ${PRICING[tier].label} × ${days}d`,
                img: it.cover,
                price,                  // ✅ already final
                qty: 1,
                tier,
                days,
                targetType: it.type,
                targetId: it.id,
                creditsUsed: { promo: 0, referral: 0 }, // already applied
                _raw: { ...it, price },
                ...(it.type === "beat" && { beatId: it.id }),
                ...(it.type === "pack" && { packId: it.id }),
                ...(it.type === "kit"  && { kitId: it.id }),
            };

            console.log(`Line ${idx} to add to cart:`, line);
            return line;
        });

        console.log("Final cart lines to add:", lines);

        lines.forEach(add);
        // window.location.href = "/checkout";
    }

    return (
        <div className="promos">
            <header className="promos__hero">
                <h1>Promotions</h1>
                <p className="promos__sub">Boost your visibility with Featured placement.</p>

                <div className="promos__toggles" role="group" aria-label="What to promote">
                    {[
                        { key: "beats", label: "Beats" },
                        { key: "packs", label: "Packs" },
                        { key: "kits",  label: "Kits"  },
                    ].map(({ key, label }) => (
                        <button
                            key={key}
                            className={`promos__toggle ${enable[key] ? "is-active" : ""}`}
                            onClick={() => toggleEnable(key)}
                        >
                            {label}
                        </button>
                    ))}
                </div>
            </header>

            <main className="promos__main">
                <section className="promos__left">
                    {loading ? (
                        <div className="promos__loading">Loading your items…</div>
                    ) : (
                        <>
                            {enable.beats && (
                                <>
                                    <ItemPicker
                                        title="Select beats"
                                        items={beats}
                                        selected={selected.beats}
                                        onToggle={(id) => togglePick("beats", id)}
                                    />
                                    {totalPages.beats > 1 && (
                                        <div className="tp-pagination-bar">
                                            <button
                                                onClick={() => goToPage("beats", pages.beats - 1)}
                                                disabled={pages.beats === 0}
                                                className="tp-pagebtn tp-backbtn"
                                            >
                                                ←
                                            </button>
                                            <div className="tp-pagination-scroll">
                                                {Array.from({ length: totalPages.beats }, (_, i) => (
                                                    <button
                                                        key={i}
                                                        className={`tp-pagebtn ${pages.beats === i ? "is-active" : ""}`}
                                                        onClick={() => goToPage("beats", i)}
                                                    >
                                                        {i + 1}
                                                    </button>
                                                ))}
                                            </div>
                                            <button
                                                onClick={() => goToPage("beats", pages.beats + 1)}
                                                disabled={pages.beats + 1 >= totalPages.beats}
                                                className="tp-pagebtn tp-nextbtn"
                                            >
                                                →
                                            </button>
                                        </div>
                                    )}
                                </>
                            )}

                            {enable.packs && (
                                <>
                                    <ItemPicker
                                        title="Select packs"
                                        items={packs}
                                        selected={selected.packs}
                                        onToggle={(id) => togglePick("packs", id)}
                                    />
                                    {totalPages.packs > 1 && (
                                        <div className="tp-pagination-bar">
                                            <button
                                                onClick={() => goToPage("packs", pages.packs - 1)}
                                                disabled={pages.packs === 0}
                                                className="tp-pagebtn tp-backbtn"
                                            >
                                                ←
                                            </button>
                                            <div className="tp-pagination-scroll">
                                                {Array.from({ length: totalPages.packs }, (_, i) => (
                                                    <button
                                                        key={i}
                                                        className={`tp-pagebtn ${pages.packs === i ? "is-active" : ""}`}
                                                        onClick={() => goToPage("packs", i)}
                                                    >
                                                        {i + 1}
                                                    </button>
                                                ))}
                                            </div>
                                            <button
                                                onClick={() => goToPage("packs", pages.packs + 1)}
                                                disabled={pages.packs + 1 >= totalPages.packs}
                                                className="tp-pagebtn tp-nextbtn"
                                            >
                                                →
                                            </button>
                                        </div>
                                    )}
                                </>
                            )}

                            {enable.kits && (
                                <>
                                    <ItemPicker
                                        title="Select kits"
                                        items={kits}
                                        selected={selected.kits}
                                        onToggle={(id) => togglePick("kits", id)}
                                    />
                                    {totalPages.kits > 1 && (
                                        <div className="tp-pagination-bar">
                                            <button
                                                onClick={() => goToPage("kits", pages.kits - 1)}
                                                disabled={pages.kits === 0}
                                                className="tp-pagebtn tp-backbtn"
                                            >
                                                ←
                                            </button>
                                            <div className="tp-pagination-scroll">
                                                {Array.from({ length: totalPages.kits }, (_, i) => (
                                                    <button
                                                        key={i}
                                                        className={`tp-pagebtn ${pages.kits === i ? "is-active" : ""}`}
                                                        onClick={() => goToPage("kits", i)}
                                                    >
                                                        {i + 1}
                                                    </button>
                                                ))}
                                            </div>
                                            <button
                                                onClick={() => goToPage("kits", pages.kits + 1)}
                                                disabled={pages.kits + 1 >= totalPages.kits}
                                                className="tp-pagebtn tp-nextbtn"
                                            >
                                                →
                                            </button>
                                        </div>
                                    )}
                                </>
                            )}

                            {!enable.beats && !enable.packs && !enable.kits && (
                                <div className="promos__empty">
                                    <h3>What do you want to promote?</h3>
                                    <p>Use the toggles above to choose Beats, Packs, and/or Kits.</p>
                                </div>
                            )}
                        </>
                    )}
                </section>

                <aside className="promos__right">
                    <div className="card">
                        <h3 className="card__title">Promotion details</h3>

                        <div className="row">
                            <div className="label">Duration</div>
                            <div className="chips">
                                {DURATIONS.map((d) => (
                                    <button
                                        key={d}
                                        className={`chip ${days === d ? "is-active" : ""}`}
                                        onClick={() => setDays(d)}
                                    >
                                        {d} days
                                    </button>
                                ))}
                            </div>
                        </div>

                        <div className="row">
                            <div className="label">Placement tier</div>
                            <div className="tiers">
                                {Object.entries(PRICING).map(([key, v]) => (
                                    <button
                                        key={key}
                                        onClick={() => setTier(key)}
                                        className={`tier ${tier === key ? "is-active" : ""}`}
                                    >
                                        <div className="tier__name">{v.label}</div>
                                        <ul className="tier__desc">
                                            {Array.isArray(v.desc) && v.desc.map((line, i) => (
                                                <li key={i}>{line}</li>
                                            ))}
                                        </ul>
                                        <div className="tier__rate">${v.rate.toFixed(2)}/item/day</div>
                                    </button>
                                ))}
                            </div>
                        </div>
                    </div>

                    <div className="card">
                        <h3 className="card__title">Summary</h3>
                        <div className="sumrow">
                            <span>Items selected</span>
                            <strong>{totalItems}</strong>
                        </div>
                        <div className="sumrow">
                            <span>Duration</span>
                            <strong>{days} days</strong>
                        </div>
                        <div className="sumrow">
                            <span>Tier</span>
                            <strong>{PRICING[tier].label}</strong>
                        </div>
                        <div className="sumrow">
                            <span>Guaranteed impressions</span>
                            <strong>{reach.toLocaleString()}</strong>
                        </div>

                        <div className="sumrow">
                            <span>Promo credits used</span>
                            <strong>${usedPromoCredits.promo.toFixed(2)}</strong>
                        </div>
                        <div className="sumrow">
                            <span>Referral credits used</span>
                            <strong>${usedPromoCredits.referral.toFixed(2)}</strong>
                        </div>
                        <div className="sumrow sumrow--total">
                            <span>Total after credits</span>
                            <strong>
                                ${finalPrice.toFixed(2)}
                                {discountLabel && <span className="promos__discount"> ({discountLabel})</span>}
                            </strong>
                        </div>

                        <button
                            className="promos__cta"
                            disabled={submitting || totalItems === 0}
                            onClick={startPromotion}
                        >
                            {submitting ? "Starting…" : "Start promotion"}
                        </button>
                    </div>
                </aside>
            </main>
        </div>
    );
}

/* ---------- subcomponents ---------- */

function ItemPicker({ title, items = [], selected, onToggle }) {
    return (
        <div className="picker">
            <h3 className="picker__title">{title}</h3>
            {items.length === 0 ? (
                <div className="picker__empty">
                    No items yet. <a href="/upload">Upload the first one</a>.
                </div>
            ) : (
                <ul className="picker__grid">
                    {items.map((it) => {
                        const isOn = selected.has(it.id);
                        return (
                            <li key={it.id} className={`pick ${isOn ? "is-selected" : ""}`}>
                                <button
                                    className="pick__btn"
                                    type="button"
                                    onClick={() => onToggle(it.id)}
                                    aria-pressed={isOn}
                                >
                                    <div className="pick__imgWrap">
                                        {it.cover ? <img src={it.cover} alt={it.title} /> : <div className="pick__imgPh" />}
                                        <span className="pick__check" aria-hidden>✓</span>
                                    </div>
                                    <div className="pick__meta">
                                        <div className="pick__title" title={it.title}>{it.title}</div>
                                        {isFinite(it.price) && <div className="pick__price">${Number(it.price || 0).toFixed(2)}</div>}
                                    </div>
                                </button>
                            </li>
                        );
                    })}
                </ul>
            )}
        </div>
    );
}