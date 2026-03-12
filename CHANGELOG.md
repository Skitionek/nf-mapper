# Changelog

All notable changes to nf-mapper are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [Unreleased]

### Changed

- **Java rewrite (breaking)** – nf-mapper is now implemented in Java
  (`nf-mapper/`).  The Python implementation has been removed.

  - **Parser**: uses the [official Nextflow AST library](https://www.nextflow.io/docs/latest/developer/nextflow.ast.html)
    (`io.nextflow:nf-lang:25.04.4`).  `ScriptAstBuilder` produces a `ScriptNode`
    that exposes `getProcesses()`, `getWorkflows()`, and `getIncludes()` —
    dedicated typed getters for each Nextflow DSL construct.  No more
    pattern-matching on raw Groovy `MethodCallExpression` trees.
  - **CLI**: picocli-based `nf-mapper` command distributed as a shaded fat JAR
    (`nf-mapper/target/nf-mapper-*.jar`).  All flags are identical
    to the previous Python CLI (`--title`, `--format`, `-o`, `--update`,
    `--marker`, `--regenerate`, `--config`).
  - **Docker**: multi-stage build — Maven builder stage produces the fat JAR;
    `eclipse-temurin:17-jre-alpine` runtime stage runs it.
    No Python runtime required.
  - **GitHub Action**: now sets up Java 17 and builds the fat JAR rather
    than installing a Python package.
  - **CI**: Maven `verify` on Java 17 replaces the Python 3.10/3.11/3.12
    test matrix and ruff linting.
  - **Renderer/theme validation matrix**: test coverage now explicitly runs
    across renderer variants (`default`, `conditional`, `metro`) and themes
    (`nf-core`, `plain`), including snapshot suites.
  - **README examples**: renderer × theme examples are now documented as
    rendered diagram blocks rather than command-only listings.
  - **`.gitignore`**: stripped Python-specific entries; replaced with
    Java/Maven artifact patterns.

---

### Fixed

- **Parser workflow channel prepass now actively used** –
  `buildChannelVarMap(...)` is now invoked during workflow extraction,
  removing dead-code/"never used locally" diagnostics and ensuring channel
  variable aliases are collected before call traversal.

- **DAG merge channel registration regression** – merge-target channel commits
  are no longer registered as branch-local when the merge target commit itself
  is not emitted on that branch, preventing invalid `cherry-pick id: "..."`
  references.

- **CLI config map casting robustness** – JSON config parsing now normalizes
  decoded objects via string-keyed map conversion before use, avoiding unsafe
  cast warnings and improving marker-regeneration config handling.

- **Multi-argument and zero-argument parenthesised calls now detected** –
  the Groovy parser represents `PROCESS(a, b)` and `PROCESS()` as
  `path_expression` AST nodes rather than `command_expression` nodes.
  Both `_is_process_call` and the `_visit` traversal now handle both AST
  forms, so calls such as `TMT(ch_fileprep_result.iso, ch_expdesign,
  ch_db)` are correctly extracted as process calls and their `PROCESS.out`
  references are used to build connections.  Previously only single-argument
  bare-identifier calls (e.g. `PROCESS reads`) were detected, causing
  pipelines like [bigbio/quantms](https://github.com/bigbio/quantms) —
  which use the parenthesised nf-core call style throughout — to produce
  empty diagrams.

- **Locally-defined workflow names registered as known identifiers** –
  after a named `workflow FOO { … }` is extracted, `FOO` is now added to
  the set of known process/workflow names.  This allows a subsequent entry
  workflow (`workflow { … }`) that calls `FOO()` to detect and record that
  call.  Previously the entry workflow silently skipped calls to any
  workflow defined in the same file.

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

- **Docker image published to GitHub Container Registry** –
  `.github/workflows/docker-publish.yml` builds and pushes the image to
  `ghcr.io/skitionek/nf-mapper` automatically on every push to `main` and on
  version tags (`v*`).  Pull requests trigger a build-only run (no push) to
  catch `Dockerfile` breakage early.  Published tags:

  | Tag pattern | When created |
  |---|---|
  | `main` | push to the `main` branch |
  | `1.2.3` / `1.2` | semver release tag (`v1.2.3`) |
  | `sha-<short>` | every commit |

- **Docker usage documentation** – README now includes a *Docker* sub-section
  under both *Installation* and *Quick start* with `docker run --rm` examples
  for printing to stdout, saving to a file, and updating an existing Markdown
  file in-place.

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
