"""Parse Nextflow pipeline files and extract structural information.

Uses the groovy_parser library to parse `.nf` files and extract processes,
workflows, includes, and the connections between processes.
"""

from __future__ import annotations

import os
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
PATH_CHILD = {"leaf": "IDENTIFIER", "value": "path"}

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
    inputs: list[str] = field(default_factory=list)
    """String-literal ``path(...)`` patterns from the ``input:`` section (e.g. ``"*.fastq.gz"``)."""
    outputs: list[str] = field(default_factory=list)
    """String-literal ``path(...)`` patterns from the ``output:`` section (e.g. ``"*.bam"``)."""


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


def _extract_path_channels(node: dict) -> Iterator[str]:
    """Yield string-literal arguments of ``path(...)`` calls anywhere in *node*'s subtree.

    Handles two AST representations:

    1. **Standalone command expression** – ``path "*.bam"`` or ``path("*.bam")``
       when ``path`` is the top-level callee of a command expression.
    2. **Path expression** – ``path("*.bam")`` when nested as an argument inside
       another call (e.g. ``tuple val(meta), path("*.bam")``).  The groovy
       parser renders this as a ``path_expression`` whose first child is the
       ``path`` identifier and whose subsequent children are ``arguments``
       path elements.

    Only string literals (e.g. ``"*.bam"``, ``"*.html"``) are yielded; bare
    variable references like ``path(reads)`` produce no output.
    """
    if not isinstance(node, dict):
        return
    # ---- Case 1: standalone command expression --------------------------------
    if _rule_ends_with(node, ["command_expression"]):
        children = node.get("children", [])
        if children:
            first = cast("dict", children[0])
            # Unwrap potential expression/path_expression wrapper
            if _rule_ends_with(first, PRE_IDENTIFIER_NAME):
                first = cast("dict", first.get("children", [{}])[0])
            if _rule_ends_with(first, IDENTIFIER_RULE):
                c0_children = first.get("children", [])
                if c0_children and c0_children[0] == PATH_CHILD:
                    for arg in children[1:]:
                        yield from _extract_strings(arg)
                    return  # don't recurse into path() args
    # ---- Case 2: nested path_expression (inside tuple args etc.) -------------
    if _rule_ends_with(node, ["path_expression"]):
        children = node.get("children", [])
        if len(children) >= 2:
            primary = children[0]
            if _rule_ends_with(primary, IDENTIFIER_RULE):
                c0 = primary.get("children", [])
                if c0 and c0[0] == PATH_CHILD:
                    for path_elem in children[1:]:
                        if _rule_ends_with(path_elem, ["arguments"]):
                            yield from _extract_strings(path_elem)
                    return
    # ---- Recurse ------------------------------------------------------------
    for child in node.get("children", []):
        yield from _extract_path_channels(child)


