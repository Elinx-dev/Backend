-- State-driven configuration. Nothing that varies between states lives in code.
-- Reference: SLATE Database Design §3

CREATE TABLE cfg.state (
  id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code          CHAR(2) NOT NULL UNIQUE,
  state_name          TEXT NOT NULL,
  locale              TEXT NOT NULL DEFAULT 'en-IN',
  languages           TEXT[] NOT NULL DEFAULT '{en}',
  timezone            TEXT NOT NULL DEFAULT 'Asia/Kolkata',
  extent_units        TEXT[] NOT NULL,
  default_extent_unit TEXT NOT NULL,
  currency            CHAR(3) NOT NULL DEFAULT 'INR',
  numeric_state_id    INT NOT NULL,            -- on-chain stateId (33 = TN, 29 = KA)
  active              BOOLEAN NOT NULL DEFAULT TRUE,
  onboarded_on        DATE
);

CREATE TABLE cfg.state_module_config (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code        CHAR(2) NOT NULL REFERENCES cfg.state(state_code),
  module            TEXT NOT NULL CHECK (module IN
                      ('REGISTRATION','SURVEY','REVENUE','RULE_CHECK','BLOCKCHAIN','PUBLIC_VIEW')),
  enabled           BOOLEAN NOT NULL DEFAULT TRUE,
  mode              TEXT NOT NULL CHECK (mode IN ('FACILITATE','RECORD','DISABLED')),
  owner_department  TEXT,
  sla_days          INT,
  notes             TEXT,
  effective_from    DATE NOT NULL,
  effective_to      DATE,
  UNIQUE (state_code, module, effective_from)
);

CREATE TABLE cfg.workflow_definition (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code      CHAR(2) NOT NULL REFERENCES cfg.state(state_code),
  deed_type_code  TEXT NOT NULL,
  workflow_code   TEXT NOT NULL,
  version         INT NOT NULL,
  status          TEXT NOT NULL CHECK (status IN ('DRAFT','PUBLISHED','RETIRED')),
  published_at    TIMESTAMPTZ,
  published_by    BIGINT,
  effective_from  DATE NOT NULL,
  effective_to    DATE,
  UNIQUE (state_code, deed_type_code, workflow_code, version)
);

-- Invariant that is correctness, not policy: no configuration may create a
-- REJECTED, RETURNED or CANCELLED status anywhere in SLATE.
CREATE TABLE cfg.workflow_stage (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  workflow_id     BIGINT NOT NULL REFERENCES cfg.workflow_definition(id) ON DELETE CASCADE,
  seq             INT NOT NULL,
  stage_code      TEXT NOT NULL,
  stage_label     TEXT NOT NULL,
  status_on_enter TEXT NOT NULL CHECK (status_on_enter NOT IN ('REJECTED','RETURNED','CANCELLED')),
  owner_role      TEXT NOT NULL,
  optional        BOOLEAN NOT NULL DEFAULT FALSE,
  skip_condition  TEXT,
  entry_guard     TEXT,
  exit_guard      TEXT,
  sla_hours       INT,
  ui_route        TEXT,
  UNIQUE (workflow_id, seq),
  UNIQUE (workflow_id, stage_code)
);

CREATE TABLE cfg.workflow_transition (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  workflow_id     BIGINT NOT NULL REFERENCES cfg.workflow_definition(id) ON DELETE CASCADE,
  from_status     TEXT NOT NULL,
  to_status       TEXT NOT NULL CHECK (to_status NOT IN ('REJECTED','RETURNED','CANCELLED')),
  action_code     TEXT NOT NULL,
  allowed_roles   TEXT[] NOT NULL,
  guard_expr      TEXT,
  effect_expr     TEXT,
  requires_reason BOOLEAN NOT NULL DEFAULT FALSE,
  emits_event     TEXT,
  UNIQUE (workflow_id, from_status, action_code)
);

CREATE TABLE cfg.option_set (
  code        TEXT PRIMARY KEY,
  description TEXT NOT NULL
);

CREATE TABLE cfg.option_value (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  option_set_code TEXT NOT NULL REFERENCES cfg.option_set(code),
  state_code      TEXT NOT NULL DEFAULT '*',
  value_code      TEXT NOT NULL,
  label           TEXT NOT NULL,
  label_i18n      JSONB,
  sort_order      INT NOT NULL DEFAULT 0,
  attributes      JSONB,
  active          BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE (option_set_code, state_code, value_code)
);

