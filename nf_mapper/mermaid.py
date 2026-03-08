"""Convert a parsed Nextflow pipeline into a Mermaid gitGraph diagram.

The generated diagram uses the ``gitGraph`` syntax to render the pipeline as
a metro-map: each process is a commit, linear chains stay on the same branch,
and parallel paths branch off and optionally merge back.

Channel nodes
-------------
Each process's ``output:`` path patterns are rendered as ``type: HIGHLIGHT``
commits immediately after the process commit, with the file extension as a
``tag``.  For example, a process that outputs ``*.bam`` yields::

    commit id: "ALIGN: *.bam" type: HIGHLIGHT tag: "bam"

Cherry-pick
-----------
When a branch process receives input from a process whose output channel was
committed on a *different* branch, a ``cherry-pick`` is emitted before the
branch process commit to show the data flow explicitly::

    cherry-pick id: "ALIGN: *.bam"

Workflow-call branches
----------------------
When there are no explicit channel connections (flat rendering), each workflow
call beyond the first is placed on its own branch so that independent calls
appear as parallel lines rather than a linear sequence.
"""

from __future__ import annotations

from collections import defaultdict, deque
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from nf_mapper.parser import NfProcess, ParsedPipeline

#: Default Mermaid gitGraph configuration applied to every generated diagram.
#: Pass *config* to :func:`pipeline_to_mermaid` to override individual keys.
_DEFAULT_GRAPH_CONFIG: dict[str, object] = {
    "showBranches": False,
    "parallelCommits": True,
}


def _format_config(config: dict[str, object]) -> str:
    """Serialize *config* as a JS-style object literal with single-quoted keys.

    Booleans are rendered as ``true``/``false``, strings are single-quoted,
    and all other values are coerced with :func:`str`.
    """
    pairs = []
    for k, v in config.items():
        if isinstance(v, bool):
            val = "true" if v else "false"
        elif isinstance(v, str):
            val = f"'{v}'"
        else:
            val = str(v)
        pairs.append(f"'{k}': {val}")
    return "{" + ", ".join(pairs) + "}"


# ---------------------------------------------------------------------------
# Channel helpers
# ---------------------------------------------------------------------------


def _file_extension(pattern: str) -> str | None:
    """Return the file extension from a glob/file pattern, or *None*.

    Examples::

        _file_extension("*.html")        → "html"
        _file_extension("*.sorted.bam")  → "bam"
        _file_extension("report.html")   → "html"
        _file_extension("prefix_*")      → None
    """
    p = pattern.strip("'\"")
    if "." in p:
        ext = p.rsplit(".", 1)[-1]
        return ext if ext else None
    return None


def _channel_ids_with_ext(
    proc_name: str,
    proc_lookup: dict[str, NfProcess],
) -> list[tuple[str, str | None]]:
    """Return ``(channel_commit_id, extension_or_None)`` for every output of *proc_name*.

    Returns an empty list when the process is not in *proc_lookup* (e.g. it is
    an imported/included process whose definition is in another file).
    """
    proc = proc_lookup.get(proc_name)
    if proc is None:
        return []
    result: list[tuple[str, str | None]] = []
    for pattern in getattr(proc, "outputs", []):
        cid = f"{proc_name}: {pattern}"
        result.append((cid, _file_extension(pattern)))
    return result


def _emit_node_with_channels(
    lines: list[str],
    proc_name: str,
    proc_lookup: dict[str, NfProcess],
    predecessors: dict[str, list[str]],
    channel_branch: dict[str, str],
    current_branch: str,
) -> None:
    """Emit a process commit, optional cherry-picks, and output channel nodes.

    Steps:

    1. For every predecessor whose output channel was committed on a *different*
       branch, emit ``cherry-pick id: "PRED: *.ext"`` to make the data flow
       explicit on the current branch.
    2. Emit ``commit id: "PROC_NAME"``.
    3. For every ``path(...)`` output pattern, emit
       ``commit id: "PROC: *.ext" type: HIGHLIGHT tag: "ext"`` and record the
       channel in *channel_branch*.
    """
    # 1. Cherry-pick predecessor channels that live on a different branch
    for src in predecessors.get(proc_name, []):
        for cid, _ext in _channel_ids_with_ext(src, proc_lookup):
            if cid in channel_branch and channel_branch[cid] != current_branch:
                lines.append(f'   cherry-pick id: "{cid}"')

    # 2. Process commit
    lines.append(f'   commit id: "{proc_name}"')

    # 3. Output channel HIGHLIGHT commits
    for cid, ext in _channel_ids_with_ext(proc_name, proc_lookup):
        tag = f' tag: "{ext}"' if ext else ""
        lines.append(f'   commit id: "{cid}" type: HIGHLIGHT{tag}')
        channel_branch[cid] = current_branch