def _extract_process_channels(body: dict) -> tuple[list[str], list[str]]:
    """Return ``(input_patterns, output_patterns)`` from a process body node.

    Scans ``input:`` and ``output:`` labeled sections and their **unlabeled
    sibling statements** (Groovy's labeled-statement syntax attaches a label
    only to the *first* following statement; subsequent declarations in the
    same section become plain siblings in the AST).  Other sections
    (``script:``, ``shell:``, ``exec:``, ``when:``, ``stub:``) are skipped
    to avoid false positives from shell code.
    """
    inputs: list[str] = []
    outputs: list[str] = []
    _SKIP_SECTIONS = {"script", "shell", "exec", "when", "stub"}

    def _is_labeled_stmt(node: dict) -> bool:
        """True iff *node* is a labeled statement (not a plain expression-statement)."""
        rule = node.get("rule", [])
        # rule[-1] == "statement" but NOT preceded by "statement_expression"
        return bool(
            rule
            and rule[-1] == "statement"
            and rule[-2:-1] != ["statement_expression"]
        )

    def _collect_siblings(children: list, current_section: str | None) -> None:
        """Walk a flat sibling list, collecting ``path(...)`` patterns."""
        for child in children:
            if not isinstance(child, dict):
                continue
            if _is_labeled_stmt(child):
                c = child.get("children", [])
                label_n = c[0] if c else None
                colon = c[1] if len(c) > 1 else None
                body_n = c[2] if len(c) > 2 else None
                if (
                    colon is not None
                    and colon.get("leaf") == "COLON"
                    and label_n is not None
                    and _rule_ends_with(label_n, ["identifier"])
                ):
                    section = _get_first_identifier(label_n)
                    current_section = section
                    if section in ("input", "output") and body_n is not None:
                        target = inputs if section == "input" else outputs
                        target.extend(_extract_path_channels(body_n))
                    elif section in _SKIP_SECTIONS:
                        current_section = None
            else:
                # Unlabeled sibling – collect if inside an active section
                if current_section in ("input", "output"):
                    target = inputs if current_section == "input" else outputs
                    target.extend(_extract_path_channels(child))

    def _visit(node: dict) -> None:
        if not isinstance(node, dict):
            return
        children = node.get("children", [])
        if not children:
            return
        # If any direct child is a labeled statement this is the right level
        if any(_is_labeled_stmt(c) for c in children if isinstance(c, dict)):
            _collect_siblings(children, None)
            return  # don't recurse – this level handles everything
        for child in children:
            _visit(child)

    _visit(body)
    return inputs, outputs


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
    inputs: list[str] = []
    outputs: list[str] = []
    if p_rule == P_RULE:
        p_c_children = node.get("children", [])
        if p_c_children and "children" in p_c_children[0]:
            pro_node = cast("dict", p_c_children[0])
            if pro_node.get("children") and "value" in pro_node["children"][0]:
                process_name = cast("dict", pro_node["children"][0])["value"]
            if len(p_c_children) > 1:
                process_body = cast("dict", p_c_children[1])
                containers, condas, templates = _extract_process_features(process_body)
                inputs, outputs = _extract_process_channels(process_body)
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
        inputs=inputs,
        outputs=outputs,
    )


# ---------------------------------------------------------------------------
# Process output reference detection
# ---------------------------------------------------------------------------


def _find_process_out_refs(
    node: dict,
    known_processes: set[str],
    channel_var_map: dict[str, set[str]] | None = None,
) -> Iterator[str]:
    """Yield names of processes whose output is referenced in *node*.

    Detects two patterns:

    1. ``PROCESS.out.channel`` – a direct process-output path expression.
    2. ``ch_var`` or ``ch_var.member`` – a channel variable whose provenance
       (``ch_var → {PROCESS, ...}``) is recorded in *channel_var_map*.
    """
    rule = node.get("rule")
    if not isinstance(rule, list) or not rule:
        for child in node.get("children", []):
            yield from _find_process_out_refs(child, known_processes, channel_var_map)
        return

    last = rule[-1]

    # Pattern 2a: simple identifier used as an argument → may be a channel var
    if last == "identifier" and channel_var_map:
        for ltype, lval in _iter_leaves(node):
            if ltype == "IDENTIFIER" and lval in channel_var_map:
                yield from channel_var_map[lval]
                return
            break  # Only check first leaf
        for child in node.get("children", []):
            yield from _find_process_out_refs(child, known_processes, channel_var_map)
        return

    if last == "path_expression":
        children = node.get("children", [])
        if len(children) >= 1:
            first = children[0]

            # Pattern 1: PROCESS.out...
            first_name = None
            if _rule_ends_with(first, IDENTIFIER_RULE):
                for ltype, lval in _iter_leaves(first):
                    if ltype == "CAPITALIZED_IDENTIFIER":
                        first_name = lval
                        break
            if first_name and first_name in known_processes and len(children) >= 2:
                second = children[1]
                if "rule" in second and second["rule"][-1] in ("path_element",):
                    for _, lval in _iter_leaves(second):
                        if lval == "out":
                            yield first_name
                            return  # Don't recurse further into this node

            # Pattern 2b: ch_var.member (path_expression with identifier as primary)
            if channel_var_map and _rule_ends_with(first, IDENTIFIER_RULE):
                ch_var = None
                for ltype, lval in _iter_leaves(first):
                    if ltype == "IDENTIFIER":
                        ch_var = lval
                        break
                if ch_var and ch_var in channel_var_map:
                    yield from channel_var_map[ch_var]
                    return  # Don't recurse – we've handled this node

    # Recurse into children
    for child in node.get("children", []):
        yield from _find_process_out_refs(child, known_processes, channel_var_map)


