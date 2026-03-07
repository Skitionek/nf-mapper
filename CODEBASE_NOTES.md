# nf-mapper Codebase Notes

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

## Mermaid internals
- `pipeline_to_mermaid()` dispatches to `_render_flat()` (no connections) or `_render_dag()`.
- `_render_dag()` builds a DAG, finds the longest chain (`main`), branches off parallel nodes.
- Off-main nodes are grouped in `branch_hang` by their latest main-path predecessor.
- Merge-back logic: if an off-chain leads to a main-path node, emit `merge`.
- Channel nodes: `commit id: "PROC: *.ext" type: HIGHLIGHT tag: "ext"` after each process commit.
- Cherry-pick: `cherry-pick id: "PROC: *.ext"` when a branch needs a channel committed on a different branch.

## Test suite (`tests/`)
- `test_parser.py` – unit + fixture tests for the parser.
- `test_mermaid.py` – gitGraph output tests (structure, branching, end-to-end).
- `test_cli.py` – CLI flag / marker-update tests.
- Fixtures in `tests/fixtures/`: `minimal_process.nf`, `simple_workflow.nf`, `complex_workflow.nf`, `nf_core_fastqc_module.nf`, `nf_core_fetchngs_sra.nf`.

## Build / test commands
```bash
pip install -e ".[dev]"
python -m pytest tests/ -x -q          # full suite (~3 min)
python -m pytest tests/test_mermaid.py  # mermaid tests only
ruff check nf_mapper/                   # lint
```

## Important constants (parser.py)
```python
INCLUDE_PROCESS_RULE = ["statement", "statement_expression", "command_expression"]
IDENTIFIER_RULE = ["primary", "identifier"]
PRE_IDENTIFIER_NAME = ["expression", "postfix_expression", "path_expression"]
PROCESS_CHILD / WORKFLOW_CHILD / INCLUDE_CHILD / CONTAINER_CHILD / CONDA_CHILD / TEMPLATE_CHILD / PATH_CHILD
```
