import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.connection.ServerDescription;
import io.mangoo.utils.JsonUtils;
import io.mangoo.utils.TotpUtils;
import org.bson.Document;
import tools.jackson.databind.JsonNode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Drives the scenarios against two running Paprika instances (A on 9201, B on 9202), nginx (9200) and a MongoDB
 * replica set (27217-27219). Usage: java -cp <classpath> SmokeTest.java init | run <work directory>
 */
public class SmokeTest {
    static final String A = "http://localhost:9201";
    static final String B = "http://localhost:9202";
    static final String LB = "http://localhost:9200";
    static final String REPLICA_SET = "mongodb://localhost:27217,localhost:27218,localhost:27219/?replicaSet=rs0";
    static final String ADMIN = "admin";
    static final String ADMIN_PASSWORD = "smoke-admin-password-1234";
    static final String USER = "smoke-user";
    static final String USER_PASSWORD = "smoke-user-password-1234";
    static final String COLLECTION = "smoke_notes";
    static final Pattern SETUP_LINK = Pattern.compile("/setup#token=([A-Za-z0-9_-]+)");
    static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    static final List<String> FAILURES = new ArrayList<>();
    static Path work;

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "init" -> init();
            case "run" -> {
                work = Path.of(args[1]);
                run();
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    static void init() throws Exception {
        try (MongoClient client = MongoClients.create("mongodb://localhost:27217/?directConnection=true")) {
            var members = List.of(member(0, 27217), member(1, 27218), member(2, 27219));
            client.getDatabase("admin").runCommand(new Document("replSetInitiate", new Document("_id", "rs0").append("members", members)));
        }
        try (MongoClient client = MongoClients.create(REPLICA_SET)) {
            await("replica set has a primary", Duration.ofSeconds(60), () -> primaryPort(client) > 0);
            System.out.println("Replica set ready, primary on " + primaryPort(client));
        }
    }

    static Document member(int id, int port) {
        return new Document("_id", id).append("host", "localhost:" + port);
    }

    static void run() throws Exception {
        scenario("nginx reaches both instances", SmokeTest::roundRobin);
        scenario("both instances started at once on the empty database", SmokeTest::concurrentFirstStart);
        scenario("admin session works on the other instance", SmokeTest::adminSessionAcrossInstances);
        scenario("admin logout revokes the session on the other instance", SmokeTest::adminLogoutAcrossInstances);
        scenario("second factor across instances, each code accepted once", SmokeTest::twoFactorAcrossInstances);
        scenario("tenant data written on one instance is read on the other", SmokeTest::dataAcrossInstances);
        scenario("realtime subscribe, event and revocation across instances", SmokeTest::realtimeAcrossInstances);
        scenario("files and image variants on the shared storage", SmokeTest::filesAcrossInstances);
        scenario("replica set failover", SmokeTest::failover);
        scenario("graceful shutdown of instance A", SmokeTest::gracefulShutdown);

        System.out.println();
        System.out.println(FAILURES.isEmpty() ? "ALL SCENARIOS PASSED" : FAILURES.size() + " SCENARIO(S) FAILED: " + FAILURES);
        System.exit(FAILURES.isEmpty() ? 0 : 1);
    }

    interface Scenario {
        void run() throws Exception;
    }

    static void scenario(String name, Scenario scenario) {
        var start = Instant.now();
        try {
            scenario.run();
            System.out.printf("PASS  %s (%d ms)%n", name, Duration.between(start, Instant.now()).toMillis());
        } catch (Throwable e) {
            FAILURES.add(name);
            System.out.printf("FAIL  %s: %s%n", name, e.getMessage());
        }
    }

    // --- scenarios ---

    static void roundRobin() throws Exception {
        Set<String> upstreams = new HashSet<>();
        for (var i = 0; i < 6; i++) {
            var response = new Client().get(LB, "/@health/ready");
            check(response.statusCode() == 200, "nginx answered " + response.statusCode());
            upstreams.add(header(response, "X-Upstream"));
        }
        check(upstreams.size() == 2, "expected both instances behind nginx, got " + upstreams);
    }

    static void concurrentFirstStart() throws Exception {
        String linkOfA = lastSetupToken(9201);
        String linkOfB = lastSetupToken(9202);
        check(!linkOfA.equals(linkOfB), "both instances logged the same link");

        // The link instance A logged, completed through instance B: B's own start must not have replaced it
        var admin = new Client();
        var setup = admin.postJson(B, "/api/admin/setup", Map.of("token", linkOfA, "username", ADMIN, "password", ADMIN_PASSWORD));
        check(setup.statusCode() == 200, "setup with the link of A failed on B: " + setup.statusCode() + " " + setup.body());

        var second = new Client().postJson(A, "/api/admin/setup", Map.of("token", linkOfB, "username", ADMIN, "password", ADMIN_PASSWORD));
        check(second.statusCode() == 400, "a second setup was accepted: " + second.statusCode());

        JsonNode tenants = json(admin.get(A, "/api/meta/tenants"));
        check(tenants.size() == 1, "expected one default tenant, got " + tenants);

        for (int port : new int[]{9201, 9202}) {
            String log = Files.readString(work.resolve("app-" + port + ".log"));
            check(!log.contains("NamespaceExists") && !log.contains("E11000"), "instance " + port + " logged a startup race");
        }
    }

    static void adminSessionAcrossInstances() throws Exception {
        var admin = adminOn(A);
        var profile = admin.get(B, "/api/admin/profile");
        check(profile.statusCode() == 200, "B does not see the session of A: " + profile.statusCode());
    }

    static void adminLogoutAcrossInstances() throws Exception {
        var admin = adminOn(A);
        var stolen = admin.copy();
        check(stolen.get(B, "/api/admin/profile").statusCode() == 200, "copied cookie not valid on B before logout");

        admin.postForm(A, "/logout", Map.of());
        int status = stolen.get(B, "/api/admin/profile").statusCode();
        check(status != 200, "revoked cookie still accepted on B: " + status);
    }

    static void twoFactorAcrossInstances() throws Exception {
        String username = "ops-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode invite = json(adminOn(A).postJson(A, "/api/admin/superadmins", Map.of("username", username)));
        var ops = new Client();
        var setup = ops.postJson(B, "/api/admin/setup", Map.of("token", invite.path("token").asString(), "username", username, "password", ADMIN_PASSWORD));
        check(setup.statusCode() == 200, "invite could not be completed on B: " + setup.body());

        // Set up on A, confirmed on B: the secret waits in the session cookie in between
        JsonNode pending = json(ops.postJson(A, "/api/admin/profile/2fa/setup", Map.of("password", ADMIN_PASSWORD)));
        String secret = pending.path("secret").asString();
        var confirmed = ops.postJson(B, "/api/admin/profile/2fa/confirm", Map.of("code", freshCode(secret)));
        check(confirmed.statusCode() == 200, "2FA could not be confirmed on B: " + confirmed.body());

        // The confirmation used the code of this step, so the sign-in waits for the next one
        String code = nextStepCode(secret);

        var first = new Client();
        check(json(first.postJson(A, "/api/admin/token", Map.of("username", username, "password", ADMIN_PASSWORD)))
                .path("requiresTwoFactor").asBoolean(false), "A did not ask for the second factor");
        var token = first.postJson(B, "/api/admin/token/2fa", Map.of("code", code));
        check(token.statusCode() == 200 && token.body().contains("accessToken"), "second step on B failed: " + token.body());

        var replay = new Client();
        replay.postJson(B, "/api/admin/token", Map.of("username", username, "password", ADMIN_PASSWORD));
        var replayed = replay.postJson(A, "/api/admin/token/2fa", Map.of("code", code));
        check(replayed.statusCode() != 200, "a used code was accepted again on A: " + replayed.statusCode());
    }

    static void dataAcrossInstances() throws Exception {
        var admin = adminOn(A);
        String tenantId = json(admin.get(B, "/api/meta/tenants")).get(0).path("id").asString();
        admin.postForm(B, "/admin/switch-tenant", Map.of("tenantId", tenantId, "redirect", "/"));

        var collection = admin.postJson(A, "/api/meta/collections/" + COLLECTION, Map.of(
                "fields", List.of(
                        Map.of("name", "title", "type", "STRING", "required", true, "nullable", false),
                        Map.of("name", "picture", "type", "FILE", "required", false, "nullable", true,
                                "options", Map.of("maxSize", 5_242_880, "maxSelect", 1, "imageWidths", List.of(32)))),
                "rules", Map.of("listRule", "*", "viewRule", "*", "createRule", "*", "updateRule", "*", "deleteRule", "*", "ownerField", "owner")));
        check(collection.statusCode() == 201, "collection not created on A: " + collection.statusCode() + " " + collection.body());

        var user = admin.postJson(B, "/api/meta/tenants/" + tenantId + "/users", Map.of("username", USER, "password", USER_PASSWORD));
        check(user.statusCode() == 201, "tenant user not created on B: " + user.statusCode() + " " + user.body());

        var client = userOn(A);
        JsonNode created = json(client.postJson(A, "/api/collections/" + COLLECTION, Map.of("title", "written on A")));
        String id = created.path("id").asString();
        var read = client.get(B, "/api/collections/" + COLLECTION + "/" + id);
        check(read.statusCode() == 200 && read.body().contains("written on A"), "B does not read the record of A: " + read.body());
    }

    static void realtimeAcrossInstances() throws Exception {
        var client = userOn(A);
        try (var stream = new EventStream(B)) {
            String clientId = JsonUtils.getMapper().readTree(stream.awaitEvent("connect", null, Duration.ofSeconds(5)))
                    .path("clientId").asString();

            var subscribe = client.postJson(A, "/api/realtime/subscribe", Map.of("clientId", clientId, "subscriptions", List.of(COLLECTION)));
            check(subscribe.statusCode() == 204, "subscribe on A for a stream on B: " + subscribe.statusCode() + " " + subscribe.body());
            stream.awaitEvent("subscribed", null, Duration.ofSeconds(5));

            String title = "event-" + UUID.randomUUID();
            client.postJson(A, "/api/collections/" + COLLECTION, Map.of("title", title));
            stream.awaitEvent(COLLECTION, title, Duration.ofSeconds(5));

            var logout = client.postJson(A, "/api/auth/logout", Map.of());
            check(logout.statusCode() == 200, "logout on A failed: " + logout.statusCode());
            check(stream.closed(Duration.ofSeconds(5)), "the stream on B stayed open after the logout on A");
        }
    }

    static void filesAcrossInstances() throws Exception {
        var client = userOn(A);
        byte[] png = png(64);
        var upload = client.postMultipart(A, "/api/collections/" + COLLECTION, "logo.png", "image/png", png);
        check(upload.statusCode() == 201, "upload on A failed: " + upload.statusCode() + " " + upload.body());
        String id = json(upload).path("id").asString();
        String file = "/api/collections/" + COLLECTION + "/" + id + "/files/picture";

        var original = client.getBytes(B, file);
        check(original.statusCode() == 200 && Arrays.equals(original.body(), png), "B does not serve the original A stored");

        await("the variant rendered on A is served by B", Duration.ofSeconds(30),
                () -> "32".equals(header(silentlyGet(() -> client.getBytes(B, file + "?width=32")), "X-Image-Width")));

        try (Stream<Path> files = Files.walk(work.resolve("storage"))) {
            List<String> temporary = files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(".") && name.endsWith(".tmp"))
                    .toList();
            check(temporary.isEmpty(), "temporary files left in the storage: " + temporary);
        }
    }

