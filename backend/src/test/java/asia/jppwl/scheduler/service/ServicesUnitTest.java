package asia.jppwl.scheduler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import asia.jppwl.scheduler.client.GitHubActionsClient;
import asia.jppwl.scheduler.client.GitHubActionsClient.RawLogTail;
import asia.jppwl.scheduler.config.AppConfig;
import asia.jppwl.scheduler.config.AwsClientProducer;
import asia.jppwl.scheduler.model.ApiException;
import asia.jppwl.scheduler.model.ApiResponse;
import asia.jppwl.scheduler.resource.GlobalExceptionMapper;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import asia.jppwl.scheduler.model.SchedulerDtos.Metrics7dView;
import asia.jppwl.scheduler.model.SchedulerDtos.RetryPolicyView;
import asia.jppwl.scheduler.model.SchedulerDtos.RunLogDetailView;
import asia.jppwl.scheduler.model.SchedulerDtos.ScheduleRoutingView;
import asia.jppwl.scheduler.model.SchedulerDtos.ScheduleRunsResponse;
import asia.jppwl.scheduler.model.SchedulerDtos.TriggerScheduleRequest;
import asia.jppwl.scheduler.model.SchedulerDtos.TriggerScheduleResult;
import asia.jppwl.scheduler.model.SchedulerDtos.UpdateScheduleConfigRequest;
import asia.jppwl.scheduler.model.SchedulerDtos.UpdateScheduleConfigResult;
import asia.jppwl.scheduler.model.SchedulerDtos.UpdateScheduleStateRequest;
import asia.jppwl.scheduler.model.SchedulerDtos.UpdateScheduleStateResult;
import asia.jppwl.scheduler.service.AwsTopologyService.ResolvedScheduleNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataResponse;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.ApiDestination;
import software.amazon.awssdk.services.eventbridge.model.ListApiDestinationsRequest;
import software.amazon.awssdk.services.eventbridge.model.ListApiDestinationsResponse;
import software.amazon.awssdk.services.eventbridge.model.ListRulesRequest;
import software.amazon.awssdk.services.eventbridge.model.ListRulesResponse;
import software.amazon.awssdk.services.eventbridge.model.ListTargetsByRuleRequest;
import software.amazon.awssdk.services.eventbridge.model.ListTargetsByRuleResponse;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResultEntry;
import software.amazon.awssdk.services.eventbridge.model.Rule;
import software.amazon.awssdk.services.eventbridge.model.Target;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.scheduler.model.EventBridgeParameters;
import software.amazon.awssdk.services.scheduler.model.FlexibleTimeWindow;
import software.amazon.awssdk.services.scheduler.model.FlexibleTimeWindowMode;
import software.amazon.awssdk.services.scheduler.model.GetScheduleRequest;
import software.amazon.awssdk.services.scheduler.model.GetScheduleResponse;
import software.amazon.awssdk.services.scheduler.model.ListSchedulesRequest;
import software.amazon.awssdk.services.scheduler.model.ListSchedulesResponse;
import software.amazon.awssdk.services.scheduler.model.RetryPolicy;
import software.amazon.awssdk.services.scheduler.model.ScheduleState;
import software.amazon.awssdk.services.scheduler.model.ScheduleSummary;
import software.amazon.awssdk.services.scheduler.model.UpdateScheduleRequest;
import software.amazon.awssdk.services.scheduler.model.UpdateScheduleResponse;

class ServicesUnitTest {

