import { IoPricetagsOutline } from "react-icons/io5";

import "./LicensePricing.css";

const LICENSES = [
    { code: "mp3",       name: "MP3 Lease" },
    { code: "wav",       name: "WAV Lease" },
    { code: "premium",   name: "Premium (WAV + Stems)" },
    { code: "exclusive", name: "Exclusive Rights" },
];

export default function LicensePricing({ value, onChange, currency = "€" }) {
    const setEnabled = (code, enabled) => {
        onChange({
            ...value,
            [code]: { ...(value?.[code] || {}), enabled }
        });
    };

    const setPrice = (code, price) => {
        onChange({
            ...value,
            [code]: { ...(value?.[code] || {}), price }
        });
    };

    return (
        <div className="licenseGrid">
            {LICENSES.map(({ code, name }) => {
                const v = value?.[code] || { enabled:false, price:"" };

                return (
                    <div key={code} className={`licenseCard ${v.enabled ? "is-on" : ""}`}>
                        <div className="licenseHead">
                            <div className="licenseTitle">
                                <IoPricetagsOutline />
                                <span>{name}</span>
                            </div>

                            <label className="switch">
                                <input
                                    type="checkbox"
                                    checked={!!v.enabled}
                                    onChange={(e) => setEnabled(code, e.target.checked)}
                                />
                                <span className="lpSlider" />
                            </label>
                        </div>

                        {v.enabled && (
                            <div className="priceRow">
                                <span className="currency">{currency}</span>
                                <input
                                    className="priceInput"
                                    type="number"
                                    step="0.01"
                                    min="0"
                                    placeholder="Enter price"
                                    value={v.price ?? ""}
                                    onChange={(e) => setPrice(code, e.target.value)}
                                />
                            </div>
                        )}
                    </div>
                );
            })}
        </div>
    );
}