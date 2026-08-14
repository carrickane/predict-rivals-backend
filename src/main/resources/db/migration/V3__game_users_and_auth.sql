CREATE TABLE game.users (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    email VARCHAR(256) UNIQUE,
    phone VARCHAR(32) UNIQUE,
    avatar_url VARCHAR(512),
    role VARCHAR(16) NOT NULL DEFAULT 'player'
        CHECK (role IN ('player', 'admin')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE game.auth_identities (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES game.users (id) ON DELETE CASCADE,
    provider VARCHAR(16) NOT NULL
        CHECK (provider IN ('email', 'google', 'apple', 'facebook', 'phone')),
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

-- Phone/SMS OTP verification, rate-limited and single-use at the application layer.
CREATE TABLE game.phone_verification_codes (
    id BIGSERIAL PRIMARY KEY,
    phone VARCHAR(32) NOT NULL,
    code_hash VARCHAR(256) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    consumed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_phone_verification_codes_phone ON game.phone_verification_codes (phone);