    static void failover() throws Exception {
        try (MongoClient client = MongoClients.create(REPLICA_SET)) {
            await("primary discovered", Duration.ofSeconds(30), () -> primaryPort(client) > 0);
            int primary = primaryPort(client);
            long pid = Long.parseLong(Files.readString(work.resolve("mongod-" + primary + ".pid")).trim());
            System.out.println("      killing primary on " + primary + " (pid " + pid + ")");
            ProcessHandle.of(pid).orElseThrow().destroyForcibly();

            var start = Instant.now();
            await("new primary", Duration.ofSeconds(60), () -> {
                int current = primaryPort(client);
                return current > 0 && current != primary;
            });
            System.out.println("      new primary on " + primaryPort(client) + " after " + Duration.between(start, Instant.now()).toMillis() + " ms");
        }

        await("sign-in and writes on A and B after failover", Duration.ofSeconds(60), () -> writes(A) && writes(B));

        // The broker reconnects with backoff; events in between are lost by design
        var client = userOn(A);
        try (var stream = new EventStream(B)) {
            String clientId = JsonUtils.getMapper().readTree(stream.awaitEvent("connect", null, Duration.ofSeconds(5)))
                    .path("clientId").asString();
            await("subscribe across instances after failover", Duration.ofSeconds(60),
                    () -> silentlyGet(() -> client.postJson(A, "/api/realtime/subscribe",
                            Map.of("clientId", clientId, "subscriptions", List.of(COLLECTION)))).statusCode() == 204
                            && stream.hasEvent("subscribed", null));
            String title = "after-failover-" + UUID.randomUUID();
            await("event across instances after failover", Duration.ofSeconds(60), () -> {
                silently(() -> client.postJson(A, "/api/collections/" + COLLECTION, Map.of("title", title)));
                return stream.waitFor(COLLECTION, title, Duration.ofSeconds(2));
            });
        }
    }

