package in.gov.slate.security;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private final JwtService jwt;
    private final UserRepository users;
    private final long idleTimeoutMinutes;

    public JwtAuthFilter(JwtService jwt, UserRepository users,
                         @Value("${slate.session.idle-timeout-minutes}") long idleTimeoutMinutes) {
        this.jwt = jwt;
        this.users = users;
        this.idleTimeoutMinutes = idleTimeoutMinutes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                var claims = jwt.parse(header.substring(7));
                UUID jti = UUID.fromString(claims.getId());
                OffsetDateTime activeAfter = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(idleTimeoutMinutes);
                if (users.isSessionActive(jti, activeAfter)) {
                    long userId = Long.parseLong(claims.getSubject());
                    users.findById(userId).ifPresent(row -> {
                        if (!"ACTIVE".equals(row.status())) {
                            return;
                        }
                        var current = users.toCurrentUser(row);
                        List<SimpleGrantedAuthority> authorities = current.roles().stream()
                                .map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
                        var auth = new UsernamePasswordAuthenticationToken(current, jti.toString(), authorities);
                        SecurityContextHolder.getContext().setAuthentication(auth);
                        users.touchSession(jti);
                    });
                }
            } catch (Exception ex) {
                SecurityContextHolder.clearContext();
                log.warn("JWT authentication failed for {} {}: {}: {}", request.getMethod(), request.getRequestURI(),
                        ex.getClass().getSimpleName(), ex.getMessage());
            }
        }
        chain.doFilter(request, response);
    }
}
