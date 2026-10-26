// Journal d'audit des opérations sensibles (infra MongoDbAuditStore).
// seq unique : garantit un seul successeur par entrée de la chaîne, y compris entre nœuds.
db.audit.createIndex({"seq": 1}, {unique: true});
// Purge à l'échéance de rétention propre à chaque entrée (champ expireAt).
db.audit.createIndex({"expireAt": 1}, {expireAfterSeconds: 0});
// Recherches de l'écran Audit.
db.audit.createIndex({"actor.id": 1, "date": -1});
db.audit.createIndex({"target.id": 1, "date": -1});
db.audit.createIndex({"action": 1, "date": -1});
