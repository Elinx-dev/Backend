package in.gov.slate.chain;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import in.gov.slate.common.ApiException;
import in.gov.slate.common.CurrentUser;

@RestController
@RequestMapping("/api/tokens")
public class TokenController {

    private final NamedParameterJdbcTemplate jdbc;
    private final TokenService tokens;
    private final VerificationService verification;

    public TokenController(NamedParameterJdbcTemplate jdbc, TokenService tokens, VerificationService verification) {
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.verification = verification;
    }

    @GetMapping("/{tokenRef}")
    public Map<String, Object> token(@PathVariable String tokenRef) {
        CurrentUser.require().requirePermission("TOKEN_READ");
        var rows = jdbc.queryForList("""
                SELECT t.id, t.token_ref, t.state_version, t.status, t.minted_at, t.superseded_at,
                       p.property_ref, parent.token_ref AS parent_token_ref,
                       encode(t.owner_set_hash,'hex') AS owner_set_hash,
                       encode(t.property_ref_hash,'hex') AS property_ref_hash
                  FROM chain.token t
                  JOIN core.property p ON p.id = t.property_id
                  LEFT JOIN chain.token parent ON parent.id = t.parent_token_id
                 WHERE t.token_ref = :tokenRef
                """, new MapSqlParameterSource("tokenRef", tokenRef));
        if (rows.isEmpty()) {
            throw ApiException.notFound("Token " + tokenRef);
        }
        return rows.get(0);
    }

    @GetMapping("/{tokenRef}/history")
    public List<Map<String, Object>> history(@PathVariable String tokenRef) {
        CurrentUser.require().requirePermission("TOKEN_READ");
        Map<String, Object> token = token(tokenRef);
        return tokens.history(((Number) token.get("id")).longValue());
    }

    @PostMapping("/{tokenRef}/verify")
    public Map<String, Object> verify(@PathVariable String tokenRef) {
        return verification.verifyToken(tokenRef);
    }
}
