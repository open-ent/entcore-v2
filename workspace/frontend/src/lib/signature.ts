/**
 * Vérification de la signature électronique d'un PDF par le module esign
 * (POST /esign/validation) : compte rendu DSS, complété par le registre de la plateforme quand le
 * document a été scellé par l'ENT.
 */
export interface SignatureReport {
  valide: boolean;
  intact: boolean;
  nbSignatures: number;
  nbValides: number;
  empreinte?: string;
  signatures: Array<{
    indication?: string;
    sousIndication?: string;
    valide?: boolean;
    niveau?: string;
    qualification?: string;
    signePar?: string;
    signeLe?: string;
    horodateLe?: string;
    erreurs?: string[];
  }>;
  registre?: {
    reference?: string;
    module?: string;
    intitule?: string;
    porteur?: string;
    horodate?: boolean;
    createdAt?: string;
    signataires?: Array<{ nom?: string; role?: string; profil?: string; date?: string }>;
  };
}

/**
 * Les quatre réponses que la page de vérification d'esign distingue :
 *  - « none » : aucune signature dans le document ;
 *  - « valid » : signature intacte, émise par un cachet de la plateforme ;
 *  - « unknown » : intacte, mais d'un émetteur que la plateforme ne connaît pas (INDETERMINATE) ;
 *  - « invalid » : document modifié depuis sa signature, ou cachet révoqué (TOTAL_FAILED).
 */
export type Verdict = "none" | "valid" | "unknown" | "invalid";

export const verdictOf = (r: SignatureReport): Verdict => {
  if (!r.nbSignatures) return "none";
  if (r.valide) return "valid";
  if (r.intact) return "unknown";
  return "invalid";
};
