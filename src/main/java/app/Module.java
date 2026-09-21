package app;

import com.google.inject.AbstractModule;
import filters.RequestLogFilter;
import handlers.PaprikaResponseHandler;
import handlers.PaprikaServerSentEventHandler;
import io.mangoo.interfaces.MangooBootstrap;
import io.mangoo.interfaces.filters.OncePerRequestFilter;
import io.mangoo.routing.handlers.ResponseHandler;
import io.mangoo.routing.handlers.ServerSentEventHandler;
import jakarta.inject.Singleton;

@Singleton
public class Module extends AbstractModule {
    @Override
    protected void configure() {
        bind(MangooBootstrap.class).to(Bootstrap.class);
        bind(ServerSentEventHandler.class).to(PaprikaServerSentEventHandler.class);
        // Binding this makes mangoo run it on every controller route - that is what gives the
        // request log a start timestamp and a correlation id for routes nobody annotated.
        bind(OncePerRequestFilter.class).to(RequestLogFilter.class);
        // Every controller response is rendered through the ResponseHandler, which is where the
        // request log entry is written - once, for all routes.
        bind(ResponseHandler.class).to(PaprikaResponseHandler.class);
    }
}
