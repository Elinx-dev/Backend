package in.gov.slate.security;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import in.gov.slate.common.CurrentUser;

@Repository
public class UserRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public UserRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record UserRow(long id, String username, String fullName, String email, String mobile,
                          String designation, String department, String stateCode, String passwordHash,
                          boolean mfaRequired, String status, int failedLoginCount) {
    }

    public Optional<UserRow> findByUsername(String username) {
        var rows = jdbc.queryForList("""
                SELECT id, username::text AS username, full_name, email::text AS email, mobile, designation,
                       department, state_code, password_hash, mfa_required, status, failed_login_count
                  FROM sec.user WHERE username = :username
                """, new MapSqlParameterSource("username", username));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> r = rows.get(0);
        return Optional.of(new UserRow(
                ((Number) r.get("id")).longValue(),
                (String) r.get("username"),
                (String) r.get("full_name"),
                (String) r.get("email"),
                (String) r.get("mobile"),
                (String) r.get("designation"),
                (String) r.get("department"),
                (String) r.get("state_code"),
                (String) r.get("password_hash"),
                (Boolean) r.get("mfa_required"),
                (String) r.get("status"),
                ((Number) r.get("failed_login_count")).intValue()));
    }

    public Optional<UserRow> findById(long id) {
        String username = jdbc.query("SELECT username FROM sec.user WHERE id = :id",
                new MapSqlParameterSource("id", id),
                rs -> rs.next() ? rs.getString(1) : null);
        return username == null ? Optional.empty() : findByUsername(username);
    }

    public CurrentUser toCurrentUser(UserRow row) {
        var p = new MapSqlParameterSource("userId", row.id());
        Set<String> roles = new HashSet<>(jdbc.queryForList("""
                SELECT r.code FROM sec.user_role ur JOIN sec.role r ON r.id = ur.role_id
                 WHERE ur.user_id = :userId
                """, p, String.class));
        Set<String> permissions = new HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT pm.code
                  FROM sec.user_role ur
                  JOIN sec.role_permission rp ON rp.role_id = ur.role_id
                  JOIN sec.permission pm ON pm.id = rp.permission_id
                 WHERE ur.user_id = :userId
                """, p, String.class));
        Set<String> sros = new HashSet<>(jdbc.queryForList(
                "SELECT sro_code FROM sec.user_jurisdiction WHERE user_id = :userId AND sro_code IS NOT NULL",
                p, String.class));
        Set<String> villages = new HashSet<>(jdbc.queryForList(
                "SELECT village_code FROM sec.user_jurisdiction WHERE user_id = :userId AND village_code IS NOT NULL",
                p, String.class));
        return new CurrentUser(row.id(), row.username(), row.fullName(), row.stateCode(),
                row.department(), roles, permissions, sros, villages);
    }

    public List<Map<String, Object>> jurisdictions(long userId) {
        return jdbc.queryForList("""
                SELECT state_code, district_code, taluk_code, village_code, sro_code
                  FROM sec.user_jurisdiction WHERE user_id = :userId
                """, new MapSqlParameterSource("userId", userId));
    }

    public void recordLoginFailure(long userId) {
        jdbc.update("""
                UPDATE sec.user
                   SET failed_login_count = failed_login_count + 1,
                       status = CASE WHEN failed_login_count + 1 >= 5 THEN 'LOCKED' ELSE status END
                 WHERE id = :id
                """, new MapSqlParameterSource("id", userId));
    }

    public void recordLoginSuccess(long userId) {
        jdbc.update("UPDATE sec.user SET failed_login_count = 0, last_login_at = now() WHERE id = :id",
                new MapSqlParameterSource("id", userId));
    }

    public UUID createOtpChallenge(long userId, String otpHash, int validitySeconds) {
        UUID challengeId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sec.login_otp (user_id, otp_hash, challenge_id, expires_at)
                VALUES (:userId, :otpHash, :challengeId, now() + make_interval(secs => :validity))
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("otpHash", otpHash)
                .addValue("challengeId", challengeId)
                .addValue("validity", validitySeconds));
        return challengeId;
    }

    public Optional<Map<String, Object>> findOpenChallenge(UUID challengeId) {
        var rows = jdbc.queryForList("""
                SELECT id, user_id, otp_hash, expires_at, consumed_at, attempt_count
                  FROM sec.login_otp WHERE challenge_id = :challengeId
                """, new MapSqlParameterSource("challengeId", challengeId));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void consumeChallenge(long id) {
        jdbc.update("UPDATE sec.login_otp SET consumed_at = now() WHERE id = :id",
                new MapSqlParameterSource("id", id));
    }

    public void incrementChallengeAttempt(long id) {
        jdbc.update("UPDATE sec.login_otp SET attempt_count = attempt_count + 1 WHERE id = :id",
                new MapSqlParameterSource("id", id));
    }

    public void openSession(long userId, UUID jti, OffsetDateTime expiresAt, String ip, String userAgent) {
        jdbc.update("""
                INSERT INTO sec.user_session (user_id, jti, expires_at, ip_address, user_agent)
                VALUES (:userId, :jti, :expiresAt, :ip, :ua)
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("jti", jti)
                .addValue("expiresAt", expiresAt)
                .addValue("ip", ip)
                .addValue("ua", userAgent));
    }

    public boolean isSessionActive(UUID jti) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM sec.user_session
                 WHERE jti = :jti AND revoked_at IS NULL AND expires_at > now()
                """, new MapSqlParameterSource("jti", jti), Integer.class);
        return count != null && count > 0;
    }

    public void revokeSession(UUID jti) {
        jdbc.update("UPDATE sec.user_session SET revoked_at = now() WHERE jti = :jti AND revoked_at IS NULL",
                new MapSqlParameterSource("jti", jti));
    }
}
