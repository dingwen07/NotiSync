# Runtime-only image for the NotiSync broker.
#
# Build the fat jar on the host first (the server is JVM-only; we deliberately do NOT run Gradle
# inside Docker, because this monorepo's :app module pulls the Android SDK at configuration time):
#
#   ./gradlew :server:buildFatJar
#   docker build -f server/Dockerfile -t notisync-broker .
#
FROM gcr.io/distroless/java21-debian12:nonroot
WORKDIR /app
COPY server/build/libs/server-all.jar /app/server.jar

# Recoverable cache lives on a mounted volume; losing it is safe — clients rebuild it via data sync.
ENV NOTISYNC_DB_PATH=/data/notisync.db
ENV NOTISYNC_FCM_PROJECT_ID=extrawdw-notifly

EXPOSE 8080
VOLUME ["/data"]
ENTRYPOINT ["java", "-jar", "/app/server.jar"]
