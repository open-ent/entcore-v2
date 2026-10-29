MERGE (s:System {name : 'Starter'}) with s where s.lastSearchDuplicates IS NULL set s.lastSearchDuplicates='2017-09-22T09:24:47.863+02:00';
