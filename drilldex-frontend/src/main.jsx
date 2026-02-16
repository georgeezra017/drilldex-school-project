import React from "react";
import ReactDOM from "react-dom/client";
import {BrowserRouter} from "react-router-dom";
import App from "./App.jsx";
import {CartProvider} from "./state/cart.jsx";
import {AuthProvider} from "./state/auth.jsx";
import {Toaster} from "react-hot-toast";


ReactDOM.createRoot(document.getElementById("root")).render(
    <React.StrictMode>
        <AuthProvider>
            <BrowserRouter>
                <CartProvider>
                        <App/>
                        <Toaster
                            position="top-right"
                            toastOptions={{
                                style: {
                                    background: "#111",
                                    color: "#fff",
                                    border: "1px solid #222",
                                    borderRadius: "10px",
                                },
                                success: {
                                    iconTheme: {primary: "#009e94", secondary: "#111"},
                                },
                                error: {
                                    iconTheme: {primary: "#e25c5c", secondary: "#111"},
                                },
                            }}
                        />
                </CartProvider>
            </BrowserRouter>
        </AuthProvider>
    </React.StrictMode>
);