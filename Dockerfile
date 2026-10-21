# The jar, a JRE of the JDK modules pdfj uses, and a few fonts, on Debian slim.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# dependencies first, cached as long as the pom does not change
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q package -DskipTests
# a small runtime: only the modules pdfj needs
RUN jlink --add-modules java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,java.scripting,java.sql,java.xml,jdk.crypto.ec,jdk.httpserver,jdk.unsupported \
      --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output /jre

FROM debian:bookworm-slim AS fonts
RUN apt-get update -q && apt-get install -yq --no-install-recommends fonts-liberation fonts-dejavu-core \
 && mkdir -p /out/fonts \
 && cp /usr/share/fonts/truetype/liberation/Liberation*-*.ttf /out/fonts/ \
 && rm -f /out/fonts/LiberationSansNarrow* \
 && cp /usr/share/fonts/truetype/dejavu/DejaVuSans.ttf /usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf /out/fonts/

FROM debian:bookworm-slim
# AWT (SVG drawing, image decoding) needs FreeType and fontconfig, even headless
RUN apt-get update -q && apt-get install -yq --no-install-recommends libfreetype6 libfontconfig1 ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 65532 --no-create-home nonroot
COPY --from=build /jre /jre
COPY --from=build /src/target/pdfj.jar /pdfj.jar
COPY --from=fonts /out/fonts /fonts
ENV FONTS_DIR=/fonts PORT=3000
USER nonroot
EXPOSE 3000
ENTRYPOINT ["/jre/bin/java", "-Djava.awt.headless=true", "-XX:MaxRAMPercentage=75", "-XX:+UseG1GC", "-jar", "/pdfj.jar"]
