-- Signalement d'abus sur un message (modération ADML)
-- Un message peut être signalé par plusieurs destinataires (1 ligne par signaleur).
-- L'état de modération est porté par la colonne reportAction de conversation.messages
-- (NULL = en attente ; sinon { action: KEEP|DELETE, userId, displayName, date }).

CREATE TABLE conversation.message_reports (
    "message_id"    VARCHAR(36) NOT NULL,
    "reporter_id"   VARCHAR(36) NOT NULL,
    "reporter_name" VARCHAR(255),
    "structures"    JSONB NOT NULL DEFAULT '[]'::jsonb,
    "reason"        TEXT,
    "created"       BIGINT NOT NULL,
    PRIMARY KEY ("message_id", "reporter_id"),
    FOREIGN KEY ("message_id") REFERENCES conversation.messages ("id") ON DELETE CASCADE
);

CREATE INDEX message_reports_structures_idx ON conversation.message_reports USING gin ("structures");

ALTER TABLE conversation.messages ADD COLUMN IF NOT EXISTS "reportAction" JSONB;

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE conversation.message_reports TO "apps";
