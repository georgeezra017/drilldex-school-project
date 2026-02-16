// AboutPage.jsx
import { useState } from "react";
import {
    IoMegaphoneOutline,
    IoLockClosedOutline,
    IoTrendingUpOutline,
    IoSparklesOutline,
    IoPeopleOutline,
    IoShieldCheckmarkOutline,
    IoChevronDown,
} from "react-icons/io5";


import { FaXTwitter, FaInstagram, FaTiktok } from "react-icons/fa6"; // X (Twitter) icon
import "./about.css";

const FAQS = [
    {
        q: "What is Drilldex?",
        a: "Drilldex is a home for drill. Charts, producer pages, kits, and clean storefront tools so the work gets found and the makers get paid."
    },
    {
        q: "How do I upgrade my subscription?",
        a: "Visit the About page and scroll to the Pricing section. Choose a plan and follow the steps to upgrade your subscription."
    },
    {
        q: "How do buyers pay?",
        a: "Payments are simulated locally for the school submission so the features can be graded without external services."
    },
    {
        q: "Do you store my financial info?",
        a: "No. This school build uses a local payment simulation and does not store real financial data."
    },
    {
        q: "Can I sell beats, kits, and videos?",
        a: "Yeah. You can upload beats and kits right now — video uploads are coming soon."
    },
    {
        q: "Why not just use BeatStars or YouTube?",
        a: "Because Drilldex is built for drill. No bloated genres, no random noise. Just drill creators, clean tools, and a culture that puts the right people in front."
    },
    {
        q: "Who is Drilldex for?",
        a: "Producers and artists in the drill scene."
    },
    {
        q: "What can I upload?",
        a: "Beats, packs and sample/preset kits. Videos are coming soon."
    },
    {
        q: "What’s the platform fee?",
        a: "Drilldex takes 0% commission. In this school build, payments are simulated locally for grading."
    }
];

export default function AboutPage() {
    const [open, setOpen] = useState(-1);

    return (
        <main className="about" aria-labelledby="about-title">
            {/* Hero */}
            <header className="about__hero">
                <div className="about__eyebrow">About</div>
                <h1 id="about-title" className="about__title">Drilldex</h1>
                <p className="about__lead">
                    Drilldex isn’t just another beat store—it’s the home for drill.
                    We’re here to push the culture forward by giving artists and producers exactly what they need: a clean way to find authentic sounds, lock down transparent licenses, and get paid without industry clutter. Drilldex is built from the ground up for drill—by the community, for the community.
                </p>
            </header>

            {/* Manifesto principles */}
            <section className="about__manifesto" aria-labelledby="about-manifesto">
                <h2 id="about-manifesto" className="about__sectionTitle">Principles</h2>
                <ul className="about__grid">
                    <li className="principle">
                        <IoMegaphoneOutline className="principle__icon" aria-hidden />
                        <h3 className="principle__title">Creator-First</h3>
                        <p className="principle__body">
                            No fluff. No bait. Your work front and center with plain prices and clear terms.
                        </p>
                    </li>
                    <li className="principle">
                        <IoTrendingUpOutline className="principle__icon" aria-hidden />
                        <h3 className="principle__title">Real Signals</h3>
                        <p className="principle__body">
                            Charts that reflect what’s moving—plays, licenses, momentum—not vanity numbers.
                        </p>
                    </li>
                    <li className="principle">
                        <IoLockClosedOutline className="principle__icon" aria-hidden />
                        <h3 className="principle__title">Clean Licensing</h3>
                        <p className="principle__body">
                            Simple licenses. Instant delivery. Receipts that stand up. No fine-print traps.
                        </p>
                    </li>
                    <li className="principle">
                        <IoPeopleOutline className="principle__icon" aria-hidden />
                        <h3 className="principle__title">Open Doors</h3>
                        <p className="principle__body">
                            Algorithms help, they don’t block. Bring your catalog. Let the work speak.
                        </p>
                    </li>
                    <li className="principle">
                        <IoSparklesOutline className="principle__icon" aria-hidden />
                        <h3 className="principle__title">Fast Tools</h3>
                        <p className="principle__body">
                            Uploads, pricing, kits, analytics—built to be quick so you can stay creating.
                        </p>
                    </li>
                    <li className="principle">
                        <IoShieldCheckmarkOutline className="principle__icon" aria-hidden />
                        <h3 className="principle__title">Respect The Craft</h3>
                        <p className="principle__body">
                            Credit is culture. We surface producers, writers, mix & master—on the record.
                        </p>
                    </li>
                </ul>
            </section>

            {/* Split blurb */}
            <section className="about__split">
                <div className="split__card">
                    <h3>For Producers</h3>
                    <p>Sell beats and kits, track licenses, and grow with live stats that actually help.</p>
                </div>
                <div className="split__card">
                    <h3>For Artists</h3>
                    <p>Hit the charts, filter by vibe, license in a click, and always credit the source.</p>
                </div>
                <div className="split__card">
                    <h3>For Fans</h3>
                    <p>Follow the makers, save sets, and watch the scene move week to week.</p>
                </div>
            </section>

            {/* FAQ */}
            <section className="about__faq" aria-labelledby="about-faq">
                <h2 id="about-faq" className="about__sectionTitle">FAQ</h2>
                <ul className="faq">
                    {FAQS.map((f, i) => (
                        <li key={i} className={`faq__item ${open === i ? "is-open" : ""}`}>
                            <button
                                className="faq__q"
                                aria-expanded={open === i}
                                aria-controls={`faq-panel-${i}`}
                                onClick={() => setOpen(open === i ? -1 : i)}
                            >
                                <span>{f.q}</span>
                                <IoChevronDown className="faq__chev" aria-hidden />
                            </button>
                            <div
                                id={`faq-panel-${i}`}
                                role="region"
                                className="faq__a"
                                aria-hidden={open !== i}
                            >
                                {f.a}
                            </div>
                        </li>
                    ))}
                </ul>
            </section>


            <section className="about__cta">
                <a className="about__btn about__btn--primary" href="/signup">Join Drilldex</a>
                <a className="about__btn about__btn--ghost" href="/contact">Contact</a>
                <a
                    className="about__btn about__btn--twitter"
                    href="https://twitter.com/drilldex777"
                    target="_blank"
                    rel="noopener noreferrer"
                >
                    <FaXTwitter aria-hidden /> X
                </a>
                <a
                    className="about__btn about__btn--twitter"
                    href="https://www.instagram.com/drilldex"
                    target="_blank"
                    rel="noopener noreferrer"
                >
                    <FaInstagram aria-hidden /> Instagram
                </a>
                <a
                    className="about__btn about__btn--twitter"
                    href="https://www.tiktok.com/@drilldex777"
                    target="_blank"
                    rel="noopener noreferrer"
                >
                    <FaTiktok aria-hidden /> TikTok
                </a>
            </section>
        </main>
    );
}