    static void gracefulShutdown() throws Exception {
        long pid = Long.parseLong(Files.readString(work.resolve("app-9201.pid")).trim());
        var stream = new EventStream(A);
        stream.awaitEvent("connect", null, Duration.ofSeconds(5));

        ProcessHandle.of(pid).orElseThrow().destroy();
        var stopping = Instant.now();

        int ready = 0;
        int live = 0;
        while (Duration.between(stopping, Instant.now()).toMillis() < 2000 && ready != 503) {
            try {
                ready = new Client().get(A, "/@health/ready").statusCode();
                live = new Client().get(A, "/@health/live").statusCode();
            } catch (Exception e) {
                break;
            }
        }
        check(ready == 503, "readiness during the drain was " + ready);
        check(live == 200, "liveness during the drain was " + live);
        check(stream.closed(Duration.ofSeconds(5)), "the realtime stream on A was not closed");
        stream.close();

        for (var i = 0; i < 4; i++) {
            var response = new Client().get(LB, "/@health/ready");
            check(response.statusCode() == 200, "nginx did not route around A: " + response.statusCode());
        }

        check(ProcessHandle.of(pid).map(handle -> handle.onExit().orTimeout(30, TimeUnit.SECONDS).join()).isPresent(), "instance A did not exit");
        System.out.println("      instance A exited " + Duration.between(stopping, Instant.now()).toMillis() + " ms after SIGTERM");
        check(!Files.readString(work.resolve("app-9201.log")).contains("Open requests did not finish"),
                "an open stream held the shutdown until application.shutdown.timeout");

        var afterwards = userOn(B).postJson(LB, "/api/collections/" + COLLECTION, Map.of("title", "after A stopped"));
        check(afterwards.statusCode() == 201, "writing through nginx after A stopped: " + afterwards.statusCode());
    }

