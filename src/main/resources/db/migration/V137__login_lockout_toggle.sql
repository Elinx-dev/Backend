-- OWASP: 5 wrong passwords lock an account, so anyone knowing a username can lock an officer out.
-- The lock now expires (locked_until) and a state admin can switch it off per user.
ALTER TABLE sec.user ADD COLUMN IF NOT EXISTS lockout_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE sec.user ADD COLUMN IF NOT EXISTS locked_until TIMESTAMPTZ;
