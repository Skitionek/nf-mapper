"""Snapshot tests – generate Mermaid diagrams and write them to
``tests/snapshots/*.md`` for visual validation.

Each test produces (or overwrites) a Markdown file containing a fenced
``mermaid`` code block.  The files are committed to the repository so they
can be inspected on GitHub or any Markdown renderer that supports Mermaid.

These tests **always write** the current output; they are not snapshot-
comparison tests.  Regression detection is handled by the behavioural
assertions in ``test_mermaid.py`` and ``test_parser.py``.
"""

from __future__ import annotations

import os

from nf_mapper import parse_nextflow_file, pipeline_to_mermaid
from nf_mapper.parser import NfProcess, NfWorkflow, ParsedPipeline

FIXTURES = os.path.join(os.path.dirname(__file__), "fixtures")
SNAPSHOTS = os.path.join(os.path.dirname(__file__), "snapshots")


def _write_snapshot(name: str, diagram: str, source: str = "") -> None:
    """Write *diagram* to ``tests/snapshots/{name}.md``."""
    os.makedirs(SNAPSHOTS, exist_ok=True)
    path = os.path.join(SNAPSHOTS, f"{name}.md")
    note = f"> Generated from `{source}`\n\n" if source else ""
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(f"# {name}\n\n{note}```mermaid\n{diagram}\n```\n")


def _make_pipeline(
    processes=None, workflows=None, includes=None, connections=None
) -> ParsedPipeline:
    return ParsedPipeline(
        processes=processes or [],
        workflows=workflows or [],
        includes=includes or [],
        connections=connections or [],
    )


# ---------------------------------------------------------------------------
# Fixture-based snapshots
# ---------------------------------------------------------------------------


class TestFixtureSnapshots:
    """One snapshot per fixture file, written to tests/snapshots/."""

    def test_snapshot_minimal_process(self):
        rel = "tests/fixtures/minimal_process.nf"
        pipeline = parse_nextflow_file(os.path.join(FIXTURES, "minimal_process.nf"))
        diagram = pipeline_to_mermaid(pipeline, title="Minimal Process")
        _write_snapshot("minimal_process", diagram, rel)
        assert "gitGraph" in diagram
        assert 'commit id: "HELLO"' in diagram

    def test_snapshot_simple_workflow(self):
        rel = "tests/fixtures/simple_workflow.nf"
        pipeline = parse_nextflow_file(os.path.join(FIXTURES, "simple_workflow.nf"))
        diagram = pipeline_to_mermaid(pipeline, title="nf-core/rnaseq QC")
        _write_snapshot("simple_workflow", diagram, rel)
        assert "gitGraph" in diagram
        assert 'commit id: "FASTQC"' in diagram
        assert 'commit id: "FASTQC: *.html" type: HIGHLIGHT tag: "html"' in diagram
        assert 'commit id: "MULTIQC"' in diagram

    def test_snapshot_complex_workflow(self):
        rel = "tests/fixtures/complex_workflow.nf"
        pipeline = parse_nextflow_file(os.path.join(FIXTURES, "complex_workflow.nf"))
        diagram = pipeline_to_mermaid(pipeline, title="RNA-seq Pipeline")
        _write_snapshot("complex_workflow", diagram, rel)
        assert "gitGraph" in diagram
        assert "branch" in diagram
        assert 'commit id: "STAR_ALIGN: *.bam" type: HIGHLIGHT tag: "bam"' in diagram

    def test_snapshot_nf_core_fastqc_module(self):
        rel = "tests/fixtures/nf_core_fastqc_module.nf"
        pipeline = parse_nextflow_file(os.path.join(FIXTURES, "nf_core_fastqc_module.nf"))
        diagram = pipeline_to_mermaid(pipeline, title="nf-core FASTQC module")
        _write_snapshot("nf_core_fastqc_module", diagram, rel)
        assert "gitGraph" in diagram
        assert 'commit id: "FASTQC"' in diagram
        assert 'commit id: "FASTQC: *.html" type: HIGHLIGHT tag: "html"' in diagram
        assert 'commit id: "FASTQC: *.zip" type: HIGHLIGHT tag: "zip"' in diagram

    def test_snapshot_nf_core_fetchngs_sra(self):
        rel = "tests/fixtures/nf_core_fetchngs_sra.nf"
        pipeline = parse_nextflow_file(os.path.join(FIXTURES, "nf_core_fetchngs_sra.nf"))
        diagram = pipeline_to_mermaid(pipeline, title="nf-core/fetchngs SRA")
        _write_snapshot("nf_core_fetchngs_sra", diagram, rel)
        assert "gitGraph" in diagram
        assert "branch" in diagram
        assert 'commit id: "SRA_IDS_TO_RUNINFO"' in diagram


