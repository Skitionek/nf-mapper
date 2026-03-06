"""Parse Nextflow pipeline files and extract structural information.

Uses the groovy_parser library to parse `.nf` files and extract processes,
workflows, includes, and the connections between processes.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, cast

if TYPE_CHECKING:
    from collections.abc import Iterator

from groovy_parser.parser import parse_and_digest_groovy_content

# ---------------------------------------------------------------------------
# AST rule path constants (matching suffixes in node["rule"] lists)
# ---------------------------------------------------------------------------
ROOT_RULE = ["compilation_unit", "script_statements"]

INCLUDE_PROCESS_RULE = [
    "statement",
    "statement_expression",
    "command_expression",
]

IDENTIFIER_RULE = ["primary", "identifier"]

PRE_IDENTIFIER_NAME = [
    "expression",
    "postfix_expression",
    "path_expression",
]

PROCESS_CHILD = {"leaf": "IDENTIFIER", "value": "process"}
INCLUDE_CHILD = {"leaf": "IDENTIFIER", "value": "include"}
WORKFLOW_CHILD = {"leaf": "IDENTIFIER", "value": "workflow"}
CONTAINER_CHILD = {"leaf": "IDENTIFIER", "value": "container"}
CONDA_CHILD = {"leaf": "IDENTIFIER", "value": "conda"}
TEMPLATE_CHILD = {"leaf": "IDENTIFIER", "value": "template"}

P_RULE = [
    "argument_list",
    "first_argument_list_element",
    "expression_list_element",
    "expression",
    "postfix_expression",
    "path_expression",
]

W_RULE = [
    "argument_list",
    "first_argument_list_element",
    "expression_list_element",
    "expression",
    "postfix_expression",
    "path_expression",
]

NAMELESS_W_RULE = [
    "argument_list",
    "first_argument_list_element",
    "expression_list_element",
    "expression",
    "postfix_expression",
    "path_expression",
    "primary",
    "closure_or_lambda_expression",
    "closure",
]

# The groovy parser sometimes appends "block" at the end of the rule chain
NAMELESS_W_RULE_BLOCK = NAMELESS_W_RULE + ["block"]


# ---------------------------------------------------------------------------
# Data classes
# ---------------------------------------------------------------------------


@dataclass
class NfProcess:
    """Represents a Nextflow process declaration."""

    name: str
    containers: list[str] = field(default_factory=list)
    condas: list[str] = field(default_factory=list)
    templates: list[str] = field(default_factory=list)


@dataclass
class NfWorkflow:
    """Represents a Nextflow workflow declaration."""

    name: str | None
    calls: list[str] = field(default_factory=list)
    """Names of processes/sub-workflows called in the main block."""


@dataclass
class NfInclude:
    """Represents a Nextflow include statement."""

    path: str
    imports: list[str] = field(default_factory=list)


@dataclass
class ParsedPipeline:
    """Container for all extracted information from a Nextflow file."""

    processes: list[NfProcess]
    workflows: list[NfWorkflow]
    includes: list[NfInclude]
    connections: list[tuple[str, str]]
    """Directed edges (source_process, destination_process)."""


# ---------------------------------------------------------------------------
# AST helper utilities
# ---------------------------------------------------------------------------


def _get_leaf_value(node: dict) -> str | None:
    """Return the string value if *node* is a leaf node, otherwise None."""
    if node.get("leaf"):
        return node.get("value")
    return None


def _iter_leaves(node: dict) -> Iterator[tuple[str, str]]:
    """Yield (leaf_type, value) for every leaf in the subtree."""
    leaf = node.get("leaf")
    if leaf is not None:
        yield leaf, node.get("value", "")
    for child in node.get("children", []):
        yield from _iter_leaves(child)


def _rule_ends_with(node: dict, suffix: list[str]) -> bool:
    rule = node.get("rule")
    return isinstance(rule, list) and rule[-len(suffix) :] == suffix


def _get_first_identifier(node: dict) -> str | None:
    """Return the value of the first IDENTIFIER / CAPITALIZED_IDENTIFIER leaf."""
    for leaf_type, value in _iter_leaves(node):
        if leaf_type in ("IDENTIFIER", "CAPITALIZED_IDENTIFIER"):
            return value
    return None


# ---------------------------------------------------------------------------
# String extraction (for container / conda / template values)
# ---------------------------------------------------------------------------


def _extract_strings(node: dict) -> Iterator[str]:
    leaf_type = node.get("leaf")
    if leaf_type is not None:
        if leaf_type in ("STRING_LITERAL", "STRING_LITERAL_PART"):
            yield node["value"]
    else:
        for child in node.get("children", []):
            yield from _extract_strings(child)


def _extract_containers(node: dict) -> Iterator[str]:
    yield from filter(
        lambda s: s not in ("singularity", "docker"), _extract_strings(node)
    )


def _extract_condas(node: dict) -> Iterator[str]:
    sp = re.compile(r"[\t ]+")
    for conda_str in _extract_strings(node):
        yield from sp.split(conda_str)


# ---------------------------------------------------------------------------
# Process body parsing (containers / condas / templates)
# ---------------------------------------------------------------------------


def _extract_process_features(t_tree: dict) -> tuple[list[str], list[str], list[str]]:
    templates: list[str] = []
    containers: list[str] = []
    condas: list[str] = []

    for child in t_tree.get("children", []):
        if "rule" not in child:
            continue
        child_rule = child["rule"]
        unprocessed = True
        if child_rule[-len(INCLUDE_PROCESS_RULE) :] == INCLUDE_PROCESS_RULE:
            c_children = child.get("children", [])
            if not c_children:
                continue
            c0 = cast("dict", c_children[0])
            c0_rule = c0.get("rule")
            if c0_rule is not None and c0_rule[-len(PRE_IDENTIFIER_NAME) :] == PRE_IDENTIFIER_NAME:
                c0 = cast("dict", c0.get("children", [{}])[0])
                c0_rule = c0.get("rule")
            if c0_rule is not None and c0_rule[-len(IDENTIFIER_RULE) :] == IDENTIFIER_RULE:
                c0_children = c0.get("children", [])
                if c0_children:
                    sentinel = c0_children[0]
                    if sentinel == CONTAINER_CHILD and len(c_children) > 1:
                        containers.extend(_extract_containers(c_children[1]))
                        unprocessed = False
                    elif sentinel == CONDA_CHILD and len(c_children) > 1:
                        condas.extend(_extract_condas(c_children[1]))
                        unprocessed = False
                    elif sentinel == TEMPLATE_CHILD and c_children:
                        templates.extend(_extract_strings(c_children[-1]))
                        unprocessed = False
        if unprocessed:
            c_con, c_cond, c_tmpl = _extract_process_features(child)
            containers.extend(c_con)
            condas.extend(c_cond)
            templates.extend(c_tmpl)

    return containers, condas, templates


def _extract_process(node: dict) -> NfProcess:
    p_rule = node.get("rule")
    process_name: str | None = None
    templates: list[str] = []
    containers: list[str] = []
    condas: list[str] = []
    if p_rule == P_RULE:
        p_c_children = node.get("children", [])
        if p_c_children and "children" in p_c_children[0]:
            pro_node = cast("dict", p_c_children[0])
            if pro_node.get("children") and "value" in pro_node["children"][0]:
                process_name = cast("dict", pro_node["children"][0])["value"]
            if len(p_c_children) > 1:
                process_body = cast("dict", p_c_children[1])
                containers, condas, templates = _extract_process_features(process_body)
    if process_name is None:
        raise ValueError(
            f"Could not extract process name from AST node with rule={p_rule!r}. "
            "The groovy-parser output may have an unexpected structure."
        )
    return NfProcess(
        name=process_name,
        templates=templates,
        containers=containers,
        condas=condas,
    )


# ---------------------------------------------------------------------------
# Process output reference detection
# ---------------------------------------------------------------------------


def _find_process_out_refs(node: dict, known_processes: set[str]) -> Iterator[str]:
    """Yield names of processes whose .out channel is referenced in *node*."""
    rule = node.get("rule")
    if rule and rule[-1] == "path_expression":
        children = node.get("children", [])
        if len(children) >= 2:
            # First child: primary/identifier with CAPITALIZED_IDENTIFIER
            first = children[0]
            first_name = None
            if _rule_ends_with(first, IDENTIFIER_RULE):
                for ltype, lval in _iter_leaves(first):
                    if ltype == "CAPITALIZED_IDENTIFIER":
                        first_name = lval
                        break
            if first_name and first_name in known_processes:
                # Second child must be path_element with 'out'
                second = children[1]
                if "rule" in second and second["rule"][-1] in ("path_element",):
                    for _, lval in _iter_leaves(second):
                        if lval == "out":
                            yield first_name
                            break
    # Recurse
    for child in node.get("children", []):
        yield from _find_process_out_refs(child, known_processes)


# ---------------------------------------------------------------------------
# Workflow body parsing
# ---------------------------------------------------------------------------


def _get_first_cap_identifier(node: dict) -> str | None:
    """Return the first CAPITALIZED_IDENTIFIER in *node*'s direct children."""
    for child in node.get("children", []):
        for ltype, lval in _iter_leaves(child):
            if ltype == "CAPITALIZED_IDENTIFIER":
                return lval
    return None