CREATE TABLE cfg.field_definition (
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  entity           TEXT NOT NULL,
  field_code       TEXT NOT NULL,
  data_type        TEXT NOT NULL CHECK (data_type IN
                     ('TEXT','INT','DECIMAL','DATE','BOOL','ENUM','FILE','GEO','MONEY')),
  db_column        TEXT,
  pii              BOOLEAN NOT NULL DEFAULT FALSE,
  public_view_safe BOOLEAN NOT NULL DEFAULT FALSE,
  UNIQUE (entity, field_code)
);

CREATE TABLE cfg.field_config (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code      TEXT NOT NULL,
  deed_type_code  TEXT NOT NULL DEFAULT '*',
  stage_code      TEXT NOT NULL DEFAULT '*',
  role_code       TEXT NOT NULL DEFAULT '*',
  field_id        BIGINT NOT NULL REFERENCES cfg.field_definition(id),
  visible         BOOLEAN NOT NULL DEFAULT TRUE,
  required        BOOLEAN NOT NULL DEFAULT FALSE,
  editable        BOOLEAN NOT NULL DEFAULT TRUE,
  masked          BOOLEAN NOT NULL DEFAULT FALSE,
  label_override  TEXT,
  help_text       TEXT,
  default_value   TEXT,
  regex           TEXT,
  min_value       NUMERIC,
  max_value       NUMERIC,
  min_length      INT,
  max_length      INT,
  option_set_code TEXT REFERENCES cfg.option_set(code),
  display_order   INT,
  ui_group        TEXT,
  effective_from  DATE NOT NULL,
  effective_to    DATE,
  UNIQUE (state_code, deed_type_code, stage_code, role_code, field_id, effective_from)
);

CREATE TABLE cfg.validation_rule (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code      TEXT NOT NULL DEFAULT '*',
  deed_type_code  TEXT NOT NULL DEFAULT '*',
  scope           TEXT NOT NULL,
  rule_code       TEXT NOT NULL,
  expression      TEXT NOT NULL,
  severity        TEXT NOT NULL CHECK (severity IN ('ERROR','WARN','INFO')),
  message         TEXT NOT NULL,
  active          BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE (state_code, deed_type_code, scope, rule_code)
);

CREATE TABLE cfg.numbering_series (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code    CHAR(2) NOT NULL,
  series_code   TEXT NOT NULL,
  pattern       TEXT NOT NULL,
  scope         TEXT NOT NULL CHECK (scope IN ('STATE','DISTRICT','SRO','YEAR')),
  current_value BIGINT NOT NULL DEFAULT 0,
  reset_policy  TEXT CHECK (reset_policy IN ('NEVER','YEARLY','FINANCIAL_YEAR')),
  UNIQUE (state_code, series_code)
);