# ---------------------------------------------------------------------------
# Channel variable tracking (.set { ch_var } assignments)
# ---------------------------------------------------------------------------


def _build_channel_var_map(
    workflow_body: dict,
    known_processes: set[str],
) -> dict[str, set[str]]:
    """Scan *workflow_body* for ``.set { ch_var }`` assignments.

    Builds and returns a map from channel variable name to the set of source
    process names.  Two passes are made so that one level of transitivity is
    resolved automatically::

        SRA_RUNINFO_TO_FTP.out.tsv.set { ch_meta }   # pass 1 → ch_meta = SRA_RUNINFO_TO_FTP
        ch_meta.branch { … }.set { ch_branched }      # pass 2 → ch_branched = SRA_RUNINFO_TO_FTP
    """
    result: dict[str, set[str]] = {}

    def _get_set_var(arg_node: dict) -> str | None:
        """Return the single IDENTIFIER inside a ``.set { ch_var }`` closure."""
        ids = [v for lt, v in _iter_leaves(arg_node) if lt == "IDENTIFIER"]
        caps = [v for lt, v in _iter_leaves(arg_node) if lt == "CAPITALIZED_IDENTIFIER"]
        # Only accept closures that resolve to exactly one lowercase identifier
        if caps or len(ids) != 1:
            return None
        return ids[0]

    def _sources_from_path(path_expr: dict) -> set[str]:
        """Return source processes for a path expression (proc.out or ch_var)."""
        children = path_expr.get("children", [])
        if not children:
            return set()
        primary = children[0]
        # Case 1: CAPITALIZED_IDENTIFIER with a .out element anywhere in chain
        for ltype, lval in _iter_leaves(primary):
            if ltype == "CAPITALIZED_IDENTIFIER" and lval in known_processes:
                for pe in children[1:]:
                    for lt2, lv2 in _iter_leaves(pe):
                        if lt2 == "IDENTIFIER" and lv2 == "out":
                            return {lval}
                break
        # Case 2: lowercase identifier whose provenance is already tracked
        for ltype, lval in _iter_leaves(primary):
            if ltype == "IDENTIFIER" and lval in result:
                return set(result[lval])
            break
        return set()

    def _scan(n: dict) -> None:
        if not isinstance(n, dict):
            return
        if _rule_ends_with(n, ["command_expression"]):
            children = n.get("children", [])
            if len(children) >= 2:
                first = children[0]
                if _rule_ends_with(first, ["path_expression"]):
                    # Check the last path_element is .set
                    path_children = first.get("children", [])
                    last_is_set = False
                    for pe in reversed(path_children):
                        if _rule_ends_with(pe, ["path_element"]):
                            for lt, lv in _iter_leaves(pe):
                                if lt == "IDENTIFIER" and lv == "set":
                                    last_is_set = True
                            break
                    if last_is_set:
                        sources = _sources_from_path(first)
                        if sources:
                            var_name = _get_set_var(children[-1])
                            if var_name and var_name not in result:
                                result[var_name] = sources
                                return
        for child in n.get("children", []):
            _scan(child)

    _scan(workflow_body)   # pass 1: PROCESS.out → ch_var
    _scan(workflow_body)   # pass 2: ch_var → ch_var2 (one level of transitivity)
    return result


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
    Return the process name if *cmd_node* is a call to a known process/workflow,
    otherwise None.

    Handles two AST representations produced by the Groovy parser:

    1. **command_expression** – Groovy command-method syntax with a single
       bare argument, e.g. ``PROCESS reads`` or ``PROCESS(reads)``.
       The parser emits this as a ``command_expression`` whose first child is
       the callee identifier.

    2. **path_expression** – parenthesised call with zero *or* multiple
       arguments, e.g. ``PROCESS()`` or ``PROCESS(a, b, c)``.
       The parser emits this as a ``path_expression`` whose first child is the
       callee identifier and whose second child is an ``arguments`` path element.
       This representation is produced for every multi-argument call *and* for
       empty-argument calls (``PROCESS()`` / ``PROCESS ()``).

    Method chains such as ``PROCESS.out.channel.map{}.set{}`` are intentionally
    excluded: in those nodes the second child is a ``path_element`` that does
    **not** end with ``arguments``.
    """
    children = cmd_node.get("children", [])
    if not children:
        return None
    first = children[0]
    # The callee must be a bare primary/identifier (no additional path elements).
    # PRE_IDENTIFIER_NAME matches are path-expression chains (e.g. PROC.out.method)
    # and must NOT be treated as process calls.
    if not _rule_ends_with(first, IDENTIFIER_RULE):
        return None
    name = None
    for ltype, lval in _iter_leaves(first):
        if ltype == "CAPITALIZED_IDENTIFIER":
            name = lval
            break
    if not name or name not in known_processes:
        return None

    if _rule_ends_with(cmd_node, ["command_expression"]):
        # Single-argument Groovy command syntax: PROCESS arg
        return name

    if _rule_ends_with(cmd_node, ["path_expression"]) and len(children) > 1:
        # Multi-arg or empty-arg parenthesised call: PROCESS(a, b) / PROCESS()
        # Reject method chains where the next element is NOT an arguments node
        # (e.g. PROCESS.out.channel would have a plain path_element here).
        if _rule_ends_with(children[1], ["arguments"]):
            return name

    return None


def _extract_workflow_body(
    workflow_body: dict,
    known_processes: set[str],
) -> tuple[list[str], list[tuple[str, str]]]:
    """
    Traverse the workflow body AST to find:
    - Process calls (ordered list of called process names)
    - Connections: (source, destination) tuples based on .out usage and
      channel variable assignments (.set { ch_var })

    Returns (calls, connections).
    """
    calls: list[str] = []
    connections: list[tuple[str, str]] = []
    seen_calls: set[str] = set()

    # Build channel variable provenance map first (two-pass for transitivity)
    channel_var_map = _build_channel_var_map(workflow_body, known_processes)

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

        # In the 'main' section (or unnamed workflow), look for process calls.
        # The Groovy parser produces either a command_expression (single bare
        # arg) or a path_expression (multi-arg / empty-arg parenthesised call).
        in_main = current_section in ("main", None)
        if in_main and (
            _rule_ends_with(node, ["command_expression"])
            or _rule_ends_with(node, ["path_expression"])
        ):
            proc_name = _is_process_call(node, known_processes)
            if proc_name:
                calls.append(proc_name)
                seen_calls.add(proc_name)
                # Find any process.out references and channel var refs in arguments
                children = node.get("children", [])
                for arg_node in children[1:]:
                    for src in _find_process_out_refs(arg_node, known_processes, channel_var_map):
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
        # Handle aliases: ``include { X as Y }`` should register only Y (the
        # alias), not X (the original name).  When an AS token is encountered
        # the most-recently added name is the original being aliased – remove
        # it so the next CAPITALIZED_IDENTIFIER (the alias) replaces it.
        for ltype, lval in _iter_leaves(include_cmd_children[1]):
            if ltype == "AS":
                if imports:
                    imports.pop()
            elif ltype == "CAPITALIZED_IDENTIFIER":
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
                        # Register the workflow name so later code in the same
                        # file (e.g. an entry workflow) can detect calls to it.
                        if wf.name:
                            known_processes.add(wf.name)
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

    Recursively follows relative-path ``include`` statements (up to one level
    deep) to gather processes and connections from directly-included workflow
    files.  This allows a top-level ``main.nf`` that delegates work to
    sub-workflows via includes to produce a diagram showing all constituent
    processes rather than just the single imported-workflow node.

    Parameters
    ----------
    filepath:
        Path to the ``.nf`` file to parse.

    Returns
    -------
    ParsedPipeline
        Extracted processes, workflows, includes, and connections from
        *filepath* and its reachable include files.
    """
    return _parse_file_recursive(
        abs_filepath=os.path.abspath(filepath),
        visited=set(),
        depth=0,
        max_depth=1,
    )


