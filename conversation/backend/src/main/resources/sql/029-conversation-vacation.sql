-- Message d'absence automatique.
--
-- Les réglages (texte riche, dates de début/fin, activation) vivent dans Mongo
-- (collection « conversation.vacation »), comme les horaires de messagerie : ils sont lus
-- sur le chemin d'envoi et doivent être mis en cache sans toucher Postgres.
--
-- Côté messages, une seule colonne est nécessaire : le marqueur de réponse automatique.
-- Il sert l'ANTI-BOUCLE (une réponse automatique n'en déclenche jamais une autre) et permet
-- de distinguer ces messages lors d'un audit. Les réponses automatiques sont insérées
-- directement (elles ne repassent pas par le chemin d'envoi), ce qui rend l'anti-boucle
-- structurel ; la colonne en garde la trace.
ALTER TABLE conversation.messages
    ADD COLUMN IF NOT EXISTS "auto_reply" BOOLEAN NOT NULL DEFAULT FALSE;
