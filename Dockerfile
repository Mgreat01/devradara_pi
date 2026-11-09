FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN mvn -B verify
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S radar && adduser -S radar -G radar
WORKDIR /app
COPY --from=build /build/target/devradar-api-0.1.0.jar app.jar
USER radar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
