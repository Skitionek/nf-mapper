"""Command-line interface for nf-mapper.

Usage
-----
.. code-block:: console

    nf-mapper pipeline.nf
    nf-mapper --direction TD --title "My Pipeline" pipeline.nf -o diagram.md
    nf-mapper pipeline.nf --format md
"""

from __future__ import annotations

import argparse
import sys

from nf_mapper.mermaid import pipeline_to_mermaid
from nf_mapper.parser import parse_nextflow_file


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="nf-mapper",
        description="Convert a Nextflow pipeline (.nf) into a Mermaid flowchart.",
    )
    p.add_argument(
        "input",
        metavar="PIPELINE.NF",
        help="Path to the Nextflow pipeline file to parse.",
    )
    p.add_argument(
        "-o",
        "--output",
        metavar="FILE",
        default=None,
        help="Write the diagram to FILE instead of stdout.",
    )
    p.add_argument(
        "--direction",
        choices=["LR", "TD", "RL", "BT"],
        default="LR",
        help="Mermaid flowchart direction (default: LR).",
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
    return p


def main(argv: list[str] | None = None) -> int:
    """Entry point for the ``nf-mapper`` command."""
    parser = build_parser()
    args = parser.parse_args(argv)

    pipeline = parse_nextflow_file(args.input)
    diagram = pipeline_to_mermaid(
        pipeline,
        direction=args.direction,
        title=args.title,
    )

    if args.format == "md":
        output = f"```mermaid\n{diagram}\n```"
    else:
        output = diagram

    if args.output:
        with open(args.output, "w", encoding="utf-8") as fh:
            fh.write(output + "\n")
    else:
        print(output)

    return 0


if __name__ == "__main__":
    sys.exit(main())
