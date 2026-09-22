# ==============================================================================================
# /**
#  * @file        Dockerfile
#  * @module      Thinklab Company Service Container Packaging Manifest
#  * @version     v3.5.0-NASA-SRE-PROD-STABLE
#  * @description Enterprise-grade, multi-stage Docker build optimized for Micronaut 4 AOT.
#  *              Implements zero-trust runtime environments using Google Distroless.
#  *              Uses standard distribution packaging (Thin JAR + Libs) to avoid build.gradle modifications.
#  *
#  * @architectural_directives
#  *   1. Immutability: Deterministic build process with strict dependency caching.
#  *   2. Minimal Attack Surface: Non-root execution with zero shell access in runtime.
#  *   3. Low Latency: Pre-configured with ZGC and generational garbage collection.
#  *
#  * @maintainer  Thinklab Core Infrastructure & High-Assurance Engineering Team
#  */
# ==============================================================================================

# ==============================================================================================
# /**
#  * @stage       1: BUILD (Dependency Resolution & AOT Compilation)
#  * @image       gradle:8.7-jdk21-alpine
#  */
# ==============================================================================================
FROM gradle:8.7-jdk21-alpine AS builder

WORKDIR /home/gradle/src

COPY --chown=gradle:gradle build.gradle settings.gradle* gradle.properties* ./
RUN gradle dependencies --no-daemon || true

COPY --chown=gradle:gradle src ./src

RUN gradle installDist -x test --no-daemon

RUN mkdir -p /app-libs && find build/install -path '*/lib/*.jar' -exec cp {} /app-libs/ \;

# ==============================================================================================
# /**
#  * @stage       2: RUNTIME (Zero-Trust, Minimal Footprint)
#  * @image       gcr.io/distroless/java21-debian12:nonroot
#  * @description Stripped-down OS containing only the JVM and its essential dependencies.
#  */
# ==============================================================================================
FROM gcr.io/distroless/java21-debian12:nonroot AS runtime

LABEL maintainer="Thinklab Core Infrastructure & High-Assurance Engineering Team"
LABEL version="v3.5.0-NASA-SRE-PROD-STABLE"
LABEL description="Thinklab Company Service - Mission-Critical Reactive Micronaut 4 Runtime"
LABEL enviroment="Personal Home-Lab for Development"
LABEL git-repo="https://github.com/fernan-89/micronaut-platform-gateway-service"

WORKDIR /app

COPY --from=builder --chown=nonroot:nonroot /app-libs/ /app/

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50.0 -XX:+UseZGC -XX:+ZGenerational -XX:+UseStringDeduplication"
ENV MICRONAUT_SERVER_PORT=8080

ENV MONGODB_URI="mongodb://localhost:27017/thinklab_company_db"
ENV HASH_SERVICE_URL="http://localhost:8080"

EXPOSE ${MICRONAUT_SERVER_PORT}

ENTRYPOINT ["java", "-cp", "/app/*", "com.thinklab.Application"]
