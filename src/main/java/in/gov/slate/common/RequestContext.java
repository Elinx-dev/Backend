package in.gov.slate.common;

/** Per-request values that cross-cut logging, auditing and row-level security. */
public final class RequestContext {

    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> IDEMPOTENCY_KEY = new ThreadLocal<>();
    private static final ThreadLocal<String> STATE_CODE = new ThreadLocal<>();

    private RequestContext() {
    }

    public static void set(String requestId, String idempotencyKey, String stateCode) {
        REQUEST_ID.set(requestId);
        IDEMPOTENCY_KEY.set(idempotencyKey);
        STATE_CODE.set(stateCode);
    }

    public static void clear() {
        REQUEST_ID.remove();
        IDEMPOTENCY_KEY.remove();
        STATE_CODE.remove();
    }

    public static String requestId() {
        return REQUEST_ID.get();
    }

    public static String idempotencyKey() {
        return IDEMPOTENCY_KEY.get();
    }

    public static String stateCode() {
        return STATE_CODE.get();
    }
}
