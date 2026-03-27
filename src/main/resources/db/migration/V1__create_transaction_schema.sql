-- ─────────────────────────────────────────────────────────────────────────────
-- V1__create_transaction_schema.sql
-- Transaction Service — initial schema
-- MySQL 8.0.13+
-- ─────────────────────────────────────────────────────────────────────────────

-- Main transaction table

CREATE TABLE transactions (
    id                   VARCHAR(36)     NOT NULL,
    sender_user_id       VARCHAR(36)     NOT NULL,
    sender_wallet_id     VARCHAR(36)     NOT NULL,
    receiver_user_id     VARCHAR(36),
    receiver_wallet_id   VARCHAR(36),
    type                 VARCHAR(30)     NOT NULL,
    status               VARCHAR(30)     NOT NULL DEFAULT 'INITIATED',
    amount               DECIMAL(19, 4)  NOT NULL,
    currency             VARCHAR(3)      NOT NULL DEFAULT 'INR',
    description          VARCHAR(500),
    idempotency_key      VARCHAR(36)     NOT NULL,
    fraud_score          DECIMAL(5, 4),
    failure_reason       VARCHAR(100),
    created_at           DATETIME(6)     NOT NULL DEFAULT NOW(6),
    updated_at           DATETIME(6)     NOT NULL DEFAULT NOW(6) ON UPDATE NOW(6),
    completed_at         DATETIME(6),
    version              BIGINT          NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    UNIQUE KEY uk_txn_idempotency_key (idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_txn_sender_user     ON transactions (sender_user_id);
CREATE INDEX idx_txn_receiver_user   ON transactions (receiver_user_id);
CREATE INDEX idx_txn_sender_wallet   ON transactions (sender_wallet_id);
CREATE INDEX idx_txn_status          ON transactions (status);
CREATE INDEX idx_txn_created_at      ON transactions (created_at);
CREATE INDEX idx_txn_idempotency_key ON transactions (idempotency_key);


-- Saga step log — one row per Saga step per transaction
CREATE TABLE saga_state (
    id                       BIGINT       NOT NULL AUTO_INCREMENT,
    transaction_id           VARCHAR(36)  NOT NULL,
    step                     VARCHAR(30)  NOT NULL,
    step_status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    command_idempotency_key  VARCHAR(80)  NOT NULL,
    failure_detail           VARCHAR(500),
    started_at               DATETIME(6)  NOT NULL DEFAULT NOW(6),
    completed_at             DATETIME(6),

    PRIMARY KEY (id),
    CONSTRAINT fk_saga_transaction FOREIGN KEY (transaction_id)
        REFERENCES transactions (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Composite index for timeout detection job
-- Query: WHERE step_status = 'PENDING' AND started_at < NOW() - INTERVAL 5 MINUTE
CREATE INDEX idx_saga_pending     ON saga_state (step_status, started_at);
CREATE INDEX idx_saga_txn_id      ON saga_state (transaction_id);
CREATE INDEX idx_saga_step_status ON saga_state (step_status);
