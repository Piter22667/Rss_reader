-- Do not delete older records: report existing duplicates before adding the constraint.
CREATE UNIQUE INDEX uq_articles_source_link ON articles (feed_source_id, link);
