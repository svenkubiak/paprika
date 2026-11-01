package services;

import models.RealtimeConnection;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

final class RecordingConnection implements RealtimeConnection {
    // Generous: an event from another instance travels through MongoDB first
    private static final long TIMEOUT_SECONDS = 5;

    private final List<Event> events = new CopyOnWriteArrayList<>();
    private volatile boolean open = true;

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void send(String data, String eventName, String id) {
        events.add(new Event(data, eventName, id));
    }

    @Override
    public void close() {
        open = false;
    }

    List<Event> events() {
        return List.copyOf(events);
    }

    List<String> eventNames() {
        return events.stream().map(Event::eventName).toList();
    }

    void awaitEventCount(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (events.size() < expected && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat(events.size(), is(expected));
    }

    void awaitClosed() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (open && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat("the stream should have been closed", open, is(false));
    }

    record Event(String data, String eventName, String id) {
    }
}
