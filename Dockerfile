# hadolint ignore=DL3006
FROM amazoncorretto:17-alpine3.19-jdk as BUILDER

COPY . "/build"
WORKDIR "/build"
RUN ./mvnw package -Dkar.finalName=nexus-casc-plugin

# hadolint ignore=DL3006
FROM sonatype/nexus3:3.70.0-java17
COPY --from=BUILDER /build/target/nexus-casc-plugin-bundle.kar /opt/sonatype/nexus/deploy/nexus-casc-plugin.kar
