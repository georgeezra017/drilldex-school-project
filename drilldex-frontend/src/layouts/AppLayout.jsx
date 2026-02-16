// src/layouts/AppLayout.jsx
import { Outlet, useLocation } from "react-router-dom";
import Header from "../components/Header";
import AudioBar from "../components/AudioBar.jsx";

export default function AppLayout() {
    const { pathname } = useLocation();

    // Any path that starts with /signup or /login (and subroutes) will hide the bar
    const HIDE_AUDIO_PATTERNS = [/^\/signup(\/|$)/, /^\/login(\/|$)/];

    const hideAudioBar = HIDE_AUDIO_PATTERNS.some((re) => re.test(pathname));
    const showAudioBar = !hideAudioBar;

    return (
        <>
            <Header />
            <main className="main">
                <Outlet />
            </main>


            {showAudioBar && <AudioBar />}
        </>
    );
}