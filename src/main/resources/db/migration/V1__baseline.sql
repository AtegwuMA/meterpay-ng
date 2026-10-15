-- Baseline: extensions only. Tables arrive in later, feature-specific migrations.
-- pgcrypto adds digest() and other hashing functions available to SQL.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
