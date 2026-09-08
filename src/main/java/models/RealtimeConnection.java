package models;

import java.io.IOException;

public interface RealtimeConnection {
    boolean isOpen();

    void send(String data, String eventName, String id);

    void close() throws IOException;
}
