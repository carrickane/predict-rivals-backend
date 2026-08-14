CREATE TABLE game.tournaments (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    season VARCHAR(32) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL
);

CREATE TABLE game.tournament_memberships (
    user_id BIGINT NOT NULL REFERENCES game.users (id) ON DELETE CASCADE,
    tournament_id BIGINT NOT NULL REFERENCES game.tournaments (id) ON DELETE CASCADE,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, tournament_id)
);

CREATE TABLE game.rounds (
    id BIGSERIAL PRIMARY KEY,
    tournament_id BIGINT NOT NULL REFERENCES game.tournaments (id) ON DELETE CASCADE,
    round_number INT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'scheduled'
        CHECK (status IN ('scheduled', 'live', 'finished')),
    UNIQUE (tournament_id, round_number)
);

CREATE INDEX idx_rounds_tournament_id ON game.rounds (tournament_id);
