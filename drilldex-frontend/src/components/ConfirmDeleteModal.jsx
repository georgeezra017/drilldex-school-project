// src/components/ConfirmDeleteModal.jsx
import "./ConfirmDeleteModal.css";

export default function ConfirmDeleteModal({ open, item, onClose, onConfirm }) {
    if (!open || !item) return null;

    return (
        <div className="confirmDeleteModal__backdrop" onClick={onClose}>
            <div className="confirmDeleteModal" onClick={(e) => e.stopPropagation()}>
                <h3>Delete {item.kind}?</h3>
                <p>Are you sure you want to permanently delete <strong>{item.title}</strong>?</p>

                <div className="confirmDeleteModal__actions">
                    <button className="btn btn--ghost" onClick={onClose}>Cancel</button>
                    <button className="btn btn--danger" onClick={() => onConfirm(item)}>Delete</button>
                </div>
            </div>
        </div>
    );
}