# ---------------------------------------------------------------------------
# Synthetic scenario snapshots
# ---------------------------------------------------------------------------


class TestScenarioSnapshots:
    """Synthetic scenarios to show specific gitGraph features."""

    def test_snapshot_channel_nodes(self):
        """Diagram showing HIGHLIGHT channel nodes after each process."""
        pipeline = _make_pipeline(
            processes=[
                NfProcess(name="TRIM", outputs=["*.trimmed.fastq.gz"]),
                NfProcess(name="ALIGN", outputs=["*.bam"]),
                NfProcess(name="SORT", outputs=["*.sorted.bam"]),
            ],
            connections=[("TRIM", "ALIGN"), ("ALIGN", "SORT")],
        )
        diagram = pipeline_to_mermaid(pipeline, title="Channel Nodes Example")
        _write_snapshot("scenario_channel_nodes", diagram)
        assert 'commit id: "ALIGN: *.bam" type: HIGHLIGHT tag: "bam"' in diagram

    def test_snapshot_cherry_pick(self):
        """Diagram showing cherry-pick of a main-branch channel onto a sub-branch."""
        pipeline = _make_pipeline(
            processes=[
                NfProcess(name="ALIGN", outputs=["*.bam"]),
                NfProcess(name="SORT", outputs=["*.sorted.bam"]),
                NfProcess(name="QC", outputs=["*.qc.txt"]),
            ],
            connections=[("ALIGN", "SORT"), ("ALIGN", "QC")],
        )
        diagram = pipeline_to_mermaid(pipeline, title="Cherry-Pick Example")
        _write_snapshot("scenario_cherry_pick", diagram)
        assert 'cherry-pick id: "ALIGN: *.bam"' in diagram

    def test_snapshot_workflow_call_branches(self):
        """Diagram showing each independent workflow call on its own branch."""
        pipeline = _make_pipeline(
            processes=[
                NfProcess(name="FASTQC", outputs=["*.html", "*.zip"]),
                NfProcess(name="TRIMGALORE", outputs=["*.trimmed.fastq.gz"]),
                NfProcess(name="MULTIQC", outputs=["multiqc_report.html"]),
            ],
            workflows=[
                NfWorkflow(name="QC_WF", calls=["FASTQC", "TRIMGALORE", "MULTIQC"]),
            ],
        )
        diagram = pipeline_to_mermaid(pipeline, title="Workflow Call Branches")
        _write_snapshot("scenario_workflow_call_branches", diagram)
        assert "branch" in diagram

    def test_snapshot_merge(self):
        """Diagram showing a branch that merges back into main.

        The longest path (ALIGN → QC → COUNT) becomes ``main``.
        SORT branches off ALIGN and merges back at COUNT.

        Main:   ALIGN → QC → COUNT
        Branch: SORT (ALIGN → SORT → COUNT) – branches off ALIGN, merges at COUNT.
        """
        pipeline = _make_pipeline(
            processes=[
                NfProcess(name="ALIGN", outputs=["*.bam"]),
                NfProcess(name="QC"),
                NfProcess(name="SORT", outputs=["*.sorted.bam"]),
                NfProcess(name="COUNT", outputs=["*.counts.txt"]),
            ],
            connections=[
                ("ALIGN", "QC"), ("ALIGN", "SORT"),
                ("QC", "COUNT"), ("SORT", "COUNT"),
            ],
        )
        diagram = pipeline_to_mermaid(pipeline, title="Branch and Merge")
        _write_snapshot("scenario_merge", diagram)
        assert "merge " in diagram
        assert "branch " in diagram
        # SORT is on the branch; it cherry-picks ALIGN's *.bam channel from main
        assert 'cherry-pick id: "ALIGN: *.bam"' in diagram
        # COUNT's channels come after the merge
        assert diagram.index("merge ") < diagram.index('commit id: "COUNT: *.counts.txt"')
