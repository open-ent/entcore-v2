import { useTranslation } from "react-i18next";
import { UploadItem } from "../services/useUploads";

/** Envois en cours et terminés, avec leur progression et leurs erreurs. */
export const UploadPanel = ({ items, onClear }: { items: UploadItem[]; onClear: () => void }) => {
  const { t } = useTranslation("workspace");
  if (items.length === 0) return null;
  return (
    <section data-upload-panel className="border rounded p-12 mb-16" aria-live="polite">
      <ul className="list-unstyled mb-8">
        {items.map((i) => (
          <li key={i.id} data-upload-item={i.status}>
            {i.name} —{" "}
            {i.status === "loading" && `${i.percent} %`}
            {i.status === "done" && t("workspace.react.upload.done")}
            {i.status === "error" && (
              <span className="text-danger" data-upload-error>
                {t(i.error ?? "workspace.react.upload.failed")}
              </span>
            )}
          </li>
        ))}
      </ul>
      <button type="button" className="btn btn-link p-0" onClick={onClear}>
        {t("workspace.react.upload.clear")}
      </button>
    </section>
  );
};
