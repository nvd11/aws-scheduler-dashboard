package asia.jppwl.scheduler.resource;

import asia.jppwl.scheduler.model.ApiException;
import asia.jppwl.scheduler.model.ApiResponse;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class GlobalExceptionMapper implements ExceptionMapper<Throwable> {

    @Override
    public Response toResponse(Throwable exception) {
        if (exception instanceof ApiException apiEx) {
            return Response.status(apiEx.getHttpStatus())
                    .type(MediaType.APPLICATION_JSON)
                    .entity(ApiResponse.error(apiEx.getErrorCode(), apiEx.getMessage()))
                    .build();
        }
        if (exception instanceof WebApplicationException webEx) {
            int status = webEx.getResponse() != null ? webEx.getResponse().getStatus() : 500;
            return Response.status(status)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(ApiResponse.error(status * 100, webEx.getMessage()))
                    .build();
        }
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .type(MediaType.APPLICATION_JSON)
                .entity(ApiResponse.error(50000, "Internal server error: " + exception.getMessage()))
                .build();
    }
}
