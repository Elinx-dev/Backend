package in.gov.slate.transaction;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Everything the validation and workflow engines need about one transaction. */
public record TransactionContext(
        Map<String, Object> transaction,
        Map<String, Object> property,
        Map<String, Object> deedType,
        List<Map<String, Object>> parties,
        List<Map<String, Object>> witnesses,
        List<Map<String, Object>> consents,
        List<Map<String, Object>> ruleResults,
        Map<String, Object> feeCalculation,
        List<Map<String, Object>> payments,
        List<Map<String, Object>> surveyParcels,
        List<Map<String, Object>> propertyOwners) {

    public long id() {
        return num(transaction.get("id")).longValue();
    }

    public String txnRef() {
        return (String) transaction.get("txn_ref");
    }

    public String status() {
        return (String) transaction.get("status");
    }

    public String deedTypeCode() {
        return (String) transaction.get("deed_type_code");
    }

    public String transferScope() {
        return (String) transaction.get("transfer_scope");
    }

    public boolean surveyRequired() {
        return Boolean.TRUE.equals(transaction.get("survey_required"));
    }

    public String propertyRef() {
        return (String) property.get("property_ref");
    }

    public BigDecimal dec(String key) {
        Object v = transaction.get(key);
        return v == null ? null : new BigDecimal(v.toString());
    }

    public List<Map<String, Object>> side(String side) {
        return parties.stream().filter(p -> side.equals(p.get("side"))).toList();
    }

    public BigDecimal paidTotal() {
        return payments.stream()
                .filter(p -> "SUCCESS".equals(p.get("status")))
                .map(p -> new BigDecimal(p.get("amount").toString()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal totalPayable() {
        return feeCalculation == null ? null : new BigDecimal(feeCalculation.get("total_payable").toString());
    }

    public static Number num(Object o) {
        return (Number) o;
    }
}
