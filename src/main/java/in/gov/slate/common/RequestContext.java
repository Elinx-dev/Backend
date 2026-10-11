package in.gov.slate.common;

/** Per-request values that cross-cut logging, auditing and row-level security. */
public final class RequestContext {

    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> IDEMPOTENCY_KEY = new ThreadLocal<>();
    private static final ThreadLocal<String> STATE_CODE = new ThreadLocal<>();
    private static final ThreadLocal<String> HTTP_METHOD = new ThreadLocal<>();
    private static final ThreadLocal<String> REQUEST_PATH = new ThreadLocal<>();
    private static final ThreadLocal<String> IP_ADDRESS = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_AGENT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> AUDITED = new ThreadLocal<>();

    private RequestContext() {
    }

    public static void set(String requestId, String idempotencyKey, String stateCode) {
        REQUEST_ID.set(requestId);
        IDEMPOTENCY_KEY.set(idempotencyKey);
        STATE_CODE.set(stateCode);
    }

    public static void setHttp(String method, String path, String ipAddress, String userAgent) {
        HTTP_METHOD.set(method);
        REQUEST_PATH.set(path);
        IP_ADDRESS.set(ipAddress);
        USER_AGENT.set(userAgent);
    }

    public static void clear() {
        REQUEST_ID.remove();
        IDEMPOTENCY_KEY.remove();
        STATE_CODE.remove();
        HTTP_METHOD.remove();
        REQUEST_PATH.remove();
        IP_ADDRESS.remove();
        USER_AGENT.remove();
        AUDITED.remove();
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

    public static String httpMethod() {
        return HTTP_METHOD.get();
    }

    public static String requestPath() {
        return REQUEST_PATH.get();
    }

    public static String ipAddress() {
        return IP_ADDRESS.get();
    }

    public static String userAgent() {
        return USER_AGENT.get();
    }

    /** True once a domain service has written an audit row for this request. */
    public static boolean audited() {
        return Boolean.TRUE.equals(AUDITED.get());
    }

    public static void markAudited() {
        AUDITED.set(Boolean.TRUE);
    }
}
