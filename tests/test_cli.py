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


# ---------------------------------------------------------------------------
# --config flag
# ---------------------------------------------------------------------------


class TestConfigFlag:
    def test_config_override_show_branches(self):
        """--config can override showBranches."""
        result = run_cli(
            fixture_path("simple_workflow.nf"),
            "--config", '{"showBranches": true}',
        )
        assert result.returncode == 0, result.stderr
        assert "'showBranches': true" in result.stdout

    def test_config_default_parallel_commits_present(self):
        """parallelCommits default appears in output even without --config."""
        result = run_cli(fixture_path("simple_workflow.nf"))
        assert result.returncode == 0, result.stderr
        assert "'parallelCommits': true" in result.stdout

    def test_config_extra_key(self):
        """--config can add keys not in the defaults."""
        result = run_cli(
            fixture_path("simple_workflow.nf"),
            "--config", '{"rotateCommitLabel": false}',
        )
        assert result.returncode == 0, result.stderr
        assert "'rotateCommitLabel': false" in result.stdout

    def test_invalid_config_json_exits_nonzero(self):
        """--config with invalid JSON exits with a non-zero return code."""
        result = run_cli(fixture_path("simple_workflow.nf"), "--config", "not-json")
        assert result.returncode != 0
        assert "JSON" in result.stderr or "json" in result.stderr.lower()


# ---------------------------------------------------------------------------
# --update / --marker flags
# ---------------------------------------------------------------------------


class TestUpdateMarker:
    def test_update_replaces_block_content(self, tmp_path):
        """--update replaces the content between default markers."""
        md = tmp_path / "README.md"
        md.write_text(
            "# Title\n\n"
            "<!-- nf-mapper -->\nold content\n<!-- /nf-mapper -->\n\n"
            "## Other\n"
        )
        result = run_cli(
            fixture_path("simple_workflow.nf"),
            "--update", str(md),
            "--format", "md",
        )
        assert result.returncode == 0, result.stderr
        content = md.read_text()
        assert "FASTQC" in content
        assert "```mermaid" in content
        assert "old content" not in content
        # Markers themselves must still be present
        assert "<!-- nf-mapper -->" in content
        assert "<!-- /nf-mapper -->" in content

    def test_update_custom_marker(self, tmp_path):
        """--update --marker targets a named block."""
        md = tmp_path / "README.md"
        md.write_text(
            "<!-- main-pipeline -->\nold\n<!-- /main-pipeline -->\n"
        )
        result = run_cli(
            fixture_path("simple_workflow.nf"),
            "--update", str(md),
            "--marker", "main-pipeline",
        )
        assert result.returncode == 0, result.stderr
        content = md.read_text()
        assert "old" not in content
        assert "FASTQC" in content

    def test_update_multiple_blocks_independent(self, tmp_path):
        """Each named block is updated independently."""
        md = tmp_path / "README.md"
        md.write_text(
            "<!-- pipeline-a -->\nblock-a-old\n<!-- /pipeline-a -->\n\n"
            "<!-- pipeline-b -->\nblock-b-old\n<!-- /pipeline-b -->\n"
        )
        # Update only pipeline-a
        run_cli(
            fixture_path("simple_workflow.nf"),
            "--update", str(md),
            "--marker", "pipeline-a",
        )
        content = md.read_text()
        assert "block-a-old" not in content   # updated
        assert "block-b-old" in content       # untouched

    def test_update_missing_markers_exits_nonzero(self, tmp_path):
        """--update exits non-zero when the markers are absent."""
        md = tmp_path / "README.md"
        md.write_text("# No markers here\n")
        result = run_cli(fixture_path("simple_workflow.nf"), "--update", str(md))
        assert result.returncode != 0
        assert "nf-mapper" in result.stderr  # helpful error mentions marker name

    def test_update_and_output_are_mutually_exclusive(self, tmp_path):
        """--update and -o cannot be used together."""
        result = run_cli(
            fixture_path("simple_workflow.nf"),
            "--update", str(tmp_path / "README.md"),
            "-o", str(tmp_path / "out.md"),
        )
        assert result.returncode != 0
