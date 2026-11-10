# Test module api-peer-caller-test (plan 230-32): runs the jar staged by build.sh (no Maven on the platform).
# The base image is the one of the other test modules, pinned on the same digest (eclipse-temurin:21-jre).
FROM eclipse-temurin:21-jre@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99
WORKDIR /app
COPY app.jar app.jar
# the manifest the SDK checks the registration against before the first request (KF_MODULE_MANIFEST, default ./kf-module-conf.json)
COPY kf-module-conf.json kf-module-conf.json
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
