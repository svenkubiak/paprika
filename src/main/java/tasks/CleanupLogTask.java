package tasks;

import io.mangoo.annotations.Run;
import jakarta.inject.Inject;
import services.RequestLogService;

import java.util.Objects;

public class CleanupLogTask {
    private final RequestLogService requestLogService;

    @Inject
    public CleanupLogTask(RequestLogService requestLogService) {
        this.requestLogService = Objects.requireNonNull(requestLogService, "requestLogService can not be null");
    }

    @Run(at = "Every 1h")
    public void execute() {
        requestLogService.purgeAllExpired();
    }
}
