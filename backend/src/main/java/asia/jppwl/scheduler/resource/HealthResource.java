package asia.jppwl.scheduler.resource;

import asia.jppwl.scheduler.model.SchedulerDtos.ProbeStatus;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class HealthResource {

    @GET
    @Path("/live")
    public ProbeStatus liveness() {
        return new ProbeStatus("UP", "scheduler-backend", isNativeImage());
    }

    @GET
    @Path("/ready")
    public ProbeStatus readiness() {
        return new ProbeStatus("UP", "scheduler-backend", isNativeImage());
    }

    private static boolean isNativeImage() {
        return System.getProperty("org.graalvm.nativeimage.imagecode") != null;
    }
}
