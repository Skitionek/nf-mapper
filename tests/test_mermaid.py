"""Tests for nf_mapper.mermaid.

These tests double as usage examples for the mermaid module.
"""

from __future__ import annotations

import os

import pytest

from nf_mapper.mermaid import pipeline_to_mermaid
from nf_mapper.parser import NfProcess, NfWorkflow, ParsedPipeline, parse_nextflow_file

FIXTURES = os.path.join(os.path.dirname(__file__), "fixtures")


def fixture_path(name: str) -> str:
    return os.path.join(FIXTURES, name)


# ---------------------------------------------------------------------------
# Helpers to build minimal ParsedPipeline instances
# ---------------------------------------------------------------------------


def _make_pipeline(
    processes=None,
    workflows=None,
    includes=None,
    connections=None,
) -> ParsedPipeline:
    return ParsedPipeline(
        processes=processes or [],
        workflows=workflows or [],
        includes=includes or [],
        connections=connections or [],
    )


# ---------------------------------------------------------------------------
# Basic structure tests
# ---------------------------------------------------------------------------


class TestPipelineToMermaidStructure:
    def test_returns_string(self):
        """pipeline_to_mermaid always returns a string."""
        pipeline = _make_pipeline()
        result = pipeline_to_mermaid(pipeline)
        assert isinstance(result, str)

    def test_starts_with_flowchart(self):
        """Output begins with a flowchart declaration."""
        pipeline = _make_pipeline()
        result = pipeline_to_mermaid(pipeline)
        assert "flowchart" in result

    def test_default_direction_lr(self):
        """Default direction is LR."""
        pipeline = _make_pipeline()
        result = pipeline_to_mermaid(pipeline)
        assert "flowchart LR" in result

    def test_custom_direction_td(self):
        """Direction can be overridden to TD."""
        pipeline = _make_pipeline()
        result = pipeline_to_mermaid(pipeline, direction="TD")
        assert "flowchart TD" in result

    def test_title_is_included(self):
        """When a title is given it appears in YAML front matter."""
        pipeline = _make_pipeline()
        result = pipeline_to_mermaid(pipeline, title="My Pipeline")
        assert "title: My Pipeline" in result
        assert "---" in result

    def test_no_title_no_front_matter(self):
        """Without a title, no YAML front matter is added."""
        pipeline = _make_pipeline()
        result = pipeline_to_mermaid(pipeline)
        assert "---" not in result


# ---------------------------------------------------------------------------
# Process node rendering
# ---------------------------------------------------------------------------


class TestProcessNodeRendering:
    def test_process_node_appears(self):
        """Every process produces a stadium-shaped node."""
        pipeline = _make_pipeline(processes=[NfProcess(name="FASTQC")])
        result = pipeline_to_mermaid(pipeline)
        assert "FASTQC" in result
        assert "([FASTQC])" in result

    def test_multiple_process_nodes(self):
        """Multiple processes each appear as nodes."""
        pipeline = _make_pipeline(
            processes=[NfProcess(name="STEP_A"), NfProcess(name="STEP_B")]
        )
        result = pipeline_to_mermaid(pipeline)
        assert "STEP_A" in result
        assert "STEP_B" in result


# ---------------------------------------------------------------------------
# Edge / connection rendering
# ---------------------------------------------------------------------------


class TestEdgeRendering:
    def test_connection_renders_arrow(self):
        """A connection appears as a --> arrow."""
        pipeline = _make_pipeline(
            processes=[NfProcess(name="A"), NfProcess(name="B")],
            connections=[("A", "B")],
        )
        result = pipeline_to_mermaid(pipeline)
        assert "A --> B" in result or "A --> B" in result.replace(" ", "")

    def test_no_connection_no_arrow(self):
        """Without connections no arrows are drawn."""
        pipeline = _make_pipeline(processes=[NfProcess(name="LONE")])
        result = pipeline_to_mermaid(pipeline)
        assert "-->" not in result

    def test_multiple_connections(self):
        """Multiple connections all appear in the output."""
        pipeline = _make_pipeline(
            processes=[
                NfProcess(name="A"),
                NfProcess(name="B"),
                NfProcess(name="C"),
            ],
            connections=[("A", "B"), ("B", "C")],
        )
        result = pipeline_to_mermaid(pipeline)
        assert "-->" in result
        assert "A" in result
        assert "B" in result
        assert "C" in result


# ---------------------------------------------------------------------------
# Subgraph / grouped rendering
# ---------------------------------------------------------------------------


class TestGroupedRendering:
    def test_subgraph_when_no_connections(self):
        """When there are no connections, workflow subgraphs are used."""
        pipeline = _make_pipeline(
            processes=[NfProcess(name="PROC_A"), NfProcess(name="PROC_B")],
            workflows=[
                NfWorkflow(name="MYWF", calls=["PROC_A", "PROC_B"])
            ],
        )
        result = pipeline_to_mermaid(pipeline)
        assert "subgraph" in result
        assert "MYWF" in result

    def test_no_subgraph_when_connections_present(self):
        """When connections are present, subgraphs are not used."""
        pipeline = _make_pipeline(
            processes=[NfProcess(name="A"), NfProcess(name="B")],
            connections=[("A", "B")],
        )
        result = pipeline_to_mermaid(pipeline)
        assert "subgraph" not in result


# ---------------------------------------------------------------------------
# End-to-end tests using fixture files
# ---------------------------------------------------------------------------


class TestEndToEnd:
    def test_simple_workflow_diagram(self):
        """End-to-end: simple_workflow.nf produces a valid Mermaid diagram."""
        pipeline = parse_nextflow_file(fixture_path("simple_workflow.nf"))
        result = pipeline_to_mermaid(pipeline, title="Simple QC")

        assert "flowchart LR" in result
        assert "title: Simple QC" in result
        assert "FASTQC" in result
        assert "MULTIQC" in result
        # Should detect the FASTQC -> MULTIQC connection
        assert "-->" in result

    def test_complex_workflow_diagram(self):
        """End-to-end: complex_workflow.nf produces a diagram with connections."""
        pipeline = parse_nextflow_file(fixture_path("complex_workflow.nf"))
        result = pipeline_to_mermaid(pipeline)

        assert "flowchart LR" in result
        assert "STAR_ALIGN" in result
        assert "SAMTOOLS_SORT" in result
        assert "-->" in result

    def test_minimal_process_diagram(self):
        """End-to-end: minimal_process.nf produces a single node diagram."""
        pipeline = parse_nextflow_file(fixture_path("minimal_process.nf"))
        result = pipeline_to_mermaid(pipeline)

        assert "HELLO" in result
        assert "-->" not in result

    def test_diagram_is_valid_mermaid_syntax(self):
        """Output starts with a recognized Mermaid keyword."""
        pipeline = parse_nextflow_file(fixture_path("simple_workflow.nf"))
        result = pipeline_to_mermaid(pipeline)
        first_meaningful_line = next(
            line for line in result.splitlines() if line.strip() and "---" not in line
        )
        assert first_meaningful_line.startswith("flowchart")
