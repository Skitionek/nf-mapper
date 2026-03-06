"""Convert a parsed Nextflow pipeline into a Mermaid flowchart diagram.

The generated diagram uses the ``flowchart`` syntax and represents each
Nextflow process as a stadium-shaped node.  When process connections can be
determined from the workflow body, directed edges are drawn between them.
When no connections are available, processes are grouped inside a
``subgraph`` for each declared workflow.
"""

from __future__ import annotations

import re
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from nf_mapper.parser import ParsedPipeline

_SAFE_ID_RE = re.compile(r"[^A-Za-z0-9_]")


def _safe_id(name: str) -> str:
    """Return a Mermaid-safe node identifier derived from *name*."""
    return _SAFE_ID_RE.sub("_", name)


def pipeline_to_mermaid(
    pipeline: ParsedPipeline,
    direction: str = "LR",
    title: str | None = None,
) -> str:
    """Convert *pipeline* to a Mermaid ``flowchart`` diagram string.

    Parameters
    ----------
    pipeline:
        A :class:`~nf_mapper.parser.ParsedPipeline` as returned by
        :func:`~nf_mapper.parser.parse_nextflow_file`.
    direction:
        Mermaid flowchart direction.  One of ``"LR"`` (left-right, default),
        ``"TD"`` (top-down), ``"RL"``, or ``"BT"``.
    title:
        Optional diagram title inserted as a YAML front-matter block.

    Returns
    -------
    str
        A Mermaid diagram as a multi-line string.

    Examples
    --------
    >>> from nf_mapper import parse_nextflow_file, pipeline_to_mermaid
    >>> pipeline = parse_nextflow_file("my_pipeline.nf")
    >>> print(pipeline_to_mermaid(pipeline, title="My Pipeline"))
    ---
    title: My Pipeline
    ---
    flowchart LR
        ...
    """
    lines: list[str] = []

    if title:
        lines += ["---", f"title: {title}", "---"]

    lines.append(f"flowchart {direction}")

    process_names = {p.name for p in pipeline.processes}

    if pipeline.connections:
        # Render with explicit directed edges
        _render_with_connections(lines, pipeline, process_names)
    elif pipeline.workflows:
        # No connections detected – group processes by workflow
        _render_grouped(lines, pipeline, process_names)
    else:
        # Bare list of processes
        for proc in pipeline.processes:
            sid = _safe_id(proc.name)
            lines.append(f"    {sid}([{proc.name}])")

    return "\n".join(lines)


# ---------------------------------------------------------------------------
# Internal rendering helpers
# ---------------------------------------------------------------------------


def _render_with_connections(
    lines: list[str],
    pipeline: ParsedPipeline,
    process_names: set[str],
) -> None:
    """Append node definitions and edges for a pipeline with known connections."""
    # Collect all node names (both endpoints of every edge)
    edge_nodes: set[str] = set()
    for src, dst in pipeline.connections:
        edge_nodes.add(src)
        edge_nodes.add(dst)

    # Emit standalone nodes for processes not referenced in any edge
    for proc in pipeline.processes:
        if proc.name not in edge_nodes:
            sid = _safe_id(proc.name)
            lines.append(f"    {sid}([{proc.name}])")

    # Emit node definitions for processes that appear in edges
    rendered_nodes: set[str] = set()
    for name in sorted(edge_nodes):
        sid = _safe_id(name)
        label = name
        lines.append(f"    {sid}([{label}])")
        rendered_nodes.add(sid)

    # Emit edges
    for src, dst in pipeline.connections:
        lines.append(f"    {_safe_id(src)} --> {_safe_id(dst)}")


def _render_grouped(
    lines: list[str],
    pipeline: ParsedPipeline,
    process_names: set[str],
) -> None:
    """Append subgraph blocks grouping processes by workflow."""
    accounted: set[str] = set()

    for wf in pipeline.workflows:
        wf_label = wf.name or "main"
        wf_calls = [c for c in wf.calls if c in process_names]
        if not wf_calls:
            continue
        sid_wf = _safe_id(wf_label)
        lines.append(f"    subgraph {sid_wf}[{wf_label}]")
        for call in wf_calls:
            sid = _safe_id(call)
            lines.append(f"        {sid}([{call}])")
            accounted.add(call)
        lines.append("    end")

    # Any processes not yet rendered
    for proc in pipeline.processes:
        if proc.name not in accounted:
            sid = _safe_id(proc.name)
            lines.append(f"    {sid}([{proc.name}])")