def pipeline_to_mermaid(
    pipeline: ParsedPipeline,
    title: str | None = None,
    config: dict[str, object] | None = None,
) -> str:
    """Convert *pipeline* to a Mermaid ``gitGraph`` diagram string.

    Each Nextflow process becomes a ``commit``.  The longest processing chain
    is placed on ``main``; parallel paths are rendered as branches.  When two
    parallel paths converge, the shorter one is merged back into ``main``.

    Parameters
    ----------
    pipeline:
        A :class:`~nf_mapper.parser.ParsedPipeline` as returned by
        :func:`~nf_mapper.parser.parse_nextflow_file`.
    title:
        Optional diagram title inserted as a YAML front-matter block.
    config:
        Optional mapping of Mermaid `gitGraph` config keys to override.
        Merged on top of :data:`_DEFAULT_GRAPH_CONFIG` (``showBranches: false``,
        ``parallelCommits: true``).  Any key accepted by the Mermaid
        ``%%{init}%%`` gitGraph directive may be supplied, e.g.
        ``{"showBranches": True, "rotateCommitLabel": False}``.

    Returns
    -------
    str
        A Mermaid ``gitGraph`` diagram as a multi-line string.

    Examples
    --------
    >>> from nf_mapper import parse_nextflow_file, pipeline_to_mermaid
    >>> pipeline = parse_nextflow_file("my_pipeline.nf")
    >>> print(pipeline_to_mermaid(pipeline, title="My Pipeline"))
    ---
    title: My Pipeline
    ---
    %%{init: {'gitGraph': {'showBranches': false, 'parallelCommits': true}} }%%
    gitGraph LR:
       checkout main
       commit id: "PROCESS_A"
       commit id: "PROCESS_B"
    """
    lines: list[str] = []

    if title:
        lines += ["---", f"title: {title}", "---"]

    merged_config = {**_DEFAULT_GRAPH_CONFIG, **(config or {})}
    lines.append(f"%%{{init: {{'gitGraph': {_format_config(merged_config)}}} }}%%")
    lines.append("gitGraph LR:")
    lines.append("   checkout main")

    if pipeline.connections:
        _render_dag(lines, pipeline)
    else:
        _render_flat(lines, pipeline)

    return "\n".join(lines)


# ---------------------------------------------------------------------------
# Flat rendering (no connection info)
# ---------------------------------------------------------------------------


def _render_flat(lines: list[str], pipeline: ParsedPipeline) -> None:
    """Emit commits in workflow-call order (or declaration order as fallback).

    With a single call the process stays on ``main``.  With multiple calls the
    first stays on ``main`` and every subsequent call gets its own branch, so
    independent workflow calls appear as parallel lines rather than a linear
    sequence.
    """
    ordered: list[str] = []
    seen: set[str] = set()

    all_process_names = {p.name for p in pipeline.processes}
    # Include imported names
    for inc in pipeline.includes:
        all_process_names.update(inc.imports)

    for wf in pipeline.workflows:
        for call in wf.calls:
            if call in all_process_names and call not in seen:
                ordered.append(call)
                seen.add(call)

    # Add any declared processes not yet listed
    for proc in pipeline.processes:
        if proc.name not in seen:
            ordered.append(proc.name)
            seen.add(proc.name)

    proc_lookup: dict[str, NfProcess] = {p.name: p for p in pipeline.processes}

    if len(ordered) <= 1:
        # Single call: stays on main
        for name in ordered:
            lines.append(f'   commit id: "{name}"')
            for cid, ext in _channel_ids_with_ext(name, proc_lookup):
                tag = f' tag: "{ext}"' if ext else ""
                lines.append(f'   commit id: "{cid}" type: HIGHLIGHT{tag}')
    else:
        # Multiple calls: first on main, each subsequent call on its own branch
        branch_counter = [0]
        first = ordered[0]
        lines.append(f'   commit id: "{first}"')
        for cid, ext in _channel_ids_with_ext(first, proc_lookup):
            tag = f' tag: "{ext}"' if ext else ""
            lines.append(f'   commit id: "{cid}" type: HIGHLIGHT{tag}')

        for name in ordered[1:]:
            branch_counter[0] += 1
            bname = f"branch_{branch_counter[0]}"
            lines.append(f"   branch {bname}")
            lines.append(f"   checkout {bname}")
            lines.append(f'   commit id: "{name}"')
            for cid, ext in _channel_ids_with_ext(name, proc_lookup):
                tag = f' tag: "{ext}"' if ext else ""
                lines.append(f'   commit id: "{cid}" type: HIGHLIGHT{tag}')
            lines.append("   checkout main")


