package asia.jppwl.scheduler.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.scheduler.SchedulerClient;

@ApplicationScoped
public class AwsClientProducer {

    private final AppConfig appConfig;

    @Inject
    public AwsClientProducer(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    AwsCredentialsProvider resolveCredentialsProvider() {
        String keyId = appConfig.aws().accessKeyId().orElse("").trim();
        String secret = appConfig.aws().secretAccessKey().orElse("").trim();
        if (!keyId.isEmpty() && !secret.isEmpty() && !keyId.startsWith("<")) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(keyId, secret));
        }
        return DefaultCredentialsProvider.create();
    }

    @Produces
    @ApplicationScoped
    public SchedulerClient produceSchedulerClient() {
        return SchedulerClient.builder()
                .region(Region.of(appConfig.aws().region()))
                .credentialsProvider(resolveCredentialsProvider())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    @Produces
    @ApplicationScoped
    public EventBridgeClient produceEventBridgeClient() {
        return EventBridgeClient.builder()
                .region(Region.of(appConfig.aws().region()))
                .credentialsProvider(resolveCredentialsProvider())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    @Produces
    @ApplicationScoped
    public CloudWatchClient produceCloudWatchClient() {
        return CloudWatchClient.builder()
                .region(Region.of(appConfig.aws().region()))
                .credentialsProvider(resolveCredentialsProvider())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }
}
