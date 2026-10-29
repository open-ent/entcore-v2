match (sub:Subject) where sub.source IS NULL or sub.source = 'CSV' set sub.source = 'MANUAL';