def _is_process_call(cmd_node: dict, known_processes: set[str]) -> str | None:
    """
    Return the process name if *cmd_node* is a command_expression calling a known
    process, otherwise None.
    """
    if not _rule_ends_with(cmd_node, ["command_expression"]):
        return None
    children = cmd_node.get("children", [])
    if not children:
        return None
    first = children[0]
    # The callee must be a path/primary/identifier with a CAPITALIZED_IDENTIFIER
    if _rule_ends_with(first, IDENTIFIER_RULE) or _rule_ends_with(first, PRE_IDENTIFIER_NAME):
        name = None
        for ltype, lval in _iter_leaves(first):
            if ltype == "CAPITALIZED_IDENTIFIER":
                name = lval
                break
        if name and name in known_processes:
            return name
    return None


def _extract_workflow_body(
    workflow_body: dict,
    known_processes: set[str],
) -> tuple[list[str], list[tuple[str, str]]]:
    """
    Traverse the workflow body AST to find:
    - Process calls (ordered list of called process names)
    - Connections: (source, destination) tuples based on .out usage

    Returns (calls, connections).
    """
    calls: list[str] = []
    connections: list[tuple[str, str]] = []
    seen_calls: set[str] = set()

    def _visit(node: dict, current_section: str | None = None) -> None:
        rule = node.get("rule")

        # Detect labeled sections (take:, main:, emit:)
        if rule and rule[-1] == "statement" and not rule[-2:] == ["statement_expression", "statement"]:
            children = node.get("children", [])
            if len(children) >= 3:
                label_node = children[0]
                colon = children[1] if len(children) > 1 else None
                body_node = children[2] if len(children) > 2 else None
                if (
                    colon
                    and colon.get("leaf") == "COLON"
                    and _rule_ends_with(label_node, ["identifier"])
                ):
                    section_name = _get_first_identifier(label_node)
                    if body_node:
                        _visit(body_node, section_name)
                    return

        # In the 'main' section (or unnamed workflow), look for process calls
        in_main = current_section in ("main", None)
        if in_main and _rule_ends_with(node, ["command_expression"]):
            proc_name = _is_process_call(node, known_processes)
            if proc_name:
                calls.append(proc_name)
                seen_calls.add(proc_name)
                # Find any process.out references in arguments
                children = node.get("children", [])
                for arg_node in children[1:]:
                    for src in _find_process_out_refs(arg_node, known_processes):
                        connections.append((src, proc_name))
                return  # Don't recurse into the call itself

        for child in node.get("children", []):
            _visit(child, current_section)

    _visit(workflow_body)
    return calls, connections


