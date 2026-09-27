package in.gov.slate.common;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Safety net for the audit trail: any state-changing request that a service did
 * not audit itself, and any request that failed, still leaves a row. Without it
 * the trail would silently miss whatever a new endpoint forgets to record.
 */
@Component
public class AuditTrailInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuditTrailInterceptor.class);
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final ObjectProvider<AuditService> audit;

    public AuditTrailInterceptor(ObjectProvider<AuditService> audit) {
        this.audit = audit;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") || path.startsWith("/api/audit/")) {
            return;
        }
        boolean mutating = !READ_METHODS.contains(request.getMethod());
        boolean failed = response.getStatus() >= 400;
        if (!mutating && !failed) {
            return;
        }
        if (RequestContext.audited() && !failed) {
            return;
        }
        if (CurrentUser.orNull() == null && RequestContext.stateCode() == null) {
            // Unauthenticated traffic has no state to file the row under; the auth
            // endpoints audit their own attempts against the account being used.
            return;
        }
        AuditService writer = audit.getIfAvailable();
        if (writer == null) {
            return;
        }
        try {
            writer.record(AuditEvent.of(actionFor(request, path))
                    .entity("API", path)
                    .transactionRef(segmentStartingWith(path, "TXN-"))
                    .propertyRef(pathValueAfter(path, "properties"))
                    .outcome(failed ? "FAILURE" : "SUCCESS")
                    .detail("HTTP " + response.getStatus() + " " + request.getMethod() + " " + path));
        } catch (RuntimeException auditFailure) {
            // Auditing must never turn a completed request into an error response.
            log.warn("Unable to write the fallback audit row for {} {}", request.getMethod(), path, auditFailure);
        }
    }

    private String actionFor(HttpServletRequest request, String path) {
        StringBuilder action = new StringBuilder(request.getMethod());
        for (String segment : path.split("/")) {
            if (segment.isBlank() || segment.equals("api") || !segment.matches("[a-zA-Z-]+")) {
                continue;
            }
            action.append('_').append(segment.replace('-', '_').toUpperCase());
        }
        return action.toString();
    }

    private String segmentStartingWith(String path, String prefix) {
        for (String segment : path.split("/")) {
            if (segment.startsWith(prefix)) {
                return segment;
            }
        }
        return null;
    }

    private String pathValueAfter(String path, String parent) {
        String[] segments = path.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if (segments[i].equals(parent) && !segments[i + 1].isBlank() && !segments[i + 1].equals("ref")) {
                return segments[i + 1];
            }
        }
        return null;
    }
}
