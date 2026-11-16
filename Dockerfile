FROM eclipse-temurin:25.0.5_8-jdk-noble AS build

WORKDIR /workspace
COPY . .

ENV ProductBuildType="Preview"
RUN chmod +x ./gradlew && \
    ./gradlew :server:shadowJar --no-daemon --stacktrace \
      -Dorg.gradle.jvmargs="-Xmx4096m" \
      -Dorg.gradle.workers.max=4 && \
    cp "$(ls -S server/build/*.jar | head -n 1)" /tmp/tachidesk_latest.jar

FROM eclipse-temurin:25.0.5_8-jre-noble

RUN apt-get update && \
    apt-get install -y --no-install-recommends curl gettext-base unzip tini ca-certificates p11-kit perl && \
    /usr/bin/p11-kit extract --format=java-cacerts --filter=certificates --overwrite --purpose server-auth "$JAVA_HOME/lib/security/cacerts" && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

RUN userdel -r ubuntu 2>/dev/null || true && \
    groupadd --gid 1000 suwayomi && \
    useradd --uid 1000 --gid suwayomi --no-log-init -G audio,video suwayomi && \
    mkdir -p /home/suwayomi/.local/share/Tachidesk /home/suwayomi/startup

COPY docker/create_server_conf.sh /home/suwayomi/create_server_conf.sh
COPY docker/startup_script.sh /home/suwayomi/startup_script.sh
COPY --from=build /tmp/tachidesk_latest.jar /home/suwayomi/startup/tachidesk_latest.jar

RUN chmod +x /home/suwayomi/create_server_conf.sh /home/suwayomi/startup_script.sh && \
    chmod -R 777 /home/suwayomi && \
    chown -R suwayomi:suwayomi /home/suwayomi

ENV HOME=/home/suwayomi \
    MALLOC_ARENA_MAX=2 \
    JDK_JAVA_OPTIONS="-XX:TrimNativeHeapInterval=60000 -XX:G1PeriodicGCInterval=300000 -XX:MaxHeapFreeRatio=30 -XX:MinHeapFreeRatio=10"

WORKDIR /home/suwayomi
USER suwayomi
EXPOSE 4567

ENTRYPOINT ["tini", "--"]
CMD ["/home/suwayomi/startup_script.sh"]
