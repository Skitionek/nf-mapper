# nf-mapper

[![CI](https://github.com/Skitionek/nf-mapper/actions/workflows/ci.yml/badge.svg)](https://github.com/Skitionek/nf-mapper/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/java-17%2B-blue.svg)](https://adoptium.net/)

**Convert Nextflow pipelines into [Mermaid](https://mermaid.js.org/) `gitGraph` diagrams.**

nf-mapper parses `.nf` files using the [official Nextflow AST library](https://www.nextflow.io/docs/latest/developer/nextflow.ast.html)
(`io.nextflow:nf-lang`) and renders the pipeline's process graph as a metro-map-style `gitGraph`:
each process is a commit, the primary processing chain stays on `main`,
and parallel or QC branches diverge just like in a real git history.

---

## Why nf-mapper?

Nextflow pipelines can grow quickly into complex, multi-step workflows that are
difficult to navigate for anyone who did not write them.  Documentation written
by hand quickly drifts out of sync as the pipeline evolves, leaving new
contributors — or your future self — without a reliable overview.

nf-mapper solves this by **generating pipeline diagrams automatically from the
source code itself**, so the documentation is always in sync with the actual
workflow.  Visualising the process graph as a metro-map-style `gitGraph` makes
it easy to:

- **Understand new projects quickly** — spot the main processing chain, parallel
  QC branches, and file-format handoffs at a glance.
- **Keep documentation uniform and up to date** — regenerate all diagrams with a
  single command (or automatically via CI) whenever the pipeline changes.
- **Onboard collaborators faster** — a visual map lowers the barrier for anyone
  joining a project mid-way.

---

## Features

- Parses real-world nf-core pipelines (tested against
  [nf-core/fetchngs](https://github.com/nf-core/fetchngs),
  [nf-core/rnaseq](https://github.com/nf-core/rnaseq) modules, and
  [bigbio/quantms](https://github.com/bigbio/quantms))
- **Native Nextflow AST** – uses `io.nextflow:nf-lang` (`ScriptAstBuilder` →
  `ScriptNode`) for accurate, first-class parsing of all Nextflow DSL2 constructs
- **Full DSL2 call-style support** – detects process/workflow calls regardless
  of whether they use bare single-argument syntax (`PROCESS reads`),
  single-argument parenthesised syntax (`PROCESS(reads)`), multi-argument
  syntax (`PROCESS(a, b, c)`), or zero-argument syntax (`PROCESS()`).
- **Locally-defined sub-workflow calls** – when a pipeline defines a named
  `workflow FOO { … }` and then calls `FOO()` from the entry workflow, the
  call is correctly detected and rendered.
- Extracts **processes**, **workflows**, **includes** and infers
  **process connections** from `.out` channel references
- Parses **`input:`/`output:`** sections to extract `path(...)` channel
  patterns (e.g. `"*.bam"`, `"*.html"`)
- Outputs valid [Mermaid `gitGraph`](https://mermaid.js.org/syntax/gitgraph.html)
  diagrams – paste directly into GitHub Markdown, Notion, Confluence, etc.
- **Channel nodes**: each output path pattern rendered as a `HIGHLIGHT` commit
  tagged with the file extension (e.g. `tag: "bam"`)
- **Cherry-pick**: when a branch process consumes a channel committed on a
  different branch, a `cherry-pick` commit shows the data flow explicitly
- **Workflow-call branches**: independent workflow calls in a flat pipeline are
  each placed on their own branch instead of a linear sequence
- Available as a **CLI tool** (fat JAR), a **Docker image** and a **GitHub Action**

---

## Installation

### Docker (recommended)

A pre-built Docker image is published to the
[GitHub Container Registry](https://ghcr.io/skitionek/nf-mapper) on every push
to `main` and for every version tag:

```bash
docker pull ghcr.io/skitionek/nf-mapper:main
```

### Build from source

**Requirements:** Java 17+, Maven 3.6+

```bash
git clone https://github.com/Skitionek/nf-mapper.git
cd nf-mapper/nf-mapper
mvn package -DskipTests
# Produces: target/nf-mapper-1.0.0.jar
```

---

## Quick start

### Command line

```bash
# Print diagram to stdout (fat JAR)
java -jar nf-mapper/target/nf-mapper-1.0.0.jar workflow.nf

# Add a title and wrap in a Markdown fenced block
java -jar nf-mapper.jar workflow.nf --title "My Pipeline" --format md

# Save to a file
java -jar nf-mapper.jar workflow.nf -o diagram.md --format md

# Override Mermaid gitGraph config options
java -jar nf-mapper.jar workflow.nf --config '{"showBranches": true}'

# Update a specific diagram block inside an existing Markdown file in-place
java -jar nf-mapper.jar workflow.nf --update README.md --format md

# Update one of several named blocks in the same file
java -jar nf-mapper.jar workflow.nf --update README.md --marker my-pipeline --format md
```

### Docker

Run nf-mapper without any local installation by mounting the directory that
contains your `.nf` file(s) into the container:

```bash
# Print diagram to stdout
docker run --rm -v "$(pwd):/data" ghcr.io/skitionek/nf-mapper:main /data/workflow.nf

# Save diagram to a file (the output file is written inside the mounted volume)
docker run --rm -v "$(pwd):/data" ghcr.io/skitionek/nf-mapper:main \
  /data/workflow.nf --title "My Pipeline" --format md -o /data/diagram.md

# Update an existing Markdown file in-place
docker run --rm -v "$(pwd):/data" ghcr.io/skitionek/nf-mapper:main \
  /data/workflow.nf --update /data/README.md --format md
```

The image tag `main` tracks the latest commit on the default branch.
Version-pinned tags (e.g. `ghcr.io/skitionek/nf-mapper:1.2.3`) are published
for every release.

---

## Example outputs

> These diagrams are kept up to date automatically on every push to `main`
> by [`.github/workflows/update-readme.yml`](.github/workflows/update-readme.yml).
> Each block is wrapped in named HTML comment markers so `nf-mapper --update`
> can regenerate them in-place.

### Linear pipeline  *(two-step QC)*

```nextflow
process FASTQC  { ... }
process MULTIQC { ... }

workflow {
    FASTQC(reads_ch)
    MULTIQC(FASTQC.out.zip.collect())
}
```

<!-- nf-mapper:example-linear pipeline="nf-mapper/src/test/resources/fixtures/simple_workflow.nf" title="nf-core/rnaseq QC" format="md" -->
```mermaid
---
title: nf-core/rnaseq QC
---
%%{init: {'theme': 'base', 'themeVariables': {'git0': '#24B064', 'gitInv0': '#ffffff', 'git1': '#FA7F19', 'gitInv1': '#ffffff', 'git2': '#0570b0', 'gitInv2': '#ffffff', 'git3': '#e63946', 'gitInv3': '#ffffff', 'git4': '#9b59b6', 'gitInv4': '#ffffff', 'git5': '#f5c542', 'gitInv5': '#000000', 'git6': '#1abc9c', 'gitInv6': '#ffffff', 'git7': '#7b2d3b', 'gitInv7': '#ffffff'}, 'gitGraph': {'showBranches': true, 'parallelCommits': false}} }%%
gitGraph LR:
   checkout main
   commit id: "FASTQC"
   commit id: "FASTQC: *" type: HIGHLIGHT tag: "*.html" tag: "*.zip"
   commit id: "MULTIQC"
   commit id: "MULTIQC: multiqc_report.html" type: HIGHLIGHT tag: "multiqc_report.html"
```
<!-- /nf-mapper:example-linear -->

### Branching pipeline  *(QC + alignment)*

```nextflow
include { FASTQC     } from './modules/fastqc'
include { TRIMGALORE } from './modules/trimgalore'

process STAR_ALIGN    { ... }
process SAMTOOLS_SORT { ... }
process FEATURECOUNTS { ... }

workflow RNASEQ {
    take: reads
    main:
        FASTQC(reads)
        TRIMGALORE(reads)
        STAR_ALIGN(TRIMGALORE.out.trimmed)
        SAMTOOLS_SORT(STAR_ALIGN.out.bam)
        FEATURECOUNTS(SAMTOOLS_SORT.out.sorted_bam)
}
```

<!-- nf-mapper:example-branching pipeline="nf-mapper/src/test/resources/fixtures/complex_workflow.nf" title="RNA-seq Pipeline" format="md" -->
```mermaid
---
title: RNA-seq Pipeline
---
%%{init: {'theme': 'base', 'themeVariables': {'git0': '#24B064', 'gitInv0': '#ffffff', 'git1': '#FA7F19', 'gitInv1': '#ffffff', 'git2': '#0570b0', 'gitInv2': '#ffffff', 'git3': '#e63946', 'gitInv3': '#ffffff', 'git4': '#9b59b6', 'gitInv4': '#ffffff', 'git5': '#f5c542', 'gitInv5': '#000000', 'git6': '#1abc9c', 'gitInv6': '#ffffff', 'git7': '#7b2d3b', 'gitInv7': '#ffffff'}, 'gitGraph': {'showBranches': true, 'parallelCommits': false}} }%%
gitGraph LR:
   checkout main
   commit id: "TRIMGALORE"
   branch FASTQC
   checkout FASTQC
   commit id: "FASTQC"
   checkout main
   commit id: "STAR_ALIGN"
   commit id: "STAR_ALIGN: *.bam" type: HIGHLIGHT tag: "*.bam"
   commit id: "SAMTOOLS_SORT"
   commit id: "SAMTOOLS_SORT: *.sorted.bam" type: HIGHLIGHT tag: "*.sorted.bam"
   commit id: "FEATURECOUNTS"
   commit id: "FEATURECOUNTS: *.counts.txt" type: HIGHLIGHT tag: "*.counts.txt"
```
<!-- /nf-mapper:example-branching -->

### Real-world example – [nf-core/fetchngs](https://github.com/nf-core/fetchngs)

```bash
nf-mapper workflows/sra/main.nf --title "nf-core/fetchngs SRA"
```

<!-- nf-mapper:example-fetchngs pipeline="nf-mapper/src/test/resources/fixtures/nf_core_fetchngs_sra.nf" title="nf-core/fetchngs SRA" format="md" -->
```mermaid
---
title: nf-core/fetchngs SRA
---
%%{init: {'theme': 'base', 'themeVariables': {'git0': '#24B064', 'gitInv0': '#ffffff', 'git1': '#FA7F19', 'gitInv1': '#ffffff', 'git2': '#0570b0', 'gitInv2': '#ffffff', 'git3': '#e63946', 'gitInv3': '#ffffff', 'git4': '#9b59b6', 'gitInv4': '#ffffff', 'git5': '#f5c542', 'gitInv5': '#000000', 'git6': '#1abc9c', 'gitInv6': '#ffffff', 'git7': '#7b2d3b', 'gitInv7': '#ffffff'}, 'gitGraph': {'showBranches': true, 'parallelCommits': false}} }%%
gitGraph LR:
   checkout main
   commit id: "SRA_IDS_TO_RUNINFO"
   branch softwareVersionsToYAML
   checkout softwareVersionsToYAML
   commit id: "softwareVersionsToYAML"
   checkout main
   commit id: "SRA_RUNINFO_TO_FTP"
   branch ASPERA_CLI
   checkout ASPERA_CLI
   commit id: "if: ASPERA_CLI" type: REVERSE
   commit id: "ASPERA_CLI"
   checkout main
   branch FASTQ_DOWNLOAD_PREFETCH_FASTERQDUMP_SRATOOLS
   checkout FASTQ_DOWNLOAD_PREFETCH_FASTERQDUMP_SRATOOLS
   commit id: "if: FASTQ_DOWNLOAD_PREFETCH_FASTERQDUMP_SRATOOLS" type: REVERSE
   commit id: "FASTQ_DOWNLOAD_PREFETCH_FASTERQDUMP_SRATOOLS"
   checkout main
   branch SRA_FASTQ_FTP
   checkout SRA_FASTQ_FTP
   commit id: "if: SRA_FASTQ_FTP" type: REVERSE
   commit id: "SRA_FASTQ_FTP"
   checkout main
   commit id: "SRA_TO_SAMPLESHEET"
   commit id: "if: MULTIQC_MAPPINGS_CONFIG" type: REVERSE
   commit id: "MULTIQC_MAPPINGS_CONFIG"
```
<!-- /nf-mapper:example-fetchngs -->

---

## CLI reference

```
usage: nf-mapper [-h] [-o FILE | --update FILE | --regenerate FILE]
                 [--marker NAME] [--title TITLE] [--format {plain,md}]
                 [--config JSON]
                 [PIPELINE.NF]

positional arguments:
  PIPELINE.NF           Path to the Nextflow pipeline file to parse.
                        Not required when --regenerate is used.

options:
  -h, --help            show this help message and exit
  -o FILE, --output FILE
                        Write the diagram to FILE instead of stdout.
  --update FILE         Update the diagram inside <!-- MARKER --> /
                        <!-- /MARKER --> comment blocks in FILE. Use
                        --marker to target a specific block when a file
                        contains multiple diagrams.
  --regenerate FILE     Scan FILE for all nf-mapper comment blocks that
                        carry a 'pipeline' preset attribute and regenerate
                        each one in-place. PIPELINE.NF is not required.
                        Preset: <!-- nf-mapper pipeline="p.nf" title="T" format="md" -->
  --marker NAME         Marker name used with --update (default: nf-mapper).
  --title TITLE         Optional diagram title.
  --format {plain,md}   Output format: 'plain' emits raw Mermaid syntax;
                        'md' wraps it in a fenced code block (default: plain).
  --config JSON         JSON object of Mermaid gitGraph config overrides,
                        e.g. '{"showBranches": true}'. Merged with defaults:
                        showBranches=false, parallelCommits=true.
```

### Preset attributes

Each `<!-- nf-mapper -->` comment block in a Markdown file can carry preset
attributes that are read back by `--regenerate`:

| Attribute | Required | Description |
|---|---|---|
| `pipeline` | ✅ | Path to the `.nf` file (relative to the Markdown file) |
| `title` | | Diagram title |
| `format` | | `plain` or `md` (default: `md`) |
| `config` | | JSON object of Mermaid gitGraph config overrides |

Use unique marker names when a file contains multiple diagrams:

```markdown
<!-- nf-mapper:main-wf pipeline="workflows/main.nf" title="Main" format="md" -->
<!-- /nf-mapper:main-wf -->

<!-- nf-mapper:qc pipeline="workflows/qc.nf" title="QC" format="md" -->
<!-- /nf-mapper:qc -->
```

Then regenerate all at once:

```bash
nf-mapper --regenerate README.md
```
---

## GitHub Action

Add nf-mapper to any workflow to automatically generate a pipeline diagram
and commit it to your repository or attach it to a pull request.

### Write to a new file

```yaml
# .github/workflows/diagram.yml
name: Generate pipeline diagram

on:
  push:
    paths:
      - "**.nf"

jobs:
  diagram:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Generate Mermaid diagram
        uses: Skitionek/nf-mapper@main
        with:
          pipeline: workflows/main.nf
          output: docs/pipeline_diagram.md
          title: My Pipeline
          format: md

      - name: Commit diagram
        run: |
          git config user.name  "github-actions[bot]"
          git config user.email "github-actions[bot]@users.noreply.github.com"
          git add docs/pipeline_diagram.md
          git diff --cached --quiet || git commit -m "chore: update pipeline diagram"
          git push
```

### Update a block inside an existing Markdown file

Place one or more marker pairs in your Markdown file:

```markdown
## Pipeline diagram

<!-- main-pipeline -->
<!-- /main-pipeline -->

## QC diagram

<!-- qc-pipeline -->
<!-- /qc-pipeline -->
```

Then update each block independently:

```yaml
- name: Update main pipeline diagram
  uses: Skitionek/nf-mapper@main
  with:
    pipeline: workflows/main.nf
    update: README.md
    marker: main-pipeline
    title: Main Pipeline
    format: md

- name: Update QC diagram
  uses: Skitionek/nf-mapper@main
  with:
    pipeline: workflows/qc.nf
    update: README.md
    marker: qc-pipeline
    title: QC Pipeline
    format: md
```

### Action inputs

| Input | Required | Default | Description |
|---|---|---|---|
| `pipeline` | ✅ | — | Path to the `.nf` file |
| `output` | | `diagram.md` | Output file path (ignored when `update` is set) |
| `title` | | _(none)_ | Diagram title |
| `format` | | `md` | `plain` or `md` |
| `config` | | _(none)_ | JSON object of Mermaid gitGraph config overrides, e.g. `{"showBranches": true}` |
| `update` | | _(none)_ | Path to a Markdown file with `<!-- MARKER -->` / `<!-- /MARKER -->` blocks to update in-place |
| `marker` | | `nf-mapper` | Marker name identifying which block to update; use unique names for multiple diagrams in one file |

### Action outputs

| Output | Description |
|---|---|
| `diagram` | Path to the generated or updated diagram file |

---

## How it works

1. **Parse** – The `.nf` file is parsed using the
   [official Nextflow AST library](https://www.nextflow.io/docs/latest/developer/nextflow.ast.html)
   (`io.nextflow:nf-lang`).  The `ScriptAstBuilder` produces a `ScriptNode` —
   a `ModuleNode` subclass that exposes the Nextflow DSL constructs via clean
   typed getters.

2. **Extract** – The `ScriptNode` is interrogated to find:
   - `process` blocks via `getProcesses()` — each `ProcessNode` has
     pre-split `directives`, `inputs`, and `outputs` statements (no manual
     label scanning needed).  Container, conda and template directives are
     read directly from the directives block; string-literal `path(...)` patterns
     (e.g. `"*.bam"`, `"*.html"`) are extracted from the inputs/outputs blocks.
   - `workflow` blocks via `getWorkflows()` — each `WorkflowNode` has `takes`,
     `main`, and `emits` statements; `isEntry()` identifies the unnamed entry workflow.
   - `include` statements via `getIncludes()` — each `IncludeNode` holds the
     source path and a list of `IncludeModuleNode` name/alias pairs.
   - **Process connections** inferred from `.out` channel references inside
     workflow `main` blocks (e.g. `SORT(ALIGN.out.bam)` → edge `ALIGN → SORT`),
     including references inside `if`/`while`/`for` blocks.

3. **Render** – The connection graph is laid out as a `gitGraph`:
   - The **longest path** through the DAG becomes the `main` branch
   - Parallel paths (e.g. QC steps) become named branches; multiple off-nodes
     from the same main-path node each get their own branch
   - Convergence points become `merge` commits (duplicate-free)
   - Each process's output `path(...)` patterns become `type: HIGHLIGHT`
     commits tagged with the file extension
   - When a branch process uses a channel committed on a different branch,
     a `cherry-pick` commit makes the data-flow direction explicit
   - In flat mode (no channel connections), each independent workflow call
     is placed on its own branch

---

## Development

### Setup

```bash
git clone https://github.com/Skitionek/nf-mapper.git
cd nf-mapper/nf-mapper
mvn package -DskipTests
```

### Running tests

```bash
cd nf-mapper
mvn test
```

Tests use real nf-core pipeline files as fixtures in
`nf-mapper/src/test/resources/fixtures/`:

| Fixture | Source |
|---|---|
| `nf_core_fetchngs_sra.nf` | [nf-core/fetchngs](https://github.com/nf-core/fetchngs) `workflows/sra/main.nf` |
| `nf_core_fastqc_module.nf` | [nf-core/modules](https://github.com/nf-core/modules) `modules/nf-core/fastqc/main.nf` |
| `complex_workflow.nf` | Synthetic – exercises multi-argument and conditional-block call detection |

### CI

GitHub Actions builds the Maven project and runs all tests (Java 17)
on every push and pull request.  See [`.github/workflows/ci.yml`](.github/workflows/ci.yml).

---

## License

nf-mapper is released under the **[MIT License](LICENSE)**.

### Third-party licences

| Dependency | Licence |
|---|---|
| [io.nextflow:nf-lang](https://github.com/nextflow-io/nextflow) | Apache-2.0 |
| [org.apache.groovy:groovy](https://groovy-lang.org/) | Apache-2.0 |
| [info.picocli:picocli](https://picocli.info/) | Apache-2.0 |
| [nf-core/fetchngs](https://github.com/nf-core/fetchngs) *(test fixture)* | MIT |
| [nf-core/modules](https://github.com/nf-core/modules) *(test fixture)* | MIT |
