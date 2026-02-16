// src/lib/playerQueue.js
import api from "../lib/api";

/**
 * Your audio bar already listens for: "audio:play-list"
 * detail: { list: Track[], index: number, sourceKey?: string }
 *
 * This helper builds hydrated queues (with preview URLs) and dispatches that event.
 */

const previewCache = new Map();
const URL_TTL_MS = 2 * 60 * 1000; // 2 minutes

function getCachedUrl(beatId) {
          const hit = previewCache.get(beatId);
          if (!hit) return null;
          if (Date.now() - hit.ts > URL_TTL_MS) {
                previewCache.delete(beatId);
                return null;
              }
          return hit.url;
        }


function fixPresignedUrlEncoding(url) {
    if (typeof url !== "string") return "";
    let out = url;
    // Unwind “%252F” -> “%2F” (at most twice, to be safe).
    for (let i = 0; i < 2; i++) {
        if (/%252F/i.test(out)) out = decodeURIComponent(out);
        else break;
    }
    return out;
}

// use your existing GET /beats/:id/preview-url
    async function getPreviewUrl(beatId, { force = false } = {}) {
          if (!force) {
                const cached = getCachedUrl(beatId);
                if (cached) return cached;
              }
          const { data } = await api.get(`/beats/${beatId}/preview-url`);
          const url = fixPresignedUrlEncoding(data?.url || "");
          if (url) previewCache.set(beatId, { url, ts: Date.now() });
          return url;
        }

function dispatchQueue(list, index, sourceKey) {
    document.dispatchEvent(
        new CustomEvent("audio:play-list", { detail: { list, index, sourceKey } })
    );
}

// ----------------- Hydration helpers -----------------
async function prefetchBeatUrls(ids = [], { force = false } = {}) {
      await Promise.allSettled(ids.map((id) => getPreviewUrl(id, { force })));
    }

async function hydrateBeatList(beats = [], forceForIndex = null) {
    // beats rows look like: { id, title, artistName, albumCoverUrl, audioUrl? }
    // ensure every item has audioUrl
    const idsNeeding = beats
        .map((b, i) => ({ id: b.id, i }))
        .filter(({ i }) => !beats[i].audioUrl || i === forceForIndex)
        .map(({ id }) => id);

    if (idsNeeding.length) {
        await prefetchBeatUrls(idsNeeding, { force: forceForIndex != null });
    }

    return beats.map((b) => ({
        ...b,
        audioUrl: fixPresignedUrlEncoding(
              b.audioUrl || (getCachedUrl(b.id) || "")
            )
    }));
}

async function lookahead(beats, currentIndex, n = 3) {
    const nextIds = [];
    for (let i = 1; i <= n; i++) {
        const row = beats[currentIndex + i];
        if (row && !row.audioUrl) nextIds.push(row.id);
    }
    if (nextIds.length) await prefetchBeatUrls(nextIds);
}

// ----------------- Public API -----------------

/** Queue a list of beats (any page) */
export async function queueBeatsList(beats, startIndex = 0, sourceKey = "") {
       // ensure clicked item has a *fresh* URL (bypass TTL)
            const clicked = beats[startIndex];
     if (clicked) {
              try { await getPreviewUrl(clicked.id, { force: true }); } catch {}
            }

    const hydrated = await hydrateBeatList(beats, startIndex);
    dispatchQueue(hydrated, startIndex, sourceKey);

    // lazy lookahead
    lookahead(hydrated, startIndex, 3).catch(() => {});
}

/** Queue a single beat (e.g., Track page). Optionally pass a “next up” list. */
export async function queueSingleBeat(beat, nextBeats = [], sourceKey = "") {
    const list = [beat, ...nextBeats];
    await queueBeatsList(list, 0, sourceKey);
}

export function BEAT_KEY(beatId) {
    return `beat:${beatId}`;
}

/** Queue a pack using your backend preview playlist */
export async function queuePack(packId, packCoverUrl, sourceKey = `pack:${packId}`) {
    const { data } = await api.get(`/packs/${packId}/preview-playlist`);
    // data should already be { id,title,artistName,albumCoverUrl?,audioUrl }
    const list = (Array.isArray(data) ? data : []).map((t, i) => ({
        id: t.id ?? i,
        title: t.title,
        artistName: t.artistName || "Unknown",
        albumCoverUrl: t.albumCoverUrl || packCoverUrl || "",
        audioUrl: fixPresignedUrlEncoding(t.previewUrl || t.audioUrl || ""),
        durationInSeconds: t.durationInSeconds || 0,
        genre: t.genre || "",
        bpm: t.bpm || 0,
        tags: t.tags || [],
        price: t.price || 0,
    }));

    dispatchQueue(list, 0, sourceKey);
}

/** Optional: append to the existing queue (if your bar supports "audio:queue-append") */
export function appendToQueue(items = []) {
    document.dispatchEvent(new CustomEvent("audio:queue-append", { detail: { items } }));
}

const toPlayerItemFromBeat = (b) => ({
    id: b.id,
    title: b.title,
    artistName: b.artistName || b.creatorName || "Unknown",
    albumCoverUrl: b.albumCoverUrl || b.coverUrl || b.img || "",
    audioUrl: b.audioUrl || "", // optional; AudioBar can fetch when needed
});

const mapPreviewItemsToPlaylist = (arr, cover) =>
    (Array.isArray(arr) ? arr : []).map((t, i) => ({
        id: t.id ?? i,
        title: t.title,
        artistName: t.artistName || "Unknown",
        albumCoverUrl: t.coverUrl || cover || "",
        audioUrl: t.previewUrl || "",
        durationInSeconds: t.durationInSeconds || 0,
    }));

export function appendBeat(beat) {
    const item = toPlayerItemFromBeat(beat);
    document.dispatchEvent(new CustomEvent("audio:queue-append", { detail: { items: [item] } }));
}

export async function appendPack(packId, coverUrl) {
    const { data } = await api.get(`/packs/${packId}/preview-playlist`);
    const items = mapPreviewItemsToPlaylist(data, coverUrl);
    if (items.length) {
        document.dispatchEvent(new CustomEvent("audio:queue-append", { detail: { items } }));
    }
}

export async function appendKit(kitId, coverUrl) {
    const { data } = await api.get(`/kits/${kitId}/preview-playlist`);
    const items = mapPreviewItemsToPlaylist(data, coverUrl);
    if (items.length) {
        document.dispatchEvent(new CustomEvent("audio:queue-append", { detail: { items } }));
    }
}