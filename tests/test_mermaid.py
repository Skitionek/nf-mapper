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


# ---------------------------------------------------------------------------
# Channel nodes (HIGHLIGHT commits)
# ---------------------------------------------------------------------------


class TestChannelNodes:
    """Tests for output-channel HIGHLIGHT commit nodes."""

    def test_output_channel_creates_highlight(self):
        """A process with an output path pattern emits a HIGHLIGHT commit."""
        pipeline = _make_pipeline(
            processes=[NfProcess(name="ALIGN", outputs=["*.bam"])],
        )
        result = pipeline_to_mermaid(pipeline)
        assert 'commit id: "ALIGN: *.bam" type: HIGHLIGHT tag: "bam"' in result

    def test_highlight_placed_after_process_commit(self):
        """HIGHLIGHT channel commit appears immediately after the process commit."""
        pipeline = _make_pipeline(
            processes=[NfProcess(name="ALIGN", outputs=["*.bam"])],
        )
        result = pipeline_to_mermaid(pipeline)
        idx_proc = result.index('commit id: "ALIGN"')
        idx_chan = result.index('commit id: "ALIGN: *.bam"')
        assert idx_proc < idx_chan

    def test_multiple_outputs_each_get_highlight(self):
        """Every output pattern produces its own HIGHLIGHT commit."""
        pipeline = _make_pipeline(
            processes=[NfProcess(name="FASTQC", outputs=["*.html", "*.zip"])],
        )
        result = pipeline_to_mermaid(pipeline)
        assert 'commit id: "FASTQC: *.html" type: HIGHLIGHT tag: "html"' in result
        assert 'commit id: "FASTQC: *.zip" type: HIGHLIGHT tag: "zip"' in result

    def test_no_highlight_when_no_outputs(self):
        """Processes without declared path outputs emit no HIGHLIGHT commits."""
        pipeline = _make_pipeline(
            processes=[NfProcess(name="HELLO")],
        )
        result = pipeline_to_mermaid(pipeline)
        assert "HIGHLIGHT" not in result

    def test_extension_used_as_tag(self):
        """The tag is the file extension stripped from the glob pattern."""
        pipeline = _make_pipeline(
            processes=[NfProcess(name="SORT", outputs=["*.sorted.bam"])],
        )
        result = pipeline_to_mermaid(pipeline)
        assert 'tag: "bam"' in result
        assert "*.sorted.bam" in result

    def test_output_without_extension_has_no_tag(self):
        """Glob patterns with no extension produce a HIGHLIGHT without a tag."""
        pipeline = _make_pipeline(
            processes=[NfProcess(name="PROC", outputs=["prefix_*"])],
        )
        result = pipeline_to_mermaid(pipeline)
        assert 'commit id: "PROC: prefix_*" type: HIGHLIGHT' in result
        assert "tag:" not in result

    def test_simple_workflow_channels(self):
        """Parsed simple_workflow.nf has correct output channel HIGHLIGHT nodes."""
        pipeline = parse_nextflow_file(fixture_path("simple_workflow.nf"))
        result = pipeline_to_mermaid(pipeline)
        assert 'commit id: "FASTQC: *.html" type: HIGHLIGHT tag: "html"' in result
        assert 'commit id: "FASTQC: *.zip" type: HIGHLIGHT tag: "zip"' in result
        assert 'commit id: "MULTIQC: multiqc_report.html" type: HIGHLIGHT tag: "html"' in result

    def test_complex_workflow_channels(self):
        """complex_workflow.nf locally-defined processes emit channel nodes."""
        pipeline = parse_nextflow_file(fixture_path("complex_workflow.nf"))
        result = pipeline_to_mermaid(pipeline)
        assert 'commit id: "STAR_ALIGN: *.bam" type: HIGHLIGHT tag: "bam"' in result
        assert 'commit id: "FEATURECOUNTS: *.counts.txt" type: HIGHLIGHT tag: "txt"' in result

    def test_channel_nodes_do_not_create_branches(self):
        """Adding channel nodes to a linear pipeline must not introduce branches."""
        pipeline = _make_pipeline(
            processes=[
                NfProcess(name="ALIGN", outputs=["*.bam"]),
                NfProcess(name="SORT", outputs=["*.sorted.bam"]),
            ],
            connections=[("ALIGN", "SORT")],
        )
        result = pipeline_to_mermaid(pipeline)
        assert "branch" not in result
        assert "HIGHLIGHT" in result


