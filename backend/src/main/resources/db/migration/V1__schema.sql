-- Payment settlement platform: ACH (NACHA) and wires (ISO 20022 / Fedwire Funds).
-- Money: ACH amounts in cents (BIGINT, as in NACHA); wire amounts as NUMERIC(18,2) (as in ISO 20022).
-- Account numbers are never stored in clear text: AES-GCM ciphertext + HMAC fingerprint + last 4 digits.

-- ============================== ACH ==============================

CREATE TABLE ach_files (
    id                    UUID PRIMARY KEY,
    direction             VARCHAR(10)  NOT NULL,              -- INBOUND (received for origination) | OUTBOUND (to FedACH)
    file_name             VARCHAR(200) NOT NULL,
    content_sha256        CHAR(64)     NOT NULL,
    content               TEXT         NOT NULL,              -- raw NACHA file (needed for re-processing and audit)
    immediate_destination VARCHAR(10)  NOT NULL,
    immediate_origin      VARCHAR(10)  NOT NULL,
    file_creation_date    VARCHAR(6)   NOT NULL,              -- YYMMDD
    file_creation_time    VARCHAR(4),                         -- HHMM
    file_id_modifier      CHAR(1)      NOT NULL,
    batch_count           INT          NOT NULL DEFAULT 0,
    entry_count           INT          NOT NULL DEFAULT 0,
    total_debit_cents     BIGINT       NOT NULL DEFAULT 0,
    total_credit_cents    BIGINT       NOT NULL DEFAULT 0,
    status                VARCHAR(20)  NOT NULL,              -- RECEIVED | PROCESSING | PROCESSED | REJECTED
    reject_reason         TEXT,
    job_execution_id      BIGINT,
    received_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at          TIMESTAMPTZ
);
CREATE UNIQUE INDEX ux_ach_files_sha ON ach_files (direction, content_sha256);
-- NACHA: a file is uniquely identified by origin + creation date/time + File ID Modifier.
CREATE UNIQUE INDEX ux_ach_files_nacha_id ON ach_files (direction, immediate_origin, file_creation_date,
                                                         COALESCE(file_creation_time, ''), file_id_modifier)
    WHERE status <> 'REJECTED';

CREATE TABLE ach_batches (
    id                   UUID PRIMARY KEY,
    file_id              UUID         NOT NULL REFERENCES ach_files (id),
    batch_number         INT          NOT NULL,
    service_class_code   CHAR(3)      NOT NULL,              -- 200 mixed, 220 credits, 225 debits
    company_name         VARCHAR(16)  NOT NULL,
    company_id           VARCHAR(10)  NOT NULL,
    sec_code             CHAR(3)      NOT NULL,              -- PPD, CCD, WEB, ...
    entry_description    VARCHAR(10)  NOT NULL,
    effective_entry_date DATE,
    odfi_id              CHAR(8)      NOT NULL,
    entry_count          INT          NOT NULL,
    total_debit_cents    BIGINT       NOT NULL,
    total_credit_cents   BIGINT       NOT NULL,
    UNIQUE (file_id, batch_number)
);

CREATE TABLE ach_settlement_batches (
    id                  UUID PRIMARY KEY,
    network             VARCHAR(20)  NOT NULL DEFAULT 'FEDACH',
    settlement_window   VARCHAR(20)  NOT NULL,               -- SAME_DAY_1 | SAME_DAY_2 | SAME_DAY_3 | NEXT_DAY
    settlement_date     DATE         NOT NULL,
    entry_count         INT          NOT NULL,
    total_debit_cents   BIGINT       NOT NULL,
    total_credit_cents  BIGINT       NOT NULL,
    net_cents           BIGINT       NOT NULL,                -- credits - debits (positive = net funds out)
    outbound_file_id    UUID REFERENCES ach_files (id),
    status              VARCHAR(20)  NOT NULL,                -- CREATED | TRANSMITTED | ACKNOWLEDGED | SETTLED | FAILED
    network_reference   VARCHAR(64),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    transmitted_at      TIMESTAMPTZ,
    settled_at          TIMESTAMPTZ,
    UNIQUE (settlement_window, settlement_date, network)
);

