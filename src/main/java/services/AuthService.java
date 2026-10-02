package services;

import auth.AuthContext;
import com.nimbusds.jwt.JWTClaimsSet;
import enums.Role;
import io.mangoo.core.Config;
import io.mangoo.exceptions.MangooJwtException;
import io.mangoo.routing.bindings.Authentication;
import io.mangoo.routing.bindings.Request;
import io.mangoo.utils.JwtUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.TokenPair;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import utils.ApiKeys;
import utils.Exchanges;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
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
    private static final String CLAIM_VERSION = "ver";
    private static final String CLAIM_AUTH_TIME = "auth_time";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";
    private static final long ACCESS_TTL_SECONDS = 3600;
    private static final long REFRESH_TTL_SECONDS = 604800;
    // Bounds renewal (refresh or tenant switch); otherwise a leaked refresh token renews itself forever
    private static final Duration MAX_SESSION = Duration.ofDays(30);
    private static final String BEARER_ATTRIBUTE = "paprika.bearer";
    private final SystemUserService systemUserService;
    private final ApiKeyService apiKeyService;
    private final TokenVersionService tokenVersionService;
    private final byte[] tokenSecret;
    private final byte[] tokenKey;

    /**
     * {@code authTime} is the original sign-in; renewal carries it over instead of resetting it.
     * {@code expiresAt} is the expiry of this token alone.
     */
    public record TokenSession(AuthContext auth, Instant authTime, Instant expiresAt) { }

    /**
     * {@code apiKeyId} is set only for an API key; {@code expiresAt} is {@code null} for a credential
     * without expiry and for no credential at all.
     */
    private record BearerResolution(AuthContext auth, TokenSession session, String apiKeyId, Instant expiresAt) {
        private static final BearerResolution GUEST = new BearerResolution(AuthContext.guest(), null, null, null);
    }

    @Inject
    public AuthService(
            Config config,
            SystemUserService systemUserService,
            ApiKeyService apiKeyService,
            TokenVersionService tokenVersionService) {
        Objects.requireNonNull(config, "config must not be null");
        this.systemUserService = Objects.requireNonNull(systemUserService, "systemUserService must not be null");
        this.apiKeyService = Objects.requireNonNull(apiKeyService, "apiKeyService must not be null");
        this.tokenVersionService = Objects.requireNonNull(tokenVersionService, "tokenVersionService must not be null");
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
        return bearer(request).auth();
    }

    public Optional<TokenSession> resolveBearerSession(Request request) {
        return Optional.ofNullable(bearer(request).session());
    }

    /** Empty for a bearer that never expires (an API key without expiry) and for none at all. */
    public Optional<Instant> resolveBearerExpiry(Request request) {
        return Optional.ofNullable(bearer(request).expiresAt());
    }

    /** Empty unless the bearer is an API key. */
    public Optional<String> resolveBearerApiKeyId(Request request) {
        return Optional.ofNullable(bearer(request).apiKeyId());
    }

    // Cached per request: checking a token reads its account, and several filters ask for it
    private BearerResolution bearer(Request request) {
        if (request.getAttribute(BEARER_ATTRIBUTE) instanceof BearerResolution resolved) {
            return resolved;
        }

        BearerResolution resolved = resolveBearerUncached(request);
        request.addAttribute(BEARER_ATTRIBUTE, resolved);
        return resolved;
    }

    private BearerResolution resolveBearerUncached(Request request) {
        String token = bearerToken(request);
        if (token != null) {
            if (ApiKeys.isApiKey(token)) {
                return resolveApiKey(token, request);
            }

            TokenSession session = parseAccessToken(token);
            if (session != null) {
                return new BearerResolution(session.auth(), session, null, session.expiresAt());
            }
        }

        return BearerResolution.GUEST;
    }

    /**
     * Scheme matched case-insensitively (RFC 7235): a lower-case {@code bearer} must not look like
     * "no bearer" to the admin API, whose only defence is rejecting every bearer it sees.
     */
    private String bearerToken(Request request) {
        String authorization = request.getHeader("Authorization");
        if (StringUtils.isBlank(authorization) || !Strings.CI.startsWith(authorization, BEARER_PREFIX)) {
            return null;
        }

        return authorization.substring(BEARER_PREFIX.length()).trim();
    }

    /**
     * Source ranges are checked against the TCP peer, never {@code X-Forwarded-For}, which the caller
     * controls. A source rejection looks like an unknown key to the caller; only the request log
     * is told the difference.
     */
    private BearerResolution resolveApiKey(String key, Request request) {
        ApiKeyService.ApiKeyResolution resolution =
                apiKeyService.resolve(key, Exchanges.peerAddress(request));

        if (resolution.sourceRejected()) {
            request.addAttribute(ApiKeys.ATTRIBUTE_SOURCE_REJECTED, Boolean.TRUE);
            request.addAttribute(ApiKeys.ATTRIBUTE_REJECTED_NAME, resolution.rejectedKeyName());
            return BearerResolution.GUEST;
        }

        return resolution.key()
                .map(resolved -> {
                    request.addAttribute(ApiKeys.ATTRIBUTE_ID, resolved.keyId());
                    request.addAttribute(ApiKeys.ATTRIBUTE_NAME, resolved.keyName());
                    if (resolved.bypassRules()) {
                        request.addAttribute(ApiKeys.ATTRIBUTE_BYPASS_RULES, Boolean.TRUE);
                    }
                    if (resolved.bypassHooks()) {
                        request.addAttribute(ApiKeys.ATTRIBUTE_BYPASS_HOOKS, Boolean.TRUE);
                    }
                    return new BearerResolution(resolved.auth(), null, resolved.keyId(), resolved.expiresAt());
                })
                .orElse(BearerResolution.GUEST);
    }

    public boolean hasBearerToken(Request request) {
        return bearerToken(request) != null;
    }

    /** Empty whenever a bearer token is present: the admin UI authenticates by cookie only. */
    public Optional<AuthContext> resolveAdmin(Request request) {
        if (hasBearerToken(request)) {
            return Optional.empty();
        }

        // mangoo binds the cookie on every controller route, already checked against the blacklist;
        // parsing it again here would accept a revoked one
        Authentication authentication = request.getAuthentication();
        if (authentication == null || !authentication.isValid()) {
            return Optional.empty();
        }

        return resolveSuperadmin(authentication.getSubject());
    }

    /**
     * The role is read from the stored user, not assumed from the cookie, which only carries a
     * subject and outlives account changes; this prevents escalation once a lesser system role exists.
     */
    private Optional<AuthContext> resolveSuperadmin(String subject) {
        if (StringUtils.isBlank(subject)) {
            return Optional.empty();
        }

        return systemUserService.findPublicUser(subject)
                .filter(user -> Role.SUPERADMIN.equals(user.get("role")))
                .map(user -> AuthContext.of(subject, Role.SUPERADMIN, null));
    }

    /** Starts a new session: the caller has just authenticated. */
    public TokenPair createTokenPair(AuthContext auth) {
        return createTokenPair(auth, Instant.now());
    }

    /** {@code auth} may differ from the session's tenant (tenant switch); empty past {@code MAX_SESSION}. */
    public Optional<TokenPair> renewTokenPair(TokenSession session, AuthContext auth) {
        if (session.authTime().plus(MAX_SESSION).isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(createTokenPair(auth, session.authTime()));
    }

    private TokenPair createTokenPair(AuthContext auth, Instant authTime) {
        // Read once so both tokens of a pair carry the same version
        int version = tokenVersionService.current(auth).orElse(0);
        return new TokenPair(
                createToken(auth, TYPE_ACCESS, ACCESS_TTL_SECONDS, version, authTime),
                createToken(auth, TYPE_REFRESH, REFRESH_TTL_SECONDS, version, authTime)
        );
    }

    public Optional<AuthContext> userFromRefreshToken(String refreshToken) {
        return sessionFromRefreshToken(refreshToken).map(TokenSession::auth);
    }

    public Optional<TokenSession> sessionFromRefreshToken(String refreshToken) {
        return Optional.ofNullable(parseRefreshToken(refreshToken));
    }

    // Read from the token rather than assuming the TTL, so it stays correct if claim handling changes
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

    private String createToken(AuthContext auth, String type, long ttlSeconds, int version, Instant authTime) {
        Map<String, String> claims = new HashMap<>();
        claims.put(CLAIM_TYPE, type);
        claims.put(CLAIM_ROLE, auth.role());
        claims.put(CLAIM_VERSION, String.valueOf(version));
        claims.put(CLAIM_AUTH_TIME, String.valueOf(authTime.getEpochSecond()));
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

    private TokenSession parseAccessToken(String token) {
        return parseToken(token, TYPE_ACCESS);
    }

    private TokenSession parseRefreshToken(String token) {
        return parseToken(token, TYPE_REFRESH);
    }

    /**
     * Valid only while it carries the account's current token version (raised by reset, credential
     * change, 2FA and logout). Tokens without version or auth time are refused as forged.
     */
    private TokenSession parseToken(String token, String expectedType) {
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

            String version = claims.getStringClaim(CLAIM_VERSION);
            String authTime = claims.getStringClaim(CLAIM_AUTH_TIME);
            if (StringUtils.isBlank(version) || StringUtils.isBlank(authTime)) {
                return null;
            }

            AuthContext auth = AuthContext.of(subject, role, tenantId);
            int expected = Integer.parseInt(version);
            // Only revocation is checked here; a deleted account is refused where the token is used
            Optional<Integer> current = tokenVersionService.current(auth);
            if (current.isPresent() && current.orElseThrow() != expected) {
                return null;
            }

            return new TokenSession(auth, Instant.ofEpochSecond(Long.parseLong(authTime)),
                    claims.getExpirationTime().toInstant());
        } catch (MangooJwtException | ParseException | IllegalArgumentException e) {
            return null;
        }
    }


}