    // --- helpers ---

    static String lastSetupToken(int port) throws Exception {
        Matcher matcher = SETUP_LINK.matcher(Files.readString(work.resolve("app-" + port + ".log")));
        String token = null;
        while (matcher.find()) {
            token = matcher.group(1);
        }
        check(token != null, "no setup link in the log of " + port);
        return token;
    }

    static Client adminOn(String instance) throws Exception {
        var admin = new Client();
        var login = admin.postJson(instance, "/api/admin/login", Map.of("username", ADMIN, "password", ADMIN_PASSWORD));
        check(login.statusCode() == 200, "admin login on " + instance + " failed: " + login.statusCode() + " " + login.body());
        return admin;
    }

    static Client userOn(String instance) throws Exception {
        var client = new Client();
        var login = client.postJson(instance, "/api/auth/login", Map.of("username", USER, "password", USER_PASSWORD));
        check(login.statusCode() == 200, "user login on " + instance + " failed: " + login.statusCode() + " " + login.body());
        client.bearer = json(login).path("accessToken").asString();
        return client;
    }

    static boolean writes(String instance) {
        try {
            return userOn(instance).postJson(instance, "/api/collections/" + COLLECTION, Map.of("title", "probe")).statusCode() == 201;
        } catch (Throwable e) {
            return false;
        }
    }

