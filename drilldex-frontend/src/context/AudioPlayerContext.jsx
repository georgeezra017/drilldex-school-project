// src/context/AudioPlayerContext.jsx
import { createContext, useContext, useState, useRef, useMemo } from "react";

const AudioPlayerContext = createContext();

export function AudioPlayerProvider({ children }) {
    const [queue, setQueue] = useState([]);
    const [currentIndex, setCurrentIndex] = useState(0);
    const [isPlaying, setIsPlaying] = useState(false);
    const [progress, setProgress] = useState({ current: 0, duration: 0 });
    const audioRef = useRef(null);

    const current = useMemo(() => queue[currentIndex] || null, [queue, currentIndex]);

    return (
        <AudioPlayerContext.Provider value={{
            queue, setQueue,
            currentIndex, setCurrentIndex,
            isPlaying, setIsPlaying,
            progress, setProgress,
            current,
            audioRef
        }}>
            {children}
        </AudioPlayerContext.Provider>
    );
}

export const useAudioPlayer = () => useContext(AudioPlayerContext);