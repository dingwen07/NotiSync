CREATE TABLE applications (
    application_id TEXT PRIMARY KEY NOT NULL,
    registration TEXT NOT NULL
);

CREATE TABLE profile (
    id INTEGER PRIMARY KEY NOT NULL,
    publication TEXT NOT NULL
);

CREATE TABLE dedup (
    message_id VARCHAR(512) PRIMARY KEY NOT NULL,
    recorded_at BIGINT NOT NULL
);

CREATE INDEX dedup_oldest ON dedup (recorded_at, message_id);

INSERT INTO profile (id, publication) VALUES (1, '{}');
