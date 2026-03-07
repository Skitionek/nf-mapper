"""Tests for nf_mapper.mermaid.

These tests verify the gitGraph output format using both synthetic pipelines
and real nf-core fixture files.
"""

from __future__ import annotations

import os

from nf_mapper.mermaid import _DEFAULT_GRAPH_CONFIG, pipeline_to_mermaid
from nf_mapper.parser import NfProcess, NfWorkflow, ParsedPipeline, parse_nextflow_file

FIXTURES = os.path.join(os.path.dirname(__file__), "fixtures")

_DEFAULT_INIT = (
    "%%{init: {'gitGraph': {'showBranches': false, 'parallelCommits': true}} }%%"
)


def fixture_path(name: str) -> str:
    return os.path.join(FIXTURES, name)


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
# Output structure
# ---------------------------------------------------------------------------


class TestGitGraphStructure:
    def test_returns_string(self):
        assert isinstance(pipeline_to_mermaid(_make_pipeline()), str)

    def test_starts_with_init_then_gitgraph(self):
        result = pipeline_to_mermaid(_make_pipeline())
        lines = result.splitlines()
        assert lines[0] == _DEFAULT_INIT
        assert lines[1] == "gitGraph LR:"
        assert lines[2] == "   checkout main"

    def test_title_adds_front_matter(self):
        result = pipeline_to_mermaid(_make_pipeline(), title="My Pipeline")
        assert "---" in result
        assert "title: My Pipeline" in result
        lines = result.splitlines()
        assert lines[0] == "---"
        assert lines[1] == "title: My Pipeline"
        assert lines[2] == "---"
        assert lines[3] == _DEFAULT_INIT
        assert lines[4] == "gitGraph LR:"
        assert lines[5] == "   checkout main"

    def test_no_title_no_front_matter(self):
        result = pipeline_to_mermaid(_make_pipeline())
        assert "---" not in result

    def test_no_flowchart_keyword(self):
        """The output must never contain the old flowchart syntax."""
        result = pipeline_to_mermaid(_make_pipeline(
            processes=[NfProcess(name="A"), NfProcess(name="B")],
            connections=[("A", "B")],
        ))
        assert "flowchart" not in result
        assert "-->" not in result


# ---------------------------------------------------------------------------
# gitGraph config
# ---------------------------------------------------------------------------


class TestConfig:
    def test_default_config_keys(self):
        """Default config contains showBranches and parallelCommits."""
        assert "showBranches" in _DEFAULT_GRAPH_CONFIG
        assert "parallelCommits" in _DEFAULT_GRAPH_CONFIG

    def test_default_showbranches_false(self):
        result = pipeline_to_mermaid(_make_pipeline())
        assert "'showBranches': false" in result

    def test_default_parallelcommits_true(self):
        result = pipeline_to_mermaid(_make_pipeline())
        assert "'parallelCommits': true" in result

    def test_override_single_key(self):
        """A single key in config overrides its default; others stay."""
        result = pipeline_to_mermaid(_make_pipeline(), config={"showBranches": True})
        assert "'showBranches': true" in result
        assert "'parallelCommits': true" in result  # default preserved

    def test_override_multiple_keys(self):
        result = pipeline_to_mermaid(
            _make_pipeline(),
            config={"showBranches": True, "parallelCommits": False},
        )
        assert "'showBranches': true" in result
        assert "'parallelCommits': false" in result

    def test_extra_key_included(self):
        """Keys not in defaults are added to the init directive."""
        result = pipeline_to_mermaid(
            _make_pipeline(), config={"rotateCommitLabel": False}
        )
        assert "'rotateCommitLabel': false" in result
        assert "'showBranches': false" in result  # defaults still present

    def test_empty_config_uses_defaults(self):
        result_none = pipeline_to_mermaid(_make_pipeline(), config=None)
        result_empty = pipeline_to_mermaid(_make_pipeline(), config={})
        assert result_none == result_empty


# ---------------------------------------------------------------------------
# Process / commit rendering
# ---------------------------------------------------------------------------


class TestCommitRendering:
    def test_process_becomes_commit(self):
        result = pipeline_to_mermaid(_make_pipeline(processes=[NfProcess(name="FASTQC")]))
        assert 'commit id: "FASTQC"' in result

    def test_multiple_processes_all_committed(self):
        pipeline = _make_pipeline(
            processes=[NfProcess(name="STEP_A"), NfProcess(name="STEP_B")]
        )
        result = pipeline_to_mermaid(pipeline)
        assert 'commit id: "STEP_A"' in result
        assert 'commit id: "STEP_B"' in result

    def test_single_process_no_branch(self):
        result = pipeline_to_mermaid(_make_pipeline(processes=[NfProcess(name="LONE")]))
        assert "branch" not in result
        assert 'commit id: "LONE"' in result


# ---------------------------------------------------------------------------
# Linear chains
# ---------------------------------------------------------------------------


class TestLinearChain:
    def test_linear_chain_on_main(self):
        """A → B → C all appear as commits; no branches needed."""
        pipeline = _make_pipeline(
            processes=[NfProcess(name="A"), NfProcess(name="B"), NfProcess(name="C")],
            connections=[("A", "B"), ("B", "C")],
        )
        result = pipeline_to_mermaid(pipeline)
        assert 'commit id: "A"' in result
        assert 'commit id: "B"' in result
        assert 'commit id: "C"' in result
        assert "branch" not in result

    def test_linear_order_preserved(self):
        pipeline = _make_pipeline(
            processes=[NfProcess(name="FIRST"), NfProcess(name="SECOND")],
            connections=[("FIRST", "SECOND")],
        )
        result = pipeline_to_mermaid(pipeline)
        idx_first = result.index("FIRST")
        idx_second = result.index("SECOND")
        assert idx_first < idx_second


