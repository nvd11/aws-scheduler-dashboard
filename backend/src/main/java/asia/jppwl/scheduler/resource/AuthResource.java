package asia.jppwl.scheduler.resource;

import asia.jppwl.scheduler.model.ApiResponse;
import asia.jppwl.scheduler.model.SchedulerDtos.LogoutResult;
import asia.jppwl.scheduler.model.SchedulerDtos.UserProfile;
import asia.jppwl.scheduler.security.ForwardAuthIdentityService;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;

@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {

    private final ForwardAuthIdentityService identityService;

    @Inject
    public AuthResource(ForwardAuthIdentityService identityService) {
        this.identityService = identityService;
    }

    @GET
    @Path("/me")
    @RunOnVirtualThread
    public ApiResponse<UserProfile> getCurrentUser(@Context HttpHeaders headers) {
        UserProfile profile = identityService.resolveCurrentUser(headers);
        return ApiResponse.ok(profile);
    }

    @POST
    @Path("/logout")
    public ApiResponse<LogoutResult> logout() {
        return ApiResponse.ok(new LogoutResult("/oauth2/sign_out?rd=/scheduler/"));
    }
}
