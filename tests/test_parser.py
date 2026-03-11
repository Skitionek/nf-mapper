"""Tests for nf_mapper.parser.

These tests double as usage examples for the parser module.  Real-world
nf-core pipeline fixtures are used to validate parsing of production-grade
Nextflow code.
"""

from __future__ import annotations

import os
import tempfile

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

    def test_multi_arg_call_detected(self):
        """Parenthesised multi-arg calls PROCESS(a, b) are detected (path_expression form)."""
        content = """
        process TRIM  { script: 'trim' }
        process ALIGN { script: 'align' }
        workflow {
            main:
            TRIM(params.reads, params.adapter)
            ALIGN(TRIM.out.trimmed, params.genome)
        }
        """
        result = parse_nextflow_content(content)
        calls_flat = [c for wf in result.workflows for c in wf.calls]
        assert "TRIM" in calls_flat
        assert "ALIGN" in calls_flat

    def test_multi_arg_connection_detected(self):
        """Connection is inferred when a process.out ref appears inside multi-arg parens."""
        content = """
        process TRIM  { script: 'trim' }
        process ALIGN { script: 'align' }
        workflow {
            main:
            TRIM(params.reads, params.adapter)
            ALIGN(TRIM.out.trimmed, params.genome)
        }
        """
        result = parse_nextflow_content(content)
        assert ("TRIM", "ALIGN") in result.connections

    def test_empty_paren_call_detected(self):
        """Zero-argument calls PROCESS() and PROCESS () are detected (path_expression form)."""
        content = """
        include { SUB } from './sub'
        workflow {
            main:
            SUB()
        }
        """
        result = parse_nextflow_content(content)
        calls_flat = [c for wf in result.workflows for c in wf.calls]
        assert "SUB" in calls_flat

    def test_local_workflow_call_in_entry_workflow(self):
        """A locally-defined named workflow can be called from the entry workflow."""
        content = """
        include { PROC_A } from './proc_a'
        include { PROC_B } from './proc_b'
        workflow INNER {
            main:
            PROC_A()
            PROC_B(PROC_A.out.result)
        }
        workflow {
            main:
            INNER()
        }
        """
        result = parse_nextflow_content(content)
        # INNER is defined locally so its calls should be captured
        inner_wf = next(w for w in result.workflows if w.name == "INNER")
        assert "PROC_A" in inner_wf.calls
        assert "PROC_B" in inner_wf.calls
        # Entry workflow should detect the INNER call
        entry_wf = next(w for w in result.workflows if w.name is None)
        assert "INNER" in entry_wf.calls


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

    # ------------------------------------------------------------------
    # multi_arg_workflow.nf – parenthesised multi-arg / zero-arg calls
    # ------------------------------------------------------------------

    def test_multi_arg_workflow_processes(self):
        """Locally defined processes are extracted from the multi-arg fixture."""
        pipeline = parse_nextflow_file(fixture_path("multi_arg_workflow.nf"))
        names = [p.name for p in pipeline.processes]
        assert "ALIGN" in names
        assert "MULTIQC" in names

    def test_multi_arg_workflow_includes(self):
        """include imports are extracted from the multi-arg fixture."""
        pipeline = parse_nextflow_file(fixture_path("multi_arg_workflow.nf"))
        imports_flat = [n for inc in pipeline.includes for n in inc.imports]
        assert "FASTQC" in imports_flat
        assert "TRIM_GALORE" in imports_flat

    def test_multi_arg_workflow_named_workflow_calls(self):
        """Multi-arg and zero-arg calls inside a named workflow are all captured."""
        pipeline = parse_nextflow_file(fixture_path("multi_arg_workflow.nf"))
        named = next(w for w in pipeline.workflows if w.name == "PIPELINE")
        for expected in ("FASTQC", "TRIM_GALORE", "ALIGN", "MULTIQC"):
            assert expected in named.calls, f"{expected} not found in PIPELINE calls"

    def test_multi_arg_workflow_entry_workflow_calls_inner(self):
        """The entry workflow detects the zero-arg PIPELINE() call."""
        pipeline = parse_nextflow_file(fixture_path("multi_arg_workflow.nf"))
        entry = next(w for w in pipeline.workflows if w.name is None)
        assert "PIPELINE" in entry.calls

    def test_multi_arg_workflow_connections(self):
        """Connections are inferred from PROCESS.out references inside multi-arg calls."""
        pipeline = parse_nextflow_file(fixture_path("multi_arg_workflow.nf"))
        assert ("TRIM_GALORE", "ALIGN") in pipeline.connections
        assert ("ALIGN", "MULTIQC") in pipeline.connections


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
# Include alias parsing (include { X as Y } should register Y, not X)
# ---------------------------------------------------------------------------


