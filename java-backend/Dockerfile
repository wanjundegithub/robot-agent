FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml ./
COPY java-backend/pom.xml java-backend/pom.xml
RUN mvn -pl java-backend -am dependency:go-offline -B

COPY java-backend/src java-backend/src
RUN mvn -pl java-backend -am -DskipTests clean package -B

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S robot && adduser -S robot -G robot
RUN apk add --no-cache curl

COPY --from=build /workspace/java-backend/target/app.jar /app/app.jar
USER robot

ENV JAVA_OPTS=""
EXPOSE 8080 8091

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health >/dev/null || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
