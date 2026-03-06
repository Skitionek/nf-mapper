"""Tests for nf_mapper.parser.

These tests double as usage examples for the parser module.  Real-world
nf-core pipeline fixtures are used to validate parsing of production-grade
Nextflow code.
"""

from __future__ import annotations

import os

import pytest

from nf_mapper.parser import (
    NfInclude,
    NfProcess,
    NfWorkflow,
    ParsedPipeline,
    parse_nextflow_content,
    parse_nextflow_file,
)

FIXTURES = os.path.join(os.path.dirname(__file__), "fixtures")


def fixture_path(name: str) -> str:
    return os.path.join(FIXTURES, name)


# ---------------------------------------------------------------------------
# parse_nextflow_content – unit tests
# ---------------------------------------------------------------------------


class TestParseNextflowContent:
    """Unit tests for parse_nextflow_content."""

    def test_returns_parsed_pipeline(self):
        result = parse_nextflow_content("process FOO { script: 'echo hi' }")
        assert isinstance(result, ParsedPipeline)

    def test_extracts_single_process(self):
        content = "process MY_PROCESS { script: ''' echo hello ''' }"
        result = parse_nextflow_content(content)
        assert len(result.processes) == 1
        assert result.processes[0].name == "MY_PROCESS"

    def test_extracts_multiple_processes(self):
        content = """
        process STEP_ONE { script: 'echo one' }
        process STEP_TWO { script: 'echo two' }
        """
        result = parse_nextflow_content(content)
        names = [p.name for p in result.processes]
        assert "STEP_ONE" in names
        assert "STEP_TWO" in names

    def test_extracts_container(self):
        content = "process CONTAINERIZED { container 'ubuntu:22.04'\nscript: 'echo hi' }"
        result = parse_nextflow_content(content)
        assert len(result.processes) == 1
        assert "ubuntu:22.04" in result.processes[0].containers

    def test_extracts_conda(self):
        content = "process CONDA_PROC { conda 'bioconda::samtools=1.15'\nscript: 'samtools --version' }"
        result = parse_nextflow_content(content)
        assert len(result.processes) == 1
        condas = result.processes[0].condas
        assert any("samtools" in c for c in condas)

    def test_empty_content_returns_empty_pipeline(self):
        result = parse_nextflow_content("")
        assert result.processes == []
        assert result.workflows == []
        assert result.includes == []
        assert result.connections == []

    def test_unnamed_workflow_connection(self):
        """Connection detection works for unnamed workflow { } blocks."""
        content = """
        process A { script: 'echo a' }
        process B { script: 'echo b' }
        workflow {
            A(params.input)
            B(A.out.result)
        }
        """
        result = parse_nextflow_content(content)
        assert ("A", "B") in result.connections

    def test_named_workflow_connection(self):
        """Connection detection works for named workflow MYFLOW { } blocks."""
        content = """
        process ALIGN { script: 'bwa mem' }
        process SORT  { script: 'samtools sort' }
        workflow MYFLOW {
            take: reads
            main:
                ALIGN(reads)
                SORT(ALIGN.out.bam)
        }
        """
        result = parse_nextflow_content(content)
        assert ("ALIGN", "SORT") in result.connections


# ---------------------------------------------------------------------------
# Handcrafted fixtures – parse_nextflow_file
# ---------------------------------------------------------------------------


class TestHandcraftedFixtures:
    def test_minimal_process(self):
        pipeline = parse_nextflow_file(fixture_path("minimal_process.nf"))
        assert len(pipeline.processes) == 1
        assert pipeline.processes[0].name == "HELLO"
        assert pipeline.workflows == []
        assert pipeline.includes == []

    def test_simple_workflow_processes(self):
        pipeline = parse_nextflow_file(fixture_path("simple_workflow.nf"))
        names = [p.name for p in pipeline.processes]
        assert "FASTQC" in names
        assert "MULTIQC" in names

    def test_simple_workflow_container(self):
        pipeline = parse_nextflow_file(fixture_path("simple_workflow.nf"))
        fastqc = next(p for p in pipeline.processes if p.name == "FASTQC")
        assert fastqc.containers
        assert any("fastqc" in c.lower() for c in fastqc.containers)

    def test_simple_workflow_conda(self):
        pipeline = parse_nextflow_file(fixture_path("simple_workflow.nf"))
        multiqc = next(p for p in pipeline.processes if p.name == "MULTIQC")
        assert multiqc.condas

    def test_simple_workflow_connection(self):
        pipeline = parse_nextflow_file(fixture_path("simple_workflow.nf"))
        assert ("FASTQC", "MULTIQC") in pipeline.connections

    def test_complex_workflow_processes(self):
        pipeline = parse_nextflow_file(fixture_path("complex_workflow.nf"))
        names = [p.name for p in pipeline.processes]
        for expected in ("STAR_ALIGN", "SAMTOOLS_SORT", "FEATURECOUNTS"):
            assert expected in names

    def test_complex_workflow_includes_with_imports(self):
        """Include statements carry the imported process names."""
        pipeline = parse_nextflow_file(fixture_path("complex_workflow.nf"))
        assert len(pipeline.includes) >= 2
        imports_flat = [name for inc in pipeline.includes for name in inc.imports]
        assert "FASTQC" in imports_flat
        assert "TRIMGALORE" in imports_flat

    def test_complex_workflow_named(self):
        pipeline = parse_nextflow_file(fixture_path("complex_workflow.nf"))
        assert "RNASEQ" in [w.name for w in pipeline.workflows]

    def test_complex_workflow_connections(self):
        pipeline = parse_nextflow_file(fixture_path("complex_workflow.nf"))
        assert ("TRIMGALORE", "STAR_ALIGN") in pipeline.connections
        assert ("STAR_ALIGN", "SAMTOOLS_SORT") in pipeline.connections
        assert ("SAMTOOLS_SORT", "FEATURECOUNTS") in pipeline.connections

    def test_file_not_found(self):
        with pytest.raises(FileNotFoundError):
            parse_nextflow_file("/nonexistent/pipeline.nf")


