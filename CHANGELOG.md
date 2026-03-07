# Changelog

All notable changes to nf-mapper are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [Unreleased]

### Added

- **Channel nodes** – each process's `output: path("*.ext")` patterns are now
  rendered as `type: HIGHLIGHT` commits immediately after the process commit,
  with the file extension surfaced as a `tag` (e.g. `tag: "bam"`).  Both
  standalone `path "*.bam"` declarations and patterns nested inside
  `tuple val(meta), path("*.bam")` are detected.

- **Cherry-pick for cross-branch channel references** – when a branch process
  consumes a channel that was committed on a different branch (e.g. a QC step
  that reads the aligner's `*.bam` output), a `cherry-pick id: "…"` commit is
  emitted before the branch process to make the data-flow direction explicit.

- **Per-call branches in flat mode** – when there are no explicit channel
  connections, each independent workflow call beyond the first is placed on its
  own `branch_N` instead of being appended linearly on `main`.

- **`NfProcess.inputs` / `NfProcess.outputs`** – new list fields on
  `NfProcess` that hold the string-literal `path(…)` patterns extracted from
  the `input:` and `output:` labeled sections of a process body.

- **Snapshot tests** – `tests/test_snapshots.py` writes
  `tests/snapshots/*.md` on every test run so diagrams can be visually
  validated in any Markdown renderer that supports Mermaid.

- **`.github/copilot-instructions.md`** – codebase summary for AI coding
  agents (replaces the ad-hoc `CODEBASE_NOTES.md`).

### Fixed

- **Branch-merge duplication bug** – `_render_dag` previously called
  `main_path.remove()` inside the iteration loop, which silently skipped
  nodes.  The loop now uses an `emitted: set[str]` guard instead.

- **Stray `break` after first merge** – a `break` statement prevented any
  off-nodes beyond the first from receiving their own branches when multiple
  parallel processes hung off the same main-path node.  All off-nodes are now
  handled correctly.

- **Generic `config` parameter for `pipeline_to_mermaid()`** – accepts an
  optional `config: dict[str, object]` whose keys are merged on top of the
  built-in defaults, allowing any Mermaid
  [gitGraph init option](https://mermaid.js.org/syntax/gitgraph.html#gitgraph-specific-configuration-options)
  to be overridden from Python, the CLI, or the GitHub Action.

- **`parallelCommits: true` default** – parallel branch commits are now
  visually aligned by default, producing a cleaner metro-map appearance.

- **`showBranches: false` default** – branch lane lines are hidden by default,
  keeping diagrams uncluttered.

- **`checkout main` first** – the `main` branch always receives at least one
  commit before any other branch is created, satisfying Mermaid's ordering
  requirement and producing a valid git-history shape.

- **`--config JSON` CLI flag** – pass a JSON object to override any Mermaid
  gitGraph config option, e.g. `--config '{"showBranches": true}'`.

- **`config` GitHub Action input** – exposes `--config` in the Action.

- **`--update FILE` CLI flag** – update the fenced code block between
  `<!-- nf-mapper -->` / `<!-- /nf-mapper -->` HTML comment markers in an
  existing Markdown file instead of writing a standalone output file.

- **`--marker NAME` CLI flag** – choose which named comment block to update
  with `--update`; defaults to `nf-mapper`.  Use unique names when a file
  contains multiple diagrams, e.g.:

  ```markdown
  <!-- main-pipeline -->
  <!-- /main-pipeline -->

  <!-- qc-pipeline -->
  <!-- /qc-pipeline -->
  ```

  Then target each independently:

  ```bash
  nf-mapper main.nf  --update README.md --marker main-pipeline --format md
  nf-mapper qc.nf    --update README.md --marker qc-pipeline   --format md
  ```

- **`update` and `marker` GitHub Action inputs** – expose `--update` /
  `--marker` in the Action for in-place README and wiki updates.

- **`--regenerate FILE`** – scan a Markdown file for every `nf-mapper`
  comment block that carries a `pipeline` preset attribute and regenerate
  each one in-place with a single command.  No `PIPELINE.NF` argument is
  needed.  Preset attributes supported in the opening comment:

  | Attribute | Description |
  |---|---|
  | `pipeline` | Path to the `.nf` file (relative to the Markdown file) |
  | `title` | Diagram title |
  | `format` | `plain` or `md` (default: `md`) |
  | `config` | JSON object of Mermaid gitGraph config overrides |

  Example — two named blocks in one file, regenerated with a single call:

  ```markdown
  <!-- nf-mapper:main pipeline="workflows/main.nf" title="Main" format="md" -->
  <!-- /nf-mapper:main -->

  <!-- nf-mapper:qc pipeline="workflows/qc.nf" title="QC" format="md" -->
  <!-- /nf-mapper:qc -->
  ```

  ```bash
  nf-mapper --regenerate README.md
  ```

- **`--update` preserves preset attributes** – when `--update FILE` is used,
  the opening comment (including any preset attributes) is left untouched;
  only the body between the markers is replaced.

- **`.github/workflows/update-readme.yml`** simplified – the workflow now
  runs a single `nf-mapper --regenerate README.md` step instead of one
  `--update` call per example, because the preset attributes live directly
  in the README marker comments.

### Changed

- `pipeline_to_mermaid()` signature: the `show_branches: bool` parameter
  introduced earlier in this PR has been superseded by the more general
  `config: dict[str, object] | None` parameter.

- `--output` / `-o` and `--update` are now mutually exclusive CLI options.

---

## Future PRs

### Planned

- **Interactive HTML output** – render diagrams via the Mermaid JS CDN for
  standalone HTML reports.

- **Sub-workflow nesting** – nested `workflow` blocks rendered as Mermaid
  sub-graphs.

- **`--watch` mode** – automatically regenerate the diagram whenever the
  `.nf` file changes on disk.

- **Configurable main-branch name** – allow renaming `main` to match the
  user's preferred branch name (e.g. `master`).
