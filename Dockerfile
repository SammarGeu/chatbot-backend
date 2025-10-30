# ---- Build Stage ----
    FROM maven:3.9-eclipse-temurin-21 AS build
    WORKDIR /app
    COPY . .
    RUN mvn -q clean package -DskipTests
    
    # ---- Run Stage ----
    FROM eclipse-temurin:21-jre
    WORKDIR /app
    COPY --from=build /app/target/*.jar app.jar
    
    # Render gives PORT; Spring reads it via server.port=${PORT:8080}
    EXPOSE 8080
    
    # helpful for tiny free instances
    ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseSerialGC"
    
    CMD ["java", "-jar", "app.jar"]
    