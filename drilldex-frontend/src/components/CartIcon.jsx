// src/components/CartIcon.jsx
import { Link } from "react-router-dom";

export default function CartIcon({ itemCount = 0 }) {
    return (
        <Link to="/cart" className="carticon" aria-label={`Cart (${itemCount})`}>
            <svg
                className="carticon__svg"
                xmlns="http://www.w3.org/2000/svg"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
                strokeWidth={2}
                aria-hidden="true"
            >
                <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    d="M3 3h2l.4 2m0 0L7 13h10l4-8H5.4zM7 13l-2 9m0 0h16m-16 0a2 2 0 104 0m12 0a2 2 0 104 0"
                />
            </svg>

            {itemCount > 0 && (
                <span className="carticon__badge">{itemCount}</span>
            )}
        </Link>
    );
}