# Test module api-settings-test: runs the jar staged by build.sh (no Maven on the platform).
# The base image is the one of KF-AAS-R3-Module, pinned on the same digest (eclipse-temurin:21-jre, read on 2026-10-21).
FROM eclipse-temurin:21-jre@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99
WORKDIR /app
COPY app.jar app.jar
# the manifest the SDK checks the registration against before the first request (KF_MODULE_MANIFEST, default ./kf-module-conf.json)
COPY kf-module-conf.json kf-module-conf.json
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
