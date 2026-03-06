"""Tests for nf_mapper.parser.

These tests double as usage examples for the parser module.
"""

from __future__ import annotations

import os

import pytest

from nf_mapper.parser import NfInclude, NfProcess, NfWorkflow, ParsedPipeline, parse_nextflow_content, parse_nextflow_file

FIXTURES = os.path.join(os.path.dirname(__file__), "fixtures")


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def fixture_path(name: str) -> str:
    return os.path.join(FIXTURES, name)


# ---------------------------------------------------------------------------
# parse_nextflow_content
# ---------------------------------------------------------------------------


class TestParseNextflowContent:
    """Unit tests for parse_nextflow_content."""

    def test_returns_parsed_pipeline(self):
        """parse_nextflow_content returns a ParsedPipeline."""
        content = """
        process FOO {
            script: 'echo hi'
        }
        """
        result = parse_nextflow_content(content)
        assert isinstance(result, ParsedPipeline)

    def test_extracts_single_process(self):
        """A single process declaration is extracted correctly."""
        content = """
        process MY_PROCESS {
            script:
            '''
            echo hello
            '''
        }
        """
        result = parse_nextflow_content(content)
        assert len(result.processes) == 1
        assert result.processes[0].name == "MY_PROCESS"

    def test_extracts_multiple_processes(self):
        """Multiple process declarations are all extracted."""
        content = """
        process STEP_ONE {
            script: 'echo one'
        }
        process STEP_TWO {
            script: 'echo two'
        }
        """
        result = parse_nextflow_content(content)
        names = [p.name for p in result.processes]
        assert "STEP_ONE" in names
        assert "STEP_TWO" in names

    def test_extracts_container(self):
        """Container directives are captured on the process."""
        content = """
        process CONTAINERIZED {
            container 'ubuntu:22.04'
            script: 'echo hi'
        }
        """
        result = parse_nextflow_content(content)
        assert len(result.processes) == 1
        assert "ubuntu:22.04" in result.processes[0].containers

    def test_extracts_conda(self):
        """Conda directives are captured on the process."""
        content = """
        process CONDA_PROC {
            conda 'bioconda::samtools=1.15'
            script: 'samtools --version'
        }
        """
        result = parse_nextflow_content(content)
        assert len(result.processes) == 1
        condas = result.processes[0].condas
        assert any("samtools" in c for c in condas)

    def test_empty_content_returns_empty_pipeline(self):
        """An empty file results in empty lists."""
        result = parse_nextflow_content("")
        assert result.processes == []
        assert result.workflows == []
        assert result.includes == []
        assert result.connections == []


class TestParseNextflowFile:
    """Integration tests using fixture .nf files."""

    def test_minimal_process(self):
        """Minimal fixture: one process, no workflow."""
        pipeline = parse_nextflow_file(fixture_path("minimal_process.nf"))
        assert len(pipeline.processes) == 1
        assert pipeline.processes[0].name == "HELLO"
        assert pipeline.workflows == []
        assert pipeline.includes == []

    def test_simple_workflow_processes(self):
        """Simple fixture: two processes are extracted."""
        pipeline = parse_nextflow_file(fixture_path("simple_workflow.nf"))
        names = [p.name for p in pipeline.processes]
        assert "FASTQC" in names
        assert "MULTIQC" in names

    def test_simple_workflow_container(self):
        """FASTQC process has a container directive."""
        pipeline = parse_nextflow_file(fixture_path("simple_workflow.nf"))
        fastqc = next(p for p in pipeline.processes if p.name == "FASTQC")
        assert fastqc.containers, "Expected at least one container entry"
        assert any("fastqc" in c.lower() for c in fastqc.containers)

    def test_simple_workflow_conda(self):
        """MULTIQC process has a conda directive."""
        pipeline = parse_nextflow_file(fixture_path("simple_workflow.nf"))
        multiqc = next(p for p in pipeline.processes if p.name == "MULTIQC")
        assert multiqc.condas, "Expected at least one conda entry"

    def test_simple_workflow_has_workflow(self):
        """Simple fixture: an unnamed workflow is detected."""
        pipeline = parse_nextflow_file(fixture_path("simple_workflow.nf"))
        assert len(pipeline.workflows) >= 1

    def test_simple_workflow_connection(self):
        """MULTIQC depends on FASTQC via .out channel."""
        pipeline = parse_nextflow_file(fixture_path("simple_workflow.nf"))
        assert ("FASTQC", "MULTIQC") in pipeline.connections

    def test_complex_workflow_processes(self):
        """Complex fixture: locally-declared processes are extracted."""
        pipeline = parse_nextflow_file(fixture_path("complex_workflow.nf"))
        names = [p.name for p in pipeline.processes]
        for expected in ("STAR_ALIGN", "SAMTOOLS_SORT", "FEATURECOUNTS"):
            assert expected in names, f"{expected} not found in {names}"

    def test_complex_workflow_includes(self):
        """Complex fixture: includes are extracted."""
        pipeline = parse_nextflow_file(fixture_path("complex_workflow.nf"))
        assert len(pipeline.includes) >= 2
        paths = [inc.path for inc in pipeline.includes]
        assert any("fastqc" in p.lower() for p in paths)
        assert any("trimgalore" in p.lower() for p in paths)

    def test_complex_workflow_named(self):
        """Complex fixture: the named 'RNASEQ' workflow is detected."""
        pipeline = parse_nextflow_file(fixture_path("complex_workflow.nf"))
        wf_names = [w.name for w in pipeline.workflows]
        assert "RNASEQ" in wf_names

    def test_complex_workflow_connections(self):
        """Complex fixture: process connections are detected from the workflow body."""
        pipeline = parse_nextflow_file(fixture_path("complex_workflow.nf"))
        # STAR_ALIGN depends on TRIMGALORE
        assert ("TRIMGALORE", "STAR_ALIGN") in pipeline.connections
        # SAMTOOLS_SORT depends on STAR_ALIGN
        assert ("STAR_ALIGN", "SAMTOOLS_SORT") in pipeline.connections
        # FEATURECOUNTS depends on SAMTOOLS_SORT
        assert ("SAMTOOLS_SORT", "FEATURECOUNTS") in pipeline.connections

    def test_file_not_found(self):
        """Parsing a missing file raises FileNotFoundError."""
        with pytest.raises(FileNotFoundError):
            parse_nextflow_file("/nonexistent/pipeline.nf")


# ---------------------------------------------------------------------------
# Data class sanity checks
# ---------------------------------------------------------------------------


class TestDataClasses:
    def test_nf_process_defaults(self):
        p = NfProcess(name="FOO")
        assert p.containers == []
        assert p.condas == []
        assert p.templates == []

    def test_nf_workflow_defaults(self):
        wf = NfWorkflow(name=None)
        assert wf.calls == []

    def test_nf_include(self):
        inc = NfInclude(path="./modules/foo", imports=["FOO"])
        assert inc.path == "./modules/foo"
        assert "FOO" in inc.imports

    def test_parsed_pipeline(self):
        pp = ParsedPipeline(processes=[], workflows=[], includes=[], connections=[])
        assert pp.processes == []
        assert pp.connections == []
