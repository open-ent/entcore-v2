match (s:Structure)<-[:BELONGS]-(c:Class)<-[:DEPENDS]-(pg:ProfileGroup) where pg.structureName IS NULL OR pg.structureName <> s.name set pg.structureName = s.name;
match (s:Structure)<-[:DEPENDS]-(pg:FunctionalGroup) where pg.structureName IS NULL OR pg.structureName <> s.name set pg.structureName = s.name;
