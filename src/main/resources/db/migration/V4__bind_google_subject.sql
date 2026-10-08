-- ==============================================================================
-- EXEGESE AI - V4: bind local accounts to the Google subject (OIDC "sub")
-- E-mail addresses can be reassigned by the mail domain or kept by a consumer Google account after the
-- mailbox is revoked, so the immutable Google subject is stored on the first login and must match on every
-- later login. Existing rows stay unbound (NULL) until their owner signs in again. Idempotent.
-- ==============================================================================

ALTER TABLE exegese_user ADD COLUMN IF NOT EXISTS google_sub VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS uk_exegese_user_google_sub
ON exegese_user (google_sub);
