# Build stage
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# Run stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S stepflow && adduser -S stepflow -G stepflow
COPY --from=build /app/target/stepflow-*.jar app.jar
USER stepflow
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
