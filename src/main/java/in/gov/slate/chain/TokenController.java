package in.gov.slate.chain;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final in.gov.slate.common.JurisdictionGuard guard;

    private final TokenLedgerService ledger;

    public TokenController(NamedParameterJdbcTemplate jdbc, TokenService tokens, VerificationService verification,
                           in.gov.slate.common.JurisdictionGuard guard, TokenLedgerService ledger) {
        this.ledger = ledger;
        this.guard = guard;
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.verification = verification;
    }

    @GetMapping("/{tokenRef}")
    public Map<String, Object> token(@PathVariable String tokenRef) {
        CurrentUser user = CurrentUser.require();
        user.requirePermission("TOKEN_READ");
        var rows = jdbc.queryForList("""
                SELECT t.id, t.token_ref, t.state_version, t.status, t.approval_status, t.minted_at, t.superseded_at,
                       p.property_ref, parent.token_ref AS parent_token_ref, p.state_code, p.district_code,
                       p.taluk_code, p.village_code, p.sro_code,
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
        guard.require(user, rows.get(0), "Token " + tokenRef);
        Map<String, Object> out = new java.util.LinkedHashMap<>(rows.get(0));
        out.put("children", jdbc.queryForList("""
                SELECT c.token_ref, c.status, c.approval_status, c.state_version, p.property_ref,
                       p.status AS property_status
                  FROM chain.token c JOIN core.property p ON p.id = c.property_id
                 WHERE c.parent_token_id = :id ORDER BY c.id
                """, new MapSqlParameterSource("id", out.get("id"))));
        return out;
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

    /** The parcel NFT on the Fabric ledger next to SLATE's copy: holders, metadata, encumbrances, blocks. */
    @GetMapping("/{tokenRef}/ledger")
    public Map<String, Object> ledger(@PathVariable String tokenRef) {
        return ledger.ledgerView(token(tokenRef));
    }

    @GetMapping("/{tokenRef}/transfer-check")
    public Map<String, Object> transferCheck(@PathVariable String tokenRef) {
        return ledger.transferCheckForToken(((Number) token(tokenRef).get("id")).longValue());
    }

    @GetMapping("/{tokenRef}/encumbrances")
    public List<Map<String, Object>> encumbrances(@PathVariable String tokenRef) {
        return ledger.encumbrances(((Number) token(tokenRef).get("id")).longValue());
    }

    @PostMapping("/{tokenRef}/encumbrances")
    public Map<String, Object> addEncumbrance(@PathVariable String tokenRef,
                                              @RequestBody TokenLedgerService.EncumbranceRequest request) {
        return ledger.addEncumbrance(token(tokenRef), request, CurrentUser.require());
    }

    @PostMapping("/{tokenRef}/encumbrances/{encRef}/release")
    public Map<String, Object> releaseEncumbrance(@PathVariable String tokenRef, @PathVariable String encRef,
                                                  @RequestBody TokenLedgerService.ReleaseRequest request) {
        return ledger.releaseEncumbrance(token(tokenRef), encRef, request, CurrentUser.require());
    }

    @PostMapping("/{tokenRef}/demo/double-sale")
    public Map<String, Object> doubleSaleDemo(@PathVariable String tokenRef) {
        return ledger.doubleSaleDemo(token(tokenRef));
    }

    @GetMapping("/metrics/summary")
    public Map<String, Object> metrics() {
        CurrentUser.require().requirePermission("TOKEN_READ");
        return ledger.metrics();
    }
}
