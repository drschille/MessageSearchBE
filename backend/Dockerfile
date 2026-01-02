# syntax=docker/dockerfile:1.6
FROM gradle:8.7-jdk21 AS build
WORKDIR /workspace

COPY gradle gradle
COPY gradlew gradlew
COPY gradlew.bat gradlew.bat
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY buildSrc buildSrc
COPY core core
COPY infra infra
COPY utils utils
COPY shared shared
COPY backend backend

RUN ./gradlew :backend:installDist -x test

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /workspace/backend/build/install/backend/ /app/

ENV JAVA_OPTS=""
EXPOSE 8080

CMD ["/app/bin/backend"]
