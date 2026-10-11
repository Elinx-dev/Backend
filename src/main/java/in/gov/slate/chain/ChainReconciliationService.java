package in.gov.slate.chain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Compares every token state hash in SLATE with the contract. A state whose anchor was
 * MINED but is not on chain is a MISMATCH (the DB was changed); a state never anchored
 * (e.g. recorded while no chain was configured) is NOT_ANCHORED.
 */
@Service
public class ChainReconciliationService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ChainService chain;
    private final ObjectMapper mapper;

    public ChainReconciliationService(NamedParameterJdbcTemplate jdbc, ChainService chain, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.chain = chain;
        this.mapper = mapper;
    }

    @Scheduled(cron = "${slate.chain.reconcile-cron:0 30 1 * * *}")
    public void nightly() {
        run("NIGHTLY");
    }

    public Map<String, Object> run(String trigger) {
        var states = jdbc.queryForList("""
                SELECT t.token_ref, t.state_code, h.state_version, '0x' || encode(h.state_hash, 'hex') AS state_hash,
                       EXISTS (SELECT 1 FROM chain.blockchain_transaction b
                                WHERE b.token_id = t.id AND b.status = 'MINED'
                                  AND b.args_json->>'stateHash' = '0x' || encode(h.state_hash, 'hex')) AS mined
                  FROM chain.token_state_history h JOIN chain.token t ON t.id = h.token_id
                 ORDER BY t.id, h.state_version
                """, Map.of());
        int notAnchored = 0;
        int mismatches = 0;
        boolean anyConnected = false;
        List<Map<String, Object>> problems = new ArrayList<>();
        java.util.Set<String> tokens = new java.util.HashSet<>();
        for (Map<String, Object> s : states) {
            tokens.add((String) s.get("token_ref"));
            Boolean onChain = chain.hasState(((String) s.get("state_code")).trim(), (String) s.get("token_ref"),
                    (String) s.get("state_hash"));
            if (onChain == null) {
                continue;
            }
            anyConnected = true;
            String status = onChain ? null : Boolean.TRUE.equals(s.get("mined")) ? "MISMATCH" : "NOT_ANCHORED";
            if (status != null) {
                if ("MISMATCH".equals(status)) {
                    mismatches++;
                } else {
                    notAnchored++;
                }
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("tokenRef", s.get("token_ref"));
                p.put("stateVersion", s.get("state_version"));
                p.put("status", status);
                problems.add(p);
            }
        }
        String outcome = !anyConnected ? "NOT_CONFIGURED" : mismatches > 0 ? "MISMATCH"
                : notAnchored > 0 ? "PARTIAL" : "VERIFIED";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trigger", trigger);
        out.put("outcome", outcome);
        out.put("tokensChecked", tokens.size());
        out.put("statesChecked", states.size());
        out.put("notAnchored", notAnchored);
        out.put("mismatches", mismatches);
        out.put("problems", problems);
        try {
            jdbc.update("""
                    INSERT INTO chain.reconciliation_run (trigger, finished_at, outcome, tokens_checked, states_checked,
                        not_anchored, mismatches, details_json)
                    VALUES (:trigger, now(), :outcome, :tokens, :states, :notAnchored, :mismatches, cast(:details AS jsonb))
                    """, new MapSqlParameterSource().addValue("trigger", trigger).addValue("outcome", outcome)
                    .addValue("tokens", tokens.size()).addValue("states", states.size())
                    .addValue("notAnchored", notAnchored).addValue("mismatches", mismatches)
                    .addValue("details", mapper.writeValueAsString(problems)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot record reconciliation run", e);
        }
        return out;
    }

    public Map<String, Object> latest() {
        var rows = jdbc.queryForList("""
                SELECT id, trigger, started_at, finished_at, outcome, tokens_checked, states_checked,
                       not_anchored, mismatches, details_json::text AS problems
                  FROM chain.reconciliation_run ORDER BY id DESC LIMIT 1
                """, Map.of());
        return rows.isEmpty() ? Map.of("outcome", "NEVER_RUN") : rows.get(0);
    }
}