# ---------------------------------------------------------------------------
# DAG-based rendering
# ---------------------------------------------------------------------------


def _render_dag(lines: list[str], pipeline: ParsedPipeline) -> None:
    """Render the pipeline DAG as a gitGraph with branches for parallel paths.

    Fixes vs. original:

    * Uses an ``emitted`` set to track committed nodes instead of mutating
      *main_path* with ``remove()``, which caused skipped nodes during
      iteration.
    * Removed the ``break`` after a merge so that multiple off-nodes hanging
      off the same main-path node all get their own branches.
    * Output channel HIGHLIGHT commits are emitted after every process commit.
    * Cherry-pick commits are emitted before a branch-process commit when its
      input channel was originally committed on a different branch.
    """
    # Build adjacency structures
    successors: dict[str, list[str]] = defaultdict(list)
    predecessors: dict[str, list[str]] = defaultdict(list)
    nodes: set[str] = set()

    for proc in pipeline.processes:
        nodes.add(proc.name)
    for inc in pipeline.includes:
        nodes.update(inc.imports)
    for src, dst in pipeline.connections:
        nodes.update([src, dst])
        successors[src].append(dst)
        predecessors[dst].append(src)

    if not nodes:
        return

    # Process lookup for channel node emission
    proc_lookup: dict[str, NfProcess] = {p.name: p for p in pipeline.processes}
    # Tracks which branch each channel commit was emitted on
    channel_branch: dict[str, str] = {}

    # Stable topological order (sources first, alphabetical for ties)
    topo = _topo_sort(nodes, predecessors, successors)

    # Longest-path distances (for picking main branch)
    dist, path_pred = _longest_paths(topo, successors)

    # Trace the main branch: sink node with maximum distance
    sinks = [n for n in topo if not successors[n]]
    if not sinks:
        sinks = topo
    end = max(sinks, key=lambda n: dist[n])
    main_path = _trace_path(end, path_pred)
    main_set = set(main_path)

    # Group off-main nodes by their "branch-off" point on the main path
    # (the main-path predecessor from which they diverge)
    branch_hang: dict[str | None, list[str]] = defaultdict(list)
    for node in topo:
        if node in main_set:
            continue
        mp_pred = _latest_main_pred(node, predecessors, main_path)
        branch_hang[mp_pred].append(node)

    branch_counter = [0]

    def next_branch() -> str:
        branch_counter[0] += 1
        return f"branch_{branch_counter[0]}"

    # Ensure main is described before any branch: attach source-only nodes
    # (those with no predecessor on main) to the first main-path node so that
    # main always has at least one commit before branching begins.
    if main_path and None in branch_hang:
        branch_hang[main_path[0]].extend(branch_hang.pop(None))

    # emitted tracks process names whose commit has already been written
    emitted: set[str] = set()
    current_branch = "main"

    for node in main_path:
        if node not in emitted:
            _emit_node_with_channels(
                lines,
                node,
                proc_lookup,
                predecessors,
                channel_branch,
                current_branch,
            )
            emitted.add(node)
        for off_node in branch_hang.get(node, []):
            bname = next_branch()
            lines.append(f"   branch {bname}")
            lines.append(f"   checkout {bname}")
            current_branch = bname

            _emit_off_chain_with_channels(
                lines, off_node, main_set, successors,
                predecessors, proc_lookup, channel_branch, current_branch,
            )

            # Merge back if the off-chain leads to a main-path node
            merge_target = _find_merge_target(off_node, successors, main_set)
            if merge_target:
                node_idx = main_path.index(node)
                merge_idx = main_path.index(merge_target)
                lines.append("   checkout main")
                current_branch = "main"
                # Fast-forward: emit intermediate main-path nodes not yet committed
                for step in main_path[node_idx + 1 : merge_idx]:
                    if step not in emitted:
                        _emit_node_with_channels(
                            lines, step, proc_lookup, predecessors,
                            channel_branch, current_branch,
                        )
                        emitted.add(step)
                lines.append(f"   merge {bname}")
                # The merge commit represents merge_target; mark it emitted
                # and emit its output channels on main.
                emitted.add(merge_target)
                for cid, ext in _channel_ids_with_ext(merge_target, proc_lookup):
                    tag = f' tag: "{ext}"' if ext else ""
                    lines.append(f'   commit id: "{cid}" type: HIGHLIGHT{tag}')
                    channel_branch[cid] = current_branch
            else:
                lines.append("   checkout main")
                current_branch = "main"


