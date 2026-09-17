package in.gov.slate.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@Service
public class JwtService {

    private final SecretKey key;
    private final long minutes;

    public JwtService(@Value("${slate.jwt.secret}") String secret,
                      @Value("${slate.jwt.access-token-minutes}") long minutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.minutes = minutes;
    }

    public record Issued(String token, UUID jti, Instant expiresAt) {
    }

    public Issued issue(long userId, String username, String stateCode) {
        UUID jti = UUID.randomUUID();
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(minutes * 60);
        String token = Jwts.builder()
                .subject(String.valueOf(userId))
                .id(jti.toString())
                .claim("username", username)
                .claim("stateCode", stateCode)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
        return new Issued(token, jti, exp);
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