CREATE TABLE cfg.fee_rule_config (
  id                         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code                 CHAR(2) NOT NULL UNIQUE,
  uses_relationship_category BOOLEAN NOT NULL DEFAULT TRUE,
  uses_transfer_nature       BOOLEAN NOT NULL DEFAULT TRUE,
  default_valuation_basis    TEXT NOT NULL
      CHECK (default_valuation_basis IN ('CONSIDERATION','GUIDELINE_VALUE','HIGHER_OF_BOTH','FLAT')),
  rounding_mode              TEXT NOT NULL DEFAULT 'HALF_UP',
  rounding_unit              NUMERIC(10,2) NOT NULL DEFAULT 1,
  cash_mode_limit            NUMERIC(18,2),
  tds_enabled                BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE cfg.rule_engine_config (
  id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code           CHAR(2) NOT NULL,
  engine               TEXT NOT NULL CHECK (engine IN ('EC','REVENUE_OWNERSHIP')),
  enabled              BOOLEAN NOT NULL DEFAULT TRUE,
  advisory_only        BOOLEAN NOT NULL DEFAULT TRUE,
  run_at_stage         TEXT NOT NULL DEFAULT 'RULE_CHECK',
  ec_lookback_years    INT DEFAULT 30,
  apply_survey_lineage BOOLEAN DEFAULT TRUE,
  name_match_policy    TEXT DEFAULT 'FORMATTING_ONLY_AUTOMATCH',
  supported_land_types TEXT[] DEFAULT '{RURAL,NATHAM}',
  extent_tolerance_pct NUMERIC(5,2) DEFAULT 2.00,
  timeout_ms           INT DEFAULT 15000,
  on_timeout_outcome   TEXT NOT NULL DEFAULT 'NOT_CHECKED',
  UNIQUE (state_code, engine)
);

CREATE TABLE cfg.connector_config (
  id                         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code                 CHAR(2) NOT NULL,
  connector_code             TEXT NOT NULL,
  impl                       TEXT NOT NULL,
  base_url                   TEXT,
  auth_type                  TEXT CHECK (auth_type IN ('NONE','API_KEY','MTLS','OAUTH2')),
  secret_ref                 TEXT,
  timeout_ms                 INT DEFAULT 10000,
  retry_count                INT DEFAULT 2,
  circuit_breaker_threshold  INT DEFAULT 5,
  active                     BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE (state_code, connector_code)
);

CREATE TABLE cfg.feature_flag (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code  TEXT NOT NULL DEFAULT '*',
  flag_code   TEXT NOT NULL,
  enabled     BOOLEAN NOT NULL DEFAULT FALSE,
  description TEXT,
  UNIQUE (state_code, flag_code)
);

CREATE TABLE cfg.config_version (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code      CHAR(2) NOT NULL,
  version         INT NOT NULL,
  status          TEXT NOT NULL CHECK (status IN ('DRAFT','PUBLISHED','ROLLED_BACK')),
  snapshot        JSONB NOT NULL,
  snapshot_sha256 BYTEA NOT NULL,
  published_by    BIGINT,
  published_at    TIMESTAMPTZ,
  anchored_tx_hash TEXT,
  UNIQUE (state_code, version)
);

CREATE TABLE cfg.config_change_log (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code  CHAR(2) NOT NULL,
  table_name  TEXT NOT NULL,
  row_id      BIGINT,
  operation   TEXT,
  before_json JSONB,
  after_json  JSONB,
  changed_by  BIGINT,
  changed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE cfg.chain_network_config (
  id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code             CHAR(2) NOT NULL,
  network_code           TEXT NOT NULL,
  chain_id               BIGINT NOT NULL,
  consensus              TEXT NOT NULL CHECK (consensus IN ('QBFT','IBFT2','CLIQUE','DEV')),
  rpc_url                TEXT NOT NULL,
  ws_url                 TEXT,
  fallback_rpc_urls      TEXT[],
  gas_price              NUMERIC(38,0) NOT NULL DEFAULT 0,
  gas_limit              BIGINT NOT NULL DEFAULT 8000000,
  block_time_seconds     INT NOT NULL DEFAULT 2,
  finality_confirmations INT NOT NULL DEFAULT 1,
  explorer_url           TEXT,
  active                 BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE (state_code, network_code)
);

CREATE TABLE cfg.chain_contract (
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  network_code     TEXT NOT NULL,
  contract_code    TEXT NOT NULL,
  address          TEXT NOT NULL,
  abi_sha256       BYTEA,
  deployed_block   BIGINT,
  deployed_at      TIMESTAMPTZ,
  deployer_address TEXT,
  source_commit    TEXT,
  active           BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE (network_code, contract_code, address)
);

CREATE TABLE cfg.chain_validator (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  network_code      TEXT NOT NULL,
  node_name         TEXT NOT NULL,
  department        TEXT NOT NULL,
  enode_url         TEXT,
  validator_address TEXT NOT NULL,
  is_bootnode       BOOLEAN DEFAULT FALSE,
  permissioned      BOOLEAN NOT NULL DEFAULT TRUE,
  active            BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE (network_code, validator_address)
);

CREATE TABLE cfg.chain_endorser (
  id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  state_code           CHAR(2) NOT NULL,
  operation            TEXT NOT NULL CHECK (operation IN ('MINT','UPDATE','SPLIT','ANNOTATE','ANCHOR')),
  required_departments TEXT[] NOT NULL,
  min_signatures       INT NOT NULL DEFAULT 1,
  UNIQUE (state_code, operation)
);

CREATE INDEX ix_field_config_lookup ON cfg.field_config (state_code, deed_type_code, stage_code, role_code);
CREATE INDEX ix_option_value_set ON cfg.option_value (option_set_code, state_code);
CREATE INDEX ix_workflow_stage_wf ON cfg.workflow_stage (workflow_id);
CREATE INDEX ix_workflow_transition_wf ON cfg.workflow_transition (workflow_id);
