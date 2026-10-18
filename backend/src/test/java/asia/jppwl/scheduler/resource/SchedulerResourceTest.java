package asia.jppwl.scheduler.resource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import asia.jppwl.scheduler.model.ApiException;
import asia.jppwl.scheduler.model.SchedulerDtos.DailyMetricBucket;
import asia.jppwl.scheduler.model.SchedulerDtos.ExecutionRecord;
import asia.jppwl.scheduler.model.SchedulerDtos.JobSummaryItem;
import asia.jppwl.scheduler.model.SchedulerDtos.Metrics7dView;
import asia.jppwl.scheduler.model.SchedulerDtos.RetryPolicyView;
import asia.jppwl.scheduler.model.SchedulerDtos.RunLogDetailView;
import asia.jppwl.scheduler.model.SchedulerDtos.ScheduleRoutingView;
import asia.jppwl.scheduler.model.SchedulerDtos.ScheduleRunsResponse;
import asia.jppwl.scheduler.model.SchedulerDtos.StepSummaryItem;
import asia.jppwl.scheduler.model.SchedulerDtos.TriggerScheduleResult;
import asia.jppwl.scheduler.model.SchedulerDtos.UpdateScheduleConfigResult;
import asia.jppwl.scheduler.model.SchedulerDtos.UpdateScheduleStateResult;
import asia.jppwl.scheduler.service.AwsTopologyService;
import asia.jppwl.scheduler.service.AwsTopologyService.ResolvedScheduleNode;
import asia.jppwl.scheduler.service.CloudWatchService;
import asia.jppwl.scheduler.service.ExecutionCorrelatorService;
import asia.jppwl.scheduler.service.JobControlService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SchedulerResourceTest {

    @InjectMock
    AwsTopologyService topologyService;

    @InjectMock
    CloudWatchService cloudWatchService;

    @InjectMock
    ExecutionCorrelatorService correlatorService;

    @InjectMock
    JobControlService jobControlService;

    private ResolvedScheduleNode sampleNode;

    @BeforeEach
    void setUp() {
        sampleNode = new ResolvedScheduleNode(
                "sms-flink-report-daily",
                "default",
                "arn:aws:scheduler:ap-southeast-1:186004631963:schedule/default/sms-flink-report-daily",
                "Nightly 23:50 financial daily report",
                "FINANCE_REPORT",
                "ENABLED",
                "cron(50 23 * * ? *)",
                "Asia/Shanghai",
                "2026-10-14T18:30:06Z",
                "OFF",
                new RetryPolicyView(86400, 185),
                "arn:aws:iam::186004631963:role/scheduler-role",
                new ScheduleRoutingView(
                        "default",
                        "finance.sms-flink-report",
                        "daily-report",
                        "rule-trigger-github-daily-report",
                        "arn:aws:events:ap-southeast-1:186004631963:rule/rule-trigger-github-daily-report",
                        "github-trigger-nuc-batch",
                        "https://api.github.com/repos/nvd11/sms-flink-etl/actions/workflows/trigger-nuc-batch-runner.yml/dispatches",
                        "nvd11",
                        "sms-flink-etl",
                        "trigger-nuc-batch-runner.yml",
                        "main",
                        Map.of("target_env", "finance", "pipeline_stage", "report", "report_period", "DAILY")
                )
        );

        when(topologyService.getResolvedSchedules(anyBoolean())).thenReturn(List.of(sampleNode));
        when(topologyService.getResolvedScheduleByName(eq("sms-flink-report-daily"), anyBoolean())).thenReturn(sampleNode);
        when(topologyService.getResolvedScheduleByName(eq("missing"), anyBoolean()))
                .thenThrow(ApiException.notFound(40401, "Schedule 'missing' not found"));

        when(cloudWatchService.fetchRuleMetrics7d(anyString()))
                .thenReturn(new Metrics7dView(
                        7L, 7L, 0L,
                        List.of(new DailyMetricBucket("2026-10-18", 1L, 0L))
                ));

        ExecutionRecord sampleRun = new ExecutionRecord(
                1948271625L,
                88,
                "Batch ETL [report - DAILY]",
                "DISPLAY_TITLE",
                "workflow_dispatch",
                "completed",
                "success",
                "2026-10-17T15:50:04Z",
                "2026-10-17T15:52:19Z",
                135L,
                "https://github.com/nvd11/sms-flink-etl/actions/runs/1948271625"
        );

        when(correlatorService.getAttributedRunsForSchedule(eq(sampleNode), anyList(), anyInt(), anyBoolean()))
                .thenReturn(new ScheduleRunsResponse(
                        "sms-flink-report-daily",
                        "nvd11/sms-flink-etl",
                        "trigger-nuc-batch-runner.yml",
                        1,
                        List.of(sampleRun)
                ));

        when(correlatorService.getRunLogs(eq(sampleNode), eq(1948271625L), isNull(), anyInt()))
                .thenReturn(new RunLogDetailView(
                        "sms-flink-report-daily",
                        1948271625L,
                        3029184712L,
                        "trigger-nuc-batch",
                        "completed",
                        "success",
                        "2026-10-17T15:50:04Z",
                        "2026-10-17T15:52:19Z",
                        135L,
                        "https://github.com/nvd11/sms-flink-etl/actions/runs/1948271625/job/3029184712",
                        List.of(new JobSummaryItem(3029184712L, "trigger-nuc-batch", "completed", "success", 135L)),
                        List.of(new StepSummaryItem(1, "Trigger Flink Daily Report", "completed", "success", 135L)),
                        false,
                        3,
                        "line1\nline2\n[SUCCESS] Report done\n"
                ));
    }

    @Test
    void shouldExposeHealthAndAuthEndpoints() {
        given()
                .when().get("/live")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"))
                .body("service", equalTo("scheduler-backend"));

        given()
                .when().get("/ready")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"));

        given()
                .when().get("/auth/me")
                .then()
                .statusCode(200)
                .body("code", equalTo(0))
                .body("data.username", equalTo("nvd11"));

        given()
                .contentType(ContentType.JSON)
                .when().post("/auth/logout")
                .then()
                .statusCode(200)
                .body("data.signoutUrl", equalTo("/oauth2/sign_out?rd=/scheduler/"));

        given()
                .when().get("/v1/health")
                .then()
                .statusCode(200)
                .body("code", equalTo(0))
                .body("data.awsRegion", equalTo("ap-southeast-1"))
                .body("data.awsAccountId", equalTo("186004631963"));
    }

    @Test
    void shouldListGetSchedulesRunsAndLogs() {
        given()
                .queryParam("refresh", true)
                .queryParam("state", "ENABLED")
                .queryParam("q", "daily")
                .when().get("/v1/schedules")
                .then()
                .statusCode(200)
                .body("code", equalTo(0))
                .body("data.summary.totalSchedules", equalTo(1))
                .body("data.items", hasSize(1))
                .body("data.items[0].name", equalTo("sms-flink-report-daily"))
                .body("data.items[0].lastExecution.runNumber", equalTo(88));

        given()
                .when().get("/v1/schedules/sms-flink-report-daily")
                .then()
                .statusCode(200)
                .body("code", equalTo(0))
                .body("data.schedule.name", equalTo("sms-flink-report-daily"))
                .body("data.upcomingFireTimes", hasSize(5));

        given()
                .when().get("/v1/schedules/sms-flink-report-daily/runs")
                .then()
                .statusCode(200)
                .body("code", equalTo(0))
                .body("data.runs", hasSize(1))
                .body("data.runs[0].attributionMethod", equalTo("DISPLAY_TITLE"));

        given()
                .when().get("/v1/schedules/sms-flink-report-daily/runs/1948271625/logs")
                .then()
                .statusCode(200)
                .body("code", equalTo(0))
                .body("data.jobId", equalTo(3029184712L))
                .body("data.steps", hasSize(1));

        given()
                .when().get("/v1/schedules/missing")
                .then()
                .statusCode(404)
                .body("code", equalTo(40401));
    }

    @Test
    void shouldTriggerUpdateStateUpdateConfigAndPreviewCron() {
        when(jobControlService.triggerSchedule(eq("sms-flink-report-daily"), org.mockito.ArgumentMatchers.any(), eq("nvd11")))
                .thenReturn(new TriggerScheduleResult(
                        "sms-flink-report-daily",
                        "EVENTBRIDGE",
                        "evt-999",
                        "2026-10-18T08:00:00Z",
                        "nvd11",
                        "nvd11/sms-flink-etl/trigger-nuc-batch-runner.yml",
                        4000
                ));

        given()
                .contentType(ContentType.JSON)
                .body("{\"mode\":\"EVENTBRIDGE\"}")
                .when().post("/v1/schedules/sms-flink-report-daily/trigger")
                .then()
                .statusCode(202)
                .body("code", equalTo(0))
                .body("data.eventId", equalTo("evt-999"));

        when(jobControlService.updateScheduleState(eq("sms-flink-report-daily"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new UpdateScheduleStateResult(
                        "sms-flink-report-daily", "ENABLED", "DISABLED", "arn:1", "2026-10-18T08:00:00Z"
                ));

        given()
                .contentType(ContentType.JSON)
                .body("{\"state\":\"DISABLED\"}")
                .when().patch("/v1/schedules/sms-flink-report-daily/state")
                .then()
                .statusCode(200)
                .body("data.currentState", equalTo("DISABLED"));

        when(jobControlService.updateScheduleConfig(eq("sms-flink-report-daily"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new UpdateScheduleConfigResult(
                        "sms-flink-report-daily",
                        "cron(30 23 * * ? *)",
                        "Asia/Shanghai",
                        "Every day at 23:30 (Asia/Shanghai)",
                        "2026-10-18T15:30:00Z",
                        "ENABLED"
                ));

        given()
                .contentType(ContentType.JSON)
                .body("{\"scheduleExpression\":\"cron(30 23 * * ? *)\",\"timezone\":\"Asia/Shanghai\"}")
                .when().put("/v1/schedules/sms-flink-report-daily")
                .then()
                .statusCode(200)
                .body("data.scheduleExpression", equalTo("cron(30 23 * * ? *)"));

        given()
                .contentType(ContentType.JSON)
                .body("{\"scheduleExpression\":\"cron(0 9 ? * MON *)\",\"timezone\":\"Asia/Shanghai\"}")
                .when().post("/v1/cron/preview")
                .then()
                .statusCode(200)
                .body("data.valid", equalTo(true))
                .body("data.upcomingFireTimes", notNullValue());
    }
}
