// src/lib/api.js
import axios from "axios"
import toast from "react-hot-toast";

// const api = axios.create({
//     baseURL: "http://localhost:8080/api",
// });

const api = axios.create({
    baseURL: import.meta.env.VITE_API_BASE_URL || "/api",
});

// attach access token from localStorage
api.interceptors.request.use((config) => {
    const raw = localStorage.getItem("auth");
    if (raw) {
        const { token } = JSON.parse(raw);
        if (token) config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
});


// auto-refresh on 401 using stored refreshToken
let refreshing = null;
api.interceptors.response.use(
    (r) => r,
    async (error) => {
        const { response, config } = error;
        if (response?.status !== 401 || config?._retry) throw error;

        const raw = localStorage.getItem("auth");
        const { refreshToken } = raw ? JSON.parse(raw) : {};
        if (!refreshToken) throw error;

        try {
            if (!refreshing) {
                refreshing = api.post("/auth/refresh-token", { refreshToken });
            }
            const { data } = await refreshing; // { accessToken, refreshToken }
            refreshing = null;

            // store new tokens
            const next = { ...(raw ? JSON.parse(raw) : {}), token: data.accessToken, refreshToken: data.refreshToken };
            localStorage.setItem("auth", JSON.stringify(next));

            // retry original request once
            config._retry = true;
            config.headers.Authorization = `Bearer ${data.accessToken}`;
            return api(config);
        } catch (e) {
            refreshing = null;
            // optionally clear auth and redirect to login
            localStorage.removeItem("auth");
            throw error;
        }
    }
);
/* ------------------- UPLOAD HELPERS ------------------- */

// Build the JSON meta for beat upload from UploadModal state
function buildBeatMeta(state) {
    const licenses = Object.entries(state.licenses || {})
        .filter(([, v]) => v?.enabled && String(v?.price || "").trim())
        .map(([type, v]) => ({
            type: String(type).toUpperCase(),
            enabled: true,
            price: Number(v.price)
        }));

    return {
        title: state.title?.trim() || "",
        bpm: Number(state.bpm || 0),
        genre: state.genre?.trim() || "",
        tags: state.tags || "",
        licenses
    };
}

/** POST /api/beats/upload (multipart/form-data) */
// export async function uploadBeatFromState(state) {
//     const meta = buildBeatMeta(state);
//
//     const fd = new FormData();
//     fd.append("meta", new Blob([JSON.stringify(meta)], { type: "application/json" }));
//     fd.append("audio", state.audioFile);                   // required
//     if (state.cover) fd.append("cover", state.cover);      // optional
//
//     // Let the browser set Content-Type with the form-data boundary
//     const { data } = await api.post("/beats/upload", fd);
//     return data; // { message, beatId, approved:false }
// }

export async function uploadBeatFromState(state) {
    const meta = buildBeatMeta(state);

    const fd = new FormData();
    fd.append("meta", new Blob([JSON.stringify(meta)], { type: "application/json" }));
    fd.append("audio", state.audioFile);
    if (state.cover) fd.append("cover", state.cover);

    // ✅ Add this line so the stems ZIP actually gets sent to the backend
    if (state.stemsZip) fd.append("stems", state.stemsZip);

    const toastId = toast.loading("Uploading beat… 0%");
    let lastUpdate = 0;

    try {
        const { data } = await api.post("/beats/upload", fd, {
            headers: { "Content-Type": "multipart/form-data" }, // ✅ make sure to set this
            onUploadProgress: (e) => {
                if (!e.total) return;
                const now = Date.now();
                if (now - lastUpdate < 200) return; // throttle updates
                lastUpdate = now;

                const percent = Math.round((e.loaded * 100) / e.total);
                const label =
                    percent < 100
                        ? `Uploading beat… ${percent}%`
                        : "Processing on server…";

                toast.loading(label, { id: toastId });
            },
        });

        toast.success("Beat uploaded successfully!", { id: toastId });
        return data;
    } catch (err) {
        const msg = err?.response?.data?.error || "Failed to upload beat";
        toast.error(msg, { id: toastId });
        throw err;
    }
}

// Build the JSON meta for pack upload from UploadModal state
function buildPackMeta(state) {
    const licenses = Object.entries(state.licenses || {})
        .filter(([, v]) => v?.enabled && String(v?.price || "").trim())
        .map(([type, v]) => ({
            type: String(type).toUpperCase(),
            enabled: true,
            price: Number(v.price)
        }));

    return {
        name: state.name?.trim() || "",
        description: state.description?.trim() || "",
        price: state.price ? Number(state.price) : 0,
        tags: state.tags || "",
        beatIds: state.beatIds || [],
        licenses
    };
}

// src/lib/api.js
// export async function uploadPackFromState(state) {
//     const meta = buildPackMeta(state);
//
//     const fd = new FormData();
//     fd.append("meta", new Blob([JSON.stringify(meta)], { type: "application/json" }));
//
//     if (state.cover) fd.append("cover", state.cover);
//
//     // ZIP is now required from the UI
//     if (!state.zip) {
//         throw new Error("Please attach a .zip file for the pack.");
//     }
//     // extra guard (browsers may report different MIME types)
//     if (!/\.zip$/i.test(state.zip.name)) {
//         throw new Error("Only .zip files are allowed for packs.");
//     }
//
//     fd.append("zip", state.zip);
//
//     // Do NOT append individual files; UI is zip-only now.
//     // if (Array.isArray(state.files)) { /* intentionally ignored */ }
//
//     const { data } = await api.post("/packs/upload", fd);
//     return data;
// }

export async function uploadPackFromState(state) {
    const meta = buildPackMeta(state);
    const fd = new FormData();
    fd.append("meta", new Blob([JSON.stringify(meta)], { type: "application/json" }));

    if (state.cover) fd.append("cover", state.cover);
    if (!state.zip) throw new Error("Please attach a .zip file for the pack.");
    if (!/\.zip$/i.test(state.zip.name)) throw new Error("Only .zip files are allowed for packs.");
    fd.append("zip", state.zip);

    // ✅ Add this — send stems ZIP if present
    if (state.stemsZip) fd.append("stems", state.stemsZip);

    const toastId = toast.loading("Uploading pack… 0%");
    let lastUpdate = 0;

    try {
        const { data } = await api.post("/packs/upload", fd, {
            headers: { "Content-Type": "multipart/form-data" }, // ✅ ensure proper multipart header
            onUploadProgress: (e) => {
                if (!e.total) return;
                const now = Date.now();
                if (now - lastUpdate < 200) return; // throttle updates
                lastUpdate = now;

                const percent = Math.round((e.loaded * 100) / e.total);
                const label =
                    percent < 100
                        ? `Uploading pack… ${percent}%`
                        : "Processing on server…";

                toast.loading(label, { id: toastId });
            },
        });

        toast.success("Pack uploaded successfully!", { id: toastId });
        return data;
    } catch (err) {
        const msg = err?.response?.data?.error || "Failed to upload pack";
        toast.error(msg, { id: toastId });
        throw err;
    }
}

/** POST /api/kits/upload (multipart/form-data) */
// export async function uploadKitFromState(kit) {
//     const meta = {
//         name: kit.name,
//         type: kit.type,
//         description: kit.description,
//         tags: kit.tags,
//         price: kit.price ? Number(kit.price) : 0,
//         bpmMin: kit.bpmMin || null,
//         bpmMax: kit.bpmMax || null,
//         key: kit.key || null,
//     };
//
//     if (!kit.zip) throw new Error("Please attach a .zip file.");
//
//     const fd = new FormData();
//     fd.append("meta", new Blob([JSON.stringify(meta)], { type: "application/json" }));
//     if (kit.cover) fd.append("cover", kit.cover);
//     fd.append("zip", kit.zip);
//     // do not append kit.files
//
//     const { data } = await api.post("/kits/upload", fd);
//     return data;
// }

export async function uploadKitFromState(kit) {
    const meta = {
        name: kit.name,
        type: kit.type,
        description: kit.description,
        tags: kit.tags,
        price: kit.price ? Number(kit.price) : 0,
        bpmMin: kit.bpmMin || null,
        bpmMax: kit.bpmMax || null,
        key: kit.key || null,
    };

    if (!kit.zip) throw new Error("Please attach a .zip file.");

    const fd = new FormData();
    fd.append("meta", new Blob([JSON.stringify(meta)], { type: "application/json" }));
    if (kit.cover) fd.append("cover", kit.cover);
    fd.append("zip", kit.zip);

    const toastId = toast.loading("Uploading kit… 0%");
    let lastUpdate = 0;

    try {
        const { data } = await api.post("/kits/upload", fd, {
            onUploadProgress: (e) => {
                if (!e.total) return;
                const now = Date.now();
                if (now - lastUpdate < 200) return;
                lastUpdate = now;

                const percent = Math.round((e.loaded * 100) / e.total);
                const label =
                    percent < 100
                        ? `Uploading kit… ${percent}%`
                        : "Processing on server…";

                toast.loading(label, { id: toastId });
            },
        });

        toast.success("Kit uploaded successfully!", { id: toastId });
        return data;
    } catch (err) {
        toast.error("Failed to upload kit", { id: toastId });
        throw err;
    }
}

/* ------------------- COMMENTS (BEATS) ------------------- */
export function getBeatComments(beatId, { cursor = null, limit = 20 } = {}) {
    return api
        .get(`/beats/${beatId}/comments`, { params: { cursor, limit } })
        .then(r => r.data);
}

export function postBeatComment(beatId, text) {
    return api.post(`/beats/${beatId}/comments`, { text }).then(r => r.data);
}

// (optional extras if you want them)
export function deleteBeatComment(beatId, commentId) {
    return api.delete(`/beats/${beatId}/comments/${commentId}`).then(r => r.data);
}
export function editBeatComment(beatId, commentId, text) {
    return api.patch(`/beats/${beatId}/comments/${commentId}`, { text }).then(r => r.data);
}


/* ------------------- COMMENTS (PACKS) — NEW ------------------- */
export function getPackComments(packId, { cursor = null, limit = 20 } = {}) {
    return api.get(`/packs/${packId}/comments`, { params: { cursor, limit } })
        .then(r => r.data);
}

export function postPackComment(packId, text) {
    return api.post(`/packs/${packId}/comments`, { text }).then(r => r.data);
}

export function deletePackComment(packId, commentId) {
    return api.delete(`/packs/${packId}/comments/${commentId}`).then(r => r.data);
}

export function editPackComment(packId, commentId, text) {
    return api.patch(`/packs/${packId}/comments/${commentId}`, { text }).then(r => r.data);
}

export function likePackComment(packId, commentId) {
    return api.post(`/packs/${packId}/comments/${commentId}/like`).then(r => r.data);
}

export function unlikePackComment(packId, commentId) {
    return api.post(`/packs/${packId}/comments/${commentId}/unlike`).then(r => r.data);
}

/* ------------------- COMMENTS (KITS) ------------------- */
export function getKitComments(kitId, { cursor = null, limit = 20 } = {}) {
    return api.get(`/kits/${kitId}/comments`, { params: { cursor, limit } })
        .then(r => r.data);
}
export function postKitComment(kitId, text) {
    return api.post(`/kits/${kitId}/comments`, { text }).then(r => r.data);
}
// (optional parity helpers)
export function deleteKitComment(kitId, commentId) {
    return api.delete(`/kits/${kitId}/comments/${commentId}`).then(r => r.data);
}
export function editKitComment(kitId, commentId, text) {
    return api.patch(`/kits/${kitId}/comments/${commentId}`, { text }).then(r => r.data);
}
export function likeKitComment(kitId, commentId) {
    return api.post(`/kits/${kitId}/comments/${commentId}/like`).then(r => r.data);
}
export function unlikeKitComment(kitId, commentId) {
    return api.post(`/kits/${kitId}/comments/${commentId}/unlike`).then(r => r.data);
}




export async function searchBeats(query, { limit = 50 } = {}) {
    try {
        const { data } = await api.get("/beats/search", { params: { q: query, limit } });
        return Array.isArray(data.items) ? data.items.map(x => ({ ...x, _kind: "BEAT" })) : [];
    } catch {
        const { data } = await api.get("/beats/approved", { params: { limit } });
        const items = Array.isArray(data.items) ? data.items : (Array.isArray(data) ? data : []);
        return items.map(x => ({ ...x, _kind: "BEAT" }));
    }
}

// Search packs
export async function searchPacks(query, { limit = 50 } = {}) {
    try {
        const { data } = await api.get("/packs/search", { params: { q: query, limit } });
        return Array.isArray(data.items) ? data.items.map(x => ({ ...x, _kind: "PACK" })) : [];
    } catch {
        const { data } = await api.get("/packs/approved", { params: { limit } });
        const items = Array.isArray(data.items) ? data.items : (Array.isArray(data) ? data : []);
        return items.map(x => ({ ...x, _kind: "PACK" }));
    }
}

// Search kits
export async function searchKits(query, { limit = 50 } = {}) {
    try {
        const { data } = await api.get("/kits/search", { params: { q: query, limit } });
        return Array.isArray(data.items) ? data.items.map(x => ({ ...x, _kind: "KIT" })) : [];
    } catch {
        const { data } = await api.get("/kits/approved", { params: { limit } });
        const items = Array.isArray(data.items) ? data.items : (Array.isArray(data) ? data : []);
        return items.map(x => ({ ...x, _kind: "KIT" }));
    }
}

// Search everything (combined)
export async function searchEverything(query, { limit = 50, kinds = ["BEAT","PACK","KIT"] } = {}) {
    const tasks = [];
    if (kinds.includes("BEAT")) tasks.push(searchBeats(query, { limit }));
    if (kinds.includes("KIT"))  tasks.push(searchKits(query,  { limit }));
    if (kinds.includes("PACK")) tasks.push(searchPacks(query, { limit }));

    const results = await Promise.allSettled(tasks);
    return results
        .flatMap(r => (r.status === "fulfilled" ? r.value : []))
        .slice(0, limit * kinds.length);
}

// --- BADGE LIST HELPERS ---------------------------------

// ===== BEATS =====
export const listBeatFeatured = (limit = 100) =>
    api.get("/beats/featured", { params: { limit } }).then(r => r.data.items);

export const listBeatPopular = (limit = 100) =>
    api.get("/beats/popular", { params: { limit } }).then(r => r.data.items);

export const listBeatTrending = (limit = 100) =>
    api.get("/beats/trending", { params: { limit } }).then(r => r.data.items);

export const listBeatNew = (limit = 100) =>
    api.get("/beats/new", { params: { limit } }).then(r => r.data.items);

export const listBeatApproved = (limit = 100) =>
    api.get("/beats/approved", { params: { limit } }).then(r => r.data.items);

// ===== KITS =====
export const listKitFeatured = (limit = 100) =>
    api.get("/kits/featured", { params: { limit } }).then(r => r.data.items);

export const listKitPopular = (limit = 100) =>
    api.get("/kits/popular", { params: { limit } }).then(r => r.data.items);

export const listKitTrending = (limit = 100) =>
    api.get("/kits/trending", { params: { limit } }).then(r => r.data.items);

export const listKitNew = (limit = 100) =>
    api.get("/kits/new", { params: { limit } }).then(r => r.data.items);

export const listKitApprovedWithFlags = (limit = 100) =>
    api.get("/kits/approved", { params: { limit } }).then(r => r.data.items);

// ===== PACKS =====
export const listPackFeatured = (limit = 100) =>
    api.get("/packs/featured", { params: { limit } }).then(r => r.data.items);

export const listPackPopular = (limit = 100) =>
    api.get("/packs/popular", { params: { limit } }).then(r => r.data.items);

export const listPackTrending = (limit = 100) =>
    api.get("/packs/trending", { params: { limit } }).then(r => r.data.items);

export const listPackNew = (limit = 100) =>
    api.get("/packs/new", { params: { limit } }).then(r => r.data.items);

export const listPackApproved = (limit = 100) =>
    api.get("/packs/approved", { params: { limit } }).then(r => r.data.items);
export default api;
