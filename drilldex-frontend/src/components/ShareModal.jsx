// components/ShareModal.jsx
import { useMemo } from "react";
import ReactDOM from "react-dom";
import { FaFacebook, FaXTwitter } from "react-icons/fa6";
import toast from "react-hot-toast";
import "./share-modal.css";

export default function ShareModal({ open, track, onClose }) {
    if (!open || !track) return null;

    const fullUrl = useMemo(
        () => `${window.location.origin}/track/${track.slug || track.id}`,
        [track]
    );

    const embedCode = `<iframe src="${fullUrl}/embed" width="100%" height="180" frameborder="0" allow="autoplay; clipboard-write"></iframe>`;

    const copy = async (text, label = "Copied!") => {
        try {
            await navigator.clipboard.writeText(text);
            toast.success(label);
        } catch {
            toast.error("Failed to copy");
        }
    };

    const shareTwitter = () =>
        window.open(
            `https://twitter.com/intent/tweet?text=${encodeURIComponent(
                `Check out "${track.title}" by ${track.artistName} on Drilldex`
            )}&url=${encodeURIComponent(fullUrl)}`,
            "_blank"
        );

    const shareFacebook = () =>
        window.open(
            `https://www.facebook.com/sharer/sharer.php?u=${encodeURIComponent(fullUrl)}`,
            "_blank"
        );

    const modalContent = (
        <div className="shareModal__backdrop" onClick={onClose} role="dialog" aria-modal="true">
            <div className="shareModal" onClick={(e) => e.stopPropagation()}>
                <div className="shareModal__header">
                    <h3>Share Track</h3>
                    <button className="shareModal__close" onClick={onClose} aria-label="Close">×</button>
                </div>

                <div className="shareModal__track">
                    <img
                        src={track.cover || track.coverImagePath || track.albumCoverUrl || ""}
                        alt=""
                    />
                    <div className="shareModal__meta">
                        <div className="shareModal__title">{track.title}</div>
                        <div className="shareModal__artist">
                            {track.artistName || track.artist || track.uploader || track.user?.displayName || ""}
                        </div>
                    </div>
                </div>

                <div className="shareModal__block">
                    <div className="shareModal__label">MARKETPLACE URL</div>
                    <div className="shareModal__row">
                        <input readOnly value={fullUrl} onFocus={(e) => e.target.select()} />
                        <button onClick={() => copy(fullUrl, "Marketplace URL copied!")}>Copy</button>
                    </div>
                </div>

                <div className="shareModal__block">
                    <div className="shareModal__label">EMBED</div>
                    <div className="shareModal__row">
                        <textarea
                            readOnly
                            value={embedCode}
                            rows={3}
                            onFocus={(e) => e.target.select()}
                        />
                        <button onClick={() => copy(embedCode, "Embed code copied!")}>Copy</button>
                    </div>
                </div>

                <div className="shareModal__actions">
                    <button className="shareModal__pill" onClick={shareFacebook} aria-label="Share on Facebook">
                        <FaFacebook size={20} />
                        Facebook
                    </button>
                    <button className="shareModal__pill" onClick={shareTwitter} aria-label="Share on X">
                        <FaXTwitter size={20} />
                        X
                    </button>
                </div>
            </div>
        </div>
    );

    return ReactDOM.createPortal(modalContent, document.body);
}