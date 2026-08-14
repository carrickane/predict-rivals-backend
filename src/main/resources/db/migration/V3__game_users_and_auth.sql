CREATE TABLE game.users (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    email VARCHAR(256) UNIQUE,
    avatar_url VARCHAR(512),
    role VARCHAR(16) NOT NULL DEFAULT 'player'
        CHECK (role IN ('player', 'admin')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE game.auth_identities (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES game.users (id) ON DELETE CASCADE,
    provider VARCHAR(16) NOT NULL
        CHECK (provider IN ('email', 'google', 'facebook')),
    provider_user_id VARCHAR(256) NOT NULL,
    password_hash VARCHAR(256),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (provider, provider_user_id)
);

CREATE INDEX idx_auth_identities_user_id ON game.auth_identities (user_id);

-- Refresh tokens, hashed at rest, rotated on each use.
CREATE TABLE game.refresh_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES game.users (id) ON DELETE CASCADE,
    token_hash VARCHAR(256) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_refresh_tokens_user_id ON game.refresh_tokens (user_id);
