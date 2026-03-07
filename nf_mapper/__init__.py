"""nf-mapper: Convert Nextflow pipelines to Mermaid diagrams."""

from nf_mapper.mermaid import pipeline_to_mermaid
from nf_mapper.parser import ParsedPipeline, parse_nextflow_file

__all__ = [
    "ParsedPipeline",
    "parse_nextflow_file",
    "pipeline_to_mermaid",
]
