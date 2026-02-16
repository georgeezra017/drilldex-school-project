// // src/state/auth.jsx
// import { createContext, useContext, useEffect, useMemo, useState } from "react";
//
// const AuthCtx = createContext(null);
//
// export function AuthProvider({ children }) {
//     const [auth, setAuth] = useState(() => {
//         const raw = localStorage.getItem("auth");
//         return raw
//             ? JSON.parse(raw)
//             : { token: null, refreshToken: null, role: null, displayName: null };
//     });
//
//     useEffect(() => { localStorage.setItem("auth", JSON.stringify(auth)); }, [auth]);
//
//     const value = useMemo(() => ({
//         ...auth,
//         login: (payload) => setAuth(payload),
//         logout: () => setAuth({ token: null, refreshToken: null, role: null, displayName: null }),
//     }), [auth]);
//
//     return <AuthCtx.Provider value={value}>{children}</AuthCtx.Provider>;
// }
//
// // eslint-disable-next-line react-refresh/only-export-components
// export const useAuth = () => useContext(AuthCtx);
// src/state/auth.jsx
import { createContext, useContext, useEffect, useMemo, useState } from "react";
import api from "../lib/api"; // add this

const AuthCtx = createContext(null);

const DEFAULT_AUTH = {
    token: null,
    refreshToken: null,
    role: null,
    displayName: null,
    userId: null,      // add
    avatarUrl: null,   // add
};

 const CDN_BASE = import.meta.env.VITE_S3_PUBLIC_BASE?.replace(/\/+$/, "") || "";
 const toFileUrl = (p) => {
       if (!p) return null;
       if (/^https?:\/\//i.test(p)) return p;
       const clean = String(p).replace(/^\/?uploads\/?/, "").replace(/^\/+/, "");
       return CDN_BASE ? `${CDN_BASE}/${clean}` : `/uploads/${clean}`;
     };

export function AuthProvider({ children }) {
    const [auth, setAuth] = useState(() => {
        try {
            const raw = JSON.parse(localStorage.getItem("auth"));
            return { ...DEFAULT_AUTH, ...(raw || {}) };
        } catch {
            return DEFAULT_AUTH;
        }
    });

    useEffect(() => {
        localStorage.setItem("auth", JSON.stringify(auth));
    }, [auth]);

    // keep axios Authorization header in sync (simple + reliable)
    useEffect(() => {
        if (auth.token) {
            api.defaults.headers.common.Authorization = `Bearer ${auth.token}`;
        } else {
            delete api.defaults.headers.common.Authorization;
        }
    }, [auth.token]);

     useEffect(() => {
           if (!auth.token) return;
           (async () => {
                 try {
                       const { data } = await api.get("/me");
                       setAuth((prev) => ({
                             ...prev,
                             displayName: data?.displayName ?? prev.displayName,
                             userId: data?.id ?? prev.userId,
                             avatarUrl: toFileUrl(data?.avatarUrl || data?.profilePicturePath) ?? prev.avatarUrl,
                           }));
                     } catch {
                       /* ignore */
                         }
               })();
         }, [auth.token]);

    const value = useMemo(() => ({
        ...auth,
        isAuthenticated: !!auth.token, // handy, optional
        login: (payload) => setAuth((prev) => ({ ...prev, ...payload })),
        logout: () => setAuth(DEFAULT_AUTH),
           setAvatarUrl: (url) => setAuth((prev) => ({ ...prev, avatarUrl: url ? toFileUrl(url) : null })),
           refreshMe: async () => {
             const { data } = await api.get("/me");
             setAuth((prev) => ({
                   ...prev,
                   displayName: data?.displayName ?? prev.displayName,
                   userId: data?.id ?? prev.userId,
                   avatarUrl: toFileUrl(data?.avatarUrl || data?.profilePicturePath) ?? prev.avatarUrl,
                 }));
           },
    }), [auth]);

    return <AuthCtx.Provider value={value}>{children}</AuthCtx.Provider>;
}

export const useAuth = () => useContext(AuthCtx);