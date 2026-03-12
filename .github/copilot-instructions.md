# nf-mapper – Copilot instructions

## Purpose
Converts Nextflow `.nf` pipeline files into Mermaid `gitGraph` diagrams.
Implemented as a **Java 17 Maven project** (`nf-mapper/`) with a GitHub Action wrapper (`action.yml`).

## Project layout (`nf-mapper/src/main/java/com/nfmapper/`)
| Package | File | Role |
|---------|------|------|
| `parser/` | `NextflowParser.java` | Nextflow AST parser → `ParsedPipeline` |
| `mermaid/` | `MermaidRenderer.java` | `ParsedPipeline` → Mermaid gitGraph string |
| `cli/` | `NfMapperCli.java` | `nf-mapper` CLI entry-point (picocli) |
| `model/` | `NfProcess.java`, `NfWorkflow.java`, `NfInclude.java`, `ParsedPipeline.java` | Data model |

## Key data classes (`model/`)
```java
NfProcess(name, containers, condas, templates, inputs, outputs)
NfWorkflow(name, calls, mainFileRefs)
NfInclude(path, imports)
ParsedPipeline(processes, workflows, includes, connections, conditionalInfo)
```
- `connections` is `List<String[]>` – directed `[source, dest]` process pairs.
- `conditionalInfo` is `Map<String, String[]>` – `processName → [groupId, conditionText]`.

## Parser internals (`NextflowParser.java`)
- Uses `io.nextflow:nf-lang` (`ScriptAstBuilder`) for native Nextflow DSL2 AST parsing.
- `parseFile(String filePath)` follows `include` statements recursively (cycle detection via visited set).
- `extractProcess(ProcessNode)` collects name, containers, condas, templates, input/output path patterns.
- `extractWorkflow(WorkflowNode, ...)` collects process/workflow calls and detects connections via `.out` channel references.
- `unfoldSubWorkflowConnections(...)` replaces named workflow call nodes in the connection graph with their entry/exit processes.
- `collectMainFileRefs(...)` extracts `Channel.fromPath` / `file()` patterns from workflow `main:` blocks.
- Conditional tracking: processes inside `if`/`else` blocks get a `groupId` + condition text in `conditionalInfo`.

## Mermaid renderer internals (`MermaidRenderer.java`)
- `render(ParsedPipeline, title, configOverrides)` dispatches to `renderFlat()` (no connections) or `renderDag()`.
- **Flat rendering**: first workflow segment on `main`, each additional segment on its own named branch.
- **DAG rendering**: topological sort, longest path becomes `main`, parallel nodes on off-branches.
  - Off-main nodes grouped by their latest main-path predecessor.
  - `emitted` set prevents double-committing already-rendered nodes.
  - Merge-back: if an off-chain leads back to a main-path node, emit `merge`.
- **Channel commits**: after each process, emit `commit id: "PROC: *.ext" type: HIGHLIGHT tag: "ext"` for every output pattern.
- **Cherry-pick**: if a predecessor's output channel was committed on a different branch, emit `cherry-pick id: "PROC: *.ext"`.
- **Conditional commits**: processes inside if/else blocks are preceded by a `type: REVERSE` commit (if-node marker).
- **nf-core color scheme**: `git0`=#24B064 (green), `git1`=#FA7F19 (orange), `git2`=#0570b0 (blue), etc.
- Default config: `showBranches: true`, `parallelCommits: false` (overridable via `--config` JSON).

## CLI (`NfMapperCli.java`, picocli)
```
nf-mapper PIPELINE.NF [options]
  -o, --output FILE       write diagram to file
  --update FILE           update diagram inside marker blocks in-place
  --regenerate FILE       scan file for nf-mapper blocks and regenerate all
  --marker NAME           marker name (default: nf-mapper)
  --title TEXT            optional diagram title
  --format FORMAT         plain | md (default: plain)
  --config JSON           JSON config overrides for Mermaid gitGraph
```
Marker format in Markdown:
```html
<!-- nf-mapper[:name] pipeline="path" [title="..."] [format="md|plain"] [config='{}'] -->
... diagram content ...
<!-- /nf-mapper[:name] -->
```

## Test suite (`nf-mapper/src/test/java/com/nfmapper/`)
- `parser/ParserTest.java`         – unit + fixture tests for the parser (process, workflow, include extraction, connections, conditionals).
- `mermaid/MermaidRendererTest.java` – gitGraph output tests (structure, branching, channels, cherry-pick, config, conditional nodes).
- `cli/CliTest.java`               – CLI flag / marker-update tests.
- `snapshot/SnapshotTest.java`     – writes `snapshots/*.md` at repo root for visual validation of each fixture.
- Test fixtures in `nf-mapper/src/test/resources/fixtures/`.

## Build / test commands
```bash
cd nf-mapper
mvn verify                       # compile + test
mvn package -DskipTests          # build fat JAR → target/nf-mapper-1.0.0.jar
mvn test -pl . -Dtest=ParserTest # run a single test class
```

## GitHub Action (`action.yml`)
- Inputs: `pipeline`, `output`, `title`, `format`, `config`, `update`, `marker`
- Sets up Java 17 (Temurin), builds the fat JAR, then runs `java -jar nf-mapper-1.0.0.jar`.
- Output: `diagram` – path to the generated or updated file.