def _extract_workflow(node: dict, known_processes: set[str]) -> NfWorkflow:
    """Extract workflow name, calls, and connections from a workflow AST node."""
    name: str | None = None
    calls: list[str] = []
    connections: list[tuple[str, str]] = []
    body_node: dict | None = None

    if node.get("rule") == W_RULE:
        # Named workflow: workflow NAME { ... }
        children = node.get("children", [])
        if children and "children" in children[0]:
            name_node = cast("dict", children[0])
            name = _get_first_identifier(name_node)
        if len(children) > 1:
            body_node = cast("dict", children[1])
    elif node.get("rule") in (NAMELESS_W_RULE, NAMELESS_W_RULE_BLOCK):
        # Unnamed entry workflow: workflow { ... }
        body_node = node

    if body_node:
        calls, connections = _extract_workflow_body(body_node, known_processes)

    return NfWorkflow(name=name, calls=calls), connections


# ---------------------------------------------------------------------------
# Include parsing
# ---------------------------------------------------------------------------


def _extract_includes(include_cmd_children: list) -> list[NfInclude]:
    """Extract include paths and imported names from the include command's children.

    The include command_expression has three children::

        include { PROC1; PROC2 }  from  './path'
        [0] 'include' identifier
        [1] { PROC1; PROC2 } closure block  ← imported names live here
        [2] command_argument with the 'from' path string

    Parameters
    ----------
    include_cmd_children:
        Children of the include ``command_expression`` node.
    """
    paths = list(_extract_strings(include_cmd_children[-1]))
    if not paths:
        return []
    imports: list[str] = []
    if len(include_cmd_children) > 1:
        for ltype, lval in _iter_leaves(include_cmd_children[1]):
            if ltype == "CAPITALIZED_IDENTIFIER":
                imports.append(lval)
    return [NfInclude(path=p, imports=imports) for p in paths]


