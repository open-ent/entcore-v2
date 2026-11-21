// Clés d'appareil et défis associés (authentification par preuve de possession du téléphone).

// Retrouver les clés d'un compte, et celles d'un appareil donné pour les révoquer ensemble.
db.authdevicekeys.createIndex({"userId": 1, "revokedAt": 1});
db.authdevicekeys.createIndex({"deviceId": 1});

// Ménage des défis jamais consommés. Cet index ne participe pas à la correction : l'échéance
// est aussi contrôlée à la consommation, car un index absent ne doit jamais se traduire par un
// défi éternellement valable. expireAfterSeconds vaut 0 : chaque document porte sa propre date
// d'expiration dans expireAt.
db.authdevicechallenges.createIndex({"expireAt": 1}, {expireAfterSeconds: 0});

// Même principe pour les tickets d'enrôlement remis par un administrateur : leur échéance est
// contrôlée à la consommation, l'index ne fait que le ménage.
db.authdevicetickets.createIndex({"expireAt": 1}, {expireAfterSeconds: 0});

// Approbations de connexion. Le code est cherché par (compte, code, état) depuis le téléphone.
db.authdeviceapprovals.createIndex({"expireAt": 1}, {expireAfterSeconds: 0});
db.authdeviceapprovals.createIndex({"userId": 1, "code": 1, "state": 1});
