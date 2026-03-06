"""Tests for the nf-mapper CLI (nf_mapper.cli)."""

from __future__ import annotations

import os
import subprocess
import sys

FIXTURES = os.path.join(os.path.dirname(__file__), "fixtures")


def fixture_path(name: str) -> str:
    return os.path.join(FIXTURES, name)


def run_cli(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, "-m", "nf_mapper.cli", *args],
        capture_output=True,
        text=True,
    )


class TestCLI:
    def test_runs_successfully(self):
        result = run_cli(fixture_path("simple_workflow.nf"))
        assert result.returncode == 0, result.stderr

    def test_stdout_contains_gitgraph(self):
        result = run_cli(fixture_path("simple_workflow.nf"))
        assert "gitGraph" in result.stdout

    def test_stdout_contains_process_names(self):
        result = run_cli(fixture_path("simple_workflow.nf"))
        assert "FASTQC" in result.stdout
        assert "MULTIQC" in result.stdout

    def test_stdout_uses_commit_syntax(self):
        result = run_cli(fixture_path("simple_workflow.nf"))
        assert 'commit id:' in result.stdout

    def test_title_flag(self):
        result = run_cli(fixture_path("simple_workflow.nf"), "--title", "Test Pipeline")
        assert "title: Test Pipeline" in result.stdout

    def test_format_md(self):
        result = run_cli(fixture_path("simple_workflow.nf"), "--format", "md")
        assert "```mermaid" in result.stdout

    def test_output_file(self, tmp_path):
        out = tmp_path / "diagram.md"
        result = run_cli(
            fixture_path("simple_workflow.nf"), "-o", str(out), "--format", "md"
        )
        assert result.returncode == 0
        content = out.read_text()
        assert "```mermaid" in content
        assert "FASTQC" in content

    def test_missing_input_file_error(self):
        result = run_cli("/nonexistent/pipeline.nf")
        assert result.returncode != 0

    def test_complex_workflow(self):
        result = run_cli(fixture_path("complex_workflow.nf"))
        assert result.returncode == 0
        assert "STAR_ALIGN" in result.stdout
        assert "commit id:" in result.stdout

    def test_nf_core_fetchngs(self):
        """nf-core/fetchngs SRA workflow renders via CLI."""
        result = run_cli(
            fixture_path("nf_core_fetchngs_sra.nf"),
            "--title", "nf-core/fetchngs",
        )
        assert result.returncode == 0, result.stderr
        assert "gitGraph" in result.stdout
        assert "SRA_IDS_TO_RUNINFO" in result.stdout

    def test_nf_core_fastqc_module(self):
        """nf-core FASTQC module renders via CLI."""
        result = run_cli(fixture_path("nf_core_fastqc_module.nf"))
        assert result.returncode == 0, result.stderr
        assert "FASTQC" in result.stdout
