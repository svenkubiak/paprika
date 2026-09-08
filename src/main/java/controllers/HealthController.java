package controllers;

import io.mangoo.persistence.interfaces.Datastore;
import io.mangoo.routing.Response;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Objects;

public class HealthController {
    private final Datastore datastore;

    @Inject
    public HealthController(Datastore datastore) {
        this.datastore = Objects.requireNonNull(datastore, "datastore must not be null");
    }

    public Response health() {
        boolean dbHealthy = datastore.isHealthy();

        if (dbHealthy) {
            return Response.ok().bodyJson(Map.of("status", "ok", "db", true));
        }

        return Response.serviceUnavailable().bodyJson(Map.of("status", "degraded", "db", false));
    }
}
