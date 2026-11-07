# Build the application using the same Java version and Maven wrapper as CI.
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/

# CI runs the full test suite with PostgreSQL and Redis before deployment.
# The image build only packages the application and needs no running databases.
RUN --mount=type=cache,target=/root/.m2 \
    sh ./mvnw --batch-mode --no-transfer-progress -DskipTests package

# The final image contains a JRE and the application, without build tools.
FROM eclipse-temurin:21-jre-noble
WORKDIR /app
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home app
COPY --from=build --chown=app:app /build/target/*.jar /app/app.jar

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=55.0 -XX:InitialRAMPercentage=10.0 -XX:+ExitOnOutOfMemoryError"
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
