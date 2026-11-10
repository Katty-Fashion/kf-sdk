# kf-demo-model-card: runs the prebuilt jar staged by build.sh (no Maven needed when the platform installs the module).
# The base image is PINNED on a digest (variant B): a signed release refuses a FROM without
# @sha256, and the platform refuses it at upload. The digest is the multi-arch index of eclipse-temurin:21-jre,
# read on 2026-10-21 (anonymous registry request: token from auth.docker.io for library/eclipse-temurin, then
# HEAD https://registry-1.docker.io/v2/library/eclipse-temurin/manifests/21-jre → Docker-Content-Digest).
# To refresh it (a new JRE patch): `docker buildx imagetools inspect eclipse-temurin:21-jre` → "Digest:", replace it
# here, commit, and release a new module version (the old digest stays valid for the versions built on it).
FROM eclipse-temurin:21-jre@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99
WORKDIR /app
COPY app.jar app.jar
# the manifest the SDK checks the registration against before the first request (KF_MODULE_MANIFEST, default ./kf-module-conf.json)
COPY kf-module-conf.json kf-module-conf.json
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
