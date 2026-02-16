-- Seed data required by the bootcamp rubric (safe, local-only)
-- Admin account is created by CommandLineRunner at startup.

-- Safety guard for reused local DBs: if the legacy column exists, set defaults.
-- If the column doesn't exist, these statements will be skipped (continue-on-error).
ALTER TABLE users ALTER COLUMN auto_payout_enabled SET DEFAULT false;
UPDATE users SET auto_payout_enabled = false WHERE auto_payout_enabled IS NULL;
ALTER TABLE users ALTER COLUMN same_day_payout_enabled SET DEFAULT false;
UPDATE users SET same_day_payout_enabled = false WHERE same_day_payout_enabled IS NULL;

INSERT INTO users (
    display_name,
    email,
    role,
    promo_credits,
    referral_credits,
    referral_count,
    paused,
    banned
) VALUES
('Demo Artist', 'artist@drilldex.local', 'ARTIST', 0, 0, 0, false, false),
('Demo User',   'user@drilldex.local',   'USER',   0, 0, 0, false, false);
