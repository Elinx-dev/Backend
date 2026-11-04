# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
# Optional Maven Central mirror, e.g. --build-arg MAVEN_MIRROR=https://maven-central.storage-download.googleapis.com/maven2/
ARG MAVEN_MIRROR=
RUN if [ -n "$MAVEN_MIRROR" ]; then mkdir -p /root/.m2 && printf '<settings><mirrors><mirror><id>mirror</id><mirrorOf>central</mirrorOf><url>%s</url></mirror></mirrors></settings>' "$MAVEN_MIRROR" > /root/.m2/settings.xml; fi
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2/repository mvn -B -q dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2/repository mvn -B -q -DskipTests package && cp target/slate-api-*.jar /app.jar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app.jar app.jar
ENV FABRIC_CRYPTO_PATH=/fabric/organizations
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
