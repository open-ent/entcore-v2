match (g:ManualGroup)<-[r:IN]-(u:User) where g.autolinkUsersFromGroups IS NOT NULL and r.updated IS NOT NULL and r.source IS NULL set r.source = 'AUTO';
