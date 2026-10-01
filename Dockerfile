FROM amazoncorretto:25-headless

RUN yum install -y shadow-utils \
    && yum clean all \
    && rm -rf /var/cache/yum \
    && groupadd --gid 1000 appgroup \
    && useradd \
        --uid 33 \
        --gid 1000 \
        --no-create-home \
        --home-dir /app \
        --shell /sbin/nologin \
        appuser \
    && mkdir -p /app \
    && chown appuser:appgroup /app

WORKDIR /app

COPY --chown=appuser:appgroup target/paprika.jar ./paprika.jar

USER appuser

# ExitOnOutOfMemoryError as in the native packages: after an OutOfMemoryError the JVM is in an
# undefined state, and a container that exits gets restarted where one that limps on does not.
ENTRYPOINT ["sh", "-c", "exec java -XX:+ExitOnOutOfMemoryError ${JAVA_OPTS:-} -jar /app/paprika.jar"]