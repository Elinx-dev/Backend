package in.gov.slate.common;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex, HttpServletRequest request) {
        logApiError(request, ex.getStatus(), ex.getCode(), ex.getMessage(), null, ex);
        return ResponseEntity.status(ex.getStatus()).body(body(ex.getCode(), ex.getMessage(), null));
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(ValidationException ex, HttpServletRequest request) {
        logApiError(request, HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", ex.getMessage(),
                ex.getViolations(), ex);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(body("VALIDATION_FAILED", ex.getMessage(), ex.getViolations()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleBinding(MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        var details = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message",
                        f.getDefaultMessage() == null ? "invalid" : f.getDefaultMessage()))
                .collect(Collectors.toList());
        logApiError(request, HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                "Request payload failed validation", details, ex);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(body("VALIDATION_FAILED", "Request payload failed validation", details));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException ex,
            HttpServletRequest request) {
        String reason = ex.getResourcePath() + " not found";
        logApiError(request, HttpStatus.NOT_FOUND, "NOT_FOUND", reason, null, ex);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(body("NOT_FOUND", reason, null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception ex, HttpServletRequest request) {
        String reason = reasonFor(ex);
        logApiError(request, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", reason, null, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("INTERNAL_ERROR", reason, null));
    }

    private Map<String, Object> body(String code, String message, Object details) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", OffsetDateTime.now().toString());
        m.put("code", code);
        m.put("message", message);
        m.put("reason", message);
        if (details != null) {
            m.put("details", details);
        }
        return m;
    }

    private void logApiError(HttpServletRequest request, HttpStatus status, String code, String reason, Object details,
            Exception ex) {
        String requestTarget = requestTarget(request);
        String requestId = valueOrDash(RequestContext.requestId());
        Throwable rootCause = rootCause(ex);
        if (status.is5xxServerError()) {
            log.error(
                    "API error status={} code={} requestId={} request=\"{}\" exception={} rootCause={} reason=\"{}\" details={}",
                    status.value(), code, requestId, requestTarget, ex.getClass().getName(),
                    rootCause.getClass().getName(), reason, details, ex);
            return;
        }
        if (details == null) {
            log.warn("API error status={} code={} requestId={} request=\"{}\" exception={} reason=\"{}\"",
                    status.value(), code, requestId, requestTarget, ex.getClass().getName(), reason);
            return;
        }
        log.warn("API error status={} code={} requestId={} request=\"{}\" exception={} reason=\"{}\" details={}",
                status.value(), code, requestId, requestTarget, ex.getClass().getName(), reason, details);
    }

    private String requestTarget(HttpServletRequest request) {
        String query = request.getQueryString();
        return request.getMethod() + " " + request.getRequestURI() + (hasText(query) ? "?" + query : "");
    }

    private String reasonFor(Throwable ex) {
        Throwable rootCause = rootCause(ex);
        if (hasText(rootCause.getMessage())) {
            return rootCause.getMessage();
        }
        if (hasText(ex.getMessage())) {
            return ex.getMessage();
        }
        return rootCause.getClass().getSimpleName();
    }

    private Throwable rootCause(Throwable ex) {
        Throwable rootCause = ex;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }
        return rootCause;
    }

    private String valueOrDash(String value) {
        return hasText(value) ? value : "-";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
