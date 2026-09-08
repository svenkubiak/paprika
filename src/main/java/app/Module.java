package app;

import com.google.inject.AbstractModule;
import handlers.PaprikaServerSentEventHandler;
import io.mangoo.interfaces.MangooBootstrap;
import io.mangoo.routing.handlers.ServerSentEventHandler;
import jakarta.inject.Singleton;

@Singleton
public class Module extends AbstractModule {
    @Override
    protected void configure() {
        bind(MangooBootstrap.class).to(Bootstrap.class);
        bind(ServerSentEventHandler.class).to(PaprikaServerSentEventHandler.class);
    }
}
