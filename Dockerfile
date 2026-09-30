# ==============================================================================================
# /**
#  * @file        Dockerfile
#  * @module      Thinklab Platform Gateway Service Container Packaging Manifest
#  * @version     v3.5.0
#  * @description Packages the distribution built by `./gradlew installDist` into a Google Distroless
#  *              runtime (non-root, no shell). The build runs outside Docker - on the developer machine
#  *              or in CI - where the GitHub Packages credentials for micronaut-thinklab-service-kit already
#  *              exist, so no secret ever enters an image layer.
#  *
#  *              ./gradlew installDist && docker build -t <image> .
#  */
# ==============================================================================================
FROM gcr.io/distroless/java21-debian12:nonroot

LABEL maintainer="Thinklab Core Infrastructure & High-Assurance Engineering Team"
LABEL version="v3.5.0"
LABEL description="Thinklab Platform Gateway Service - Reactive Micronaut 4 Runtime"
LABEL environment="Personal Home-Lab for Development"
LABEL git-repo="https://github.com/fernan-89/micronaut-platform-gateway-service"

WORKDIR /app

# Application JAR plus every runtime dependency, flat on the classpath.
COPY --chown=nonroot:nonroot build/install/*/lib/*.jar /app/

# ZGC (generational) for low pause times under Netty/Reactor; heap capped relative to the container limit.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50.0 -XX:+UseZGC -XX:+ZGenerational -XX:+UseStringDeduplication"
ENV MICRONAUT_SERVER_PORT=8080

EXPOSE 8080

ENTRYPOINT ["java", "-cp", "/app/*", "com.thinklab.Application"]