# ---------------------------------------------------------------------------
# nf-core fixtures – real-world pipeline files
# ---------------------------------------------------------------------------


class TestNfCoreFixtures:
    """Tests using real nf-core pipeline and module files as examples."""

    # ------------------------------------------------------------------
    # nf-core/fetchngs – SRA download workflow
    # ------------------------------------------------------------------

    def test_fetchngs_parses_without_error(self):
        """nf-core/fetchngs SRA workflow parses without raising an exception."""
        pipeline = parse_nextflow_file(fixture_path("nf_core_fetchngs_sra.nf"))
        assert isinstance(pipeline, ParsedPipeline)

    def test_fetchngs_has_sra_workflow(self):
        """nf-core/fetchngs declares a named 'SRA' workflow."""
        pipeline = parse_nextflow_file(fixture_path("nf_core_fetchngs_sra.nf"))
        assert "SRA" in [w.name for w in pipeline.workflows]

    def test_fetchngs_includes_are_extracted(self):
        """nf-core/fetchngs include statements are all captured."""
        pipeline = parse_nextflow_file(fixture_path("nf_core_fetchngs_sra.nf"))
        # The pipeline has multiple include statements (at least 6 named ones)
        assert len(pipeline.includes) >= 6
        paths = [inc.path for inc in pipeline.includes]
        assert any("sra_ids_to_runinfo" in p for p in paths)
        assert any("sra_runinfo_to_ftp" in p for p in paths)
        assert any("sra_to_samplesheet" in p for p in paths)

    def test_fetchngs_imported_process_names_extracted(self):
        """Imported process names from include blocks are resolved."""
        pipeline = parse_nextflow_file(fixture_path("nf_core_fetchngs_sra.nf"))
        imports_flat = [name for inc in pipeline.includes for name in inc.imports]
        assert "SRA_IDS_TO_RUNINFO" in imports_flat
        assert "SRA_RUNINFO_TO_FTP" in imports_flat
        assert "SRA_TO_SAMPLESHEET" in imports_flat

    def test_fetchngs_primary_connection_detected(self):
        """The SRA_IDS_TO_RUNINFO → SRA_RUNINFO_TO_FTP connection is detected."""
        pipeline = parse_nextflow_file(fixture_path("nf_core_fetchngs_sra.nf"))
        assert ("SRA_IDS_TO_RUNINFO", "SRA_RUNINFO_TO_FTP") in pipeline.connections

    def test_fetchngs_diagram_renders(self):
        """nf-core/fetchngs pipeline produces a valid Mermaid gitGraph."""
        from nf_mapper.mermaid import pipeline_to_mermaid

        pipeline = parse_nextflow_file(fixture_path("nf_core_fetchngs_sra.nf"))
        diagram = pipeline_to_mermaid(pipeline, title="nf-core/fetchngs")
        assert "gitGraph" in diagram
        assert "SRA_IDS_TO_RUNINFO" in diagram
        assert "SRA_RUNINFO_TO_FTP" in diagram

    # ------------------------------------------------------------------
    # nf-core module – FASTQC process definition
    # ------------------------------------------------------------------

    def test_fastqc_module_parses(self):
        """nf-core FASTQC module file parses correctly."""
        pipeline = parse_nextflow_file(fixture_path("nf_core_fastqc_module.nf"))
        assert len(pipeline.processes) == 1
        assert pipeline.processes[0].name == "FASTQC"

    def test_fastqc_module_has_containers(self):
        """nf-core FASTQC module captures both Singularity and Docker containers."""
        pipeline = parse_nextflow_file(fixture_path("nf_core_fastqc_module.nf"))
        containers = pipeline.processes[0].containers
        assert len(containers) >= 1
        assert any("fastqc" in c.lower() for c in containers)

    def test_fastqc_module_has_conda(self):
        """nf-core FASTQC module captures the conda environment path."""
        pipeline = parse_nextflow_file(fixture_path("nf_core_fastqc_module.nf"))
        condas = pipeline.processes[0].condas
        assert condas, "Expected at least one conda entry"

    def test_fastqc_module_diagram(self):
        """nf-core FASTQC module produces a single-commit gitGraph."""
        from nf_mapper.mermaid import pipeline_to_mermaid

        pipeline = parse_nextflow_file(fixture_path("nf_core_fastqc_module.nf"))
        diagram = pipeline_to_mermaid(pipeline)
        assert "gitGraph" in diagram
        assert 'commit id: "FASTQC"' in diagram


# ---------------------------------------------------------------------------
# Data-class sanity checks
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
