CREATE INDEX IF NOT EXISTS FOR (n:User) ON (n.disappearanceDate);
CREATE INDEX IF NOT EXISTS FOR (n:User) ON (n.modified);
CREATE INDEX IF NOT EXISTS FOR (n:User) ON (n.created);
