# The jar and a few fonts on distroless Java (JRE, CA certificates, tzdata, nonroot user, no shell).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# dependencies first, cached as long as the pom does not change
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q package -DskipTests

FROM debian:bookworm-slim AS fonts
# Liberation: metric-compatible Helvetica / Times / Courier; DejaVu Sans: fallback for missing glyphs
RUN apt-get update -q && apt-get install -yq --no-install-recommends fonts-liberation fonts-dejavu-core \
 && mkdir -p /out/fonts \
 && cp /usr/share/fonts/truetype/liberation/Liberation*-*.ttf /out/fonts/ \
 && rm -f /out/fonts/LiberationSansNarrow* \
 && cp /usr/share/fonts/truetype/dejavu/DejaVuSans.ttf /usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf /out/fonts/

FROM gcr.io/distroless/java21-debian12:nonroot
COPY --from=build /src/target/pdfj.jar /pdfj.jar
COPY --from=fonts /out/fonts /fonts
# the heap is not bounded (up to 1/4 of the container's memory, the JVM's default);
# bound it with -e JAVA_TOOL_OPTIONS=-Xmx256m for a smaller footprint (see the README's Performance)
ENV FONTS_DIR=/fonts PORT=3000
EXPOSE 3000
# GCTimeRatio=2: G1 may spend up to 1/3 of its time collecting (12 by default: 1/13) rather than grow the heap,
# ~700 MB under load instead of ~1.2 GB, for the same throughput (the heap live after a collection is ~35 MB)
ENTRYPOINT ["java", "-Djava.awt.headless=true", "-XX:+UseG1GC", "-XX:GCTimeRatio=2", "-jar", "/pdfj.jar"]
