# Build stage: compile and package the fat JAR
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# Copy Maven descriptor first to cache dependency downloads
COPY nf-mapper/pom.xml ./nf-mapper/pom.xml
RUN mvn -f nf-mapper/pom.xml dependency:go-offline -q

# Copy sources and build
COPY nf-mapper/src ./nf-mapper/src
RUN mvn -f nf-mapper/pom.xml package -DskipTests -q

# Runtime stage: minimal JRE
FROM eclipse-temurin:17-jre-alpine

LABEL org.opencontainers.image.source="https://github.com/Skitionek/nf-mapper"
LABEL org.opencontainers.image.description="Convert Nextflow pipelines to Mermaid diagrams"

WORKDIR /app

COPY --from=builder /build/nf-mapper/target/nf-mapper-*.jar /app/nf-mapper.jar

ENTRYPOINT ["java", "-jar", "/app/nf-mapper.jar"]
