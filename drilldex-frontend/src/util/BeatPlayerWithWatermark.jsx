// BeatPlayerWithWatermark.jsx
import { useEffect, useRef, useState } from "react";
import api from "../lib/api"; // your axios instance

function dbToGain(db) {
    return Math.pow(10, db / 20);
}

export default function BeatPlayerWithWatermark({
                                                    beatId,                 // ← we fetch /api/beats/:id/preview-url
                                                    wmUrl,                  // public or presigned URL to your watermark mp3
                                                    intervalSec = 5,        // how often to play it
                                                    wmGainDb = -8,          // base watermark gain
                                                    hipassHz = 150,         // make it cut through but not muddy
                                                    lopassHz = 6500,
                                                    startMuted = false,
                                                }) {
    const audioRef = useRef(null);
    const ctxRef = useRef(null);
    const mainNodeRef = useRef(null);
    const wmBufferRef = useRef(null);
    const wmTimerRef = useRef(null);
    const [ready, setReady] = useState(false);
    const [playing, setPlaying] = useState(false);
    const [muted, setMuted] = useState(startMuted);

    // 1) Ask backend for a short-lived preview URL for this beat
    useEffect(() => {
        let cancelled = false;
        (async () => {
            try {
                const { data } = await api.get(`/beats/${beatId}/preview-url`);
                if (cancelled) return;
                const url = data?.url;
                if (url && audioRef.current) {
                    audioRef.current.src = url;
                    audioRef.current.crossOrigin = "anonymous"; // required for WebAudio
                    audioRef.current.load();
                    setReady(true);
                }
            } catch {
                // ignore or toast
            }
        })();
        return () => { cancelled = true; };
    }, [beatId]);

    // 2) Build the audio graph once the <audio> has a source
    useEffect(() => {
        if (!ready || !audioRef.current) return;

        const ctx = new (window.AudioContext || window.webkitAudioContext)();
        ctxRef.current = ctx;

        // Main element -> compressor -> destination
        const mainNode = ctx.createMediaElementSource(audioRef.current);
        mainNodeRef.current = mainNode;

        const comp = ctx.createDynamicsCompressor();
        comp.threshold.value = -12;
        comp.knee.value = 30;
        comp.ratio.value = 6;
        comp.attack.value = 0.003;
        comp.release.value = 0.25;

        mainNode.connect(comp);
        comp.connect(ctx.destination);

        // Preload watermark buffer
        (async () => {
            const res = await fetch(wmUrl, { mode: "cors" });
            const arr = await res.arrayBuffer();
            const buffer = await ctx.decodeAudioData(arr);
            wmBufferRef.current = buffer;
        })();

        return () => {
            try { mainNode.disconnect(); } catch {}
            try { comp.disconnect(); } catch {}
            if (ctx && ctx.state !== "closed") ctx.close();
            ctxRef.current = null;
            mainNodeRef.current = null;
            wmBufferRef.current = null;
        };
    }, [ready, wmUrl]);

    // Helper to spawn a single watermark “burst”
    const spawnWatermark = () => {
        const ctx = ctxRef.current;
        const buf = wmBufferRef.current;
        if (!ctx || !buf) return;

        // Each repeat must use a NEW BufferSource
        const src = ctx.createBufferSource();
        src.buffer = buf;

        // Filters to make it sit in the mix
        const hip = ctx.createBiquadFilter();
        hip.type = "highpass";
        hip.frequency.value = hipassHz;

        const lop = ctx.createBiquadFilter();
        lop.type = "lowpass";
        lop.frequency.value = lopassHz;

        const gain = ctx.createGain();
        gain.gain.value = dbToGain(wmGainDb);

        // Optional: tiny “room” via short feedback delay (cheap pseudo-reverb)
        const dly = ctx.createDelay();
        dly.delayTime.value = 0.06; // 60 ms slap
        const dlyGain = ctx.createGain();
        dlyGain.gain.value = 0.15;

        // src -> hip -> lop -> (split)
        src.connect(hip);
        hip.connect(lop);

        // dry to gain
        lop.connect(gain);

        // wet loop
        lop.connect(dly);
        dly.connect(dlyGain);
        dlyGain.connect(gain); // mix back

        // to output (through same compressor path the main uses)
        // simplest is to connect directly to destination too, but we keep it consistent:
        gain.connect(ctx.destination);

        // Soft fade-in / fade-out to avoid clicks
        const now = ctx.currentTime;
        const dur = Math.min(buf.duration, 2.5); // safety
        gain.gain.cancelScheduledValues(now);
        const base = dbToGain(wmGainDb);
        gain.gain.setValueAtTime(0.0001, now);
        gain.gain.linearRampToValueAtTime(base, now + 0.03);
        gain.gain.setValueAtTime(base, now + Math.max(0, dur - 0.06));
        gain.gain.linearRampToValueAtTime(0.0001, now + Math.max(0.01, dur - 0.01));

        src.start(now);
        src.stop(now + dur + 0.05);
    };

    // Repeat every intervalSec while playing
    const startRepeater = () => {
        spawnWatermark(); // first one immediately
        const id = window.setInterval(spawnWatermark, Math.max(1, intervalSec) * 1000);
        wmTimerRef.current = id;
    };
    const stopRepeater = () => {
        if (wmTimerRef.current != null) {
            clearInterval(wmTimerRef.current);
            wmTimerRef.current = null;
        }
    };

    const togglePlay = async () => {
        const el = audioRef.current;
        const ctx = ctxRef.current;
        if (!el || !ctx) return;

        await ctx.resume(); // required on Safari/iOS after user gesture
        if (el.paused) {
            await el.play();
            setPlaying(true);
            if (!wmTimerRef.current) startRepeater();
        } else {
            el.pause();
            setPlaying(false);
            stopRepeater();
        }
    };

    useEffect(() => () => stopRepeater(), []);

    return (
        <div className="beat-player">
            <audio ref={audioRef} preload="auto" muted={muted} controls={false} />
            <div className="controls" style={{ display: "flex", gap: 8 }}>
                <button onClick={togglePlay} disabled={!ready}>
                    {playing ? "Pause" : "Play"}
                </button>
                <button onClick={() => setMuted(m => !m)}>
                    {muted ? "Unmute" : "Mute"}
                </button>
            </div>
        </div>
    );
}