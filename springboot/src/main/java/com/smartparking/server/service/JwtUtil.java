package com.smartparking.server.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.security.Key;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    private final Key secretKey;
    private final long expirationMs;

    public JwtUtil(
            @Value("${smartparking.jwt.secret}") String secret,
            @Value("${smartparking.jwt.expiration-ms}") long expirationMs) {
        this.secretKey = Keys.hmacShaKeyFor(normalizeSecret(secret));
        this.expirationMs = expirationMs;
    }

    public String generateToken(String username) {
        return Jwts.builder()
                .setSubject(username)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(secretKey)
                .compact();
    }

    public String extractUsername(String token) {
        return extractClaims(token).getSubject();
    }

    public boolean isValidToken(String token) {
        try {
            extractClaims(token);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private Claims extractClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(secretKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private byte[] normalizeSecret(String secret) {
        String value = secret == null ? "" : secret.trim();
        if (value.isEmpty()) {
            value = "smartparking-dev-secret-smartparking-dev-secret";
        }

        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        if (raw.length >= 32) {
            return raw;
        }

        byte[] expanded = new byte[32];
        for (int i = 0; i < expanded.length; i++) {
            expanded[i] = raw[i % raw.length];
        }
        return expanded;
    }
}
