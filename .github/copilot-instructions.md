# nf-mapper – Copilot instructions

## Purpose
Converts Nextflow `.nf` pipeline files into Mermaid `gitGraph` diagrams.

## Package layout (`nf_mapper/`)
| File | Role |
|------|------|
| `parser.py` | Groovy/Nextflow AST parser → `ParsedPipeline` |
| `mermaid.py` | `ParsedPipeline` → Mermaid gitGraph string |
| `cli.py` | `nf-mapper` CLI entry-point |
| `__init__.py` | Public exports: `parse_nextflow_file`, `pipeline_to_mermaid`, `ParsedPipeline` |

## Key data classes (`parser.py`)
```python
NfProcess(name, containers, condas, templates, inputs, outputs)
NfWorkflow(name, calls)
NfInclude(path, imports)
ParsedPipeline(processes, workflows, includes, connections)
```
`connections` is `list[tuple[str,str]]` – directed `(source_process, dest_process)` edges.

## Parser internals
- Uses `groovy_parser.parser.parse_and_digest_groovy_content` to get an AST dict.
- `_extract_features()` does a single recursive pass to collect processes, workflows, includes.
- `_extract_workflow_body()` finds process calls and `PROCESS.out.*` / `.set{}` channel refs to build `connections`.
- Labeled sections (e.g. `take:`, `main:`, `emit:`, `input:`, `output:`) detected by:
  `rule[-1] == "statement"` **and** children are `[label_node, COLON, body_node]`.
- `PATH_CHILD = {"leaf": "IDENTIFIER", "value": "path"}` – sentinel for `path(...)` calls.
- `_extract_process_channels(body)` scans `input:` / `output:` labeled sections and returns
  `(input_patterns, output_patterns)` – only string-literal `path("*.ext")` patterns.

## Mermaid internals
- `pipeline_to_mermaid()` dispatches to `_render_flat()` (no connections) or `_render_dag()`.
- `_render_flat()`: first call on `main`, each additional call on its own `branch_N`.
- `_render_dag()` builds a DAG, finds the longest chain (`main`), branches off parallel nodes.
  - Off-main nodes grouped in `branch_hang` by their latest main-path predecessor.
  - Uses `emitted: set[str]` to skip already-committed nodes (fixes old `main_path.remove` bug).
  - No `break` after merge → all off-nodes from the same main-path node get branches.
  - Merge-back: if an off-chain leads to a main-path node, emit `merge`; then emit that
    node's output channels and mark it as emitted.
- **Channel nodes**: after each process commit, emit
  `commit id: "PROC: *.ext" type: HIGHLIGHT tag: "ext"` for every `path("*.ext")` output.
- **Cherry-pick**: before a process commit on a branch, if any predecessor's output channel
  was committed on a *different* branch, emit `cherry-pick id: "PROC: *.ext"` to show data flow.

## Test suite (`tests/`)
- `test_parser.py`   – unit + fixture tests for the parser (including channel extraction).
- `test_mermaid.py`  – gitGraph output tests (structure, branching, channels, cherry-pick).
- `test_cli.py`      – CLI flag / marker-update tests.
- `test_snapshots.py`– writes `tests/snapshots/*.md` for visual validation of each fixture.
- Fixtures in `tests/fixtures/`: `minimal_process.nf`, `simple_workflow.nf`,
  `complex_workflow.nf`, `nf_core_fastqc_module.nf`, `nf_core_fetchngs_sra.nf`.
- Snapshots in `tests/snapshots/`: auto-generated `.md` files (always overwritten on test run).

## Build / test commands
```bash
pip install -e ".[dev]"
python -m pytest tests/ -x -q           # full suite (~3 min)
python -m pytest tests/test_mermaid.py  # mermaid tests only
python -m pytest tests/test_snapshots.py -v  # regenerate snapshots
ruff check nf_mapper/ tests/            # lint
```

## Important constants (parser.py)
```python
INCLUDE_PROCESS_RULE = ["statement", "statement_expression", "command_expression"]
IDENTIFIER_RULE      = ["primary", "identifier"]
PRE_IDENTIFIER_NAME  = ["expression", "postfix_expression", "path_expression"]
# Sentinel leaf-node dicts
PROCESS_CHILD / WORKFLOW_CHILD / INCLUDE_CHILD
CONTAINER_CHILD / CONDA_CHILD / TEMPLATE_CHILD / PATH_CHILD
```
