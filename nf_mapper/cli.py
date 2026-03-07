"""Command-line interface for nf-mapper.

Usage
-----
.. code-block:: console

    nf-mapper pipeline.nf
    nf-mapper pipeline.nf --title "My Pipeline" --format md
    nf-mapper pipeline.nf -o diagram.md --format md
    nf-mapper pipeline.nf --config '{"showBranches": true}'
    nf-mapper pipeline.nf --update README.md --format md
    nf-mapper pipeline.nf --update README.md --marker my-pipeline --format md
    nf-mapper --regenerate README.md
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys

from nf_mapper.mermaid import pipeline_to_mermaid
from nf_mapper.parser import parse_nextflow_file

# ---------------------------------------------------------------------------
# Marker helpers
# ---------------------------------------------------------------------------

# Matches a complete <!-- nf-mapper[:name]? [attrs] --> ... <!-- /nf-mapper[:name]? -->
# block.  Groups: (1) opening comment, (2) body, (3) closing comment.
_BLOCK_RE = re.compile(
    r"(<!--\s*nf-mapper(?::[\w-]+)?[^>]*-->)"
    r"(.*?)"
    r"(<!--\s*/nf-mapper(?::[\w-]+)?\s*-->)",
    re.DOTALL | re.IGNORECASE,
)


def _parse_marker_attrs(attrs_str: str) -> dict[str, str]:
    """Parse ``key="value"`` or ``key='value'`` pairs from *attrs_str*.

    .. note::
        Escaped quotes inside attribute values (e.g. ``key="a \\"b\\" c"``) are
        not supported.  Values should not contain the quote character used as
        their delimiter.
    """
    result: dict[str, str] = {}
    for m in re.finditer(r'([\w-]+)=(?:"([^"]*)"|\'([^\']*)\')', attrs_str):
        key = m.group(1)
        value = m.group(2) if m.group(2) is not None else m.group(3)
        result[key] = value
    return result


def _update_marker(filepath: str, content: str, marker: str) -> None:
    """Replace the body between ``<!-- MARKER -->`` ... ``<!-- /MARKER -->`` in *filepath*.

    The opening comment is preserved as-is (so any preset attributes it
    carries are not lost).  Raises :class:`ValueError` if the markers are
    not found.
    """
    start_pat = re.escape(f"<!-- {marker}") + r"[^>]*-->"
    end_str = f"<!-- /{marker} -->"
    pattern = f"({start_pat})" + r".*?" + re.escape(end_str)

    with open(filepath, encoding="utf-8") as fh:
        text = fh.read()

    def _replace(m: re.Match) -> str:
        return f"{m.group(1)}\n{content}\n{end_str}"

    new_text, count = re.subn(pattern, _replace, text, flags=re.DOTALL)

    if count == 0:
        raise ValueError(
            f"Markers not found in {filepath!r}. "
            f"Add '<!-- {marker} -->' and '<!-- /{marker} -->' to the file."
        )

    with open(filepath, "w", encoding="utf-8") as fh:
        fh.write(new_text)


def _regenerate_all(filepath: str) -> int:
    """Scan *filepath* for every ``nf-mapper`` block that carries a
    ``pipeline`` preset attribute and regenerate its content in-place.

    Returns the number of blocks that could *not* be regenerated (errors).

    Preset format::

        <!-- nf-mapper pipeline="path/to/workflow.nf" title="My Pipeline" format="md" -->
        ...generated content...
        <!-- /nf-mapper -->

    Named blocks (multiple diagrams in one file)::

        <!-- nf-mapper:main pipeline="workflows/main.nf" title="Main" format="md" -->
        ...
        <!-- /nf-mapper:main -->

        <!-- nf-mapper:qc pipeline="workflows/qc.nf" title="QC" format="md" -->
        ...
        <!-- /nf-mapper:qc -->
    """
    base_dir = os.path.dirname(os.path.abspath(filepath))
    errors = [0]

    with open(filepath, encoding="utf-8") as fh:
        raw = fh.read()

    # Mask fenced code blocks (``` ... ```) with null bytes of the same length
    # so that documentation examples inside code fences are not matched by
    # _BLOCK_RE.  Same-length replacement preserves character positions.
    # If a fence is unclosed (malformed Markdown) the regex simply won't match
    # it, and the original text is used for that region – safe by default.
    masked = re.sub(
        r"`{3,}[^\n]*\n.*?`{3,}",
        lambda m: "\x00" * len(m.group()),
        raw,
        flags=re.DOTALL,
    )

    # Collect replacements: iterate over matches in the masked text but read
    # group content from the original text (positions are identical).
    segments: list[str] = []
    last_end = 0

    for m in _BLOCK_RE.finditer(masked):
        segments.append(raw[last_end : m.start()])

        opening = raw[m.start(1) : m.end(1)]
        closing = raw[m.start(3) : m.end(3)]

        attrs_m = re.search(
            r"<!--\s*nf-mapper(?::[\w-]+)?\s*(.*?)\s*-->",
            opening,
            re.IGNORECASE | re.DOTALL,
        )
        if not attrs_m:
            segments.append(raw[m.start() : m.end()])
            last_end = m.end()
            continue

        attrs = _parse_marker_attrs(attrs_m.group(1))
        pipeline_path = attrs.get("pipeline")
        if not pipeline_path:
            segments.append(raw[m.start() : m.end()])
            last_end = m.end()
            continue

        if not os.path.isabs(pipeline_path):
            pipeline_path = os.path.join(base_dir, pipeline_path)

        title = attrs.get("title") or None
        fmt = attrs.get("format", "md")
        config_str = attrs.get("config")

        try:
            config: dict[str, object] | None = (
                json.loads(config_str) if config_str else None
            )
        except json.JSONDecodeError as exc:
            print(
                f"nf-mapper: error parsing config in marker: {exc}",
                file=sys.stderr,
            )
            errors[0] += 1
            segments.append(raw[m.start() : m.end()])
            last_end = m.end()
            continue

        try:
            pipeline_obj = parse_nextflow_file(pipeline_path)
            diagram = pipeline_to_mermaid(pipeline_obj, title=title, config=config)
            body = f"```mermaid\n{diagram}\n```" if fmt == "md" else diagram
        except Exception as exc:  # noqa: BLE001
            print(
                f"nf-mapper: error processing '{pipeline_path}': {exc}",
                file=sys.stderr,
            )
            errors[0] += 1
            segments.append(raw[m.start() : m.end()])
            last_end = m.end()
            continue

        segments.append(f"{opening}\n{body}\n{closing}")
        last_end = m.end()

    segments.append(raw[last_end:])
    new_text = "".join(segments)

    if new_text != raw:
        with open(filepath, "w", encoding="utf-8") as fh:
            fh.write(new_text)

    return errors[0]


# ---------------------------------------------------------------------------
# Argument parser
# ---------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="nf-mapper",
        description="Convert a Nextflow pipeline (.nf) into a Mermaid gitGraph diagram.",
    )
    p.add_argument(
        "input",
        metavar="PIPELINE.NF",
        nargs="?",
        default=None,
        help=(
            "Path to the Nextflow pipeline file to parse. "
            "Not required when --regenerate is used."
        ),
    )

    out_group = p.add_mutually_exclusive_group()
    out_group.add_argument(
        "-o",
        "--output",
        metavar="FILE",
        default=None,
        help="Write the diagram to FILE instead of stdout.",
    )
    out_group.add_argument(
        "--update",
        metavar="FILE",
        default=None,
        help=(
            "Update the diagram inside <!-- MARKER --> / <!-- /MARKER --> "
            "comment blocks in FILE.  Use --marker to target a specific block "
            "when a file contains multiple diagrams."
        ),
    )
    out_group.add_argument(
        "--regenerate",
        metavar="FILE",
        default=None,
        help=(
            "Scan FILE for all nf-mapper comment blocks that carry a "
            "'pipeline' preset attribute and regenerate each one in-place.  "
            "PIPELINE.NF is not required when this flag is used.  "
            "Preset format: "
            "<!-- nf-mapper pipeline=\"path.nf\" title=\"T\" format=\"md\" -->"
        ),
    )

    p.add_argument(
        "--marker",
        default="nf-mapper",
        metavar="NAME",
        help=(
            "Marker name used with --update (default: nf-mapper).  "
            "Use a unique name per diagram when a file has multiple blocks."
        ),
    )
    p.add_argument(
        "--title",
        default=None,
        help="Optional diagram title.",
    )
    p.add_argument(
        "--format",
        choices=["plain", "md"],
        default="plain",
        help=(
            "Output format. "
            "'plain' emits the raw Mermaid syntax; "
            "'md' wraps it in a fenced code block (default: plain)."
        ),
    )
    p.add_argument(
        "--config",
        metavar="JSON",
        default=None,
        help=(
            "JSON object of Mermaid gitGraph config overrides, "
            "e.g. '{\"showBranches\": true}'. "
            "Merged with defaults: showBranches=false, parallelCommits=true."
        ),
    )
    return p


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    """Entry point for the ``nf-mapper`` command."""
    parser = build_parser()
    args = parser.parse_args(argv)

    # --regenerate mode: no PIPELINE.NF required
    if args.regenerate:
        rc = _regenerate_all(args.regenerate)
        return 1 if rc else 0

    # All other modes require PIPELINE.NF
    if not args.input:
        parser.error("PIPELINE.NF is required unless --regenerate is used")

    # Parse optional config JSON
    config: dict[str, object] | None = None
    if args.config:
        try:
            config = json.loads(args.config)
        except json.JSONDecodeError as exc:
            print(
                f"nf-mapper: error: --config is not valid JSON: {exc}",
                file=sys.stderr,
            )
            return 1

    pipeline = parse_nextflow_file(args.input)
    diagram = pipeline_to_mermaid(pipeline, title=args.title, config=config)

    if args.format == "md":
        output = f"```mermaid\n{diagram}\n```"
    else:
        output = diagram

    if args.update:
        try:
            _update_marker(args.update, output, args.marker)
        except (OSError, ValueError) as exc:
            print(f"nf-mapper: error: {exc}", file=sys.stderr)
            return 1
    elif args.output:
        with open(args.output, "w", encoding="utf-8") as fh:
            fh.write(output + "\n")
    else:
        print(output)

    return 0


if __name__ == "__main__":
    sys.exit(main())
