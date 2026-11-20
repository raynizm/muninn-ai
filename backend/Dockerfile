# Build context is the repository root (see infrastructure/docker-compose.yml).
FROM node:22-alpine AS web
WORKDIR /src
COPY package.json package-lock.json ./
COPY packages/api packages/api
COPY web web
# The lockfile covers every workspace, so mobile's manifest must be present even though only web is built.
COPY mobile/package.json mobile/package.json
RUN npm ci --workspace @muninn/web --workspace @muninn/api && npm run build -w @muninn/web

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src/backend
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY backend/lombok.config ./
COPY backend/src src
COPY --from=web /src/web/dist /src/web/dist
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
