-- Why Not You server data. Only for people who create an account (to connect an AI or sync Pro).
CREATE TABLE IF NOT EXISTS users (
  id          TEXT PRIMARY KEY,
  google_sub  TEXT UNIQUE,
  email       TEXT,
  created_at  INTEGER NOT NULL,
  pro_until   INTEGER NOT NULL DEFAULT 0,   -- ms epoch; 0 = free. Lifetime = far future.
  pro_product TEXT
);

-- Latest state the phone uploaded (the phone is the source of truth for alarms).
CREATE TABLE IF NOT EXISTS state (
  user_id     TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  alarms      TEXT NOT NULL DEFAULT '[]',     -- JSON array of alarms
  plan        TEXT NOT NULL DEFAULT '',       -- plan for tomorrow
  tomorrow    TEXT NOT NULL DEFAULT '[]',     -- JSON: tomorrow's calendar events (Pro, if shared)
  usage       TEXT NOT NULL DEFAULT '{}',     -- JSON: { "2026-10-16": { "Instagram": 23, ... } }
  limits      TEXT NOT NULL DEFAULT '{}',     -- JSON: { "limitMin": 20, "apps": ["Instagram"], "strict": false }
  updated_at  INTEGER NOT NULL DEFAULT 0
);

-- Changes the AI asked for, waiting for the phone to apply them.
CREATE TABLE IF NOT EXISTS changes (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind        TEXT NOT NULL,                  -- set_plan | create_alarm | update_alarm | delete_alarm | set_app_limit
  payload     TEXT NOT NULL,
  created_at  INTEGER NOT NULL,
  applied_at  INTEGER
);
CREATE INDEX IF NOT EXISTS changes_pending ON changes(user_id, applied_at);

-- "What your AI did" log shown in the app.
CREATE TABLE IF NOT EXISTS actions (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  summary     TEXT NOT NULL,
  created_at  INTEGER NOT NULL
);

-- Phones to wake with a push message when the AI changes something (Firebase Cloud Messaging tokens).
CREATE TABLE IF NOT EXISTS devices (
  token       TEXT PRIMARY KEY,
  user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  updated_at  INTEGER NOT NULL
);

-- Google Play purchases, keyed by purchase token.
CREATE TABLE IF NOT EXISTS purchases (
  token       TEXT PRIMARY KEY,
  user_id     TEXT REFERENCES users(id) ON DELETE SET NULL,
  product     TEXT NOT NULL,
  expiry      INTEGER NOT NULL,               -- ms epoch
  state       TEXT NOT NULL,
  updated_at  INTEGER NOT NULL
);
