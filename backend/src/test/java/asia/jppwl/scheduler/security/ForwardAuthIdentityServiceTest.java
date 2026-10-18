package asia.jppwl.scheduler.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import asia.jppwl.scheduler.config.AppConfig;
import asia.jppwl.scheduler.model.ApiException;
import asia.jppwl.scheduler.model.SchedulerDtos.UserProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ForwardAuthIdentityServiceTest {

    private AppConfig buildConfig(boolean authEnabled) {
        return new AppConfig() {
            @Override public String env() { return "test"; }
            @Override public AuthConfig auth() {
                return new AuthConfig() {
                    @Override public boolean enabled() { return authEnabled; }
                    @Override public String mode() { return "forward-auth"; }
                    @Override public String logtoEndpoint() { return "https://sodaxw.logto.app"; }
                };
            }
            @Override public AwsConfig aws() {
                return new AwsConfig() {
                    @Override public String region() { return "ap-southeast-1"; }
                    @Override public String accountId() { return "186004631963"; }
                    @Override public Optional<String> accessKeyId() { return Optional.empty(); }
                    @Override public Optional<String> secretAccessKey() { return Optional.empty(); }
                    @Override public String schedulerGroup() { return "default"; }
                    @Override public String eventBusName() { return "default"; }
                };
            }
            @Override public GitHubConfig github() {
                return new GitHubConfig() {
                    @Override public Optional<String> token() { return Optional.of("ghp_test123"); }
                    @Override public String apiBaseUrl() { return "https://api.github.com"; }
                };
            }
            @Override public CacheConfig cache() {
                return new CacheConfig() {
                    @Override public int topologyTtlSeconds() { return 60; }
                    @Override public int runHistoryTtlSeconds() { return 15; }
                    @Override public int userInfoTtlSeconds() { return 300; }
                };
            }
        };
    }

    private HttpHeaders headersOf(MultivaluedMap<String, String> map) {
        HttpHeaders headers = mock(HttpHeaders.class);
        when(headers.getRequestHeaders()).thenReturn(map);
        for (String key : map.keySet()) {
            when(headers.getHeaderString(key)).thenReturn(map.getFirst(key));
        }
        return headers;
    }

    @Test
    void shouldReturnMockUserWhenAuthDisabled() {
        ForwardAuthIdentityService service = new ForwardAuthIdentityService(buildConfig(false), new ObjectMapper());
        UserProfile user = service.resolveCurrentUser(null);
        assertThat(user.username()).isEqualTo("nvd11");
        assertThat(user.authMode()).isEqualTo("disabled");
    }

    @Test
    void shouldThrow401WhenAuthEnabledAndHeaderMissing() {
        ForwardAuthIdentityService service = new ForwardAuthIdentityService(buildConfig(true), new ObjectMapper());
        HttpHeaders emptyHeaders = headersOf(new MultivaluedHashMap<>());
        assertThatThrownBy(() -> service.resolveCurrentUser(emptyHeaders))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getHttpStatus()).isEqualTo(401));
    }

    @Test
    void shouldResolveFromForwardAuthHeadersAndCacheResult() {
        ForwardAuthIdentityService service = new ForwardAuthIdentityService(buildConfig(true), new ObjectMapper());
        MultivaluedMap<String, String> map = new MultivaluedHashMap<>();
        map.putSingle("x-auth-request-user", "usr_123");
        map.putSingle("x-auth-request-preferred-username", "nvd11");
        map.putSingle("x-auth-request-email", "nvd11@163.com");

        UserProfile first = service.resolveCurrentUser(headersOf(map));
        assertThat(first.id()).isEqualTo("usr_123");
        assertThat(first.username()).isEqualTo("nvd11");
        assertThat(first.email()).isEqualTo("nvd11@163.com");
        assertThat(first.avatarUrl()).isEqualTo("https://github.com/nvd11.png");

        // Second call hits cache
        UserProfile second = service.resolveCurrentUser(headersOf(map));
        assertThat(second).isEqualTo(first);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldEnrichFromLogtoOidcMeWhenUsernameEqualsSub() throws Exception {
        ForwardAuthIdentityService service = new ForwardAuthIdentityService(buildConfig(true), new ObjectMapper());
        HttpClient mockHttp = mock(HttpClient.class);
        HttpResponse<String> mockRes = mock(HttpResponse.class);
        when(mockRes.statusCode()).thenReturn(200);
        when(mockRes.body()).thenReturn("""
                {
                  "sub": "zhspkt3s6lm2",
                  "email": "nvd11@163.com",
                  "identities": {
                    "github": {
                      "details": {
                        "login": "nvd11",
                        "avatar_url": "https://avatars.githubusercontent.com/u/1?v=4"
                      }
                    }
                  }
                }
                """);
        when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockRes);
        service.setHttpClientForTesting(mockHttp);

        MultivaluedMap<String, String> map = new MultivaluedHashMap<>();
        map.putSingle("X-Auth-Request-User", "zhspkt3s6lm2");
        map.putSingle("X-Auth-Request-Email", "not-an-email");
        map.putSingle("X-Auth-Request-Access-Token", "tok_xyz");

        UserProfile profile = service.resolveCurrentUser(headersOf(map));
        assertThat(profile.username()).isEqualTo("nvd11");
        assertThat(profile.email()).isEqualTo("nvd11@163.com");
        assertThat(profile.avatarUrl()).isEqualTo("https://avatars.githubusercontent.com/u/1?v=4");
        service.clearCache();
    }
}
