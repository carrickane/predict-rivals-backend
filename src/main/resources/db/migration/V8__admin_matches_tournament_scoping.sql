-- Matches are curated per-tournament now, not globally; round numbers are scoped per tournament.
ALTER TABLE admin_ref.admin_matches
    ADD COLUMN tournament_id BIGINT NOT NULL REFERENCES game.tournaments (id);

DROP INDEX admin_ref.idx_admin_matches_round_number;
CREATE INDEX idx_admin_matches_tournament_round ON admin_ref.admin_matches (tournament_id, round_number);

-- external_match_id was globally unique under the single-tournament model. Two different
-- tournaments can now legitimately feature the same real-world match in their own round, so
-- uniqueness moves to the (tournament_id, external_match_id) pair instead.
ALTER TABLE admin_ref.admin_matches DROP CONSTRAINT admin_matches_external_match_id_key;
ALTER TABLE admin_ref.admin_matches
    ADD CONSTRAINT admin_matches_tournament_external_match_unique UNIQUE (tournament_id, external_match_id);
CREATE INDEX idx_admin_matches_external_match_id ON admin_ref.admin_matches (external_match_id);
