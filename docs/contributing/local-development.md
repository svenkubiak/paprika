# Local Development

**Requirements:** JDK 25+, Maven 3.9+, Node.js 24+

## Backend

```bash
git clone https://github.com/svenkubiak/paprika.git
cd paprika
mvn clean package -DskipTests
APPLICATION_MODE=dev java -jar target/paprika.jar
```

The dev profile runs on **port 9090** with an embedded MongoDB instance, so no external database is needed. Follow the superadmin setup in the [Initial Setup](../installation/initial-setup) guide when starting with a fresh database.

## Admin UI

The admin UI gets compiled into the application artifact during `mvn package`. For UI development, run the Vite dev server separately:

```bash
cd admin-ui
npm install
npm run dev
```

The dev server proxies API calls to the backend on port 9090 and supports hot-module replacement.

## Tests

```bash
mvn test
```

Tests run against an embedded MongoDB with a dedicated test profile, so no external services are required.
