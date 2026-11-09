package app;

import com.google.inject.AbstractModule;
import filters.RequestLogFilter;
import handlers.PaprikaResponseHandler;
import io.mangoo.interfaces.MangooBootstrap;
import io.mangoo.interfaces.filters.OncePerRequestFilter;
import io.mangoo.routing.handlers.ResponseHandler;
import jakarta.inject.Singleton;
import services.MongoRealtimeCluster;
import services.RealtimeCluster;

@Singleton
public class Module extends AbstractModule {
    @Override
    protected void configure() {
        bind(MangooBootstrap.class).to(Bootstrap.class);
        // mangoo runs this on every controller route, giving the request log a start time and id.
        bind(OncePerRequestFilter.class).to(RequestLogFilter.class);
        // Every controller response passes the ResponseHandler, where the request log entry is written.
        bind(ResponseHandler.class).to(PaprikaResponseHandler.class);
        // Realtime streams stay on the instance that accepted them; this connects the instances.
        bind(RealtimeCluster.class).to(MongoRealtimeCluster.class);
    }
}
