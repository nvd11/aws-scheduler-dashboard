package asia.jppwl.scheduler.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

@ConfigMapping(prefix = "app")
public interface AppConfig {

    @WithDefault("local")
    String env();

    AuthConfig auth();

    AwsConfig aws();

    GitHubConfig github();

    CacheConfig cache();

    interface AuthConfig {
        @WithDefault("false")
        boolean enabled();

        @WithDefault("forward-auth")
        String mode();

        @WithDefault("https://sodaxw.logto.app")
        String logtoEndpoint();
    }

    interface AwsConfig {
        @WithDefault("ap-southeast-1")
        String region();

        @WithDefault("186004631963")
        String accountId();

        Optional<String> accessKeyId();

        Optional<String> secretAccessKey();

        @WithDefault("default")
        String schedulerGroup();

        @WithDefault("default")
        String eventBusName();
    }

    interface GitHubConfig {
        Optional<String> token();

        @WithDefault("https://api.github.com")
        String apiBaseUrl();

        Optional<String> proxyHost();

        @WithDefault("7890")
        int proxyPort();
    }

    interface CacheConfig {
        @WithDefault("60")
        int topologyTtlSeconds();

        @WithDefault("15")
        int runHistoryTtlSeconds();

        @WithDefault("300")
        int userInfoTtlSeconds();
    }
}