    static int primaryPort(MongoClient client) {
        return client.getClusterDescription().getServerDescriptions().stream()
                .filter(ServerDescription::isPrimary)
                .mapToInt(description -> description.getAddress().getPort())
                .findFirst()
                .orElse(-1);
    }

    // A code that stays valid for at least five more seconds, so the request does not cross the 30 second step
    static String freshCode(String secret) throws InterruptedException {
        long remaining = 30 - (Instant.now().getEpochSecond() % 30);
        if (remaining < 5) {
            Thread.sleep((remaining + 1) * 1000);
        }
        return TotpUtils.getTotp(secret);
    }

    static String nextStepCode(String secret) throws InterruptedException {
        long remaining = 30 - (Instant.now().getEpochSecond() % 30);
        Thread.sleep((remaining + 1) * 1000);
        return TotpUtils.getTotp(secret);
    }

    static byte[] png(int size) throws Exception {
        var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                image.setRGB(x, y, (x * 4) << 16 | (y * 4) << 8);
            }
        }
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    static JsonNode json(HttpResponse<String> response) {
        return JsonUtils.getMapper().readTree(response.body());
    }

    static String header(HttpResponse<?> response, String name) {
        return response == null ? null : response.headers().firstValue(name).orElse(null);
    }

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static void await(String what, Duration timeout, BooleanSupplier condition) throws InterruptedException {
        var deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (RuntimeException e) {
                // retried until the deadline
            }
            Thread.sleep(500);
        }
        throw new AssertionError("timeout waiting for " + what);
    }

    interface Call<T> {
        T call() throws Exception;
    }

    static <T> T silentlyGet(Call<T> call) {
        try {
            return call.call();
        } catch (Exception e) {
            return null;
        }
    }

    static void silently(Scenario action) {
        try {
            action.run();
        } catch (Exception e) {
            // ignored
        }
    }

    /** Keeps its cookies and bearer token and sends them to whichever instance it calls, as a client behind a load balancer. */
    static final class Client {
        private final Map<String, String> cookies = new LinkedHashMap<>();
        private String bearer;

        Client copy() {
            var copy = new Client();
            copy.cookies.putAll(cookies);
            copy.bearer = bearer;
            return copy;
        }

        HttpResponse<String> get(String base, String path) throws Exception {
            return send(HttpRequest.newBuilder(URI.create(base + path)).GET(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<byte[]> getBytes(String base, String path) throws Exception {
            return send(HttpRequest.newBuilder(URI.create(base + path)).GET(), HttpResponse.BodyHandlers.ofByteArray());
        }

        HttpResponse<String> postJson(String base, String path, Object body) throws Exception {
            return send(HttpRequest.newBuilder(URI.create(base + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJson(body))), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> postForm(String base, String path, Map<String, String> form) throws Exception {
            String body = form.entrySet().stream()
                    .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&"));
            return send(HttpRequest.newBuilder(URI.create(base + path))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> postMultipart(String base, String path, String fileName, String mimeType, byte[] content) throws Exception {
            String boundary = "----paprika-smoke";
            var body = new ByteArrayOutputStream();
            body.write(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"title\"\r\n\r\n"
                    + "with a picture\r\n"
                    + "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"picture\"; filename=\"" + fileName + "\"\r\n"
                    + "Content-Type: " + mimeType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(content);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return send(HttpRequest.newBuilder(URI.create(base + path))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())), HttpResponse.BodyHandlers.ofString());
        }

        private <T> HttpResponse<T> send(HttpRequest.Builder request, HttpResponse.BodyHandler<T> handler) throws Exception {
            if (!cookies.isEmpty()) {
                request.header("Cookie", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("; ")));
            }
            if (bearer != null) {
                request.header("Authorization", "Bearer " + bearer);
            }
            var response = HTTP.send(request.timeout(Duration.ofSeconds(15)).build(), handler);
            for (String cookie : response.headers().allValues("Set-Cookie")) {
                String pair = cookie.split(";", 2)[0];
                int separator = pair.indexOf('=');
                String name = pair.substring(0, separator).trim();
                String value = pair.substring(separator + 1).trim();
                if (value.isEmpty() || cookie.toLowerCase(Locale.ENGLISH).contains("max-age=0")) {
                    cookies.remove(name);
                } else {
                    cookies.put(name, value);
                }
            }
            return response;
        }
    }

    record Event(String name, String data) {
    }

    /** A client of GET /api/realtime that collects the events it receives. */
    static final class EventStream implements AutoCloseable {
        private final List<Event> events = new CopyOnWriteArrayList<>();
        private final CompletableFuture<Void> ended = new CompletableFuture<>();
        private final Thread reader;
        private volatile BufferedReader input;

        EventStream(String base) throws Exception {
            var connected = new CountDownLatch(1);
            reader = Thread.ofVirtual().start(() -> {
                try {
                    var response = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/realtime"))
                            .header("Accept", "text/event-stream").build(), HttpResponse.BodyHandlers.ofInputStream());
                    input = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
                    connected.countDown();
                    String name = "message";
                    String line;
                    while ((line = input.readLine()) != null) {
                        if (line.startsWith("event:")) {
                            name = line.substring("event:".length()).trim();
                        } else if (line.startsWith("data:")) {
                            events.add(new Event(name, line.substring("data:".length()).trim()));
                        } else if (line.isEmpty()) {
                            name = "message";
                        }
                    }
                } catch (Exception e) {
                    // the connection was closed
                } finally {
                    connected.countDown();
                    ended.complete(null);
                }
            });
            connected.await(5, TimeUnit.SECONDS);
        }

        boolean hasEvent(String name, String containing) {
            return events.stream().anyMatch(event -> event.name().equals(name)
                    && (containing == null || event.data().contains(containing)));
        }

        boolean waitFor(String name, String containing, Duration timeout) {
            var deadline = Instant.now().plus(timeout);
            while (Instant.now().isBefore(deadline)) {
                if (hasEvent(name, containing)) {
                    return true;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        }

        String awaitEvent(String name, String containing, Duration timeout) {
            check(waitFor(name, containing, timeout), "no '" + name + "' event" + (containing == null ? "" : " with " + containing)
                    + " within " + timeout.toSeconds() + " s, got " + events);
            return events.stream().filter(event -> event.name().equals(name)
                    && (containing == null || event.data().contains(containing))).findFirst().orElseThrow().data();
        }

        boolean closed(Duration timeout) {
            try {
                ended.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public void close() {
            reader.interrupt();
            try {
                if (input != null) {
                    input.close();
                }
            } catch (Exception e) {
                // ignored
            }
        }
    }
}
