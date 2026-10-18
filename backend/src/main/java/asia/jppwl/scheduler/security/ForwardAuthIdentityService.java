package asia.jppwl.scheduler.security;

import asia.jppwl.scheduler.config.AppConfig;
import asia.jppwl.scheduler.model.ApiException;
import asia.jppwl.scheduler.model.SchedulerDtos.UserProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ForwardAuthIdentityService {

    private static final String HEADER_USER = "X-Auth-Request-User";
    private static final String HEADER_USERNAME = "X-Auth-Request-Preferred-Username";
    private static final String HEADER_EMAIL = "X-Auth-Request-Email";
    private static final String HEADER_ACCESS_TOKEN = "X-Auth-Request-Access-Token";

    private final AppConfig appConfig;
    private final ObjectMapper objectMapper;
    private HttpClient httpClient;
    private final Map<String, CachedProfile> profileCache = new ConcurrentHashMap<>();

    record CachedProfile(UserProfile profile, Instant expiresAt) {
    }

    @Inject
    public ForwardAuthIdentityService(AppConfig appConfig, ObjectMapper objectMapper) {
        this.appConfig = appConfig;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    void setHttpClientForTesting(HttpClient customClient) {
        this.httpClient = customClient;
    }

    public void clearCache() {
        profileCache.clear();
    }

    public UserProfile resolveCurrentUser(HttpHeaders headers) {
        if (!appConfig.auth().enabled()) {
            return new UserProfile(
                    "local-dev",
                    "nvd11",
                    "nvd11@163.com",
                    "https://github.com/nvd11.png",
                    "disabled"
            );
        }

        String ssoId = getHeaderCaseInsensitive(headers, HEADER_USER);
        if (ssoId == null || ssoId.isBlank()) {
            throw ApiException.unauthorized("Missing SSO identity headers (X-Auth-Request-User)");
        }

        CachedProfile cached = profileCache.get(ssoId);
        Instant now = Instant.now();
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.profile();
        }

        String username = getHeaderCaseInsensitive(headers, HEADER_USERNAME);
        if (username == null || username.isBlank()) {
            username = ssoId;
        }
        String email = getHeaderCaseInsensitive(headers, HEADER_EMAIL);
        if (email != null && !email.contains("@")) {
            email = null;
        }
        String avatarUrl = (!username.equals(ssoId)) ? "https://github.com/" + username + ".png" : null;

        String accessToken = getHeaderCaseInsensitive(headers, HEADER_ACCESS_TOKEN);
        if (accessToken != null && !accessToken.isBlank() && (username.equals(ssoId) || avatarUrl == null)) {
            UserProfile enriched = fetchLogtoUserInfo(ssoId, username, email, avatarUrl, accessToken);
            if (enriched != null) {
                profileCache.put(ssoId, new CachedProfile(enriched, now.plusSeconds(appConfig.cache().userInfoTtlSeconds())));
                return enriched;
            }
        }

        if (avatarUrl == null) {
            avatarUrl = "https://github.com/" + username + ".png";
        }
        UserProfile resolved = new UserProfile(ssoId, username, email, avatarUrl, appConfig.auth().mode());
        profileCache.put(ssoId, new CachedProfile(resolved, now.plusSeconds(appConfig.cache().userInfoTtlSeconds())));
        return resolved;
    }

    UserProfile fetchLogtoUserInfo(String ssoId, String fallbackUsername, String fallbackEmail, String fallbackAvatar, String accessToken) {
        try {
            String endpoint = appConfig.auth().logtoEndpoint().replaceAll("/+$", "") + "/oidc/me";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                return null;
            }
            JsonNode root = objectMapper.readTree(res.body());
            JsonNode githubDetails = root.path("identities").path("github").path("details");

            String realUsername = firstNonBlank(
                    textOrNull(githubDetails, "login"),
                    textOrNull(githubDetails, "username"),
                    textOrNull(root, "username"),
                    textOrNull(root, "preferred_username"),
                    textOrNull(root, "name"),
                    fallbackUsername
            );

            String realAvatar = firstNonBlank(
                    textOrNull(githubDetails, "avatar_url"),
                    textOrNull(root, "picture"),
                    fallbackAvatar,
                    "https://github.com/" + realUsername + ".png"
            );

            String candidateEmail = firstNonBlank(
                    textOrNull(githubDetails, "email"),
                    textOrNull(root, "email"),
                    fallbackEmail
            );
            if (candidateEmail != null && !candidateEmail.contains("@")) {
                candidateEmail = fallbackEmail;
            }

            return new UserProfile(ssoId, realUsername, candidateEmail, realAvatar, appConfig.auth().mode());
        } catch (Exception e) {
            return null;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) {
            return null;
        }
        String val = child.asText("").trim();
        return val.isEmpty() ? null : val;
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c;
            }
        }
        return null;
    }

    private static String getHeaderCaseInsensitive(HttpHeaders headers, String name) {
        if (headers == null) {
            return null;
        }
        String direct = headers.getHeaderString(name);
        if (direct != null && !direct.isBlank()) {
            return direct.trim();
        }
        if (headers.getRequestHeaders() != null) {
            for (Map.Entry<String, java.util.List<String>> entry : headers.getRequestHeaders().entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)
                        && entry.getValue() != null && !entry.getValue().isEmpty()) {
                    String val = entry.getValue().get(0);
                    if (val != null && !val.isBlank()) {
                        return val.trim();
                    }
                }
            }
        }
        return null;
    }
}
