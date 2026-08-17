-- Multi-tournament rework: tournaments are now user-created and joined by code, not a single
-- global date-ranged tournament. Both tables are empty at this point (never used in production),
-- so this is a plain add/drop rather than a backfill.

ALTER TABLE game.tournaments DROP COLUMN season;
ALTER TABLE game.tournaments DROP COLUMN start_date;
ALTER TABLE game.tournaments DROP COLUMN end_date;

ALTER TABLE game.tournaments ADD COLUMN owner_user_id BIGINT NOT NULL REFERENCES game.users (id);
ALTER TABLE game.tournaments ADD COLUMN join_code VARCHAR(8) NOT NULL;
ALTER TABLE game.tournaments ADD CONSTRAINT tournaments_join_code_unique UNIQUE (join_code);
ALTER TABLE game.tournaments ADD COLUMN player_limit INT NOT NULL CHECK (player_limit BETWEEN 2 AND 50);

-- Only 'solo_points' has an implemented code path; 'round_robin' / 'playoff' are reserved so a
-- later phase doesn't need another migration just to add the format names.
ALTER TABLE game.tournaments ADD COLUMN format VARCHAR(16) NOT NULL DEFAULT 'solo_points'
    CHECK (format IN ('solo_points', 'round_robin', 'playoff'));

-- open = accepting joins; active = started, matches/rounds can now be created.
ALTER TABLE game.tournaments ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'open'
    CHECK (status IN ('open', 'active'));

ALTER TABLE game.tournaments ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX idx_tournaments_owner_user_id ON game.tournaments (owner_user_id);
CREATE INDEX idx_tournaments_join_code ON game.tournaments (join_code);