CREATE TABLE ach_entries (
    id                    UUID PRIMARY KEY,
    file_id               UUID         NOT NULL REFERENCES ach_files (id),
    batch_id              UUID         NOT NULL REFERENCES ach_batches (id),
    trace_number          CHAR(15)     NOT NULL,
    transaction_code      CHAR(2)      NOT NULL,             -- 22/32 credit, 27/37 debit, 23/28/33/38 prenote
    direction             VARCHAR(6)   NOT NULL,             -- CREDIT | DEBIT
    rdfi_routing          CHAR(9)      NOT NULL,
    account_ciphertext    TEXT         NOT NULL,
    account_fingerprint   CHAR(64)     NOT NULL,
    account_last4         VARCHAR(4)   NOT NULL,
    amount_cents          BIGINT       NOT NULL CHECK (amount_cents >= 0),
    individual_id         VARCHAR(15),
    individual_name       VARCHAR(22)  NOT NULL,
    sec_code              CHAR(3)      NOT NULL,
    company_id            VARCHAR(10)  NOT NULL,
    company_name          VARCHAR(16)  NOT NULL,
    effective_date        DATE         NOT NULL,
    same_day              BOOLEAN      NOT NULL DEFAULT FALSE,
    status                VARCHAR(20)  NOT NULL,             -- ACCEPTED | HELD_FRAUD | REJECTED | DUPLICATE | SCHEDULED | SENT | SETTLED | RETURNED
    reject_reason         TEXT,
    fraud_score           INT          NOT NULL DEFAULT 0,
    fraud_reasons         TEXT[]       NOT NULL DEFAULT '{}',
    duplicate_of          UUID,
    settlement_batch_id   UUID REFERENCES ach_settlement_batches (id),
    return_code           CHAR(3),                           -- NACHA return reason, e.g. R01
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    settled_at            TIMESTAMPTZ
);
CREATE UNIQUE INDEX ux_ach_entries_trace ON ach_entries (trace_number) WHERE status <> 'DUPLICATE';
CREATE INDEX ix_ach_entries_status ON ach_entries (status, effective_date);
CREATE INDEX ix_ach_entries_created ON ach_entries (created_at);
CREATE INDEX ix_ach_entries_dupe ON ach_entries (company_id, account_fingerprint, amount_cents, effective_date);

-- ============================== Wires ==============================

CREATE TABLE wires (
    id                      UUID PRIMARY KEY,
    idempotency_key         VARCHAR(100) NOT NULL UNIQUE,
    request_hash            CHAR(64)     NOT NULL,           -- detects reuse of an Idempotency-Key with a different body
    uetr                    UUID         NOT NULL,           -- ISO 20022 Unique End-to-end Transaction Reference
    msg_id                  VARCHAR(35)  NOT NULL,
    instruction_id          VARCHAR(35)  NOT NULL,
    end_to_end_id           VARCHAR(35)  NOT NULL,
    amount                  NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    currency                CHAR(3)      NOT NULL,
    value_date              DATE         NOT NULL,
    debtor_name             VARCHAR(140) NOT NULL,
    debtor_account_ct       TEXT         NOT NULL,
    debtor_account_fp       CHAR(64)     NOT NULL,
    debtor_account_last4    VARCHAR(4)   NOT NULL,
    debtor_agent_aba        CHAR(9)      NOT NULL,
    creditor_name           VARCHAR(140) NOT NULL,
    creditor_account_ct     TEXT         NOT NULL,
    creditor_account_fp     CHAR(64)     NOT NULL,
    creditor_account_last4  VARCHAR(4)   NOT NULL,
    creditor_agent_aba      CHAR(9)      NOT NULL,
    remittance_info         VARCHAR(140),
    channel                 VARCHAR(20)  NOT NULL,           -- API_JSON | API_ISO20022 | MAINFRAME_BRIDGE
    status                  VARCHAR(20)  NOT NULL,
    status_reason           TEXT,
    fraud_score             INT          NOT NULL DEFAULT 0,
    fraud_reasons           TEXT[]       NOT NULL DEFAULT '{}',
    sanctions_hit           TEXT,
    duplicate_of            UUID,
    pacs008                 TEXT         NOT NULL,
    pacs002                 TEXT,
    network                 VARCHAR(20)  NOT NULL DEFAULT 'FEDWIRE',
    network_reference       VARCHAR(64),                     -- Fedwire IMAD/OMAD
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    settled_at              TIMESTAMPTZ,
    version                 INT          NOT NULL DEFAULT 0  -- optimistic locking for state transitions
);
CREATE UNIQUE INDEX ux_wires_uetr ON wires (uetr) WHERE status <> 'REJECTED';
CREATE INDEX ix_wires_status ON wires (status, created_at);
CREATE INDEX ix_wires_dupe ON wires (debtor_account_fp, creditor_account_fp, amount, created_at);
CREATE INDEX ix_wires_created ON wires (created_at);

