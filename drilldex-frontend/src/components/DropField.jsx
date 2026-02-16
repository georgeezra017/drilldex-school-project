import { useCallback, useMemo } from "react";
import { useDropzone } from "react-dropzone";
import { IoCloudUploadOutline } from "react-icons/io5";
import toast from "react-hot-toast";

/**
 * Reusable drop field
 * Props:
 * - label: string (visible label)
 * - value: File | File[] | null (current selection)
 * - onChange: (File | File[] | null) => void
 * - accept: react-dropzone accept map, e.g. { "audio/mpeg": [".mp3"], "audio/wav": [".wav"] }
 * - multiple: boolean
 * - maxSize: number (bytes)
 * - placeholder: string (fallback text)
 */
export default function DropField({
                                      label,
                                      value,
                                      onChange,
                                      accept,
                                      multiple = false,
                                      maxSize = 50 * 1024 * 1024, // 50MB default
                                      placeholder = "Click to select or drop files",
                                  }) {
    const prettyValue = useMemo(() => {
        if (!value) return "";
        if (Array.isArray(value)) {
            if (value.length === 0) return "";       // <-- allow placeholder / drag text
            return `${value.length} file(s) selected`;
        }
        return value.name;
    }, [value]);

    const onDrop = useCallback((acceptedFiles, rejections) => {
        if (rejections?.length) {
            // surface the first rejection reasons
            const reasons = rejections[0].errors.map(e => e.message).join(", ");
            toast.error(reasons || "File not accepted");
            return;
        }
        if (!acceptedFiles.length) return;

        if (multiple) {
            onChange(acceptedFiles);
            toast.success(`Selected ${acceptedFiles.length} file(s)`);
        } else {
            onChange(acceptedFiles[0]);
            toast.success(`Selected: ${acceptedFiles[0].name}`);
        }
    }, [multiple, onChange]);

    const {
        getRootProps,
        getInputProps,
        isDragActive,
        isDragAccept,
        isDragReject,
        open,
    } = useDropzone({
        onDrop,
        multiple,
        maxSize,
        accept,
        noClick: true,     // we’ll handle click to open manually on the wrapper
        noKeyboard: true,
    });

    return (
        <div className="uplfield">
            {label && <label>{label}</label>}

            <div
                {...getRootProps()}
                className={[
                    "dropzone",
                    isDragActive && "is-active",
                    isDragAccept && "is-accept",
                    isDragReject && "is-reject",
                ].filter(Boolean).join(" ")}
                onClick={open}
                role="button"
                tabIndex={0}
                aria-label={label || "file dropzone"}
            >
                <IoCloudUploadOutline aria-hidden />
                <span>
          {prettyValue || (isDragActive ? "Drop it here…" : placeholder)}
        </span>
                <input {...getInputProps()} />
            </div>
        </div>
    );
}