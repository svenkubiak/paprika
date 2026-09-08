package services;

import auth.AuthContext;
import com.nimbusds.jwt.JWTClaimsSet;
import enums.Role;
import io.mangoo.core.Config;
import io.mangoo.exceptions.MangooJwtException;
import io.mangoo.routing.bindings.Authentication;
import io.mangoo.routing.bindings.Request;
import io.mangoo.utils.JwtUtils;
import io.undertow.server.handlers.Cookie;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.TokenPair;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Singleton
public class AuthService {
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ISSUER = "paprika";
    private static final String AUDIENCE = "paprika-api";
    private static final String CLAIM_TYPE = "type";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_TID = "tid";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";
    private static final long ACCESS_TTL_SECONDS = 3600;
    private static final long REFRESH_TTL_SECONDS = 604800;
    private final Config config;
    private final SystemUserService systemUserService;
    private final byte[] tokenSecret;
    private final byte[] tokenKey;

    @Inject
    public AuthService(Config config, SystemUserService systemUserService) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.systemUserService = Objects.requireNonNull(systemUserService, "systemUserService must not be null");
        this.tokenSecret = resolveTokenSecret(config);
        this.tokenKey = resolveTokenKey(config);
    }

    private static byte[] resolveTokenSecret(Config config) {
        String secret = config.getString("token.secret");
        if (secret == null || secret.isBlank() || secret.length() < 64) {
            throw new IllegalStateException(
                "token.secret must be configured and at least 64 characters for AES-256-CBC-HS512 (current length: "
                + (secret == null ? 0 : secret.length()) + ")");
        }
        return secret.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] resolveTokenKey(Config config) {
        String key = config.getString("token.key");
        if (key == null || key.isBlank() || key.length() < 64) {
            throw new IllegalStateException(
                "token.key must be configured and at least 64 characters for HS512 (current length: "
                + (key == null ? 0 : key.length()) + ")");
        }
        return key.getBytes(StandardCharsets.UTF_8);
    }

    public AuthContext resolveBearer(Request request) {
        String authorization = request.getHeader("Authorization");
        if (StringUtils.isNotBlank(authorization) && authorization.startsWith(BEARER_PREFIX)) {
            String token = authorization.substring(BEARER_PREFIX.length()).trim();
            AuthContext user = parseAccessToken(token);
            if (user != null) {
                return user;
            }
        }

        return AuthContext.guest();
    }

    public boolean hasBearerToken(Request request) {
        String authorization = request.getHeader("Authorization");
        return StringUtils.isNotBlank(authorization) && authorization.startsWith(BEARER_PREFIX);
    }

    /**
     * Resolves the admin Web UI user from the Mangoo authentication cookie.
     * Returns empty when a Bearer token is present or the cookie is missing/invalid.
     */
    public Optional<AuthContext> resolveAdmin(Request request) {
        if (hasBearerToken(request)) {
            return Optional.empty();
        }

        Authentication authentication = request.getAuthentication();
        if (authentication != null && authentication.isValid()) {
            String subject = authentication.getSubject();
            return systemUserService.findPublicUser(subject)
                    .map(user -> AuthContext.of(
                            subject,
                            Role.SUPERADMIN,
                            null));
        }

        Cookie cookie = request.getCookie(config.getAuthenticationCookieName());
        if (cookie == null || StringUtils.isBlank(cookie.getValue())) {
            return Optional.empty();
        }

        try {
            JwtUtils.JwtData jwtData = JwtUtils.jwtData()
                    .withKey(config.getAuthenticationCookieKey())
                    .withSecret(config.getAuthenticationCookieSecret())
                    .withIssuer(config.getApplicationName())
                    .withAudience(config.getAuthenticationCookieName())
                    .withTtlSeconds(config.getAuthenticationCookieRememberExpires());

            JWTClaimsSet claims = JwtUtils.parseJwt(cookie.getValue(), jwtData);
            String subject = claims.getSubject();
            if (StringUtils.isBlank(subject)) {
                return Optional.empty();
            }

            return systemUserService.findPublicUser(subject)
                    .map(user -> AuthContext.of(subject, Role.SUPERADMIN, null));
        } catch (MangooJwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public TokenPair createTokenPair(AuthContext auth) {
        return new TokenPair(
                createAccessToken(auth),
                createRefreshToken(auth)
        );
    }

    public Optional<AuthContext> userFromRefreshToken(String refreshToken) {
        AuthContext user = parseRefreshToken(refreshToken);
        return Optional.ofNullable(user);
    }

    public String createAccessToken(AuthContext auth) {
        return createToken(auth, TYPE_ACCESS, ACCESS_TTL_SECONDS);
    }

    private String createRefreshToken(AuthContext auth) {
        return createToken(auth, TYPE_REFRESH, REFRESH_TTL_SECONDS);
    }

    private String createToken(AuthContext auth, String type, long ttlSeconds) {
        Map<String, String> claims = new HashMap<>();
        claims.put(CLAIM_TYPE, type);
        claims.put(CLAIM_ROLE, auth.role());
        if (auth.tenantId() != null) {
            claims.put(CLAIM_TID, auth.tenantId());
        }

        JwtUtils.JwtData jwtData = JwtUtils.jwtData()
                .withSecret(tokenSecret)
                .withKey(tokenKey)
                .withIssuer(ISSUER)
                .withAudience(AUDIENCE)
                .withSubject(auth.id())
                .withTtlSeconds(ttlSeconds)
                .withClaims(claims);

        try {
            return JwtUtils.createJwt(jwtData);
        } catch (MangooJwtException e) {
            throw new IllegalStateException("Failed to create JWT", e);
        }
    }

    private AuthContext parseAccessToken(String token) {
        return parseToken(token, TYPE_ACCESS);
    }

    private AuthContext parseRefreshToken(String token) {
        return parseToken(token, TYPE_REFRESH);
    }

    private AuthContext parseToken(String token, String expectedType) {
        long maxTtlSeconds = TYPE_REFRESH.equals(expectedType)
                ? REFRESH_TTL_SECONDS
                : ACCESS_TTL_SECONDS;

        JwtUtils.JwtData jwtData = JwtUtils.jwtData()
                .withSecret(tokenSecret)
                .withKey(tokenKey)
                .withIssuer(ISSUER)
                .withAudience(AUDIENCE)
                .withTtlSeconds(maxTtlSeconds);

        try {
            JWTClaimsSet claims = JwtUtils.parseJwt(token, jwtData);
            String subject = claims.getSubject();
            if (StringUtils.isBlank(subject)) {
                return null;
            }

            String type = claims.getStringClaim(CLAIM_TYPE);
            if (!expectedType.equals(type)) {
                return null;
            }

            String role = claims.getStringClaim(CLAIM_ROLE);
            String tenantId = claims.getStringClaim(CLAIM_TID);

            if (StringUtils.isBlank(role)) {
                role = Role.USER;
            }

            return AuthContext.of(subject, role, tenantId);
        } catch (MangooJwtException | ParseException | IllegalArgumentException e) {
            return null;
        }
    }


}
