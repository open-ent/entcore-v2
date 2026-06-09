-- Envoi différé : un message peut être programmé pour un envoi futur.
-- État 'SCHEDULED' (en plus de DRAFT/SENT/RECALL) + horodatage d'envoi prévu
-- (epoch millis, cohérent avec la colonne "date").
ALTER TABLE conversation.messages ADD COLUMN "scheduled_at" BIGINT;

-- Contexte expéditeur figé à la programmation ({type, structures, username}) : permet au
-- worker (hors requête HTTP) de revérifier les horaires de messagerie à l'échéance
-- (règle « on retient hors plage ») et d'émettre la notification.
ALTER TABLE conversation.messages ADD COLUMN "sender_context" JSONB;

-- Le worker dépile les messages dus : index partiel sur les seuls programmés.
CREATE INDEX idx_messages_scheduled ON conversation.messages ("scheduled_at")
    WHERE state = 'SCHEDULED';
