import {Link} from "react-router-dom";

function DiscoverDrillStyles({ styles, tags = [], onStyleClick, onTagClick }) {
    return (
        <section className="discover">
            <div className="discover__header">
                <h2 className="discover__title">Discover Drill Styles</h2>
                {/*<a className="discover__link" href="#">View All</a>*/}
            </div>

            {/* Cards (horizontal scroll if needed) */}
            <div className="discover__track">
                <ul className="discover__grid">
                    {styles.map((s, i) => {
                        const slug = s.slug || encodeURIComponent(s.title);
                        return (
                            <li key={`${slug}-${i}`} className="discover__card">
                                <Link
                                    to={`/styles/${slug}`}
                                    state={s}                         // make data available on the style page
                                    className="discover__cardBtn"
                                    onClick={() => onStyleClick?.(s, i)} // still fire optional callback
                                    aria-label={`Open ${s.title}`}
                                >
                                    <img src={s.image} alt={s.title} />
                                    <div className="discover__overlay">
                                        <span>{s.title}</span>
                                    </div>
                                </Link>
                            </li>
                        );
                    })}
                </ul>
            </div>

            {/* Hashtag chips */}
            {/*{tags?.length > 0 && (*/}
            {/*    <div className="discover__tags" role="list">*/}
            {/*        {tags.map((t, i) => (*/}
            {/*            <button*/}
            {/*                key={`${t}-${i}`}*/}
            {/*                className="chip"*/}
            {/*                role="listitem"*/}
            {/*                onClick={() => onTagClick?.(t)}*/}
            {/*                aria-label={`Filter by ${t}`}*/}
            {/*            >*/}
            {/*                {t}*/}
            {/*            </button>*/}
            {/*        ))}*/}
            {/*    </div>*/}
            {/*)}*/}
        </section>
    );
}

export default DiscoverDrillStyles;