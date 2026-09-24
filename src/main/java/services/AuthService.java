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
import org.apache.commons.lang3.Strings;
import utils.ApiKeys;

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
    private final ApiKeyService apiKeyService;
    private final byte[] tokenSecret;
    private final byte[] tokenKey;

    @Inject
    public AuthService(Config config, SystemUserService systemUserService, ApiKeyService apiKeyService) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.systemUserService = Objects.requireNonNull(systemUserService, "systemUserService must not be null");
        this.apiKeyService = Objects.requireNonNull(apiKeyService, "apiKeyService must not be null");
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
        String token = bearerToken(request);
        if (token != null) {
            // An API key is a bearer value as well, so it travels the existing filter paths
            // untouched; the prefix decides which of the two it is without a parse attempt.
            if (ApiKeys.isApiKey(token)) {
                return resolveApiKey(token, request);
            }

            AuthContext user = parseAccessToken(token);
            if (user != null) {
                return user;
            }
        }

        return AuthContext.guest();
    }

    /**
     * The credential of an {@code Authorization: Bearer …} header, or {@code null} when the header
     * is absent or carries a different scheme.
     * <p>
     * RFC 7235 defines the scheme as case-insensitive, and it is matched that way here so that the
     * exact spelling a client chose can never decide an authorization outcome: a lower case
     * {@code bearer} must not turn an API key into an anonymous request on the data plane, and it
     * must not look like "no bearer at all" to the admin API, whose only defence is rejecting
     * every bearer it sees.
     */
    private String bearerToken(Request request) {
        String authorization = request.getHeader("Authorization");
        if (StringUtils.isBlank(authorization) || !Strings.CI.startsWith(authorization, BEARER_PREFIX)) {
            return null;
        }

        return authorization.substring(BEARER_PREFIX.length()).trim();
    }

    /**
     * An API key yields the very same context an access token of the bound user yields, so nothing
     * downstream has to know which of the two authenticated the request. The key id and name are
     * put on the request so the request log can name it - the key itself never is.
     */
    private AuthContext resolveApiKey(String key, Request request) {
        return apiKeyService.resolve(key)
                .map(resolved -> {
                    request.addAttribute(ApiKeys.ATTRIBUTE_ID, resolved.keyId());
                    request.addAttribute(ApiKeys.ATTRIBUTE_NAME, resolved.keyName());
                    if (resolved.bypassRules()) {
                        request.addAttribute(ApiKeys.ATTRIBUTE_BYPASS_RULES, Boolean.TRUE);
                    }
                    return resolved.auth();
                })
                .orElseGet(AuthContext::guest);
    }

    public boolean hasBearerToken(Request request) {
        return bearerToken(request) != null;
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
            return resolveSuperadmin(authentication.getSubject());
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

            return resolveSuperadmin(subject);
        } catch (MangooJwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * The admin context of an account, but only if that account still <em>is</em> a superadmin.
     * <p>
     * The role is read back from the stored user instead of being assumed from the fact that a
     * valid session cookie exists: the cookie only carries a subject, and it outlives any change
     * to the account it points at. Without this check every system user would be handed superadmin
     * authority - which today is true for all of them, but is exactly the assumption that would
     * silently turn into a privilege escalation the day a lesser system role is introduced.
     */
    private Optional<AuthContext> resolveSuperadmin(String subject) {
        if (StringUtils.isBlank(subject)) {
            return Optional.empty();
        }

        return systemUserService.findPublicUser(subject)
                .filter(user -> Role.SUPERADMIN.equals(user.get("role")))
                .map(user -> AuthContext.of(subject, Role.SUPERADMIN, null));
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

    /**
     * Reads {@code exp}/{@code iat} back off a freshly issued access token rather than assuming the
     * configured TTL, so the value stays correct if the TTL or claim handling ever changes.
     */
    public long resolveExpiresIn(String accessToken) {
        JwtUtils.JwtData jwtData = JwtUtils.jwtData()
                .withSecret(tokenSecret)
                .withKey(tokenKey)
                .withIssuer(ISSUER)
                .withAudience(AUDIENCE)
                .withTtlSeconds(ACCESS_TTL_SECONDS);

        try {
            JWTClaimsSet claims = JwtUtils.parseJwt(accessToken, jwtData);
            return (claims.getExpirationTime().getTime() - claims.getIssueTime().getTime()) / 1000L;
        } catch (MangooJwtException e) {
            throw new IllegalStateException("Failed to parse freshly issued access token", e);
        }
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
