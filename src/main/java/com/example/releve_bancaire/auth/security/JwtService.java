package com.example.releve_bancaire.auth.security;

import com.example.releve_bancaire.auth.entity.AppRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.Map;

@Service
public class JwtService {

    private final Key signingKey;
    private final long expirationMs;

    public JwtService(
            @Value("${security.jwt.secret:change-this-secret-change-this-secret-change-this-secret}") String secret,
            @Value("${security.jwt.expiration-ms:86400000}") long expirationMs) {
        this.signingKey = buildSigningKey(secret);
        this.expirationMs = expirationMs;
    }

    public String generateToken(Long userId, String email, AppRole role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .setSubject(email)
                .addClaims(Map.of(
                        "uid", userId,
                        "role", role.name()))
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();
    }

    public String extractEmail(String token) {
        return parseClaims(token).getSubject();
    }

    public boolean isTokenValid(String token, String email) {
        Claims claims = parseClaims(token);
        String subject = claims.getSubject();
        Date expiration = claims.getExpiration();
        return subject != null
                && subject.equalsIgnoreCase(email)
                && expiration != null
                && expiration.after(new Date());
    }

    private Claims parseClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private Key buildSigningKey(String rawSecret) {
        String secret = rawSecret == null ? "" : rawSecret.trim();
        try {
            byte[] decoded = Decoders.BASE64.decode(secret);
            if (decoded.length >= 32) {
                return Keys.hmacShaKeyFor(decoded);
            }
        } catch (Exception ignored) {
            // Fallback to raw string bytes when secret is not valid base64
        }

        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(bytes, 0, padded, 0, Math.min(bytes.length, 32));
            for (int i = bytes.length; i < 32; i++) {
                padded[i] = '0';
            }
            bytes = padded;
        }
        return Keys.hmacShaKeyFor(bytes);
    }
}
