CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS recommendations;

CREATE TABLE IF NOT EXISTS core.users (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS core.accounts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    number VARCHAR(50) NOT NULL UNIQUE,
    type VARCHAR(50) NOT NULL DEFAULT 'debit',
    currency VARCHAR(3) NOT NULL DEFAULT 'RUB',
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS core.transactions (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES core.accounts(id) ON DELETE RESTRICT,
    user_id UUID NOT NULL REFERENCES core.users(id),
    amount DECIMAL(19,4) NOT NULL,
    description TEXT,
    merchant_name VARCHAR(255),
    mcc_code VARCHAR(10),
    transaction_date TIMESTAMP NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'NEW',
    category VARCHAR(100),
    classifier_source VARCHAR(10),
    classifier_confidence REAL,
    classified_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS recommendations.recommendations (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    completed_at TIMESTAMP NULL,
    advice_data JSONB NULL,
    request_params JSONB NULL,
    error TEXT NULL
);
