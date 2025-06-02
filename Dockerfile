ARG DOCKER_MIRROR=docker.io
ARG PLUGIN_BASE_IMAGE=${DOCKER_MIRROR}/busybox:1.37
ARG SONATYPE_IMAGE=${DOCKER_MIRROR}/sonatype/nexus3:3.80.0-java17-alpine
ARG BUILDER_IMAGE=${DOCKER_MIRROR}/maven:3.9.9-eclipse-temurin-17-focal
# temurin has git preinstlalled
ARG ALL_PROXY=""
ARG NO_PROXY="localhost,.example.com"

# hadolint ignore=DL3006
FROM ${BUILDER_IMAGE} AS builder

ARG GIT_REPO="https://github.com/ReSearchITEng/nexus-casc-plugin.git"

ARG MAVEN_MIRROR="https://repo.maven.apache.org/maven2"
ARG GROOVY_MIRROR="https://groovy.jfrog.io/artifactory/plugins-release"

RUN mkdir -p /root/.m2
WORKDIR /root

# ENV ALL_PROXY=${ALL_PROXY}
# ENV NO_PROXY=${NO_PROXY}
RUN git clone -c http.sslVerify=false ${GIT_REPO}

WORKDIR "/root/nexus-casc-plugin"
#COPY .mavenrc /root/.mavenrc
COPY m2_settings.xml /root/.m2/settings.xml
RUN sed -i "s!https://repo.maven.apache.org/maven2!${MAVEN_MIRROR}!g" .mvn/wrapper/maven-wrapper.properties /root/.m2/settings.xml pom.xml
RUN sed -i "s!https://groovy.jfrog.io/artifactory/plugins-release!${GROOVY_MIRROR}!g" pom.xml

RUN ./mvnw --no-transfer-progress package -Dkar.finalName=nexus-casc-plugin
#-Dorg.slf4j.simpleLogger.defaultLogLevel=WARN

# # hadolint ignore=DL3006
# FROM ${SONATYPE_IMAGE} AS sonatype-with-plugin

# # Copy the plugin bundle from builder stage
# COPY --from=builder /root/nexus-casc-plugin/target/nexus-casc-plugin-bundle.kar /opt/sonatype/nexus/deploy/nexus-casc-plugin.kar
# COPY deploy-plugin.sh /usr/local/bin/deploy-plugin.sh

# # extra copy to both: allow also volume mounts as well as allow new version to tranported 
# # and deployed on top of other images
# #RUN mkdir -p /root/nexus-casc-plugin/target
# COPY --from=builder /root/nexus-casc-plugin/target/nexus-casc-plugin-bundle.kar /home/nexus/

# hadolint ignore=DL3006
FROM ${PLUGIN_BASE_IMAGE} AS plugin-only

# Copy the plugin bundle from builder stage
COPY --from=builder /root/nexus-casc-plugin/target/nexus-casc-plugin-bundle.kar /opt/sonatype/nexus/deploy/nexus-casc-plugin.kar
COPY deploy-plugin.sh /usr/local/bin/deploy-plugin.sh
RUN mkdir -p /home/nexus
# extra copy to both: allow also volume mounts as well as allow new version to tranported 
# and deployed on top of other images
#RUN mkdir -p /root/nexus-casc-plugin/target
COPY --from=builder /root/nexus-casc-plugin/target/nexus-casc-plugin-bundle.kar /home/nexus/
