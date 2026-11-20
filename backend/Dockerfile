# Build context is the repository root (see infrastructure/docker-compose.yml).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src/backend
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY backend/lombok.config ./
COPY backend/src src
RUN ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd --system --create-home --uid 10001 muninn \
    && mkdir /data && chown muninn /data
USER muninn
WORKDIR /app
COPY --from=build /src/backend/target/muninn-backend-*.jar /app/muninn.jar
ENV MUNINN_DATA_DIR=/data
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/muninn.jar"]
