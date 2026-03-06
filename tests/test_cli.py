"""Tests for the nf-mapper CLI (nf_mapper.cli)."""

from __future__ import annotations

import os
import subprocess
import sys

import pytest

FIXTURES = os.path.join(os.path.dirname(__file__), "fixtures")


def fixture_path(name: str) -> str:
    return os.path.join(FIXTURES, name)


def run_cli(*args: str) -> subprocess.CompletedProcess:
    """Run `python -m nf_mapper.cli <args>` and return the CompletedProcess."""
    return subprocess.run(
        [sys.executable, "-m", "nf_mapper.cli", *args],
        capture_output=True,
        text=True,
    )


class TestCLI:
    def test_runs_successfully(self):
        """CLI exits with 0 for a valid input file."""
        result = run_cli(fixture_path("simple_workflow.nf"))
        assert result.returncode == 0, result.stderr

    def test_stdout_contains_flowchart(self):
        """CLI outputs a Mermaid flowchart to stdout."""
        result = run_cli(fixture_path("simple_workflow.nf"))
        assert "flowchart" in result.stdout

    def test_stdout_contains_process_names(self):
        """Process names appear in the CLI output."""
        result = run_cli(fixture_path("simple_workflow.nf"))
        assert "FASTQC" in result.stdout
        assert "MULTIQC" in result.stdout

    def test_direction_flag(self):
        """--direction TD changes flowchart direction."""
        result = run_cli(fixture_path("simple_workflow.nf"), "--direction", "TD")
        assert "flowchart TD" in result.stdout

    def test_title_flag(self):
        """--title adds YAML front matter."""
        result = run_cli(
            fixture_path("simple_workflow.nf"), "--title", "Test Pipeline"
        )
        assert "title: Test Pipeline" in result.stdout

    def test_format_md(self):
        """--format md wraps output in a fenced code block."""
        result = run_cli(fixture_path("simple_workflow.nf"), "--format", "md")
        assert "```mermaid" in result.stdout
        assert "```" in result.stdout

    def test_output_file(self, tmp_path):
        """--output writes to a file."""
        out = tmp_path / "diagram.md"
        result = run_cli(
            fixture_path("simple_workflow.nf"), "-o", str(out), "--format", "md"
        )
        assert result.returncode == 0
        content = out.read_text()
        assert "```mermaid" in content
        assert "FASTQC" in content

    def test_missing_input_file_error(self):
        """CLI returns non-zero exit code when input file is missing."""
        result = run_cli("/nonexistent/pipeline.nf")
        assert result.returncode != 0

    def test_complex_workflow(self):
        """CLI handles the complex workflow fixture."""
        result = run_cli(fixture_path("complex_workflow.nf"))
        assert result.returncode == 0
        assert "STAR_ALIGN" in result.stdout
        assert "-->" in result.stdout
