FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd --uid 1001 --create-home app \
 && mkdir -p /app/uploads \
 && chown app:app /app /app/uploads
WORKDIR /app
COPY --from=build --chown=app:app /src/target/surshongi-api.jar app.jar
USER root
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "mkdir -p /app/uploads && chown app:app /app/uploads && exec su -s /bin/sh app -c \"exec /opt/java/openjdk/bin/java -jar /app/app.jar\""]
