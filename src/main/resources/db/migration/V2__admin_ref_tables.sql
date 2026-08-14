CREATE TABLE admin_ref.admin_matches (
    id BIGSERIAL PRIMARY KEY,
    external_match_id VARCHAR(64) NOT NULL UNIQUE,
    league VARCHAR(128) NOT NULL,
    home_team VARCHAR(128) NOT NULL,
    away_team VARCHAR(128) NOT NULL,
    kickoff_at TIMESTAMPTZ NOT NULL,
    round_number INT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'scheduled'
        CHECK (status IN ('scheduled', 'live', 'finished')),
    home_score INT,
    away_score INT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_admin_matches_round_number ON admin_ref.admin_matches (round_number);
CREATE INDEX idx_admin_matches_status ON admin_ref.admin_matches (status);
