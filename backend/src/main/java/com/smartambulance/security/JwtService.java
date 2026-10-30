package com.smartambulance.security;

import com.smartambulance.config.AppProperties;
import com.smartambulance.domain.AppUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

@Component
public class JwtService {
    private final SecretKey key;
    private final long expiryMinutes;

    public JwtService(AppProperties props) {
        byte[] secret = props.jwt().secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) throw new IllegalStateException("san.jwt.secret must be at least 32 characters");
        this.key = Keys.hmacShaKeyFor(secret);
        this.expiryMinutes = props.jwt().expiryMinutes();
    }

    public String issue(AppUser u) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(u.getId()))
                .claim("role", u.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expiryMinutes * 60)))
                .signWith(key)
                .compact();
    }

    /** @return the user id inside a valid, unexpired token */
    public Optional<Long> parseUserId(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            return Optional.of(Long.parseLong(c.getSubject()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
