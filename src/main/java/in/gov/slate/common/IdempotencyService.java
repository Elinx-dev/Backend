package in.gov.slate.common;

import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Replay protection for state-changing endpoints. A repeated key with the same
 * payload returns the stored response; with a different payload it is a conflict.
 */
@Service
public class IdempotencyService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public IdempotencyService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<Map<String, Object>> replay(String scope, String key, Object request) {
        if (key == null) {
            return Optional.empty();
        }
        var rows = jdbc.queryForList("""
                SELECT request_hash, response_body
                  FROM sec.idempotency_record
                 WHERE scope = :scope AND idempotency_key = :key
                """, new MapSqlParameterSource().addValue("scope", scope).addValue("key", key));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        byte[] stored = (byte[]) rows.get(0).get("request_hash");
        byte[] current = Hashes.sha256(json(request));
        if (!java.util.Arrays.equals(stored, current)) {
            throw ApiException.conflict("Idempotency key " + key + " was already used with a different payload");
        }
        Object body = rows.get(0).get("response_body");
        return Optional.of(readMap(body == null ? "{}" : body.toString()));
    }

    public void store(String scope, String key, Object request, Object response) {
        if (key == null) {
            return;
        }
        jdbc.update("""
                INSERT INTO sec.idempotency_record (scope, idempotency_key, request_hash, response_status, response_body)
                VALUES (:scope, :key, :hash, 200, CAST(:body AS jsonb))
                ON CONFLICT (scope, idempotency_key) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("scope", scope)
                .addValue("key", key)
                .addValue("hash", Hashes.sha256(json(request)))
                .addValue("body", json(response)));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
