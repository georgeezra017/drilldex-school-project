// src/App.jsx
import { Routes, Route, Navigate } from "react-router-dom";
import LandingPage from './pages/LandingPage.jsx';
import AppLayout from './layouts/AppLayout.jsx';
import StylePage from './pages/StylePage.jsx';
import BrowsePage from './pages/BrowsePage.jsx';
import ChartsPage from './pages/ChartsPage.jsx';
import ProducersPage from './pages/ProducersPage.jsx';
import SellPage from './pages/SellBeatsPage.jsx';
import SellKitsPage from './pages/SellKitsPage.jsx';
import AboutPage from './pages/AboutPage.jsx';
import SignUpPage from './pages/SignUpPage.jsx';
import AccountPage from './pages/AccountPage.jsx';
import ProfilePage from './pages/ProfilePage.jsx';
import CheckoutPage from "./pages/CheckoutPage.jsx";
import AdminBeatsReview from "./pages/admin/AdminBeatsReview";
import PromotionsPage from "./pages/PromotionsPage.jsx";
import TrackPage from "./pages/TrackPage.jsx";
import SearchPage from "./pages/SearchPage.jsx";
import ChatPage from "./pages/ChatPage.jsx";
import OrderCompletePage from "./pages/OrderCompletePage.jsx";
import MyLibraryPage from "./pages/MyLibraryPage.jsx";
import NotificationsPage from "./pages/NotificationsPage";
import PaymentConfirm from "./pages/PaymentConfirm.jsx";
import CheckoutPageWrapper from "./pages/CheckoutPageWrapper.jsx";



export default function App() {
    return (
        <Routes>
            <Route element={<AppLayout />}>
                <Route path="/" element={<LandingPage />} />
                <Route path="/styles/:slug" element={<StylePage />} />
                <Route path="/browse" element={<BrowsePage />} />
                <Route path="/charts" element={<ChartsPage />} />
                <Route path="/producers" element={<ProducersPage />} />
                <Route path="/sell" element={<SellPage />} />
                <Route path="/kits" element={<SellKitsPage />} />
                <Route path="/about" element={<AboutPage />} />
                <Route path="/register" element={<SignUpPage />} />
                <Route path="/account" element={<AccountPage />} />
                <Route path="/profile" element={<Navigate to="/profile/me" replace />} />
                <Route path="/profile/:id" element={<ProfilePage />} />
                <Route path="/checkout" element={<CheckoutPageWrapper />} />
                <Route path="/promos" element={<PromotionsPage />} />
                <Route path="/track/:slug" element={<TrackPage />} />
                <Route path="/kit/:slug" element={<TrackPage />} />
                <Route path="/pack/:slug" element={<TrackPage />} />
                <Route path="/search" element={<SearchPage />} />
                <Route path="/chat/:recipientId" element={<ChatPage />} />
                <Route path="/order/complete" element={<OrderCompletePage />} />
                <Route path="/library" element={<MyLibraryPage />} />
                <Route path="/notifications" element={<NotificationsPage />} />
                <Route path="/payment/confirm" element={<PaymentConfirm />} />
                <Route path="/admin" element={<AdminBeatsReview />} />

            </Route>

            <Route path="*" element={<Navigate to="/" replace />} />

        </Routes>
    );
}