    private AppConfig appConfig;
    private ObjectMapper objectMapper;
    private SchedulerClient schedulerClient;
    private EventBridgeClient eventBridgeClient;
    private CloudWatchClient cloudWatchClient;
    private AwsCronEvaluator cronEvaluator;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        cronEvaluator = new AwsCronEvaluator();
        schedulerClient = mock(SchedulerClient.class);
        eventBridgeClient = mock(EventBridgeClient.class);
        cloudWatchClient = mock(CloudWatchClient.class);
        appConfig = new AppConfig() {
            @Override public String env() { return "test"; }
            @Override public AuthConfig auth() {
                return new AuthConfig() {
                    @Override public boolean enabled() { return false; }
                    @Override public String mode() { return "forward-auth"; }
                    @Override public String logtoEndpoint() { return "https://sodaxw.logto.app"; }
                };
            }
            @Override public AwsConfig aws() {
                return new AwsConfig() {
                    @Override public String region() { return "ap-southeast-1"; }
                    @Override public String accountId() { return "186004631963"; }
                    @Override public Optional<String> accessKeyId() { return Optional.of("AKIA_TEST"); }
                    @Override public Optional<String> secretAccessKey() { return Optional.of("SECRET_TEST"); }
                    @Override public String schedulerGroup() { return "default"; }
                    @Override public String eventBusName() { return "default"; }
                };
            }
            @Override public GitHubConfig github() {
                return new GitHubConfig() {
                    @Override public Optional<String> token() { return Optional.of("ghp_valid_token"); }
                    @Override public String apiBaseUrl() { return "https://api.github.com"; }
                    @Override public Optional<String> proxyHost() { return Optional.of("10.0.1.227"); }
                    @Override public int proxyPort() { return 7890; }
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

    @Test
    void shouldDiscoverThreeHopTopologyAndCorrelateTargets() {
        String destArn = "arn:aws:events:ap-southeast-1:186004631963:api-destination/github-trigger-nuc-batch/111";
        when(eventBridgeClient.listApiDestinations(any(ListApiDestinationsRequest.class)))
                .thenReturn(ListApiDestinationsResponse.builder()
                        .apiDestinations(ApiDestination.builder()
                                .name("github-trigger-nuc-batch")
                                .apiDestinationArn(destArn)
                                .invocationEndpoint("https://api.github.com/repos/nvd11/sms-flink-etl/actions/workflows/trigger-nuc-batch-runner.yml/dispatches")
                                .build())
                        .build());

        when(eventBridgeClient.listRules(any(ListRulesRequest.class)))
                .thenReturn(ListRulesResponse.builder()
                        .rules(
                                Rule.builder()
                                        .name("rule-trigger-github-daily-report")
                                        .arn("arn:aws:events:ap-southeast-1:186004631963:rule/rule-trigger-github-daily-report")
                                        .eventPattern("{\"source\":[\"finance.sms-flink-report\"],\"detail-type\":[\"daily-report\"]}")
                                        .build(),
                                Rule.builder()
                                        .name("rule-trigger-github-workflow")
                                        .arn("arn:aws:events:ap-southeast-1:186004631963:rule/rule-trigger-github-workflow")
                                        .eventPattern("{\"source\":[\"finance.sms-flink\"]}")
                                        .build()
                        )
                        .build());

        when(eventBridgeClient.listTargetsByRule(any(ListTargetsByRuleRequest.class)))
                .thenAnswer(inv -> {
                    ListTargetsByRuleRequest req = inv.getArgument(0);
                    if ("rule-trigger-github-daily-report".equals(req.rule())) {
                        return ListTargetsByRuleResponse.builder()
                                .targets(Target.builder()
                                        .id("t1")
                                        .arn(destArn)
                                        .input("{\"ref\":\"main\",\"inputs\":{\"target_env\":\"finance\",\"pipeline_stage\":\"report\",\"report_period\":\"DAILY\"}}")
                                        .build())
                                .build();
                    }
                    return ListTargetsByRuleResponse.builder()
                            .targets(Target.builder()
                                    .id("t2")
                                    .arn(destArn)
                                    .input("{\"ref\":\"main\"}")
                                    .build())
                            .build();
                });

        when(schedulerClient.listSchedules(any(ListSchedulesRequest.class)))
                .thenReturn(ListSchedulesResponse.builder()
                        .schedules(
                                ScheduleSummary.builder().name("sms-flink-report-daily").build(),
                                ScheduleSummary.builder().name("test-sms-flink-cron").build()
                        )
                        .build());

        when(schedulerClient.getSchedule(any(GetScheduleRequest.class)))
                .thenAnswer(inv -> {
                    GetScheduleRequest req = inv.getArgument(0);
                    if ("sms-flink-report-daily".equals(req.name())) {
                        return GetScheduleResponse.builder()
                                .name("sms-flink-report-daily")
                                .groupName("default")
                                .arn("arn:aws:scheduler:ap-southeast-1:186004631963:schedule/default/sms-flink-report-daily")
                                .description("Daily report")
                                .state(ScheduleState.ENABLED)
                                .scheduleExpression("cron(50 23 * * ? *)")
                                .scheduleExpressionTimezone("Asia/Shanghai")
                                .flexibleTimeWindow(FlexibleTimeWindow.builder().mode(FlexibleTimeWindowMode.OFF).build())
                                .target(software.amazon.awssdk.services.scheduler.model.Target.builder()
                                        .arn("arn:aws:events:ap-southeast-1:186004631963:event-bus/default")
                                        .roleArn("arn:aws:iam::186004631963:role/scheduler-role")
                                        .retryPolicy(RetryPolicy.builder().maximumEventAgeInSeconds(3600).maximumRetryAttempts(5).build())
                                        .eventBridgeParameters(EventBridgeParameters.builder()
                                                .source("finance.sms-flink-report")
                                                .detailType("daily-report")
                                                .build())
                                        .build())
                                .build();
                    }
                    return GetScheduleResponse.builder()
                            .name("test-sms-flink-cron")
                            .groupName("default")
                            .arn("arn:aws:scheduler:ap-southeast-1:186004631963:schedule/default/test-sms-flink-cron")
                            .description("Incremental ingest")
                            .state(ScheduleState.ENABLED)
                            .scheduleExpression("rate(10 minutes)")
                            .scheduleExpressionTimezone("UTC")
                            .target(software.amazon.awssdk.services.scheduler.model.Target.builder()
                                    .eventBridgeParameters(EventBridgeParameters.builder()
                                            .source("finance.sms-flink")
                                            .detailType("minutely-tick")
                                            .build())
                                    .build())
                            .build();
                });

        AwsTopologyService topologyService = new AwsTopologyService(appConfig, schedulerClient, eventBridgeClient, objectMapper);
        List<ResolvedScheduleNode> nodes = topologyService.getResolvedSchedules(true);
        assertThat(nodes).hasSize(2);

        ResolvedScheduleNode daily = topologyService.getResolvedScheduleByName("sms-flink-report-daily", false);
        assertThat(daily.category()).isEqualTo("FINANCE_REPORT");
        assertThat(daily.routing().githubOwner()).isEqualTo("nvd11");
        assertThat(daily.routing().githubRepo()).isEqualTo("sms-flink-etl");
        assertThat(daily.routing().workflowFile()).isEqualTo("trigger-nuc-batch-runner.yml");
        assertThat(daily.routing().defaultInputs()).containsEntry("report_period", "DAILY");

        assertThatThrownBy(() -> topologyService.getResolvedScheduleByName("non-existent", false))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void shouldAggregate7DayCloudWatchMetrics() {
        Instant now = Instant.now();
        when(cloudWatchClient.getMetricData(any(GetMetricDataRequest.class)))
                .thenReturn(GetMetricDataResponse.builder()
                        .metricDataResults(
                                MetricDataResult.builder()
                                        .id("m_invocations")
                                        .timestamps(now)
                                        .values(7.0)
                                        .build(),
                                MetricDataResult.builder()
                                        .id("m_failed")
                                        .timestamps(now)
                                        .values(1.0)
                                        .build()
                        )
                        .build());

        CloudWatchService cwService = new CloudWatchService(cloudWatchClient);
        Metrics7dView view = cwService.fetchRuleMetrics7d("rule-trigger-github-daily-report");
        assertThat(view.invocations()).isEqualTo(7L);
        assertThat(view.successfulInvocations()).isEqualTo(6L);
        assertThat(view.failedInvocations()).isEqualTo(1L);
        assertThat(view.dailyBuckets()).hasSize(7);

        Metrics7dView empty = cwService.fetchRuleMetrics7d(null);
        assertThat(empty.invocations()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldCorrelateRunsViaTier1AndTier2AndStreamLogs() throws Exception {
        GitHubActionsClient ghClient = new GitHubActionsClient(appConfig, objectMapper);
        HttpClient mockHttp = mock(HttpClient.class);

        HttpResponse<String> runsRes = mock(HttpResponse.class);
        when(runsRes.statusCode()).thenReturn(200);
        when(runsRes.body()).thenReturn("""
                {
                  "workflow_runs": [
                    {
                      "id": 101,
                      "run_number": 88,
                      "display_title": "Batch ETL [report | DAILY | finance]",
                      "event": "workflow_dispatch",
                      "status": "completed",
                      "conclusion": "success",
                      "created_at": "2026-10-17T10:15:00Z",
                      "run_started_at": "2026-10-17T10:15:02Z",
                      "updated_at": "2026-10-17T10:17:02Z",
                      "html_url": "https://github.com/nvd11/sms-flink-etl/actions/runs/101"
                    },
                    {
                      "id": 102,
                      "run_number": 87,
                      "display_title": "Trigger NUC Batch Runner",
                      "event": "workflow_dispatch",
                      "status": "completed",
                      "conclusion": "success",
                      "created_at": "2026-10-16T15:50:12Z",
                      "run_started_at": "2026-10-16T15:50:12Z",
                      "updated_at": "2026-10-16T15:51:12Z",
                      "html_url": "https://github.com/nvd11/sms-flink-etl/actions/runs/102"
                    },
                    {
                      "id": 103,
                      "run_number": 86,
                      "display_title": "Batch ETL [all | INCREMENTAL | finance]",
                      "event": "workflow_dispatch",
                      "status": "completed",
                      "conclusion": "success",
                      "created_at": "2026-10-16T15:40:05Z",
                      "run_started_at": "2026-10-16T15:40:05Z",
                      "updated_at": "2026-10-16T15:40:55Z",
                      "html_url": "https://github.com/nvd11/sms-flink-etl/actions/runs/103"
                    }
                  ]
                }
                """);

        HttpResponse<String> jobsRes = mock(HttpResponse.class);
        when(jobsRes.statusCode()).thenReturn(200);
        when(jobsRes.body()).thenReturn("""
                {
                  "jobs": [
                    {
                      "id": 9001,
                      "name": "Dispatch Ephemeral Batch Job to NUC",
                      "status": "completed",
                      "conclusion": "failure",
                      "started_at": "2026-10-17T10:15:02Z",
                      "completed_at": "2026-10-17T10:17:02Z",
                      "html_url": "https://github.com/nvd11/sms-flink-etl/actions/runs/101/job/9001",
                      "steps": [
                        {
                          "number": 1,
                          "name": "Setup SSH Key & Execute Batch Job on K3s",
                          "status": "completed",
                          "conclusion": "failure",
                          "started_at": "2026-10-17T10:15:05Z",
                          "completed_at": "2026-10-17T10:17:00Z"
                        }
                      ]
                    }
                  ]
                }
                """);

        HttpResponse< java.io.InputStream> logStreamRes = mock(HttpResponse.class);
        when(logStreamRes.statusCode()).thenReturn(200);
        when(logStreamRes.body()).thenAnswer(i -> new ByteArrayInputStream(
                "line1\nline2\nline3 [ERROR] Job failed\n".getBytes(StandardCharsets.UTF_8)
        ));

        when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(inv -> {
                    HttpRequest req = inv.getArgument(0);
                    String uri = req.uri().toString();
                    if (uri.contains("/workflows/") && uri.contains("/runs")) {
                        return runsRes;
                    }
                    if (uri.contains("/actions/runs/101/jobs")) {
                        return jobsRes;
                    }
                    if (uri.contains("/actions/jobs/9001/logs")) {
                        return logStreamRes;
                    }
                    HttpResponse<String> ok204 = mock(HttpResponse.class);
                    when(ok204.statusCode()).thenReturn(204);
                    return ok204;
                });

        ghClient.setHttpClientForTesting(mockHttp);
        ExecutionCorrelatorService correlator = new ExecutionCorrelatorService(appConfig, ghClient, cronEvaluator);

        ResolvedScheduleNode dailyNode = new ResolvedScheduleNode(
                "sms-flink-report-daily", "default", "arn:1", "Daily", "FINANCE_REPORT", "ENABLED",
                "cron(50 23 * * ? *)", "Asia/Shanghai", "2026-10-14T00:00:00Z", "OFF",
                new RetryPolicyView(86400, 185), "role", new ScheduleRoutingView(
                        "default", "finance.sms-flink-report", "daily-report", "rule-daily", "arn:rule",
                        "dest", "https://api.github.com/...", "nvd11", "sms-flink-etl",
                        "trigger-nuc-batch-runner.yml", "main",
                        Map.of("pipeline_stage", "report", "report_period", "DAILY")
                )
        );
        ResolvedScheduleNode cronNode = new ResolvedScheduleNode(
                "test-sms-flink-cron", "default", "arn:2", "Cron", "FINANCE_ETL", "ENABLED",
                "rate(10 minutes)", "UTC", "2026-10-14T00:00:00Z", "OFF",
                new RetryPolicyView(86400, 185), "role", new ScheduleRoutingView(
                        "default", "finance.sms-flink", "minutely-tick", "rule-cron", "arn:rule2",
                        "dest", "https://api.github.com/...", "nvd11", "sms-flink-etl",
                        "trigger-nuc-batch-runner.yml", "main",
                        Map.of("pipeline_stage", "all")
                )
        );

        List<ResolvedScheduleNode> peers = List.of(dailyNode, cronNode);
        ScheduleRunsResponse dailyRuns = correlator.getAttributedRunsForSchedule(dailyNode, peers, 20, true);
        assertThat(dailyRuns.runs()).hasSize(2);
        assertThat(dailyRuns.runs().get(0).runId()).isEqualTo(101L);
        assertThat(dailyRuns.runs().get(0).attributionMethod()).isEqualTo("DISPLAY_TITLE");
        assertThat(dailyRuns.runs().get(1).runId()).isEqualTo(102L);
        assertThat(dailyRuns.runs().get(1).attributionMethod()).isEqualTo("CRON_WINDOW");

        ScheduleRunsResponse cronRuns = correlator.getAttributedRunsForSchedule(cronNode, peers, 20, false);
        assertThat(cronRuns.runs()).hasSize(1);
        assertThat(cronRuns.runs().get(0).runId()).isEqualTo(103L);

        RunLogDetailView logs = correlator.getRunLogs(dailyNode, 101L, null, 500);
        assertThat(logs.jobId()).isEqualTo(9001L);
        assertThat(logs.steps()).hasSize(1);
        assertThat(logs.logContent()).contains("[ERROR] Job failed");

        // Verify bounded tail truncation helper directly
        RawLogTail truncatedTail = GitHubActionsClient.readBoundedTail(
                new ByteArrayInputStream("l1\nl2\nl3\nl4\nl5\n".getBytes(StandardCharsets.UTF_8)),
                3,
                1024
        );
        assertThat(truncatedTail.truncated()).isTrue();
        assertThat(truncatedTail.totalLines()).isEqualTo(5);
        assertThat(truncatedTail.content()).isEqualTo("l3\nl4\nl5\n");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldControlScheduleTriggerStateAndConfig() throws Exception {
        AwsTopologyService topologyService = mock(AwsTopologyService.class);
        ExecutionCorrelatorService correlatorService = mock(ExecutionCorrelatorService.class);
        GitHubActionsClient ghClient = new GitHubActionsClient(appConfig, objectMapper);
        HttpClient mockHttp = mock(HttpClient.class);
        HttpResponse<String> ok204 = mock(HttpResponse.class);
        when(ok204.statusCode()).thenReturn(204);
        when(mockHttp.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(ok204);
        ghClient.setHttpClientForTesting(mockHttp);

        ResolvedScheduleNode dailyNode = new ResolvedScheduleNode(
                "sms-flink-report-daily", "default", "arn:1", "Daily", "FINANCE_REPORT", "ENABLED",
                "cron(50 23 * * ? *)", "Asia/Shanghai", "2026-10-14T00:00:00Z", "OFF",
                new RetryPolicyView(86400, 185), "role", new ScheduleRoutingView(
                        "default", "finance.sms-flink-report", "daily-report", "rule-daily", "arn:rule",
                        "dest", "https://api.github.com/...", "nvd11", "sms-flink-etl",
                        "trigger-nuc-batch-runner.yml", "main",
                        Map.of("pipeline_stage", "report", "report_period", "DAILY")
                )
        );
        when(topologyService.getResolvedScheduleByName("sms-flink-report-daily", false)).thenReturn(dailyNode);

        when(eventBridgeClient.putEvents(any(PutEventsRequest.class)))
                .thenReturn(PutEventsResponse.builder()
                        .failedEntryCount(0)
                        .entries(PutEventsResultEntry.builder().eventId("evt-123").build())
                        .build());

        when(schedulerClient.getSchedule(any(GetScheduleRequest.class)))
                .thenReturn(GetScheduleResponse.builder()
                        .name("sms-flink-report-daily")
                        .groupName("default")
                        .state(ScheduleState.ENABLED)
                        .scheduleExpression("cron(50 23 * * ? *)")
                        .scheduleExpressionTimezone("Asia/Shanghai")
                        .description("Old desc")
                        .flexibleTimeWindow(FlexibleTimeWindow.builder().mode(FlexibleTimeWindowMode.OFF).build())
                        .target(software.amazon.awssdk.services.scheduler.model.Target.builder().arn("arn:bus").roleArn("arn:role").build())
                        .build());

        when(schedulerClient.updateSchedule(any(UpdateScheduleRequest.class)))
                .thenReturn(UpdateScheduleResponse.builder().scheduleArn("arn:updated").build());

        JobControlService controlService = new JobControlService(
                appConfig, schedulerClient, eventBridgeClient, ghClient,
                topologyService, correlatorService, cronEvaluator, objectMapper
        );

        // 1. Trigger via EVENTBRIDGE
        TriggerScheduleResult ebRes = controlService.triggerSchedule(
                "sms-flink-report-daily",
                new TriggerScheduleRequest("EVENTBRIDGE", "main", Map.of("extra", "1")),
                "nvd11"
        );
        assertThat(ebRes.mode()).isEqualTo("EVENTBRIDGE");
        assertThat(ebRes.eventId()).isEqualTo("evt-123");

        // 2. Trigger via GITHUB_DIRECT
        TriggerScheduleResult ghRes = controlService.triggerSchedule(
                "sms-flink-report-daily",
                new TriggerScheduleRequest("GITHUB_DIRECT", "main", Map.of("report_period", "WEEKLY")),
                "nvd11"
        );
        assertThat(ghRes.mode()).isEqualTo("GITHUB_DIRECT");
        assertThat(ghRes.eventId()).startsWith("gh-dispatch-");

        // 3. Update state
        UpdateScheduleStateResult stateRes = controlService.updateScheduleState(
                "sms-flink-report-daily",
                new UpdateScheduleStateRequest("DISABLED")
        );
        assertThat(stateRes.previousState()).isEqualTo("ENABLED");
        assertThat(stateRes.currentState()).isEqualTo("DISABLED");

        // 4. Update config
        UpdateScheduleConfigResult cfgRes = controlService.updateScheduleConfig(
                "sms-flink-report-daily",
                new UpdateScheduleConfigRequest("cron(30 23 * * ? *)", "Asia/Shanghai", "New desc", "ENABLED")
        );
        assertThat(cfgRes.humanReadableCron()).isEqualTo("Every day at 23:30 (Asia/Shanghai)");

        // 5. Error & edge branches
        assertThatThrownBy(() -> controlService.triggerSchedule(
                "sms-flink-report-daily",
                new TriggerScheduleRequest("INVALID_MODE", "main", Map.of()),
                "nvd11"
        )).isInstanceOf(ApiException.class);

        assertThatThrownBy(() -> controlService.updateScheduleState(
                "sms-flink-report-daily",
                new UpdateScheduleStateRequest("INVALID")
        )).isInstanceOf(ApiException.class);

        assertThatThrownBy(() -> controlService.updateScheduleConfig(
                "sms-flink-report-daily",
                new UpdateScheduleConfigRequest("", "Asia/Shanghai", "Desc", "ENABLED")
        )).isInstanceOf(ApiException.class);
    }

    @Test
    void shouldCoverAwsClientProducerAndGlobalExceptionMapper() {
        AwsClientProducer producer = new AwsClientProducer(appConfig);
        try (SchedulerClient sc = producer.produceSchedulerClient();
             EventBridgeClient eb = producer.produceEventBridgeClient();
             CloudWatchClient cw = producer.produceCloudWatchClient()) {
            assertThat(sc).isNotNull();
            assertThat(eb).isNotNull();
            assertThat(cw).isNotNull();
        }

        GlobalExceptionMapper mapper = new GlobalExceptionMapper();
        Response r1 = mapper.toResponse(ApiException.badRequest(40001, "Bad cron"));
        assertThat(r1.getStatus()).isEqualTo(400);

        Response r2 = mapper.toResponse(new WebApplicationException("Not allowed", 405));
        assertThat(r2.getStatus()).isEqualTo(405);

        Response r3 = mapper.toResponse(new RuntimeException("Unexpected boom"));
        assertThat(r3.getStatus()).isEqualTo(500);
        assertThat(((ApiResponse<?>) r3.getEntity()).code()).isEqualTo(50000);
    }
}
