# nf-mapper

[![CI](https://github.com/Skitionek/nf-mapper/actions/workflows/ci.yml/badge.svg)](https://github.com/Skitionek/nf-mapper/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Python 3.10+](https://img.shields.io/badge/python-3.10%2B-blue.svg)](https://www.python.org/)

**Convert Nextflow pipelines into [Mermaid](https://mermaid.js.org/) `gitGraph` diagrams.**

nf-mapper parses `.nf` files using the
[python-groovy-parser](https://github.com/inab/python-groovy-parser) and
renders the pipeline's process graph as a metro-map-style `gitGraph`:
each process is a commit, the primary processing chain stays on `main`,
and parallel or QC branches diverge just like in a real git history.

---

## Features

- Parses real-world nf-core pipelines (tested against
  [nf-core/fetchngs](https://github.com/nf-core/fetchngs) and
  [nf-core/rnaseq](https://github.com/nf-core/rnaseq) modules)
- Extracts **processes**, **workflows**, **includes** and infers
  **process connections** from `.out` channel references
- Outputs valid [Mermaid `gitGraph`](https://mermaid.js.org/syntax/gitgraph.html)
  diagrams – paste directly into GitHub Markdown, Notion, Confluence, etc.
- Available as a **Python package**, a **CLI tool** and a **GitHub Action**

---

## Installation

```bash
pip install nf-mapper
```

Or install the latest development version:

```bash
pip install git+https://github.com/Skitionek/nf-mapper.git
```

**Requirements:** Python ≥ 3.10

---

## Quick start

### Command line

```bash
# Print diagram to stdout
nf-mapper workflow.nf

# Add a title and wrap in a Markdown fenced block
nf-mapper workflow.nf --title "My Pipeline" --format md

# Save to a file
nf-mapper workflow.nf -o diagram.md --format md
```

### Python API

```python
from nf_mapper import parse_nextflow_file, pipeline_to_mermaid

pipeline = parse_nextflow_file("workflow.nf")
diagram  = pipeline_to_mermaid(pipeline, title="My Pipeline")
print(diagram)
```

---

## Example outputs

### Linear pipeline  *(two-step QC)*

```nextflow
process FASTQC  { ... }
process MULTIQC { ... }

workflow {
    FASTQC(reads_ch)
    MULTIQC(FASTQC.out.zip.collect())
}
```

```mermaid
---
title: nf-core/rnaseq QC
---
gitGraph
   commit id: "FASTQC"
   commit id: "MULTIQC"
```

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

```mermaid
---
title: RNA-seq Pipeline
---
gitGraph
   branch branch_1
   checkout branch_1
   commit id: "FASTQC"
   checkout main
   commit id: "TRIMGALORE"
   commit id: "STAR_ALIGN"
   commit id: "SAMTOOLS_SORT"
   commit id: "FEATURECOUNTS"
```

### Real-world example – [nf-core/fetchngs](https://github.com/nf-core/fetchngs)

```bash
nf-mapper workflows/sra/main.nf --title "nf-core/fetchngs SRA"
```

```mermaid
---
title: nf-core/fetchngs SRA
---
gitGraph
   branch branch_1
   checkout branch_1
   commit id: "ASPERA_CLI"
   checkout main
   branch branch_2
   checkout branch_2
   commit id: "FASTQ_DOWNLOAD_PREFETCH_FASTERQDUMP_SRATOOLS"
   checkout main
   branch branch_3
   checkout branch_3
   commit id: "MULTIQC_MAPPINGS_CONFIG"
   checkout main
   branch branch_4
   checkout branch_4
   commit id: "SRA_FASTQ_FTP"
   checkout main
   branch branch_5
   checkout branch_5
   commit id: "SRA_TO_SAMPLESHEET"
   checkout main
   commit id: "SRA_IDS_TO_RUNINFO"
   commit id: "SRA_RUNINFO_TO_FTP"
```

---

## CLI reference

```
usage: nf-mapper [-h] [-o FILE] [--title TITLE] [--format {plain,md}] PIPELINE.NF

positional arguments:
  PIPELINE.NF           Path to the Nextflow pipeline file to parse.

options:
  -h, --help            show this help message and exit
  -o FILE, --output FILE
                        Write the diagram to FILE instead of stdout.
  --title TITLE         Optional diagram title.
  --format {plain,md}   Output format: 'plain' emits raw Mermaid syntax;
                        'md' wraps it in a fenced code block (default: plain).
```

---

## Python API reference

### `parse_nextflow_file(filepath) → ParsedPipeline`

Parse a `.nf` file and return a structured `ParsedPipeline` object.

```python
from nf_mapper import parse_nextflow_file

pipeline = parse_nextflow_file("workflow.nf")

print(pipeline.processes)    # list[NfProcess]  – declared process blocks
print(pipeline.workflows)    # list[NfWorkflow] – workflow blocks
print(pipeline.includes)     # list[NfInclude]  – include statements
print(pipeline.connections)  # list[tuple[str, str]] – (src, dst) edges
```

### `parse_nextflow_content(content) → ParsedPipeline`

Same as above but accepts a string instead of a file path.

```python
from nf_mapper import parse_nextflow_content

content = open("workflow.nf").read()
pipeline = parse_nextflow_content(content)
```

### `pipeline_to_mermaid(pipeline, title=None) → str`

Convert a `ParsedPipeline` to a Mermaid `gitGraph` string.

```python
from nf_mapper import pipeline_to_mermaid

diagram = pipeline_to_mermaid(pipeline, title="My Workflow")
```

### Data classes

| Class | Fields |
|---|---|
| `NfProcess` | `name`, `containers`, `condas`, `templates` |
| `NfWorkflow` | `name`, `calls` |
| `NfInclude` | `path`, `imports` |
| `ParsedPipeline` | `processes`, `workflows`, `includes`, `connections` |

---

## GitHub Action

Add nf-mapper to any workflow to automatically generate a pipeline diagram
and commit it to your repository or attach it to a pull request.

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

### Action inputs

| Input | Required | Default | Description |
|---|---|---|---|
| `pipeline` | ✅ | — | Path to the `.nf` file |
| `output` | | `diagram.md` | Output file path |
| `title` | | _(none)_ | Diagram title |
| `format` | | `md` | `plain` or `md` |

### Action outputs

| Output | Description |
|---|---|
| `diagram` | Path to the generated diagram file |

---

## How it works

1. **Parse** – The `.nf` file is tokenised and parsed with
   [python-groovy-parser](https://github.com/inab/python-groovy-parser),
   which implements a full Groovy 3 grammar using
   [Pygments](https://pygments.org/) + [Lark](https://github.com/lark-parser/lark).

2. **Extract** – The resulting AST is traversed to find:
   - `process` declarations (with `container` / `conda` directives)
   - `workflow` blocks (named and entry workflows, with `take:`/`main:`/`emit:` sections)
   - `include` statements (including imported process names)
   - **Process connections** inferred from `.out` channel references inside
     workflow bodies (e.g. `SORT(ALIGN.out.bam)` → edge `ALIGN → SORT`)

3. **Render** – The connection graph is laid out as a `gitGraph`:
   - The **longest path** through the DAG becomes the `main` branch
   - Parallel paths (e.g. QC steps) become named branches
   - Convergence points become `merge` commits

---

## Development

### Setup

```bash
git clone https://github.com/Skitionek/nf-mapper.git
cd nf-mapper
pip install -e ".[dev]"
```

### Running tests

```bash
pytest
```

Tests use real nf-core pipeline files as fixtures:

| Fixture | Source |
|---|---|
| `tests/fixtures/nf_core_fetchngs_sra.nf` | [nf-core/fetchngs](https://github.com/nf-core/fetchngs) `workflows/sra/main.nf` |
| `tests/fixtures/nf_core_fastqc_module.nf` | [nf-core/modules](https://github.com/nf-core/modules) `modules/nf-core/fastqc/main.nf` |

### Linting

```bash
ruff check nf_mapper/ tests/
```

### CI

GitHub Actions runs linting and the full test matrix (Python 3.10 / 3.11 / 3.12)
on every push and pull request.  See [`.github/workflows/ci.yml`](.github/workflows/ci.yml).

---

## License

nf-mapper is released under the **[MIT License](LICENSE)**.

### Third-party licences

| Dependency | Licence |
|---|---|
| [groovy-parser](https://github.com/inab/python-groovy-parser) | Apache-2.0 |
| [Lark](https://github.com/lark-parser/lark) | MIT |
| [nf-core/fetchngs](https://github.com/nf-core/fetchngs) *(test fixture)* | MIT |
| [nf-core/modules](https://github.com/nf-core/modules) *(test fixture)* | MIT |