class TestIncludeAlias:
    """Tests for the ``include { X as Y }`` alias syntax."""

    def test_alias_registers_alias_not_original(self):
        """``include { ORIG as ALIAS }`` must register only ALIAS."""
        content = """
        include { PMULTIQC as SUMMARY_PIPELINE } from '../modules/pmultiqc'
        workflow W {
            main:
            SUMMARY_PIPELINE(params.input)
        }
        """
        result = parse_nextflow_content(content)
        imports = result.includes[0].imports
        assert "SUMMARY_PIPELINE" in imports
        assert "PMULTIQC" not in imports

    def test_mixed_alias_and_plain(self):
        """A block with both plain and aliased imports extracts the right names."""
        content = """
        include { FASTQC; TRIMGALORE as TRIM } from './modules'
        """
        result = parse_nextflow_content(content)
        imports = result.includes[0].imports
        assert "FASTQC" in imports
        assert "TRIM" in imports
        assert "TRIMGALORE" not in imports

    def test_alias_not_creating_ghost_node(self):
        """The original name (before ``as``) must not appear as a diagram node."""
        from nf_mapper.mermaid import pipeline_to_mermaid

        content = """
        include { PMULTIQC as SUMMARY_PIPELINE } from '../modules/pmultiqc'
        workflow QUANTMS {
            main:
            SUMMARY_PIPELINE(params.input)
        }
        """
        result = parse_nextflow_content(content)
        diagram = pipeline_to_mermaid(result)
        assert "SUMMARY_PIPELINE" in diagram
        assert "PMULTIQC" not in diagram


# ---------------------------------------------------------------------------
# Recursive include resolution (parse_nextflow_file follows relative paths)
# ---------------------------------------------------------------------------


class TestRecursiveIncludeResolution:
    """Tests for recursive include parsing introduced to fix the quantms
    'one node' issue (running on main.nf should expand included workflows)."""

    def test_quantms_style_fixture_expands_subworkflow(self):
        """Parsing quantms_style/main.nf should expand QUANTMS workflow calls."""
        pipeline = parse_nextflow_file(
            fixture_path("quantms_style/main.nf")
        )
        proc_names = {p.name for p in pipeline.processes}
        # These are the process CALLS inside the QUANTMS sub-workflow
        assert "INPUT_CHECK" in proc_names
        assert "CREATE_INPUT_CHANNEL" in proc_names
        assert "FILE_PREPARATION" in proc_names
        assert "SUMMARY_PIPELINE" in proc_names

    def test_quantms_style_fixture_quantms_not_isolated_node(self):
        """QUANTMS (the wrapper) should not appear as a separate isolated node
        when it was successfully expanded into its constituent processes."""
        pipeline = parse_nextflow_file(
            fixture_path("quantms_style/main.nf")
        )
        # QUANTMS should have been removed from includes once expanded
        all_imports = {name for inc in pipeline.includes for name in inc.imports}
        assert "QUANTMS" not in all_imports

    def test_quantms_style_fixture_alias_not_in_imports(self):
        """The PMULTIQC alias (registered as SUMMARY_PIPELINE) must not
        appear as a ghost import in the sub-workflow's parsed includes."""
        pipeline = parse_nextflow_file(
            fixture_path("quantms_style/main.nf")
        )
        proc_names = {p.name for p in pipeline.processes}
        # Only alias should be present
        assert "SUMMARY_PIPELINE" in proc_names
        assert "PMULTIQC" not in proc_names

    def test_quantms_style_fixture_connections_merged(self):
        """Connections from the included sub-workflow are merged into the
        top-level pipeline."""
        pipeline = parse_nextflow_file(
            fixture_path("quantms_style/main.nf")
        )
        # quantms.nf produces INPUT_CHECK → CREATE_INPUT_CHANNEL
        assert ("INPUT_CHECK", "CREATE_INPUT_CHANNEL") in pipeline.connections

    def test_quantms_style_fixture_diagram_has_multiple_nodes(self):
        """The diagram for main.nf must have substantially more than one node
        once the sub-workflow is expanded."""
        from nf_mapper.mermaid import pipeline_to_mermaid

        pipeline = parse_nextflow_file(
            fixture_path("quantms_style/main.nf")
        )
        diagram = pipeline_to_mermaid(pipeline)
        process_commits = [
            line for line in diagram.splitlines()
            if "commit id:" in line and "HIGHLIGHT" not in line
        ]
        assert len(process_commits) > 3, (
            f"Expected >3 nodes but got {len(process_commits)}: {process_commits}"
        )

    def test_missing_include_file_falls_back_gracefully(self):
        """When an include path does not exist, parsing continues and the
        import stays in the includes list unchanged."""
        content = """
        include { MISSING_PROC } from './does_not_exist'
        workflow W {
            main:
            MISSING_PROC(params.input)
        }
        """
        with tempfile.NamedTemporaryFile(suffix=".nf", mode="w", delete=False) as f:
            f.write(content)
            tmp = f.name
        try:
            result = parse_nextflow_file(tmp)
        finally:
            os.unlink(tmp)
        # The import should still be present (file wasn't found)
        all_imports = {n for inc in result.includes for n in inc.imports}
        assert "MISSING_PROC" in all_imports


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


