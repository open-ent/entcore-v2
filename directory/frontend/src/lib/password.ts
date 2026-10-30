/** Score de robustesse d'un mot de passe, identique à l'ancienne interface (< 12 faible, < 20 modérée, sinon forte). */
export const passwordComplexity = (password: string): number => {
  if (!password) return 0;
  if (password.length < 6) return password.length;
  let score = password.length;
  if (/[0-9]+/.test(password) && /[a-zA-Z]+/.test(password)) score += 5;
  if (!/^[a-zA-Z0-9- ]+$/.test(password)) score += 5;
  if (/[a-z]/.test(password) && /[A-Z]/.test(password)) score += 5;
  return score;
};

export type Complexity = "weak" | "moderate" | "strong";

export const complexityLevel = (password: string): Complexity => {
  const score = passwordComplexity(password);
  if (score < 12) return "weak";
  return score < 20 ? "moderate" : "strong";
};

/** Largeur de la jauge, en pourcentage (plafonnée à 100). */
export const complexityPercent = (password: string): number => Math.min(100, (passwordComplexity(password) / 20) * 100);

export type PasswordProblem = "empty-old" | "pattern" | "different" | null;

/** Premier défaut bloquant du formulaire de changement de mot de passe. `regex` vient de /auth/context (`passwordRegex`). */
export const passwordProblem = (oldPassword: string, password: string, confirm: string, regex: string): PasswordProblem => {
  if (!oldPassword) return "empty-old";
  let ok = true;
  try {
    ok = new RegExp(regex).test(password);
  } catch {
    ok = password.length >= 6;
  }
  if (!ok) return "pattern";
  return password === confirm ? null : "different";
};
