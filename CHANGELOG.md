# Changelog

All notable changes to nf-mapper are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [Unreleased]

### Added

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

- **`.github/workflows/update-readme.yml`** – new workflow that regenerates
  the three README example diagrams automatically on every push to `main`
  and on pull requests that touch source or fixture files.

### Changed

- `pipeline_to_mermaid()` signature: the `show_branches: bool` parameter
  introduced earlier in this PR has been superseded by the more general
  `config: dict[str, object] | None` parameter.

- `--output` / `-o` and `--update` are now mutually exclusive CLI options.

---

## Future PRs

### Planned

- **Multiple diagram blocks by ID** – support `<!-- nf-mapper:my-id -->` /
  `<!-- /nf-mapper:my-id -->` as a shorthand alternative to `--marker`.

- **Interactive HTML output** – render diagrams via the Mermaid JS CDN for
  standalone HTML reports.

- **Sub-workflow nesting** – nested `workflow` blocks rendered as Mermaid
  sub-graphs.

- **`--watch` mode** – automatically regenerate the diagram whenever the
  `.nf` file changes on disk.

- **Configurable main-branch name** – allow renaming `main` to match the
  user's preferred branch name (e.g. `master`).
