// src/lib/pricing.js
import api from "../lib/api";

export function minPriceFromLicenses(licenses = []) {
    const nums = licenses
        .filter(l => l && (l.enabled ?? true))
        .map(l => Number(l.price || 0))
        .filter(n => Number.isFinite(n) && n >= 0);
    return nums.length ? Math.min(...nums) : null;
}

/** If licenses are already inline on the entity, return the min; otherwise null. */
export function derivePriceIfPossible(row) {
    return Array.isArray(row?.licenses) && row.licenses.length
        ? minPriceFromLicenses(row.licenses)
        : null;
}

/** Fetch licenses for one item (beats|packs|kits) and return the min price, or null. */
export async function fetchMinPrice(kindPlural, id) {
    try {
        const { data } = await api.get(`/${kindPlural}/${id}/licenses`);
        const p = minPriceFromLicenses(Array.isArray(data) ? data : []);
        return Number.isFinite(p) ? p : null;
    } catch {
        return null;
    }
}

/**
 * Hydrate a list’s prices by fetching licenses for items that don’t have a price yet.
 * - kindPlural: "beats" | "packs" | "kits"
 * - list: array of rows with { id, price? }
 * - patch: (id, patchObj) => void
 */
export async function hydratePrices(kindPlural, list, patch) {
    const targets = (list || []).filter(x => x && x.id != null && (x.price == null));
    if (!targets.length) return;

    const results = await Promise.allSettled(
        targets.map(x => fetchMinPrice(kindPlural, x.id))
    );

    results.forEach((res, i) => {
        const row = targets[i];
        if (res.status === "fulfilled" && res.value != null) {
            patch(row.id, { price: res.value });
        }
    });
}