-- ============================== Cross-rail ==============================

-- Immutable audit trail of every state transition.
CREATE TABLE payment_events (
    id          BIGSERIAL PRIMARY KEY,
    rail        VARCHAR(10)  NOT NULL,
    payment_id  UUID         NOT NULL,
    from_status VARCHAR(20),
    to_status   VARCHAR(20)  NOT NULL,
    actor       VARCHAR(100) NOT NULL,
    detail      TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_payment_events_payment ON payment_events (payment_id, id);

CREATE TABLE fraud_alerts (
    id           UUID PRIMARY KEY,
    rail         VARCHAR(10)  NOT NULL,
    payment_id   UUID         NOT NULL,
    score        INT          NOT NULL,
    action       VARCHAR(10)  NOT NULL,                     -- REVIEW | BLOCK
    rules        TEXT[]       NOT NULL,
    amount       NUMERIC(18,2) NOT NULL,
    summary      TEXT         NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'OPEN',      -- OPEN | CLEARED | CONFIRMED_FRAUD
    resolved_by  VARCHAR(100),
    resolved_at  TIMESTAMPTZ,
    note         TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_fraud_alerts_status ON fraud_alerts (status, created_at DESC);

CREATE TABLE duplicate_events (
    id            BIGSERIAL PRIMARY KEY,
    rail          VARCHAR(10)  NOT NULL,
    kind          VARCHAR(30)  NOT NULL,   -- ACH_FILE | ACH_TRACE | ACH_ENTRY_MATCH | WIRE_IDEMPOTENT_REPLAY | WIRE_UETR | WIRE_MATCH
    payment_id    UUID,
    duplicate_of  UUID,
    amount        NUMERIC(18,2),
    detail        TEXT         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_duplicate_events_created ON duplicate_events (created_at DESC);

-- Sanctions screening list (load OFAC SDN data in production; sample entries in V2).
CREATE TABLE sanctions_entries (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(200) NOT NULL,
    program    VARCHAR(40)  NOT NULL,
    source     VARCHAR(40)  NOT NULL
);

-- Transactional outbox: settlement hand-off is written in the same transaction as the state change.
CREATE TABLE outbox (
    id              BIGSERIAL PRIMARY KEY,
    aggregate_type  VARCHAR(20)  NOT NULL,
    aggregate_id    UUID         NOT NULL,
    event_type      VARCHAR(40)  NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',  -- PENDING | DONE | FAILED
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_outbox_pending ON outbox (status, next_attempt_at);

-- ShedLock: distributed lock so only one instance runs a scheduled sweep.
CREATE TABLE shedlock (
    name       VARCHAR(64)  PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
