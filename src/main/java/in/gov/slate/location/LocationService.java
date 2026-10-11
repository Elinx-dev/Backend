package in.gov.slate.location;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import in.gov.slate.common.ApiException;

/**
 * Location masters for property entry, resolved down the hierarchy
 * state -> registration district -> sub-registrar office -> taluk -> revenue village.
 */
@Service
public class LocationService {

    private final NamedParameterJdbcTemplate jdbc;

    public LocationService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> districts(String stateCode) {
        return jdbc.queryForList("""
                SELECT d.district_code AS code, d.district_name AS name
                  FROM master.registration_district d
                 WHERE d.state_code = :stateCode AND d.active
                 ORDER BY d.sort_order, d.district_name
                """, new MapSqlParameterSource("stateCode", stateCode));
    }

    public List<Map<String, Object>> subRegistrarOffices(String stateCode, String districtCode) {
        return jdbc.queryForList("""
                SELECT s.sro_code AS code, s.sro_name AS name
                  FROM master.sub_registrar_office s
                  JOIN master.registration_district d ON d.id = s.district_id
                 WHERE d.state_code = :stateCode AND d.district_code = :districtCode
                   AND d.active AND s.active
                 ORDER BY s.sort_order, s.sro_name
                """, new MapSqlParameterSource()
                .addValue("stateCode", stateCode)
                .addValue("districtCode", districtCode));
    }

    public List<Map<String, Object>> taluks(String stateCode, String districtCode, String sroCode) {
        return jdbc.queryForList("""
                SELECT t.taluk_code AS code, t.taluk_name AS name
                  FROM master.taluk t
                  JOIN master.sub_registrar_office s ON s.id = t.sro_id
                  JOIN master.registration_district d ON d.id = s.district_id
                 WHERE d.state_code = :stateCode AND d.district_code = :districtCode AND s.sro_code = :sroCode
                   AND d.active AND s.active AND t.active
                 ORDER BY t.sort_order, t.taluk_name
                """, new MapSqlParameterSource()
                .addValue("stateCode", stateCode)
                .addValue("districtCode", districtCode)
                .addValue("sroCode", sroCode));
    }

    public List<Map<String, Object>> revenueVillages(String stateCode, String districtCode, String sroCode,
                                                     String talukCode) {
        return jdbc.queryForList("""
                SELECT v.village_code AS code, v.village_name AS name
                  FROM master.revenue_village v
                  JOIN master.taluk t ON t.id = v.taluk_id
                  JOIN master.sub_registrar_office s ON s.id = t.sro_id
                  JOIN master.registration_district d ON d.id = s.district_id
                 WHERE d.state_code = :stateCode AND d.district_code = :districtCode
                   AND s.sro_code = :sroCode AND t.taluk_code = :talukCode
                   AND d.active AND s.active AND t.active AND v.active
                 ORDER BY v.sort_order, v.village_name
                """, new MapSqlParameterSource()
                .addValue("stateCode", stateCode)
                .addValue("districtCode", districtCode)
                .addValue("sroCode", sroCode)
                .addValue("talukCode", talukCode));
    }

    /** Rejects a district / SRO / taluk / village combination that is not mapped in the masters. */
    public void requireValidPath(String stateCode, String districtCode, String sroCode,
                                 String talukCode, String villageCode) {
        requireIn(districts(stateCode), districtCode, "Registration district " + districtCode, stateCode);
        requireIn(subRegistrarOffices(stateCode, districtCode), sroCode,
                "Sub-Registrar Office " + sroCode, "district " + districtCode);
        if (isBlank(talukCode)) {
            return;
        }
        requireIn(taluks(stateCode, districtCode, sroCode), talukCode, "Taluk " + talukCode, "SRO " + sroCode);
        if (isBlank(villageCode)) {
            return;
        }
        requireIn(revenueVillages(stateCode, districtCode, sroCode, talukCode), villageCode,
                "Revenue village " + villageCode, "taluk " + talukCode);
    }

    private static void requireIn(List<Map<String, Object>> rows, String code, String what, String parent) {
        boolean found = rows.stream().anyMatch(r -> code.equals(r.get("code")));
        if (!found) {
            throw ApiException.badRequest(what + " is not mapped to " + parent);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
