-- Track the Markdown (full text) enrichment state of every article.
-- PENDING  - not fetched yet
-- OK       - full_text was stored successfully
-- FAILED   - the fetch attempt failed (for example a broken link); do not retry automatically
ALTER TABLE articles ADD COLUMN full_text_status VARCHAR(16) NOT NULL DEFAULT 'PENDING';
ALTER TABLE articles ADD COLUMN full_text_attempted_at TIMESTAMPTZ;

UPDATE articles SET full_text_status = 'OK' WHERE full_text IS NOT NULL;