# ---------------------------------------------------------------------------
# DAG helpers
# ---------------------------------------------------------------------------


def _topo_sort(
    nodes: set[str],
    predecessors: dict[str, list[str]],
    successors: dict[str, list[str]],
) -> list[str]:
    in_deg: dict[str, int] = {n: len(predecessors[n]) for n in nodes}
    queue: deque[str] = deque(sorted(n for n in nodes if in_deg[n] == 0))
    result: list[str] = []
    while queue:
        n = queue.popleft()
        result.append(n)
        for s in sorted(successors[n]):
            in_deg[s] -= 1
            if in_deg[s] == 0:
                queue.append(s)
    # Catch any nodes not reached (e.g., in cycles)
    for n in sorted(nodes):
        if n not in result:
            result.append(n)
    return result


def _longest_paths(
    topo: list[str],
    successors: dict[str, list[str]],
) -> tuple[dict[str, int], dict[str, str | None]]:
    dist: dict[str, int] = {n: 0 for n in topo}
    pred: dict[str, str | None] = {n: None for n in topo}
    for n in topo:
        for s in successors[n]:
            if dist[n] + 1 > dist[s]:
                dist[s] = dist[n] + 1
                pred[s] = n
    return dist, pred


def _trace_path(end: str, pred: dict[str, str | None]) -> list[str]:
    path: list[str] = []
    cur: str | None = end
    while cur is not None:
        path.append(cur)
        cur = pred[cur]
    path.reverse()
    return path


def _latest_main_pred(
    node: str,
    predecessors: dict[str, list[str]],
    main_path: list[str],
) -> str | None:
    """Return the main-path predecessor closest to the end of *main_path*."""
    candidates = [p for p in predecessors[node] if p in main_path]
    if not candidates:
        return None
    return max(candidates, key=main_path.index)


def _emit_off_chain_with_channels(
    lines: list[str],
    start: str,
    main_set: set[str],
    successors: dict[str, list[str]],
    predecessors: dict[str, list[str]],
    proc_lookup: dict[str, NfProcess],
    channel_branch: dict[str, str],
    current_branch: str,
) -> None:
    """Emit commits for an off-main chain beginning at *start*.

    Stops at main-path nodes.  For each node, cherry-picks any predecessor
    output channels that were committed on a different branch, then emits the
    process commit and its own output channels.
    """
    cur: str | None = start
    while cur is not None:
        _emit_node_with_channels(
            lines, cur, proc_lookup, predecessors, channel_branch, current_branch
        )
        off_succs = [s for s in successors[cur] if s not in main_set]
        cur = off_succs[0] if off_succs else None


def _find_merge_target(
    off_start: str,
    successors: dict[str, list[str]],
    main_set: set[str],
) -> str | None:
    """Return the first main-path node reachable from *off_start*, or None."""
    visited: set[str] = set()
    queue: deque[str] = deque([off_start])
    while queue:
        node = queue.popleft()
        if node in visited:
            continue
        visited.add(node)
        for s in successors[node]:
            if s in main_set:
                return s
            queue.append(s)
    return None
