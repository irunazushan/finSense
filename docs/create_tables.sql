CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS recommendations;

CREATE TABLE core.users (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE core.accounts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    account_number VARCHAR(50) NOT NULL UNIQUE,
    account_type VARCHAR(50) NOT NULL DEFAULT 'debit',
    currency VARCHAR(3) NOT NULL DEFAULT 'RUB',
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_accounts_user_id ON core.accounts(user_id);

CREATE TABLE core.transactions (
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
    classifier_source VARCHAR(10), -- 'RULE', 'ML', 'LLM'
    classifier_confidence REAL,
    classified_at TIMESTAMP
);

CREATE INDEX idx_transactions_user_account ON core.transactions(user_id, account_id, transaction_date);
CREATE INDEX idx_transactions_classifier ON core.transactions(classifier_source, classified_at);
CREATE INDEX idx_transactions_category ON core.transactions(category);
CREATE INDEX idx_transactions_status ON core.transactions(status);

CREATE TABLE recommendations.recommendations (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    advice_data JSONB NOT NULL
);

CREATE INDEX idx_recommendations_user_created ON recommendations.recommendations(user_id, created_at DESC);
CREATE INDEX idx_recommendations_advice_gin ON recommendations.recommendations USING GIN (advice_data);