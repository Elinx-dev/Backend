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
import org.springframework.transaction.annotation.Transactional;

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

    public record LoginOtpRow(long userId, String otpHash, OffsetDateTime expiresAt,
                              OffsetDateTime consumedAt, int attemptCount) {
    }

    public record PasswordResetRow(long id, long userId, OffsetDateTime expiresAt, OffsetDateTime consumedAt) {
    }

    public Optional<UserRow> findByUsername(String username) {
        return findUser("""
                SELECT id, username::text AS username, full_name, email::text AS email, mobile, designation,
                       department, state_code, password_hash, mfa_required, status, failed_login_count
                  FROM sec.user WHERE username = :identifier
                """, new MapSqlParameterSource("identifier", username));
    }

    public Optional<UserRow> findByLoginId(String loginId) {
        return findUser("""
                SELECT id, username::text AS username, full_name, email::text AS email, mobile, designation,
                       department, state_code, password_hash, mfa_required, status, failed_login_count
                  FROM sec.user
                 WHERE username = :identifier OR email = :identifier
                """, new MapSqlParameterSource("identifier", loginId));
    }

    private Optional<UserRow> findUser(String sql, MapSqlParameterSource params) {
        var rows = jdbc.queryForList(sql, params);
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
                Boolean.TRUE.equals(r.get("mfa_required")),
                (String) r.get("status"),
                ((Number) r.get("failed_login_count")).intValue()));
    }

    public Optional<UserRow> findById(long id) {
        String username = jdbc.query("SELECT username FROM sec.user WHERE id = :id",
                new MapSqlParameterSource("id", id),
                rs -> rs.next() ? rs.getString(1) : null);
        return username == null ? Optional.empty() : findByUsername(username);
    }

    public List<Map<String, Object>> adminUsers(String stateCode) {
        return jdbc.queryForList("""
                SELECT u.id, u.username, u.full_name, u.email, u.mobile, u.designation,
                       u.department, u.state_code, u.status, u.mfa_required,
                       COALESCE(array_agg(r.code ORDER BY r.code) FILTER (WHERE r.code IS NOT NULL), '{}') AS roles
                  FROM sec.user u
                  LEFT JOIN sec.user_role ur ON ur.user_id = u.id
                  LEFT JOIN sec.role r ON r.id = ur.role_id
                 WHERE u.state_code = :stateCode
                 GROUP BY u.id
                 ORDER BY u.full_name, u.username
                """, new MapSqlParameterSource("stateCode", stateCode));
    }

    public List<Map<String, Object>> roles() {
        return jdbc.queryForList("""
                SELECT code, name, department, description
                  FROM sec.role ORDER BY name
                """, new MapSqlParameterSource());
    }

    @Transactional
    public long createUser(String stateCode, String username, String fullName, String email, String mobile,
                           String designation, String department, String passwordHash, String status,
                           boolean mfaRequired, List<String> roleCodes) {
        long userId = jdbc.queryForObject("""
                INSERT INTO sec.user (state_code, username, full_name, email, mobile, designation,
                                     department, password_hash, status, mfa_required)
                VALUES (:stateCode, :username, :fullName, :email, :mobile, :designation,
                        :department, :passwordHash, :status, :mfaRequired)
                RETURNING id
                """, new MapSqlParameterSource()
                .addValue("stateCode", stateCode).addValue("username", username)
                .addValue("fullName", fullName).addValue("email", email).addValue("mobile", mobile)
                .addValue("designation", designation).addValue("department", department)
                .addValue("passwordHash", passwordHash).addValue("status", status)
                .addValue("mfaRequired", mfaRequired), Long.class);
        replaceRoles(userId, roleCodes);
        return userId;
    }

    @Transactional
    public void updateUser(long userId, String stateCode, String fullName, String email, String mobile,
                           String designation, String department, String status, boolean mfaRequired,
                           List<String> roleCodes) {
        jdbc.update("""
                UPDATE sec.user
                   SET full_name = :fullName, email = :email, mobile = :mobile,
                       designation = :designation, department = :department,
                       status = :status, mfa_required = :mfaRequired
                 WHERE id = :userId AND state_code = :stateCode
                """, new MapSqlParameterSource()
                .addValue("userId", userId).addValue("stateCode", stateCode)
                .addValue("fullName", fullName).addValue("email", email).addValue("mobile", mobile)
                .addValue("designation", designation).addValue("department", department)
                .addValue("status", status).addValue("mfaRequired", mfaRequired));
        replaceRoles(userId, roleCodes);
    }

    private void replaceRoles(long userId, List<String> roleCodes) {
        var params = new MapSqlParameterSource("userId", userId);
        jdbc.update("DELETE FROM sec.user_role WHERE user_id = :userId", params);
        if (roleCodes == null || roleCodes.isEmpty()) return;
        jdbc.update("""
                INSERT INTO sec.user_role (user_id, role_id)
                SELECT :userId, id FROM sec.role WHERE code IN (:roleCodes)
                """, params.addValue("roleCodes", roleCodes));
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

    public void createLoginOtp(long userId, UUID challengeId, String otpHash, OffsetDateTime expiresAt) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("challengeId", challengeId)
                .addValue("otpHash", otpHash)
                .addValue("expiresAt", expiresAt);
        jdbc.update("""
                UPDATE sec.login_otp
                   SET consumed_at = now()
                 WHERE user_id = :userId AND consumed_at IS NULL
                """, params);
        jdbc.update("""
                INSERT INTO sec.login_otp (user_id, otp_hash, challenge_id, expires_at)
                VALUES (:userId, :otpHash, :challengeId, :expiresAt)
                """, params);
    }

    public Optional<LoginOtpRow> findLoginOtpForUpdate(UUID challengeId) {
        LoginOtpRow row = jdbc.query("""
                SELECT user_id, otp_hash, expires_at, consumed_at, attempt_count
                  FROM sec.login_otp
                 WHERE challenge_id = :challengeId
                   FOR UPDATE
                """, new MapSqlParameterSource("challengeId", challengeId),
                rs -> rs.next() ? new LoginOtpRow(
                        rs.getLong("user_id"),
                        rs.getString("otp_hash"),
                        rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getObject("consumed_at", OffsetDateTime.class),
                        rs.getInt("attempt_count")) : null);
        return Optional.ofNullable(row);
    }

    public void recordLoginOtpFailure(UUID challengeId) {
        jdbc.update("""
                UPDATE sec.login_otp
                   SET attempt_count = attempt_count + 1
                 WHERE challenge_id = :challengeId AND consumed_at IS NULL
                """, new MapSqlParameterSource("challengeId", challengeId));
    }

    public void consumeLoginOtp(UUID challengeId) {
        jdbc.update("""
                UPDATE sec.login_otp
                   SET consumed_at = now()
                 WHERE challenge_id = :challengeId AND consumed_at IS NULL
                """, new MapSqlParameterSource("challengeId", challengeId));
    }

    public void openSession(long userId, UUID jti, OffsetDateTime expiresAt, String ip, String userAgent) {
        jdbc.update("""
                INSERT INTO sec.user_session (user_id, jti, expires_at, last_activity_at, ip_address, user_agent)
                VALUES (:userId, :jti, :expiresAt, now(), :ip, :ua)
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("jti", jti)
                .addValue("expiresAt", expiresAt)
                .addValue("ip", ip)
                .addValue("ua", userAgent));
    }

    public boolean isSessionActive(UUID jti, OffsetDateTime activeAfter) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM sec.user_session
                 WHERE jti = :jti
                   AND revoked_at IS NULL
                   AND expires_at > now()
                   AND last_activity_at > :activeAfter
                """, new MapSqlParameterSource()
                .addValue("jti", jti)
                .addValue("activeAfter", activeAfter), Integer.class);
        return count != null && count > 0;
    }

    public void touchSession(UUID jti) {
        jdbc.update("""
                UPDATE sec.user_session
                   SET last_activity_at = now()
                 WHERE jti = :jti AND revoked_at IS NULL
                """, new MapSqlParameterSource("jti", jti));
    }

    public void revokeSession(UUID jti) {
        jdbc.update("UPDATE sec.user_session SET revoked_at = now() WHERE jti = :jti AND revoked_at IS NULL",
                new MapSqlParameterSource("jti", jti));
    }

    public void revokeAllSessions(long userId) {
        jdbc.update("UPDATE sec.user_session SET revoked_at = now() WHERE user_id = :userId AND revoked_at IS NULL",
                new MapSqlParameterSource("userId", userId));
    }

    public void createPasswordResetToken(long userId, byte[] tokenHash, OffsetDateTime expiresAt) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("tokenHash", tokenHash)
                .addValue("expiresAt", expiresAt);
        jdbc.update("""
                UPDATE sec.password_reset_token
                   SET consumed_at = now()
                 WHERE user_id = :userId AND consumed_at IS NULL
                """, params);
        jdbc.update("""
                INSERT INTO sec.password_reset_token (user_id, token_hash, expires_at)
                VALUES (:userId, :tokenHash, :expiresAt)
                """, params);
    }

    public Optional<PasswordResetRow> findPasswordResetForUpdate(byte[] tokenHash) {
        PasswordResetRow row = jdbc.query("""
                SELECT id, user_id, expires_at, consumed_at
                  FROM sec.password_reset_token
                 WHERE token_hash = :tokenHash
                   FOR UPDATE
                """, new MapSqlParameterSource("tokenHash", tokenHash),
                rs -> rs.next() ? new PasswordResetRow(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getObject("consumed_at", OffsetDateTime.class)) : null);
        return Optional.ofNullable(row);
    }

    public void updatePassword(long userId, String passwordHash) {
        jdbc.update("""
                UPDATE sec.user
                   SET password_hash = :passwordHash,
                       password_changed_at = now(),
                       failed_login_count = 0,
                       status = CASE WHEN status = 'LOCKED' THEN 'ACTIVE' ELSE status END
                 WHERE id = :userId
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("passwordHash", passwordHash));
    }

    public void consumePasswordReset(long resetId) {
        jdbc.update("""
                UPDATE sec.password_reset_token
                   SET consumed_at = now()
                 WHERE id = :id AND consumed_at IS NULL
                """, new MapSqlParameterSource("id", resetId));
    }
}
