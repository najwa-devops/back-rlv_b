# syntax=docker/dockerfile:1.7

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .
COPY .mvn .mvn
COPY mvnw mvnw
COPY mvnw.cmd mvnw.cmd
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 \
    sh -c 'for i in 1 2 3; do ./mvnw -B -DskipTests -Dmaven.wagon.http.retryHandler.count=5 dependency:go-offline && exit 0; echo "dependency:go-offline failed (attempt $i), retrying..."; sleep 5; done; exit 1'

COPY src src
RUN --mount=type=cache,target=/root/.m2 \
    sh -c 'for i in 1 2 3; do ./mvnw -B clean package -DskipTests && exit 0; echo "package failed (attempt $i), retrying..."; sleep 5; done; exit 1'

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       tesseract-ocr \
       tesseract-ocr-eng \
       tesseract-ocr-fra \
       curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/*.jar /app/app.jar

RUN mkdir -p /app/logs

ENV JAVA_OPTS="-Xms512m -Xmx1024m" \
    SERVER_PORT=8096 \
    TESSERACT_PATH=/usr/bin/tesseract \
    TESSERACT_DATAPATH=/usr/share/tesseract-ocr/5/tessdata \
    TESSERACT_LANGUAGE=eng+fra

EXPOSE 8096

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD sh -c "curl --fail --silent http://localhost:${SERVER_PORT:-8096}/actuator/health > /dev/null || exit 1"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
