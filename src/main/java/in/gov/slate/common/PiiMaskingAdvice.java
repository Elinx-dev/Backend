package in.gov.slate.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Masks Aadhaar, PAN and mobile numbers in record APIs, so full values never reach the
 * browser. Full values are only returned by the audited {@code /api/pii/reveal} endpoint.
 * Any value still encrypted (never decrypted by the service) is replaced as well.
 */
@ControllerAdvice
public class PiiMaskingAdvice implements ResponseBodyAdvice<Object> {

    static final Set<String> MASKED_FIELDS = Set.of(
            "aadhaar_number", "representative_aadhaar", "pan", "representative_pan",
            "mobile", "representative_mobile");

    private static final List<String> MASKED_PATHS = List.of(
            "/api/properties", "/api/transactions", "/api/vao", "/api/tahsildar", "/api/surveyor",
            "/api/survey", "/api/revenue", "/api/registration", "/api/lineage", "/api/tokens",
            "/api/public", "/api/ec", "/api/dashboard");

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        String path = request.getURI().getPath();
        if (MASKED_PATHS.stream().noneMatch(path::startsWith)) {
            return body;
        }
        return mask(body);
    }

    @SuppressWarnings("unchecked")
    static Object mask(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                if (k instanceof String key && MASKED_FIELDS.contains(key) && v instanceof String s) {
                    out.put(k, PiiCrypto.isEncrypted(s) ? "[protected]" : PiiCrypto.mask(key, s));
                } else {
                    out.put(k, mask(v));
                }
            });
            return out;
        }
        if (value instanceof Collection<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            list.forEach(item -> out.add(mask(item)));
            return out;
        }
        if (PiiCrypto.isEncrypted(value)) {
            return "[protected]";
        }
        return value;
    }
}
