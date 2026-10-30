CREATE SEQUENCE server_revision AS bigint MINVALUE 1 NO CYCLE;

CREATE TABLE users (
    user_id uuid PRIMARY KEY,
    active boolean NOT NULL DEFAULT true,
    revision bigint NOT NULL DEFAULT nextval('server_revision') CHECK (revision > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE identities (
    issuer text COLLATE "C" NOT NULL CHECK (octet_length(issuer) BETWEEN 1 AND 1024),
    subject text COLLATE "C" NOT NULL CHECK (length(subject) BETWEEN 1 AND 255),
    user_id uuid NOT NULL REFERENCES users(user_id),
    PRIMARY KEY (issuer, subject)
);
CREATE INDEX identities_user_id ON identities(user_id);

CREATE TABLE devices (
    device_id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(user_id),
    revision bigint NOT NULL DEFAULT nextval('server_revision') CHECK (revision > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX devices_user_id ON devices(user_id);