# ---------------------------------------------------------------------------
# Branching / parallel paths
# ---------------------------------------------------------------------------


class TestBranching:
    def test_parallel_process_gets_branch(self):
        """A QC process parallel to the main chain goes on a branch."""
        # Main: A → B.  QC: C (standalone, no connections)
        pipeline = _make_pipeline(
            processes=[NfProcess(name="A"), NfProcess(name="B"), NfProcess(name="QC")],
            connections=[("A", "B")],
        )
        result = pipeline_to_mermaid(pipeline)
        assert "branch" in result
        assert "checkout" in result

    def test_parallel_branch_then_back_to_main(self):
        """After a branch the diagram returns to main."""
        pipeline = _make_pipeline(
            processes=[NfProcess(name="A"), NfProcess(name="B"), NfProcess(name="QC")],
            connections=[("A", "B")],
        )
        result = pipeline_to_mermaid(pipeline)
        assert "checkout main" in result

    def test_no_branch_for_linear_pipeline(self):
        """A purely linear pipeline has no branches."""
        pipeline = _make_pipeline(
            processes=[NfProcess(name="X"), NfProcess(name="Y")],
            connections=[("X", "Y")],
        )
        result = pipeline_to_mermaid(pipeline)
        assert "branch" not in result

    def test_workflow_order_used_when_no_connections(self):
        """Without connections, workflow call order determines commit order."""
        pipeline = _make_pipeline(
            processes=[NfProcess(name="C"), NfProcess(name="A"), NfProcess(name="B")],
            workflows=[NfWorkflow(name="WF", calls=["A", "B", "C"])],
        )
        result = pipeline_to_mermaid(pipeline)
        idx_a = result.index('"A"')
        idx_b = result.index('"B"')
        idx_c = result.index('"C"')
        assert idx_a < idx_b < idx_c

    def test_main_commit_precedes_branches(self):
        """main must have at least one commit before any branch is created."""
        # Main: A → B; QC is a source node with no main-path predecessor
        pipeline = _make_pipeline(
            processes=[NfProcess(name="A"), NfProcess(name="B"), NfProcess(name="QC")],
            connections=[("A", "B")],
        )
        result = pipeline_to_mermaid(pipeline)
        idx_first_commit = result.index("commit id:")
        idx_first_branch = result.index("branch ")
        assert idx_first_commit < idx_first_branch


# ---------------------------------------------------------------------------
# End-to-end – handcrafted fixtures
# ---------------------------------------------------------------------------


class TestHandcraftedEndToEnd:
    def test_simple_workflow(self):
        pipeline = parse_nextflow_file(fixture_path("simple_workflow.nf"))
        result = pipeline_to_mermaid(pipeline, title="Simple QC")
        assert "gitGraph" in result
        assert "title: Simple QC" in result
        assert 'commit id: "FASTQC"' in result
        assert 'commit id: "MULTIQC"' in result
        # Linear chain: FASTQC before MULTIQC, no branches
        assert result.index("FASTQC") < result.index("MULTIQC")
        assert "branch" not in result

    def test_complex_workflow(self):
        pipeline = parse_nextflow_file(fixture_path("complex_workflow.nf"))
        result = pipeline_to_mermaid(pipeline)
        assert "gitGraph" in result
        # Main chain should include the linear processing path
        for name in ("TRIMGALORE", "STAR_ALIGN", "SAMTOOLS_SORT", "FEATURECOUNTS"):
            assert name in result
        # FASTQC is a parallel branch (no output connections)
        assert "FASTQC" in result
        assert "branch" in result  # FASTQC goes on a branch
        # Main (TRIMGALORE) must appear before the branch
        assert result.index('commit id: "TRIMGALORE"') < result.index("branch ")

    def test_minimal_process(self):
        pipeline = parse_nextflow_file(fixture_path("minimal_process.nf"))
        result = pipeline_to_mermaid(pipeline)
        assert "gitGraph" in result
        assert 'commit id: "HELLO"' in result
        assert "branch" not in result


# ---------------------------------------------------------------------------
# End-to-end – nf-core fixtures
# ---------------------------------------------------------------------------


class TestNfCoreEndToEnd:
    def test_fetchngs_gitgraph(self):
        """nf-core/fetchngs SRA workflow renders as a valid gitGraph."""
        pipeline = parse_nextflow_file(fixture_path("nf_core_fetchngs_sra.nf"))
        result = pipeline_to_mermaid(pipeline, title="nf-core/fetchngs")
        assert result.startswith(
            "---\ntitle: nf-core/fetchngs\n---\n"
            + _DEFAULT_INIT
            + "\ngitGraph"
        )
        assert 'commit id: "SRA_IDS_TO_RUNINFO"' in result
        assert 'commit id: "SRA_RUNINFO_TO_FTP"' in result
        # Parallel tools (Aspera, sra-tools, FTP) show as branches
        assert "branch" in result
        assert "ASPERA_CLI" in result
        # main must have a commit before the first branch
        assert result.index("commit id:") < result.index("branch ")

    def test_fastqc_module_gitgraph(self):
        """nf-core FASTQC module renders as a single-commit gitGraph."""
        pipeline = parse_nextflow_file(fixture_path("nf_core_fastqc_module.nf"))
        result = pipeline_to_mermaid(pipeline)
        assert "gitGraph" in result
        assert 'commit id: "FASTQC"' in result
        assert "branch" not in result