def _parse_file_recursive(
    abs_filepath: str,
    visited: set[str],
    depth: int,
    max_depth: int,
) -> ParsedPipeline:
    """Internal recursive helper for :func:`parse_nextflow_file`.

    At each level the file is parsed with :func:`parse_nextflow_content`.
    When *depth* is still below *max_depth* the function also follows every
    relative-path ``include`` statement:

    * If the included file defines a **workflow** whose name matches one of
      the imported identifiers, that workflow's constituent process calls are
      **expanded** into stub :class:`NfProcess` objects and merged into the
      caller's process list.  The imported workflow name is removed from the
      caller's include list (since it is now represented by its constituent
      nodes) to avoid it appearing as an isolated ghost node in the diagram.
    * If the included file defines a **process** whose name matches, that
      process definition (with containers, condas, outputs, etc.) is merged
      in, replacing the stub that would otherwise come from the include.
    * Connections extracted from included files are merged into the caller's
      connection list.

    Files that cannot be found or cannot be parsed are silently skipped.
    Circular includes are detected via the *visited* set.
    """
    if abs_filepath in visited:
        return ParsedPipeline(processes=[], workflows=[], includes=[], connections=[])
    visited.add(abs_filepath)

    with open(abs_filepath, encoding="utf-8") as fh:
        content = fh.read()
    pipeline = parse_nextflow_content(content)

    if depth >= max_depth:
        return pipeline

    base_dir = os.path.dirname(abs_filepath)

    merged_processes: list[NfProcess] = list(pipeline.processes)
    merged_connections: list[tuple[str, str]] = list(pipeline.connections)
    existing_proc_names: set[str] = {p.name for p in merged_processes}
    existing_conn_set: set[tuple[str, str]] = set(pipeline.connections)

    # Track which imported names were successfully expanded so we can remove
    # them from the includes list (they no longer need to appear as isolated
    # nodes – their constituent processes represent them).
    expanded_imports: set[str] = set()

    for inc in pipeline.includes:
        # Only follow relative-path includes; skip plugin paths etc.
        if not (inc.path.startswith("./") or inc.path.startswith("../")):
            continue

        resolved = os.path.normpath(os.path.join(base_dir, inc.path))
        if not resolved.endswith(".nf"):
            resolved += ".nf"

        if not os.path.isfile(resolved):
            continue

        try:
            sub = _parse_file_recursive(resolved, visited, depth + 1, max_depth)
        except Exception:  # noqa: BLE001
            continue

        sub_wf_map = {wf.name: wf for wf in sub.workflows if wf.name}
        sub_proc_map = {p.name: p for p in sub.processes}

        for import_name in inc.imports:
            if import_name in sub_wf_map:
                # Expand the workflow's calls as stub (or full) processes
                sub_wf = sub_wf_map[import_name]
                for call_name in sub_wf.calls:
                    if call_name not in existing_proc_names:
                        proc = sub_proc_map.get(call_name, NfProcess(name=call_name))
                        merged_processes.append(proc)
                        existing_proc_names.add(call_name)
                expanded_imports.add(import_name)
            elif import_name in sub_proc_map:
                # Replace the stub with the full process definition
                if import_name not in existing_proc_names:
                    merged_processes.append(sub_proc_map[import_name])
                    existing_proc_names.add(import_name)
                expanded_imports.add(import_name)

        # Merge connections from the sub-pipeline
        for conn in sub.connections:
            if conn not in existing_conn_set:
                merged_connections.append(conn)
                existing_conn_set.add(conn)

    # Rebuild includes, removing entries that were fully expanded (all their
    # imported names are now represented as concrete processes).
    new_includes: list[NfInclude] = []
    for inc in pipeline.includes:
        remaining = [name for name in inc.imports if name not in expanded_imports]
        if remaining:
            new_includes.append(NfInclude(path=inc.path, imports=remaining))

    # Deduplicate connections while preserving order
    seen_conns: set[tuple[str, str]] = set()
    unique_conns: list[tuple[str, str]] = []
    for conn in merged_connections:
        if conn not in seen_conns:
            seen_conns.add(conn)
            unique_conns.append(conn)

    return ParsedPipeline(
        processes=merged_processes,
        workflows=pipeline.workflows,
        includes=new_includes,
        connections=unique_conns,
    )