# ---------------------------------------------------------------------------
# Channel parsing (NfProcess.inputs / NfProcess.outputs)
# ---------------------------------------------------------------------------


class TestChannelParsing:
    """Tests for path-channel extraction from process input/output sections."""

    def test_output_glob_patterns_extracted(self):
        """String-literal path patterns in output: are stored in outputs."""
        content = """
        process ALIGN {
            output:
                tuple val(meta), path("*.bam"), emit: bam
            script: 'echo hi'
        }
        """
        result = parse_nextflow_content(content)
        assert len(result.processes) == 1
        assert "*.bam" in result.processes[0].outputs

    def test_multiple_output_lines(self):
        """Multiple output lines each contribute to outputs."""
        content = """
        process FASTQC {
            output:
                tuple val(meta), path("*.html"), emit: html
                tuple val(meta), path("*.zip"),  emit: zip
            script: 'echo hi'
        }
        """
        result = parse_nextflow_content(content)
        proc = result.processes[0]
        assert "*.html" in proc.outputs
        assert "*.zip" in proc.outputs

    def test_bare_path_without_tuple(self):
        """A bare path "file.html" output (no tuple) is also captured."""
        content = """
        process MULTIQC {
            output:
                path "multiqc_report.html", emit: report
            script: 'echo hi'
        }
        """
        result = parse_nextflow_content(content)
        assert "multiqc_report.html" in result.processes[0].outputs

    def test_variable_path_not_captured(self):
        """path(reads) with a bare variable name produces no output entry."""
        content = """
        process PROC {
            input:
                path reads
            output:
                path outfile
            script: 'echo hi'
        }
        """
        result = parse_nextflow_content(content)
        proc = result.processes[0]
        assert proc.inputs == []
        assert proc.outputs == []

    def test_script_paths_not_captured(self):
        """String literals inside script: blocks are not captured as channels."""
        content = """
        process PROC {
            output:
                path "*.bam", emit: bam
            script:
            '''
            path("some/fake/path.bam")
            '''
        }
        """
        result = parse_nextflow_content(content)
        proc = result.processes[0]
        # Only the real output, not any strings from the script block
        assert proc.outputs == ["*.bam"]

    def test_default_inputs_outputs_empty(self):
        """NfProcess created without inputs/outputs defaults to empty lists."""
        proc = NfProcess(name="FOO")
        assert proc.inputs == []
        assert proc.outputs == []

    def test_simple_workflow_fixture_outputs(self):
        """simple_workflow.nf FASTQC process has the expected outputs."""
        pipeline = parse_nextflow_file(
            os.path.join(FIXTURES, "simple_workflow.nf")
        )
        fastqc = next(p for p in pipeline.processes if p.name == "FASTQC")
        assert "*.html" in fastqc.outputs
        assert "*.zip" in fastqc.outputs

    def test_nf_core_fastqc_module_outputs(self):
        """nf_core_fastqc_module.nf FASTQC process has *.html and *.zip outputs."""
        pipeline = parse_nextflow_file(
            os.path.join(FIXTURES, "nf_core_fastqc_module.nf")
        )
        fastqc = pipeline.processes[0]
        assert "*.html" in fastqc.outputs
        assert "*.zip" in fastqc.outputs
