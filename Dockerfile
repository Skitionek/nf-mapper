FROM python:3.12-slim

LABEL org.opencontainers.image.source="https://github.com/Skitionek/nf-mapper"
LABEL org.opencontainers.image.description="Convert Nextflow pipelines to Mermaid diagrams"

WORKDIR /app

COPY . .

RUN pip install --no-cache-dir .

ENTRYPOINT ["nf-mapper"]
