# Build stage: compile and package the fat JAR
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# Copy Maven descriptor first to cache dependency downloads
COPY nf-mapper-java/pom.xml ./nf-mapper-java/pom.xml
RUN mvn -f nf-mapper-java/pom.xml dependency:go-offline -q

# Copy sources and build
COPY nf-mapper-java/src ./nf-mapper-java/src
RUN mvn -f nf-mapper-java/pom.xml package -DskipTests -q

# Runtime stage: minimal JRE
FROM eclipse-temurin:17-jre-alpine

LABEL org.opencontainers.image.source="https://github.com/Skitionek/nf-mapper"
LABEL org.opencontainers.image.description="Convert Nextflow pipelines to Mermaid diagrams"

WORKDIR /app

COPY --from=builder /build/nf-mapper-java/target/nf-mapper-java-*.jar /app/nf-mapper.jar

ENTRYPOINT ["java", "-jar", "/app/nf-mapper.jar"]
