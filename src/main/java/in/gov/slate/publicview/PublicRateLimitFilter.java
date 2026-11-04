package in.gov.slate.publicview;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Limits how many /api/public requests one client can make per minute, so the
 * no-login search can't be scripted to walk the land register. X-Forwarded-For is
 * only trusted when the request comes from a local proxy (e.g. the nginx UI container).
 */
@Component
@Order(2)
public class PublicRateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MS = 60_000L;
    private static final int MAX_TRACKED_CLIENTS = 50_000;

    private final int limitPerMinute;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public PublicRateLimitFilter(@Value("${slate.public.rate-limit-per-minute:120}") int limitPerMinute) {
        this.limitPerMinute = limitPerMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/public/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long now = System.currentTimeMillis();
        if (windows.size() > MAX_TRACKED_CLIENTS) {
            windows.entrySet().removeIf(e -> now - e.getValue().start >= WINDOW_MS);
        }
        Window window = windows.compute(clientIp(request),
                (ip, current) -> current == null || now - current.start >= WINDOW_MS ? new Window(now) : current);
        if (window.count.incrementAndGet() > limitPerMinute) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(Math.max(1, (window.start + WINDOW_MS - now) / 1000)));
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"TOO_MANY_REQUESTS\",\"message\":\"Too many searches. Please wait a minute and try again.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    static String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank() && fromLocalProxy(remote)) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty() && first.length() <= 45) {
                return first;
            }
        }
        return remote;
    }

    private static boolean fromLocalProxy(String remote) {
        try {
            InetAddress address = InetAddress.getByName(remote);
            return address.isLoopbackAddress() || address.isSiteLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static final class Window {
        final long start;
        final AtomicInteger count = new AtomicInteger();

        Window(long start) {
            this.start = start;
        }
    }
}
