CREATE TABLE game.predictions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES game.users (id) ON DELETE CASCADE,
    match_id BIGINT NOT NULL REFERENCES admin_ref.admin_matches (id) ON DELETE CASCADE,
    round_id BIGINT NOT NULL REFERENCES game.rounds (id) ON DELETE CASCADE,
    predicted_home_score INT NOT NULL CHECK (predicted_home_score BETWEEN 0 AND 20),
    predicted_away_score INT NOT NULL CHECK (predicted_away_score BETWEEN 0 AND 20),
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_late BOOLEAN NOT NULL DEFAULT FALSE,
    points_awarded INT,
    is_exact BOOLEAN,
    UNIQUE (user_id, match_id)
);

CREATE INDEX idx_predictions_round_id ON game.predictions (round_id);
CREATE INDEX idx_predictions_match_id ON game.predictions (match_id);
CREATE INDEX idx_predictions_user_id ON game.predictions (user_id);

-- Per-user, per-round snapshot. Recomputed live while the round is in progress,
-- frozen (is_frozen = true) once all of the round's matches are finished; the
-- remainder below the next multiple of 3 points is discarded at freeze time.
CREATE TABLE game.round_scores (
    user_id BIGINT NOT NULL REFERENCES game.users (id) ON DELETE CASCADE,
    round_id BIGINT NOT NULL REFERENCES game.rounds (id) ON DELETE CASCADE,
    points_raw INT NOT NULL DEFAULT 0,
    exact_count INT NOT NULL DEFAULT 0,
    goals_awarded INT NOT NULL DEFAULT 0,
    is_frozen BOOLEAN NOT NULL DEFAULT FALSE,
    computed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, round_id)
);

CREATE TABLE game.standings (
    user_id BIGINT NOT NULL REFERENCES game.users (id) ON DELETE CASCADE,
    tournament_id BIGINT NOT NULL REFERENCES game.tournaments (id) ON DELETE CASCADE,
    total_goals INT NOT NULL DEFAULT 0,
    total_exact_scores INT NOT NULL DEFAULT 0,
    rounds_played INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, tournament_id)
);

CREATE INDEX idx_standings_tournament_id ON game.standings (tournament_id);
