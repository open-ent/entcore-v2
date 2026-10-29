CREATE FULLTEXT INDEX `User[firstName,lastName]` IF NOT EXISTS FOR (n:User) ON EACH [n.firstName, n.lastName];
