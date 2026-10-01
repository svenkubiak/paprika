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
    // How long a session may be renewed - by refresh or by switching the tenant - before the
    // user has to sign in again. Without a bound, one leaked refresh token renews itself forever.
    private static final Duration MAX_SESSION = Duration.ofDays(30);
    private static final String BEARER_ATTRIBUTE = "paprika.bearer";
    private final Config config;
    private final SystemUserService systemUserService;
    private final ApiKeyService apiKeyService;
    private final TokenVersionService tokenVersionService;
    private final byte[] tokenSecret;
    private final byte[] tokenKey;

    /**
     * The identity a token stands for, plus when the session it belongs to was started by a
     * sign-in. Renewing a session carries that point in time over instead of resetting it.
     */
    public record TokenSession(AuthContext auth, Instant authTime) { }

    /** A resolved bearer value; {@code session} is {@code null} for an API key or an invalid token. */
    private record BearerResolution(AuthContext auth, TokenSession session) { }

    @Inject
    public AuthService(
            Config config,
            SystemUserService systemUserService,
            ApiKeyService apiKeyService,
            TokenVersionService tokenVersionService) {
        this.config = Objects.requireNonNull(config, "config must not be null");
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

    /**
     * The session of the bearer access token, or empty for an API key, an invalid token or no
     * bearer at all.
     */
    public Optional<TokenSession> resolveBearerSession(Request request) {
        return Optional.ofNullable(bearer(request).session());
    }

    /**
     * Resolved once per request: checking a token reads its account, and several filters of one
     * request ask for the bearer.
     */
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
            // An API key is a bearer value as well, so it travels the existing filter paths
            // untouched; the prefix decides which of the two it is without a parse attempt.
            if (ApiKeys.isApiKey(token)) {
                return new BearerResolution(resolveApiKey(token, request), null);
            }

            TokenSession session = parseAccessToken(token);
            if (session != null) {
                return new BearerResolution(session.auth(), session);
            }
        }

        return new BearerResolution(AuthContext.guest(), null);
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
     * <p>
     * A key bound to source ranges is checked against the peer of the TCP connection, which is
     * read here because this is the only layer that sees the key at all. Deliberately not against
     * {@code X-Forwarded-For}: that header is written by the caller, so a binding that honoured
     * it could be lifted by the very party it is meant to keep out. A rejection is
     * indistinguishable from an unknown key to the caller - same guest context, and therefore the
     * same status, body and {@code WWW-Authenticate} header further up - and only the request log
     * is told which of the two it was, so the operator does not go looking for a broken key.
     */
    private AuthContext resolveApiKey(String key, Request request) {
        ApiKeyService.ApiKeyResolution resolution =
                apiKeyService.resolve(key, Exchanges.peerAddress(request));

        if (resolution.sourceRejected()) {
            request.addAttribute(ApiKeys.ATTRIBUTE_SOURCE_REJECTED, Boolean.TRUE);
            request.addAttribute(ApiKeys.ATTRIBUTE_REJECTED_NAME, resolution.rejectedKeyName());
            return AuthContext.guest();
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

    /** Starts a new session: the caller has just authenticated. */
    public TokenPair createTokenPair(AuthContext auth) {
        return createTokenPair(auth, Instant.now());
    }

    /**
     * Continues a session with a fresh token pair, for the identity given - which may differ from
     * the session's own in its tenant, as on a tenant switch.
     *
     * @return empty when the session is older than the maximum session length; the user has to
     *         sign in again
     */
    public Optional<TokenPair> renewTokenPair(TokenSession session, AuthContext auth) {
        if (session.authTime().plus(MAX_SESSION).isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(createTokenPair(auth, session.authTime()));
    }

    private TokenPair createTokenPair(AuthContext auth, Instant authTime) {
        // Read once, so both tokens of a pair carry the same version. Every caller has just
        // verified the account; one deleted in between is refused where its token is used.
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
     * A token is valid only while it carries the account's current token version: a password
     * reset, a credential change, 2FA and a logout raise it and so revoke every token issued
     * before. A token without a version or a session start was not issued by this code and is
     * refused like a forged one.
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
            // An account that is gone is refused where the token is used, as it always was - with
            // the answer each of those places gives. Here it is only about a revoked token.
            Optional<Integer> current = tokenVersionService.current(auth);
            if (current.isPresent() && current.orElseThrow() != expected) {
                return null;
            }

            return new TokenSession(auth, Instant.ofEpochSecond(Long.parseLong(authTime)));
        } catch (MangooJwtException | ParseException | IllegalArgumentException e) {
            return null;
        }
    }


}
