# Stage 1: Build with Maven
FROM maven:3.9.15-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
# Download dependencies first to leverage Docker cache
RUN mvn dependency:go-offline

COPY src ./src
RUN mvn package -DskipTests

# Stage 2: Run with JDK (needed for javac)
FROM eclipse-temurin:21-jdk-noble
WORKDIR /app

# Install G++ and Python3 for code execution (Ubuntu environment)
RUN apt-get update && apt-get install -y --no-install-recommends g++ python3 && rm -rf /var/lib/apt/lists/*

# Create data directory
RUN mkdir -p /app/data

# Copy the built jar from stage 1
COPY --from=build /app/target/*.jar app.jar

# Expose the port Spring Boot runs on
EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
