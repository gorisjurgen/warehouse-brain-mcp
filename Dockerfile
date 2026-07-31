FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd --system --home-dir /app app
WORKDIR /app
COPY --from=build /build/target/warehouse-brain-mcp-*.jar app.jar
USER app
EXPOSE 8817
ENTRYPOINT ["java", "-jar", "app.jar"]