# ---------------------------------------------------------------------------
# Cherry-pick (cross-branch channel references)
# ---------------------------------------------------------------------------


class TestCherryPick:
    """Tests for cherry-pick commits when a branch process uses a channel
    that was committed on a different branch."""

    def test_cherry_pick_when_branch_uses_main_channel(self):
        """Branch process cherry-picks output channel from its main predecessor."""
        pipeline = _make_pipeline(
            processes=[
                NfProcess(name="ALIGN", outputs=["*.bam"]),
                NfProcess(name="SORT"),
                NfProcess(name="QC"),
            ],
            connections=[("ALIGN", "SORT"), ("ALIGN", "QC")],
        )
        result = pipeline_to_mermaid(pipeline)
        assert 'cherry-pick id: "ALIGN: *.bam"' in result

    def test_no_cherry_pick_on_same_branch(self):
        """No cherry-pick when both source and consumer are on main."""
        pipeline = _make_pipeline(
            processes=[
                NfProcess(name="ALIGN", outputs=["*.bam"]),
                NfProcess(name="SORT"),
            ],
            connections=[("ALIGN", "SORT")],
        )
        result = pipeline_to_mermaid(pipeline)
        assert "cherry-pick" not in result

    def test_cherry_pick_before_process_commit(self):
        """Cherry-pick appears before the branch process commit."""
        pipeline = _make_pipeline(
            processes=[
                NfProcess(name="ALIGN", outputs=["*.bam"]),
                NfProcess(name="SORT"),
                NfProcess(name="QC"),
            ],
            connections=[("ALIGN", "SORT"), ("ALIGN", "QC")],
        )
        result = pipeline_to_mermaid(pipeline)
        idx_pick = result.index('cherry-pick id: "ALIGN: *.bam"')
        idx_qc = result.index('commit id: "QC"')
        assert idx_pick < idx_qc


# ---------------------------------------------------------------------------
# Workflow-call branches (flat rendering)
# ---------------------------------------------------------------------------


class TestWorkflowCallBranches:
    """Tests for the flat renderer: each workflow call beyond the first
    is placed on its own branch."""

    def test_single_call_stays_on_main(self):
        """A single workflow call requires no branch."""
        pipeline = _make_pipeline(
            processes=[NfProcess(name="ONLY")],
            workflows=[NfWorkflow(name="WF", calls=["ONLY"])],
        )
        result = pipeline_to_mermaid(pipeline)
        assert "branch" not in result
        assert 'commit id: "ONLY"' in result

    def test_multiple_calls_create_branches(self):
        """Two or more independent calls each get their own branch."""
        pipeline = _make_pipeline(
            processes=[NfProcess(name="A"), NfProcess(name="B")],
            workflows=[NfWorkflow(name="WF", calls=["A", "B"])],
        )
        result = pipeline_to_mermaid(pipeline)
        assert "branch" in result
        assert 'commit id: "A"' in result
        assert 'commit id: "B"' in result

    def test_first_call_on_main(self):
        """The first workflow call is committed on main before any branch."""
        pipeline = _make_pipeline(
            processes=[NfProcess(name="A"), NfProcess(name="B"), NfProcess(name="C")],
            workflows=[NfWorkflow(name="WF", calls=["A", "B", "C"])],
        )
        result = pipeline_to_mermaid(pipeline)
        assert result.index('commit id: "A"') < result.index("branch ")

    def test_call_order_preserved_in_branches(self):
        """Workflow call order is preserved even across branches."""
        pipeline = _make_pipeline(
            processes=[NfProcess(name="C"), NfProcess(name="A"), NfProcess(name="B")],
            workflows=[NfWorkflow(name="WF", calls=["A", "B", "C"])],
        )
        result = pipeline_to_mermaid(pipeline)
        assert result.index('"A"') < result.index('"B"') < result.index('"C"')

    def test_checkout_main_between_branches(self):
        """Each branch is followed by checkout main."""
        pipeline = _make_pipeline(
            processes=[NfProcess(name="A"), NfProcess(name="B")],
            workflows=[NfWorkflow(name="WF", calls=["A", "B"])],
        )
        result = pipeline_to_mermaid(pipeline)
        assert "checkout main" in result


