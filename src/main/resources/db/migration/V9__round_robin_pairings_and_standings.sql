-- Round-robin format: real head-to-head pairing schedule + league-table standings, replacing
-- the flat individual-accumulation model game.standings was built for (never used by any format
-- in production, and doesn't fit real pairing) — see
-- docs/superpowers/specs/2026-08-18-round-robin-format-design.md.

DROP TABLE game.standings;

CREATE TABLE game.tournament_pairings (
    tournament_id BIGINT NOT NULL REFERENCES game.tournaments (id) ON DELETE CASCADE,
    round_number INT NOT NULL,
    player_a_user_id BIGINT NOT NULL REFERENCES game.users (id) ON DELETE CASCADE,
    player_b_user_id BIGINT REFERENCES game.users (id) ON DELETE CASCADE,
    PRIMARY KEY (tournament_id, round_number, player_a_user_id)
);

CREATE INDEX idx_tournament_pairings_tournament_round ON game.tournament_pairings (tournament_id, round_number);

-- One row per player per round: for a real pairing (A, B), both (A, B) and (B, A) are stored so
-- "who is my opponent this round" is a single-row lookup by player_a_user_id. player_b_user_id
-- IS NULL marks a bye/BOT round for player_a (no reverse row in that case).
COMMENT ON TABLE game.tournament_pairings IS 'One row per player per round_number; symmetric pair rows for real matchups, single row with NULL player_b for a bye.';

CREATE TABLE game.round_robin_standings (
    tournament_id BIGINT NOT NULL REFERENCES game.tournaments (id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES game.users (id) ON DELETE CASCADE,
    league_points INT NOT NULL DEFAULT 0,
    wins INT NOT NULL DEFAULT 0,
    draws INT NOT NULL DEFAULT 0,
    losses INT NOT NULL DEFAULT 0,
    goals_for INT NOT NULL DEFAULT 0,
    goals_against INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tournament_id, user_id)
);

CREATE INDEX idx_round_robin_standings_tournament_id ON game.round_robin_standings (tournament_id);