# ---------------------------------------------------------------------------
# Top-level feature extraction
# ---------------------------------------------------------------------------


def _extract_features(
    t_tree: dict,
    known_processes: set[str] | None = None,
) -> tuple[list[NfProcess], list[NfInclude], list[NfWorkflow], list[tuple[str, str]]]:
    processes: list[NfProcess] = []
    includes: list[NfInclude] = []
    workflows: list[NfWorkflow] = []
    connections: list[tuple[str, str]] = []

    if known_processes is None:
        known_processes = set()

    for a_child in t_tree.get("children", []):
        if "rule" not in a_child:
            continue
        child = cast("dict", a_child)
        child_rule = child["rule"]
        unprocessed = True

        if child_rule[-len(INCLUDE_PROCESS_RULE) :] == INCLUDE_PROCESS_RULE:
            c_children = child.get("children", [])
            if not c_children:
                continue
            c0 = cast("dict", c_children[0])
            c0_rule = c0.get("rule")
            if c0_rule is not None and c0_rule[-len(PRE_IDENTIFIER_NAME) :] == PRE_IDENTIFIER_NAME:
                c0 = cast("dict", c0.get("children", [{}])[0])
                c0_rule = c0.get("rule")
            if c0_rule is not None and c0_rule[-len(IDENTIFIER_RULE) :] == IDENTIFIER_RULE:
                c0_children = c0.get("children", [])
                if c0_children:
                    sentinel = c0_children[0]
                    if sentinel == PROCESS_CHILD and len(c_children) > 1:
                        proc = _extract_process(cast("dict", c_children[1]))
                        processes.append(proc)
                        known_processes.add(proc.name)
                        unprocessed = False
                    elif sentinel == WORKFLOW_CHILD and len(c_children) > 1:
                        wf, wf_conns = _extract_workflow(
                            cast("dict", c_children[1]), known_processes
                        )
                        workflows.append(wf)
                        connections.extend(wf_conns)
                        unprocessed = False
                    elif sentinel == INCLUDE_CHILD:
                        incs = _extract_includes(c_children)
                        includes.extend(incs)
                        # Treat imported names as known processes for connection detection
                        for inc in incs:
                            known_processes.update(inc.imports)
                        unprocessed = False

        if unprocessed:
            c_procs, c_incs, c_wfs, c_conns = _extract_features(child, known_processes)
            processes.extend(c_procs)
            includes.extend(c_incs)
            workflows.extend(c_wfs)
            connections.extend(c_conns)

    return processes, includes, workflows, connections


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------


def parse_nextflow_content(content: str) -> ParsedPipeline:
    """Parse Nextflow pipeline *content* string and return a :class:`ParsedPipeline`.

    Parameters
    ----------
    content:
        The text content of a Nextflow ``.nf`` file.

    Returns
    -------
    ParsedPipeline
        Extracted processes, workflows, includes, and connections.
    """
    t_tree = parse_and_digest_groovy_content(content)
    if "rule" not in t_tree:
        return ParsedPipeline(
            processes=[], workflows=[], includes=[], connections=[]
        )

    processes, includes, workflows, connections = _extract_features(
        cast("dict", t_tree)
    )

    # Deduplicate connections while preserving order
    seen: set[tuple[str, str]] = set()
    unique_connections: list[tuple[str, str]] = []
    for conn in connections:
        if conn not in seen:
            seen.add(conn)
            unique_connections.append(conn)

    return ParsedPipeline(
        processes=processes,
        workflows=workflows,
        includes=includes,
        connections=unique_connections,
    )


def parse_nextflow_file(filepath: str) -> ParsedPipeline:
    """Parse a Nextflow pipeline file and return a :class:`ParsedPipeline`.

    Parameters
    ----------
    filepath:
        Path to the ``.nf`` file to parse.

    Returns
    -------
    ParsedPipeline
        Extracted processes, workflows, includes, and connections.
    """
    with open(filepath, encoding="utf-8") as fh:
        content = fh.read()
    return parse_nextflow_content(content)