# ---------------------------------------------------------------------------
# Merge logic (bug-fix regression tests)
# ---------------------------------------------------------------------------


class TestMergeLogic:
    """Tests for branch-merge behaviour, including regression tests for fixes:
    - No duplicate commits after a fast-forward merge.
    - Multiple off-nodes from the same main-path node all get branches.
    """

    def _merge_pipeline(self):
        """Return a pipeline where a branch converges back onto main.

        Main:  A → B → C → D
        Branch: QC (A → QC → C)  – branches off A, merges at C.
        """
        return _make_pipeline(
            processes=[
                NfProcess(name="A"), NfProcess(name="B"),
                NfProcess(name="C"), NfProcess(name="D"),
                NfProcess(name="QC"),
            ],
            connections=[("A", "B"), ("B", "C"), ("C", "D"), ("A", "QC"), ("QC", "C")],
        )

    def test_merge_keyword_present(self):
        """A branch that converges back to main must emit a ``merge`` statement."""
        result = pipeline_to_mermaid(self._merge_pipeline())
        assert "merge " in result

    def test_merge_appears_after_branch(self):
        """The ``merge`` must come after the branch checkout."""
        result = pipeline_to_mermaid(self._merge_pipeline())
        assert result.index("branch ") < result.index("merge ")

    def test_merge_appears_before_downstream_main_commit(self):
        """D (the node after the merge point C) appears after the merge."""
        result = pipeline_to_mermaid(self._merge_pipeline())
        assert result.index("merge ") < result.index('commit id: "D"')

    def test_no_duplicate_commits_after_merge(self):
        """Fast-forwarded nodes must not appear twice in the output."""
        result = pipeline_to_mermaid(self._merge_pipeline())
        # B is fast-forwarded before the merge and must appear exactly once
        assert result.count('commit id: "B"') == 1

    def test_all_nodes_present_after_merge(self):
        """All five nodes appear in the merged diagram.

        Note: the merge-target node (C) is represented by the ``merge`` commit
        rather than a separate ``commit id: "C"`` line.
        """
        result = pipeline_to_mermaid(self._merge_pipeline())
        for name in ("A", "B", "D", "QC"):
            assert f'"{name}"' in result
        # C is the merge target – it is represented by the merge statement itself
        assert "merge " in result

    def test_merge_with_channel_outputs(self):
        """Channel HIGHLIGHT nodes are emitted correctly around a merge."""
        pipeline = _make_pipeline(
            processes=[
                NfProcess(name="ALIGN", outputs=["*.bam"]),
                NfProcess(name="SORT", outputs=["*.sorted.bam"]),
                NfProcess(name="QC"),
                NfProcess(name="COUNT", outputs=["*.counts.txt"]),
            ],
            connections=[
                ("ALIGN", "SORT"), ("ALIGN", "QC"),
                ("QC", "COUNT"), ("SORT", "COUNT"),
            ],
        )
        result = pipeline_to_mermaid(pipeline)
        assert "merge " in result
        # ALIGN is on main; SORT (branch) cherry-picks ALIGN's *.bam channel
        assert 'cherry-pick id: "ALIGN: *.bam"' in result
        # COUNT's output channels appear after the merge
        idx_merge = result.index("merge ")
        idx_count_chan = result.index('commit id: "COUNT: *.counts.txt"')
        assert idx_merge < idx_count_chan

    def test_multiple_off_nodes_from_same_main_node(self):
        """Two branches from the same main-path node both get branches."""
        pipeline = _make_pipeline(
            processes=[
                NfProcess(name="A"), NfProcess(name="B"),
                NfProcess(name="QC1"), NfProcess(name="QC2"),
            ],
            connections=[("A", "B")],
        )
        result = pipeline_to_mermaid(pipeline)
        assert result.count("branch ") == 2
        assert "QC1" in result
        assert "QC2" in result
