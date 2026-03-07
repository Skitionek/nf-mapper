"""Convert a parsed Nextflow pipeline into a Mermaid gitGraph diagram.

The generated diagram uses the ``gitGraph`` syntax to render the pipeline as
a metro-map: each process is a commit, linear chains stay on the same branch,
and parallel paths branch off and optionally merge back.
"""

from __future__ import annotations

from collections import defaultdict, deque
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from nf_mapper.parser import ParsedPipeline


def pipeline_to_mermaid(
    pipeline: ParsedPipeline,
    title: str | None = None,
    show_branches: bool = False,
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
    show_branches:
        When ``True`` the branch lanes are visible in the rendered diagram.
        Defaults to ``False`` (branch lanes hidden via the Mermaid init
        directive).

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
    %%{init: {'gitGraph': {'showBranches': false}} }%%
    gitGraph LR:
       checkout main
       commit id: "PROCESS_A"
       commit id: "PROCESS_B"
    """
    lines: list[str] = []

    if title:
        lines += ["---", f"title: {title}", "---"]

    show_branches_val = str(show_branches).lower()
    lines.append(f"%%{{init: {{'gitGraph': {{'showBranches': {show_branches_val}}}}} }}%%")
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
    """Emit commits in workflow-call order (or declaration order as fallback)."""
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

    for name in ordered:
        lines.append(f'   commit id: "{name}"')


# ---------------------------------------------------------------------------
# DAG-based rendering
# ---------------------------------------------------------------------------


def _render_dag(lines: list[str], pipeline: ParsedPipeline) -> None:
    """Render the pipeline DAG as a gitGraph with branches for parallel paths."""
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

    # Emit main path commits, with branches hanging off each node
    for node in main_path:
        lines.append(f'   commit id: "{node}"')

        for off_node in branch_hang.get(node, []):
            bname = next_branch()
            lines.append(f"   branch {bname}")
            lines.append(f"   checkout {bname}")
            _emit_off_chain(lines, off_node, main_set, successors)
            # Merge back if the off-chain leads to a main-path node
            merge_target = _find_merge_target(off_node, successors, main_set)
            if merge_target:
                idx = main_path.index(merge_target)
                lines.append("   checkout main")
                # fast-forward to merge point then merge
                for step in main_path[main_path.index(node) + 1 : idx]:
                    lines.append(f'   commit id: "{step}"')
                lines.append(f"   merge {bname}")
                # mark those nodes as already emitted
                for step in main_path[main_path.index(node) + 1 : idx + 1]:
                    main_set.discard(step)
                    main_path.remove(step)
                break
            else:
                lines.append("   checkout main")


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


def _emit_off_chain(
    lines: list[str],
    start: str,
    main_set: set[str],
    successors: dict[str, list[str]],
) -> None:
    """Emit commits for a chain beginning at *start*, stopping at main-path nodes."""
    cur: str | None = start
    while cur is not None:
        lines.append(f'   commit id: "{cur}"')
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
