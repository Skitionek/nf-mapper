"""Command-line interface for nf-mapper.

Usage
-----
.. code-block:: console

    nf-mapper pipeline.nf
    nf-mapper pipeline.nf --title "My Pipeline" --format md
    nf-mapper pipeline.nf -o diagram.md --format md
    nf-mapper pipeline.nf --config '{"showBranches": true}'
    nf-mapper pipeline.nf --update README.md --format md
    nf-mapper pipeline.nf --update README.md --marker qc-pipeline --format md
"""

from __future__ import annotations

import argparse
import json
import re
import sys

from nf_mapper.mermaid import pipeline_to_mermaid
from nf_mapper.parser import parse_nextflow_file

_MARKER_OPEN = "<!-- {marker} -->"
_MARKER_CLOSE = "<!-- /{marker} -->"


def _update_marker(filepath: str, content: str, marker: str) -> None:
    """Replace the block between HTML comment markers in *filepath*.

    Looks for ``<!-- MARKER -->`` … ``<!-- /MARKER -->`` in the file and
    replaces everything between (and including) those two lines with
    *content*.  Raises :class:`ValueError` if the markers are not found.
    """
    start = _MARKER_OPEN.format(marker=marker)
    end = _MARKER_CLOSE.format(marker=marker)

    with open(filepath, encoding="utf-8") as fh:
        text = fh.read()

    pattern = re.escape(start) + r".*?" + re.escape(end)
    replacement = f"{start}\n{content}\n{end}"
    new_text, count = re.subn(pattern, replacement, text, flags=re.DOTALL)

    if count == 0:
        raise ValueError(
            f"Markers not found in {filepath!r}. "
            f"Add '{start}' and '{end}' to the file."
        )

    with open(filepath, "w", encoding="utf-8") as fh:
        fh.write(new_text)


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="nf-mapper",
        description="Convert a Nextflow pipeline (.nf) into a Mermaid gitGraph diagram.",
    )
    p.add_argument(
        "input",
        metavar="PIPELINE.NF",
        help="Path to the Nextflow pipeline file to parse.",
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
            "comment blocks in FILE.  Use --marker to specify which block "
            "when a file contains multiple diagrams."
        ),
    )

    p.add_argument(
        "--marker",
        default="nf-mapper",
        metavar="NAME",
        help=(
            "Marker name used with --update (default: nf-mapper).  "
            "Use a unique name per diagram when a file has multiple blocks, "
            "e.g. '<!-- my-pipeline -->' / '<!-- /my-pipeline -->'."
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


def main(argv: list[str] | None = None) -> int:
    """Entry point for the ``nf-mapper`` command."""
    parser = build_parser()
    args = parser.parse_args(argv)

    # Parse optional config JSON
    config: dict[str, object] | None = None
    if args.config:
        try:
            config = json.loads(args.config)
        except json.JSONDecodeError as exc:
            print(f"nf-mapper: error: --config is not valid JSON: {exc}", file=sys.stderr)
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
