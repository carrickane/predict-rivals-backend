-- Audit trail for admin actions that mutate scoring-relevant data
-- (e.g. manual score overrides), per the design's auditability requirement.
CREATE TABLE game.admin_audit_log (
    id BIGSERIAL PRIMARY KEY,
    admin_user_id BIGINT NOT NULL REFERENCES game.users (id),
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    before_value TEXT,
    after_value TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_admin_audit_log_target ON game.admin_audit_log (target_type, target_id);
