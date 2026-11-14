import { Button, Modal } from "@open-ent/react";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { SignatureReport, Verdict, verdictOf } from "../lib/signature";
import { WorkspaceElement } from "../lib/types";
import { verifySignature } from "../services/esign.service";

const TONE: Record<Verdict, string> = { valid: "success", unknown: "warning", invalid: "danger", none: "info" };

const when = (iso?: string) => (iso ? new Date(iso).toLocaleString() : null);

/**
 * « Vérifier la signature » : le module esign examine les signatures du PDF et dit ce qu'on peut en
 * affirmer (valide, émetteur inconnu, invalide, aucune signature), avec ce que le registre de la
 * plateforme sait du document quand l'ENT l'a lui-même scellé. Rien n'est modifié.
 */
export const SignatureCheckModal = ({ element, onClose }: { element: WorkspaceElement | null; onClose: () => void }) => {
  const { t } = useTranslation("workspace");
  const [report, setReport] = useState<SignatureReport | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!element) return;
    let alive = true;
    setReport(null);
    setError(null);
    verifySignature(element._id)
      .then((r) => alive && setReport(r))
      .catch((key) => alive && setError(typeof key === "string" ? key : "workspace.signature.failed"));
    return () => {
      alive = false;
    };
  }, [element]);

  const verdict = report ? verdictOf(report) : null;

  return (
    <Modal id="workspace-signature-check" isOpen={!!element} onModalClose={onClose} size="lg">
      <Modal.Header onModalClose={onClose}>{t("workspace.signature.title")}</Modal.Header>
      <Modal.Body>
        {!report && !error && <p data-signature-check="loading">{t("workspace.signature.loading")}</p>}
        {error && (
          <p className="text-danger" role="alert" data-signature-check="error">
            {t(error)}
          </p>
        )}
        {report && verdict && (
          <div data-signature-check={verdict}>
            <div className={`alert alert-${TONE[verdict]} mb-16`} role="status">
              {/* un seul bloc : l'alerte du socle aligne ses enfants en ligne */}
              <div>
                <strong>{t(`workspace.signature.verdict.${verdict}`)}</strong>
                <div className="small mt-4">{t(`workspace.signature.verdict.${verdict}.help`)}</div>
              </div>
            </div>

            {report.registre && (
              <section className="mb-16" data-signature-registry>
                <h3 className="h6">{t("workspace.signature.registry")}</h3>
                <dl className="row small mb-0">
                  {report.registre.intitule && (
                    <>
                      <dt className="col-4">{t("workspace.signature.registry.title")}</dt>
                      <dd className="col-8">{report.registre.intitule}</dd>
                    </>
                  )}
                  {report.registre.porteur && (
                    <>
                      <dt className="col-4">{t("workspace.signature.registry.holder")}</dt>
                      <dd className="col-8">{report.registre.porteur}</dd>
                    </>
                  )}
                  {report.registre.createdAt && (
                    <>
                      <dt className="col-4">{t("workspace.signature.registry.date")}</dt>
                      <dd className="col-8">{when(report.registre.createdAt)}</dd>
                    </>
                  )}
                  {report.registre.reference && (
                    <>
                      <dt className="col-4">{t("workspace.signature.registry.reference")}</dt>
                      <dd className="col-8">{report.registre.reference}</dd>
                    </>
                  )}
                </dl>
                {!!report.registre.signataires?.length && (
                  <>
                    <div className="small fw-bold mt-8">{t("workspace.signature.registry.signatories")}</div>
                    <ul className="small mb-0">
                      {report.registre.signataires.map((s, i) => (
                        <li key={i}>
                          {s.nom}
                          {s.role ? ` — ${s.role}` : ""}
                          {s.date ? ` (${when(s.date)})` : ""}
                        </li>
                      ))}
                    </ul>
                  </>
                )}
              </section>
            )}

            {report.signatures.length > 0 && (
              <section>
                <h3 className="h6">{t("workspace.signature.list", { n: report.nbSignatures })}</h3>
                <ul className="list-unstyled small mb-0">
                  {report.signatures.map((s, i) => (
                    <li key={i} className="border-top py-8" data-signature-item>
                      <div>
                        <strong>{s.signePar || t("workspace.signature.unknown.signer")}</strong>
                        {" — "}
                        {t(s.valide ? "workspace.signature.item.valid" : "workspace.signature.item.invalid")}
                      </div>
                      {s.signeLe && <div>{t("workspace.signature.item.signed", { date: when(s.signeLe) })}</div>}
                      {s.horodateLe && <div>{t("workspace.signature.item.timestamp", { date: when(s.horodateLe) })}</div>}
                      {s.niveau && <div className="text-gray-700">{s.niveau}</div>}
                    </li>
                  ))}
                </ul>
              </section>
            )}
            <p className="small text-gray-700 mt-16 mb-0">{t("workspace.signature.scope")}</p>
          </div>
        )}
      </Modal.Body>
      <Modal.Footer>
        <Button type="button" color="primary" onClick={onClose}>
          {t("workspace.signature.close")}
        </Button>
      </Modal.Footer>
    </Modal>
  );
};
