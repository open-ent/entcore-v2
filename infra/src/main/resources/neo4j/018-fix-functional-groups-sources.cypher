MATCH (s:Structure)<-[:DEPENDS]-(fg)
WHERE (fg:FunctionalGroup OR fg:FunctionGroup) AND fg.source IS NULL
SET fg.source = CASE WHEN s.timetable = '' THEN s.source ELSE coalesce(s.timetable, s.source) END;