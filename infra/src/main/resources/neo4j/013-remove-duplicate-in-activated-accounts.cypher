match (u1:User)-[r:DUPLICATE]->(u2:User) where u1.password IS NOT NULL and u2.password IS NOT NULL delete r;
