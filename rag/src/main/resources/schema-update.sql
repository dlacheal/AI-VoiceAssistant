-- Crear índice GIN para Full-Text Search
CREATE INDEX IF NOT EXISTS vector_store_content_idx ON vector_store USING GIN (to_tsvector('spanish', content));
