#!/usr/bin/env python3
"""Audit C# source changes against the Android port compatibility patches.

The tool builds a lightweight syntax-level index for two Slay the Spire 2 C#
source exports, then scans port-mod for Harmony/reflection references. It is
intended for game-version bring-up work where a full Roslyn environment is not
always available. The parser is deliberately conservative: it tracks types,
direct members, method/property/field signatures, and normalized implementation
hashes without compiling either source tree.

Example:

  tools/port_mod_ast_audit.py \
    --old-source ../s2_original/s201071 \
    --new-source ../s2_original/s201080 \
    --port-mod port-mod/STS2AndroidPortCompat \
    --out .agent/reports/v108-port-mod-ast-audit
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import re
import sys
from collections import defaultdict
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Iterable, Iterator, Sequence


SKIP_DIRS = {
    ".git",
    ".godot",
    ".gradle",
    ".idea",
    ".vs",
    "bin",
    "build",
    "obj",
    "Library",
    "Temp",
}

TYPE_KEYWORDS = {"class", "struct", "interface", "enum", "record"}
MEMBER_KIND_FOR_GET = {
    "GetMethod": "method",
    "GetMethods": "method",
    "GetField": "field",
    "GetFields": "field",
    "GetProperty": "property",
    "GetProperties": "property",
}
MEMBER_KIND_FOR_ACCESSTOOLS = {
    "Method": "method",
    "DeclaredMethod": "method",
    "Field": "field",
    "DeclaredField": "field",
    "Property": "property",
    "DeclaredProperty": "property",
}
EXTERNAL_TYPE_PREFIXES = (
    "System.",
    "Microsoft.",
    "HarmonyLib.",
    "MonoMod.",
    "Godot.",
    "Steamworks.",
)
EXTERNAL_SIMPLE_TYPES = {
    "bool",
    "byte",
    "char",
    "decimal",
    "double",
    "float",
    "int",
    "long",
    "nint",
    "nuint",
    "object",
    "sbyte",
    "short",
    "string",
    "uint",
    "ulong",
    "ushort",
    "void",
    "Harmony",
    "PatchProcessor",
}
RISKY_REFERENCE_STATUSES = {
    "target_type_removed",
    "target_member_removed",
    "target_member_signature_changed",
    "target_member_not_found",
    "target_type_not_found",
    "target_member_implementation_changed",
    "target_member_overloads_changed",
    "type_signature_changed",
    "unresolved",
}

IDENT_RE = re.compile(r"@?[A-Za-z_][A-Za-z0-9_]*")
TYPE_TOKEN_RE = re.compile(r"\b(class|struct|interface|enum|record)\b")
NAMESPACE_RE = re.compile(r"\bnamespace\s+([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)\s*(?:;|\{)")
USING_RE = re.compile(r"^\s*using\s+(?!static\b)(?:[A-Za-z_][A-Za-z0-9_]*\s*=\s*)?([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)\s*;", re.M)


@dataclass
class MemberInfo:
    kind: str
    name: str
    signature: str
    signature_id: str
    body_hash: str
    file: str
    line: int
    parameters: str = ""
    parameter_count: int = 0


@dataclass
class TypeInfo:
    full_name: str
    name: str
    namespace: str
    kind: str
    signature: str
    body_hash: str
    files: list[str]
    line: int
    members: dict[str, MemberInfo] = field(default_factory=dict)
    member_names: dict[str, list[str]] = field(default_factory=dict)


@dataclass
class SourceIndex:
    root: str
    types: dict[str, TypeInfo] = field(default_factory=dict)
    simple_type_names: dict[str, list[str]] = field(default_factory=dict)
    file_count: int = 0
    parse_errors: list[dict[str, str]] = field(default_factory=list)


@dataclass
class TypeChange:
    type: str
    status: str
    old_kind: str = ""
    new_kind: str = ""
    old_files: list[str] = field(default_factory=list)
    new_files: list[str] = field(default_factory=list)


@dataclass
class MemberChange:
    type: str
    member: str
    kind: str
    status: str
    old_signature: str = ""
    new_signature: str = ""
    old_file: str = ""
    old_line: int = 0
    new_file: str = ""
    new_line: int = 0


@dataclass
class PortReference:
    source_file: str
    line: int
    reference_kind: str
    type_expr: str
    member_name: str
    syntax: str
    resolved_types: list[str]
    status: str = "unresolved"
    detail: str = ""


@dataclass
class AuditResult:
    old_source: str
    new_source: str
    port_mod: str
    old_type_count: int
    new_type_count: int
    old_file_count: int
    new_file_count: int
    type_changes: list[TypeChange]
    member_changes: list[MemberChange]
    port_references: list[PortReference]
    old_parse_errors: list[dict[str, str]]
    new_parse_errors: list[dict[str, str]]


@dataclass
class ParsedTypeDecl:
    info: TypeInfo
    nested: list["ParsedTypeDecl"]
    start: int
    end: int


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8", errors="replace")).hexdigest()


def line_number(text: str, pos: int) -> int:
    return text.count("\n", 0, max(0, min(pos, len(text)))) + 1


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def iter_cs_files(root: Path) -> Iterator[Path]:
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [name for name in dirnames if name not in SKIP_DIRS]
        current = Path(dirpath)
        for name in filenames:
            if name.endswith(".cs"):
                yield current / name


def replace_range_with_spaces(out: list[str], start: int, end: int) -> None:
    for idx in range(start, end):
        if out[idx] != "\n":
            out[idx] = " "


def mask_csharp(text: str, *, keep_strings: bool = False) -> str:
    """Return text with comments, and optionally strings, blanked out.

    The returned string has the same length as the input so all offsets remain
    valid for slicing the original source.
    """
    out = list(text)
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if ch == "/" and nxt == "/":
            start = i
            i += 2
            while i < n and text[i] != "\n":
                i += 1
            replace_range_with_spaces(out, start, i)
            continue
        if ch == "/" and nxt == "*":
            start = i
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i = min(n, i + 2)
            replace_range_with_spaces(out, start, i)
            continue
        if ch == "@" and nxt == '"':
            start = i
            i += 2
            while i < n:
                if text[i] == '"':
                    if i + 1 < n and text[i + 1] == '"':
                        i += 2
                        continue
                    i += 1
                    break
                i += 1
            if not keep_strings:
                replace_range_with_spaces(out, start, i)
            continue
        if ch == "$" and nxt == "@" and i + 2 < n and text[i + 2] == '"':
            start = i
            i += 3
            while i < n:
                if text[i] == '"':
                    if i + 1 < n and text[i + 1] == '"':
                        i += 2
                        continue
                    i += 1
                    break
                i += 1
            if not keep_strings:
                replace_range_with_spaces(out, start, i)
            continue
        if ch == "@" and nxt == "$" and i + 2 < n and text[i + 2] == '"':
            start = i
            i += 3
            while i < n:
                if text[i] == '"':
                    if i + 1 < n and text[i + 1] == '"':
                        i += 2
                        continue
                    i += 1
                    break
                i += 1
            if not keep_strings:
                replace_range_with_spaces(out, start, i)
            continue
        if ch == '"':
            start = i
            quote_count = 1
            while i + quote_count < n and text[i + quote_count] == '"':
                quote_count += 1
            if quote_count >= 3:
                i += quote_count
                while i < n:
                    if text.startswith('"' * quote_count, i):
                        i += quote_count
                        break
                    i += 1
            else:
                i += 1
                while i < n:
                    if text[i] == "\\":
                        i += 2
                        continue
                    if text[i] == '"':
                        i += 1
                        break
                    i += 1
            if not keep_strings:
                replace_range_with_spaces(out, start, i)
            continue
        if ch == "'":
            start = i
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == "'":
                    i += 1
                    break
                i += 1
            if not keep_strings:
                replace_range_with_spaces(out, start, i)
            continue
        i += 1
    return "".join(out)


def canonicalize_code(text: str) -> str:
    no_comments = mask_csharp(text, keep_strings=True)
    return re.sub(r"\s+", " ", no_comments).strip()


def canonical_hash(text: str) -> str:
    return sha256_text(canonicalize_code(text))


def normalize_signature(text: str) -> str:
    cleaned = canonicalize_code(text)
    return re.sub(r"\s*([<>,()\[\]{}:=;+*/&|?.!-])\s*", r"\1", cleaned)


def skip_ws(masked: str, pos: int, end: int | None = None) -> int:
    limit = len(masked) if end is None else min(end, len(masked))
    while pos < limit and masked[pos].isspace():
        pos += 1
    return pos


def parse_identifier(masked: str, pos: int, end: int | None = None) -> tuple[str, int] | None:
    limit = len(masked) if end is None else min(end, len(masked))
    pos = skip_ws(masked, pos, limit)
    match = IDENT_RE.match(masked, pos)
    if not match or match.end() > limit:
        return None
    return match.group(0).lstrip("@"), match.end()


def find_matching(masked: str, open_pos: int, open_char: str = "{", close_char: str = "}") -> int:
    depth = 0
    for idx in range(open_pos, len(masked)):
        if masked[idx] == open_char:
            depth += 1
        elif masked[idx] == close_char:
            depth -= 1
            if depth == 0:
                return idx
    return -1


def first_top_level(masked: str, start: int, end: int, targets: set[str]) -> tuple[str, int] | None:
    paren = bracket = angle = 0
    idx = start
    while idx < end:
        ch = masked[idx]
        if ch == "(":
            paren += 1
        elif ch == ")" and paren:
            paren -= 1
        elif ch == "[":
            bracket += 1
        elif ch == "]" and bracket:
            bracket -= 1
        elif ch == "<":
            angle += 1
        elif ch == ">" and angle:
            angle -= 1
        elif paren == 0 and bracket == 0 and angle == 0 and ch in targets:
            return ch, idx
        idx += 1
    return None


def split_top_level(value: str, delimiter: str = ",") -> list[str]:
    result: list[str] = []
    start = 0
    paren = bracket = brace = angle = 0
    for idx, ch in enumerate(value):
        if ch == "(":
            paren += 1
        elif ch == ")" and paren:
            paren -= 1
        elif ch == "[":
            bracket += 1
        elif ch == "]" and bracket:
            bracket -= 1
        elif ch == "{":
            brace += 1
        elif ch == "}" and brace:
            brace -= 1
        elif ch == "<":
            angle += 1
        elif ch == ">" and angle:
            angle -= 1
        elif ch == delimiter and paren == 0 and bracket == 0 and brace == 0 and angle == 0:
            part = value[start:idx].strip()
            if part:
                result.append(part)
            start = idx + 1
    tail = value[start:].strip()
    if tail:
        result.append(tail)
    return result


def find_namespace(masked: str) -> str:
    match = NAMESPACE_RE.search(masked)
    return match.group(1) if match else ""


def find_declaration_end(masked: str, start: int, end: int) -> tuple[str, int] | None:
    paren = bracket = angle = 0
    idx = start
    while idx < end:
        ch = masked[idx]
        if ch == "(":
            paren += 1
        elif ch == ")" and paren:
            paren -= 1
        elif ch == "[":
            bracket += 1
        elif ch == "]" and bracket:
            bracket -= 1
        elif ch == "<":
            angle += 1
        elif ch == ">" and angle:
            angle -= 1
        elif paren == 0 and bracket == 0 and angle == 0 and ch in "{;":
            return ch, idx
        idx += 1
    return None


def normalize_type_expr(type_expr: str) -> str:
    value = type_expr.strip()
    value = re.sub(r"\bglobal::", "", value)
    value = value.replace("?", "")
    value = re.sub(r"\[\s*\]", "", value)
    value = re.sub(r"\s+", "", value)
    if "<" in value:
        out: list[str] = []
        depth = 0
        for ch in value:
            if ch == "<":
                depth += 1
                continue
            if ch == ">" and depth:
                depth -= 1
                continue
            if depth == 0:
                out.append(ch)
        value = "".join(out)
    return value.strip(".")


def member_simple_name(name: str) -> str:
    cleaned = name.strip().replace(" ", "")
    if "." in cleaned:
        return cleaned.rsplit(".", 1)[-1]
    return cleaned


def compact_key(value: str) -> str:
    return re.sub(r"\s+", "", value)


def parse_parameters(params_text: str) -> tuple[str, int]:
    normalized = normalize_signature(params_text)
    if not normalized:
        return "", 0
    parts = split_top_level(normalized)
    return normalized, len(parts)


def find_parameter_list(masked: str, start: int, end: int) -> tuple[int, int] | None:
    paren = bracket = angle = 0
    result: tuple[int, int] | None = None
    idx = start
    while idx < end:
        ch = masked[idx]
        if ch == "=":
            if paren == 0 and bracket == 0 and angle == 0:
                return result
        if ch == "[":
            bracket += 1
        elif ch == "]" and bracket:
            bracket -= 1
        elif ch == "<":
            angle += 1
        elif ch == ">" and angle:
            angle -= 1
        elif ch == "(" and bracket == 0 and angle == 0:
            close = find_matching(masked, idx, "(", ")")
            if close < 0 or close > end:
                return result
            result = (idx, close)
            idx = close
        idx += 1
    return result


def name_before_paren(masked_decl: str, paren_start: int) -> str:
    prefix = masked_decl[:paren_start].strip()
    operator_match = re.search(r"\boperator\s+(.+)$", prefix)
    if operator_match:
        return "operator " + compact_key(operator_match.group(1))
    destructor_match = re.search(r"~\s*([A-Za-z_@][A-Za-z0-9_]*)\s*$", prefix)
    if destructor_match:
        return "~" + destructor_match.group(1).lstrip("@")
    match = re.search(r"([A-Za-z_@][A-Za-z0-9_@]*(?:\s*\.\s*[A-Za-z_@][A-Za-z0-9_@]*)?)\s*$", prefix)
    if not match:
        return ""
    return re.sub(r"\s+", "", match.group(1)).replace("@", "")


def property_name_from_decl(masked_decl: str) -> str:
    cleaned = masked_decl.strip()
    indexer = re.search(r"\bthis\s*\[", cleaned)
    if indexer:
        return "this"
    match = re.search(r"([A-Za-z_@][A-Za-z0-9_@]*(?:\s*\.\s*[A-Za-z_@][A-Za-z0-9_@]*)?)\s*$", cleaned)
    if not match:
        return ""
    return re.sub(r"\s+", "", match.group(1)).replace("@", "")


def field_names_from_decl(masked_decl: str) -> list[str]:
    decl = re.sub(r"\b(delegate|event)\b", " ", masked_decl).strip()
    parts = split_top_level(decl)
    names: list[str] = []
    for part in parts:
        part_before_eq = part.split("=", 1)[0].strip()
        match = re.search(r"([A-Za-z_@][A-Za-z0-9_@]*)\s*$", part_before_eq)
        if match:
            name = match.group(1).lstrip("@")
            if name not in TYPE_KEYWORDS and name not in {"get", "set", "add", "remove"}:
                names.append(name)
    return names


def is_auto_property_body(body_text: str) -> bool:
    normalized = normalize_signature(body_text)
    if not normalized.startswith("{"):
        return False
    close = normalized.find("}")
    if close < 0:
        return False
    accessors = normalized[1:close]
    accessors = re.sub(r"\b(public|private|protected|internal|readonly)\b", "", accessors)
    accessors = accessors.replace("protectedinternal", "").replace("privateprotected", "")
    accessors = re.sub(r"\s+", "", accessors)
    return bool(re.fullmatch(r"(?:(?:get|set|init);)+", accessors))


def make_member_key(kind: str, name: str, parameters: str = "") -> str:
    simple = member_simple_name(name)
    if kind in {"method", "constructor", "delegate"}:
        return f"{kind}:{simple}({parameters})"
    return f"{kind}:{simple}"


def parse_member_segment(text: str, masked: str, seg_start: int, seg_end: int, rel_file: str) -> list[MemberInfo]:
    segment_mask = masked[seg_start:seg_end]
    if not segment_mask.strip():
        return []
    local_start = 0
    while True:
        local_start = skip_ws(segment_mask, local_start)
        if local_start < len(segment_mask) and segment_mask[local_start] == "[":
            close = find_matching(segment_mask, local_start, "[", "]")
            if close < 0:
                break
            local_start = close + 1
            continue
        break
    local_end = len(segment_mask)
    decl_stop: int | None = None
    body_start: int | None = None
    expression_arrow: int | None = None
    scan = local_start
    paren = bracket = angle = 0
    while scan < local_end:
        ch = segment_mask[scan]
        nxt = segment_mask[scan + 1] if scan + 1 < local_end else ""
        if ch == "(":
            paren += 1
        elif ch == ")" and paren:
            paren -= 1
        elif ch == "[":
            bracket += 1
        elif ch == "]" and bracket:
            bracket -= 1
        elif ch == "<":
            angle += 1
        elif ch == ">" and angle:
            angle -= 1
        elif paren == 0 and bracket == 0 and angle == 0:
            if ch == "{":
                decl_stop = scan
                body_start = scan
                break
            if ch == "=" and nxt == ">":
                decl_stop = scan
                expression_arrow = scan
                break
            if ch == ";":
                decl_stop = scan
                break
        scan += 1
    if decl_stop is None:
        return []
    decl_mask = segment_mask[local_start:decl_stop]
    decl_text = text[seg_start + local_start:seg_start + decl_stop]
    decl_norm = normalize_signature(decl_text)
    if not decl_norm:
        return []
    line = line_number(text, seg_start + local_start)
    body_offset = body_start if body_start is not None else expression_arrow if expression_arrow is not None else decl_stop
    body_text = text[seg_start + body_offset:seg_end]
    body_hash = canonical_hash(body_text)
    members: list[MemberInfo] = []

    if re.search(r"\bdelegate\b", decl_mask):
        plist = find_parameter_list(decl_mask, 0, len(decl_mask))
        if plist:
            name = name_before_paren(decl_mask, plist[0])
            params, param_count = parse_parameters(decl_mask[plist[0] + 1:plist[1]])
            key = make_member_key("delegate", name, params)
            members.append(MemberInfo("delegate", member_simple_name(name), decl_norm, key, body_hash, rel_file, line, params, param_count))
        return members

    plist = find_parameter_list(decl_mask, 0, len(decl_mask))
    if plist:
        name = name_before_paren(decl_mask, plist[0])
        if name:
            params, param_count = parse_parameters(decl_mask[plist[0] + 1:plist[1]])
            kind = "method"
            key = make_member_key(kind, name, params)
            members.append(MemberInfo(kind, member_simple_name(name), decl_norm, key, body_hash, rel_file, line, params, param_count))
            return members

    if body_start is not None or expression_arrow is not None:
        kind = "event" if re.search(r"\bevent\b", decl_mask) else "property"
        name = property_name_from_decl(decl_mask)
        if name:
            simple_name = member_simple_name(name)
            key = make_member_key(kind, name)
            members.append(MemberInfo(kind, simple_name, decl_norm, key, body_hash, rel_file, line))
            if kind == "property" and is_auto_property_body(body_text):
                backing_name = f"<{simple_name}>k__BackingField"
                backing_key = make_member_key("field", backing_name)
                members.append(MemberInfo("field", backing_name, f"synthetic backing field for {decl_norm}", backing_key, body_hash, rel_file, line))
        return members

    kind = "event" if re.search(r"\bevent\b", decl_mask) else "field"
    for name in field_names_from_decl(decl_mask):
        key = make_member_key(kind, name)
        members.append(MemberInfo(kind, name, decl_norm, key, body_hash, rel_file, line))
    return members


def parse_member_segments(text: str, masked: str, start: int, end: int, rel_file: str) -> list[MemberInfo]:
    members: list[MemberInfo] = []
    pos = start
    while pos < end:
        pos = skip_ws(masked, pos, end)
        if pos >= end:
            break
        seg_start = pos
        scan = pos
        paren = bracket = angle = 0
        segment_end: int | None = None
        while scan < end:
            ch = masked[scan]
            if ch == "(":
                paren += 1
            elif ch == ")" and paren:
                paren -= 1
            elif ch == "[":
                bracket += 1
            elif ch == "]" and bracket:
                bracket -= 1
            elif ch == "<":
                angle += 1
            elif ch == ">" and angle:
                angle -= 1
            elif paren == 0 and bracket == 0 and angle == 0:
                if ch == ";":
                    segment_end = scan + 1
                    break
                if ch == "{":
                    close = find_matching(masked, scan)
                    if close < 0 or close >= end:
                        segment_end = end
                        break
                    segment_end = close + 1
                    tail = skip_ws(masked, segment_end, end)
                    if tail < end and masked[tail] == "=":
                        semi = first_top_level(masked, tail, end, {";"})
                        if semi:
                            segment_end = semi[1] + 1
                    break
            scan += 1
        if segment_end is None:
            segment_end = end
        members.extend(parse_member_segment(text, masked, seg_start, segment_end, rel_file))
        pos = max(segment_end, seg_start + 1)
    return members


def parse_type_decl(
    text: str,
    masked: str,
    rel_file: str,
    namespace: str,
    parent_names: Sequence[str],
    match: re.Match[str],
    end: int,
) -> tuple[ParsedTypeDecl | None, int]:
    kind = match.group(1)
    cursor = match.end()
    if kind == "record":
        probe = parse_identifier(masked, cursor, end)
        if probe and probe[0] in {"class", "struct"}:
            kind = "record " + probe[0]
            cursor = probe[1]
    parsed = parse_identifier(masked, cursor, end)
    if not parsed:
        return None, match.end()
    name, cursor = parsed
    if name in {"where", "new", "get", "set"}:
        return None, match.end()
    decl_end = find_declaration_end(masked, cursor, end)
    if not decl_end:
        return None, match.end()
    terminator, term_pos = decl_end
    decl_start = match.start()
    signature_text = text[decl_start:term_pos]
    full_parts = [part for part in [namespace, *parent_names, name] if part]
    full_name = ".".join(full_parts)
    nested: list[ParsedTypeDecl] = []
    members: dict[str, MemberInfo] = {}
    member_names: dict[str, list[str]] = {}
    if terminator == ";":
        body_hash = ""
        end_pos = term_pos + 1
    else:
        body_open = term_pos
        body_close = find_matching(masked, body_open)
        if body_close < 0:
            return None, match.end()
        nested = parse_types_in_range(text, masked, rel_file, namespace, [*parent_names, name], body_open + 1, body_close)
        direct_ranges: list[tuple[int, int]] = []
        cursor_range = body_open + 1
        for nested_decl in sorted(nested, key=lambda item: item.start):
            if nested_decl.start > cursor_range:
                direct_ranges.append((cursor_range, nested_decl.start))
            cursor_range = max(cursor_range, nested_decl.end)
        if cursor_range < body_close:
            direct_ranges.append((cursor_range, body_close))
        members_list: list[MemberInfo] = []
        for range_start, range_end in direct_ranges:
            members_list.extend(parse_member_segments(text, masked, range_start, range_end, rel_file))
        for member in members_list:
            key = member.signature_id
            if key in members:
                suffix = 2
                while f"{key}#{suffix}" in members:
                    suffix += 1
                key = f"{key}#{suffix}"
                member.signature_id = key
            members[key] = member
            member_names.setdefault(member.name, []).append(key)
        body_hash = canonical_hash(text[body_open:body_close + 1])
        end_pos = body_close + 1
    info = TypeInfo(
        full_name=full_name,
        name=name,
        namespace=namespace,
        kind=kind,
        signature=normalize_signature(signature_text),
        body_hash=body_hash,
        files=[rel_file],
        line=line_number(text, decl_start),
        members=members,
        member_names=member_names,
    )
    return ParsedTypeDecl(info=info, nested=nested, start=decl_start, end=end_pos), end_pos


def parse_types_in_range(
    text: str,
    masked: str,
    rel_file: str,
    namespace: str,
    parent_names: Sequence[str],
    start: int,
    end: int,
) -> list[ParsedTypeDecl]:
    types: list[ParsedTypeDecl] = []
    pos = start
    while pos < end:
        match = TYPE_TOKEN_RE.search(masked, pos, end)
        if not match:
            break
        parsed, next_pos = parse_type_decl(text, masked, rel_file, namespace, parent_names, match, end)
        if parsed is None:
            pos = max(match.end(), pos + 1)
            continue
        types.append(parsed)
        pos = max(next_pos, match.end())
    return types


def flatten_type_decls(decls: Iterable[ParsedTypeDecl]) -> Iterator[TypeInfo]:
    for decl in decls:
        yield decl.info
        yield from flatten_type_decls(decl.nested)


def add_type(index: SourceIndex, info: TypeInfo) -> None:
    existing = index.types.get(info.full_name)
    if not existing:
        index.types[info.full_name] = info
        return
    existing.files.extend(info.files)
    existing.body_hash = sha256_text(existing.body_hash + ":" + info.body_hash)
    if existing.signature != info.signature:
        existing.signature = existing.signature + " | " + info.signature
    for key, member in info.members.items():
        merged_key = key
        if merged_key in existing.members:
            suffix = 2
            while f"{key}#{suffix}" in existing.members:
                suffix += 1
            merged_key = f"{key}#{suffix}"
            member.signature_id = merged_key
        existing.members[merged_key] = member
        existing.member_names.setdefault(member.name, []).append(merged_key)


def build_source_index(root: Path) -> SourceIndex:
    root = root.resolve(strict=False)
    index = SourceIndex(root=str(root))
    for path in iter_cs_files(root):
        rel_file = path.relative_to(root).as_posix()
        index.file_count += 1
        try:
            text = read_text(path)
            masked = mask_csharp(text)
            namespace = find_namespace(masked)
            for info in flatten_type_decls(parse_types_in_range(text, masked, rel_file, namespace, [], 0, len(masked))):
                add_type(index, info)
        except Exception as exc:  # noqa: BLE001 - report and continue for audit use.
            index.parse_errors.append({"file": rel_file, "error": str(exc)})
    simple: dict[str, list[str]] = defaultdict(list)
    for full_name, info in index.types.items():
        simple[info.name].append(full_name)
    index.simple_type_names = {key: sorted(value) for key, value in simple.items()}
    return index


def diff_indexes(old: SourceIndex, new: SourceIndex) -> tuple[list[TypeChange], list[MemberChange]]:
    type_changes: list[TypeChange] = []
    member_changes: list[MemberChange] = []
    for type_name in sorted(set(old.types) | set(new.types)):
        old_type = old.types.get(type_name)
        new_type = new.types.get(type_name)
        if old_type and not new_type:
            type_changes.append(TypeChange(type_name, "removed", old_type.kind, "", old_type.files, []))
            for member in old_type.members.values():
                member_changes.append(MemberChange(type_name, member.name, member.kind, "removed", member.signature, "", member.file, member.line))
            continue
        if new_type and not old_type:
            type_changes.append(TypeChange(type_name, "added", "", new_type.kind, [], new_type.files))
            for member in new_type.members.values():
                member_changes.append(MemberChange(type_name, member.name, member.kind, "added", "", member.signature, "", 0, member.file, member.line))
            continue
        if not old_type or not new_type:
            continue
        if old_type.kind != new_type.kind or old_type.signature != new_type.signature:
            status = "signature_changed"
        elif old_type.body_hash != new_type.body_hash:
            status = "implementation_changed"
        else:
            status = "unchanged"
        if status != "unchanged":
            type_changes.append(TypeChange(type_name, status, old_type.kind, new_type.kind, old_type.files, new_type.files))

        old_keys = set(old_type.members)
        new_keys = set(new_type.members)
        for key in sorted(old_keys - new_keys):
            old_member = old_type.members[key]
            status = "signature_changed" if old_member.name in new_type.member_names else "removed"
            replacement = None
            if status == "signature_changed":
                replacement_key = new_type.member_names[old_member.name][0]
                replacement = new_type.members[replacement_key]
            member_changes.append(MemberChange(
                type_name,
                old_member.name,
                old_member.kind,
                status,
                old_member.signature,
                replacement.signature if replacement else "",
                old_member.file,
                old_member.line,
                replacement.file if replacement else "",
                replacement.line if replacement else 0,
            ))
        for key in sorted(new_keys - old_keys):
            new_member = new_type.members[key]
            if new_member.name in old_type.member_names:
                continue
            member_changes.append(MemberChange(type_name, new_member.name, new_member.kind, "added", "", new_member.signature, "", 0, new_member.file, new_member.line))
        for key in sorted(old_keys & new_keys):
            old_member = old_type.members[key]
            new_member = new_type.members[key]
            if old_member.body_hash != new_member.body_hash:
                member_changes.append(MemberChange(
                    type_name,
                    old_member.name,
                    old_member.kind,
                    "implementation_changed",
                    old_member.signature,
                    new_member.signature,
                    old_member.file,
                    old_member.line,
                    new_member.file,
                    new_member.line,
                ))
    return type_changes, member_changes


def parse_string_or_nameof(token: str) -> tuple[str, str]:
    raw = token.strip()
    if raw.startswith("nameof"):
        inner = raw[raw.find("(") + 1:raw.rfind(")")].strip()
        return member_simple_name(inner), inner
    if raw.startswith('@"') and raw.endswith('"'):
        return raw[2:-1].replace('""', '"'), raw
    if raw.startswith('"') and raw.endswith('"'):
        try:
            return bytes(raw[1:-1], "utf-8").decode("unicode_escape"), raw
        except UnicodeDecodeError:
            return raw[1:-1], raw
    return raw, raw


def strip_typeof(value: str) -> str:
    value = value.strip()
    match = re.match(r"typeof\s*\((.*)\)\s*$", value, re.S)
    return match.group(1).strip() if match else value


def extract_usings(text: str) -> list[str]:
    return sorted(set(USING_RE.findall(mask_csharp(text))))


def build_all_type_maps(old: SourceIndex, new: SourceIndex) -> tuple[set[str], dict[str, list[str]]]:
    all_types = set(old.types) | set(new.types)
    simple: dict[str, list[str]] = defaultdict(list)
    for full_name in all_types:
        simple[full_name.rsplit(".", 1)[-1]].append(full_name)
    return all_types, {key: sorted(value) for key, value in simple.items()}


def resolve_type_expr(type_expr: str, usings: Sequence[str], all_types: set[str], simple_map: dict[str, list[str]]) -> list[str]:
    value = normalize_type_expr(strip_typeof(type_expr))
    if not value:
        return []
    if value in all_types:
        return [value]
    if "." in value:
        suffix_matches = sorted(name for name in all_types if name.endswith("." + value) or name.endswith("+" + value))
        if suffix_matches:
            return suffix_matches
    for namespace in usings:
        candidate = f"{namespace}.{value}"
        if candidate in all_types:
            return [candidate]
    return simple_map.get(value.rsplit(".", 1)[-1], [])


def make_ref(
    root: Path,
    path: Path,
    text: str,
    pos: int,
    reference_kind: str,
    type_expr: str,
    member_name: str,
    syntax: str,
    usings: Sequence[str],
    all_types: set[str],
    simple_map: dict[str, list[str]],
) -> PortReference:
    rel = path.relative_to(root).as_posix()
    resolved = resolve_type_expr(type_expr, usings, all_types, simple_map)
    return PortReference(rel, line_number(text, pos), reference_kind, type_expr.strip(), member_name, syntax, resolved)


def scan_port_references(port_root: Path, old: SourceIndex, new: SourceIndex) -> list[PortReference]:
    all_types, simple_map = build_all_type_maps(old, new)
    refs: list[PortReference] = []
    seen: set[tuple[str, int, str, str, str, str]] = set()

    def add_ref(ref: PortReference) -> None:
        key = (ref.source_file, ref.line, ref.reference_kind, ref.type_expr, ref.member_name, ref.syntax)
        if key not in seen:
            seen.add(key)
            refs.append(ref)

    string_token = r'(?:@?"(?:""|[^"])*"|nameof\s*\([^)]*\))'
    patch_helper_re = re.compile(
        rf"PatchHelper\.(PatchCritical|PatchGetter|Patch)\s*\(\s*[^,]+,\s*typeof\s*\((?P<type>[^)]+)\)\s*,\s*(?P<member>{string_token})",
        re.S,
    )
    harmony_patch_re = re.compile(
        rf"\[\s*HarmonyPatch\s*\(\s*typeof\s*\((?P<type>[^)]+)\)(?:\s*,\s*(?P<member>{string_token}))?",
        re.S,
    )
    get_member_re = re.compile(
        rf"typeof\s*\((?P<type>[^)]+)\)\s*\.\s*(?P<kind>GetMethod|GetField|GetProperty)\s*\(\s*(?P<member>{string_token})",
        re.S,
    )
    access_tools_re = re.compile(
        rf"AccessTools\.(?P<kind>Method|DeclaredMethod|Field|DeclaredField|Property|DeclaredProperty)\s*\(\s*(?P<type>typeof\s*\([^)]+\)|[A-Za-z_][A-Za-z0-9_\.]*)\s*,\s*(?P<member>{string_token})",
        re.S,
    )
    typeof_re = re.compile(r"\btypeof\s*\((?P<type>[A-Za-z_@][A-Za-z0-9_@\.<>?,\s\[\]]*)\)")
    get_type_string_re = re.compile(r"\.GetType\s*\(\s*@?\"(?P<type>[A-Za-z_][A-Za-z0-9_\.]+)\"")

    port_root = port_root.resolve(strict=False)
    for path in iter_cs_files(port_root):
        text = read_text(path)
        usings = extract_usings(text)
        for match in patch_helper_re.finditer(text):
            member, _raw = parse_string_or_nameof(match.group("member"))
            helper = match.group(1)
            kind = "property" if helper == "PatchGetter" else "method"
            add_ref(make_ref(port_root, path, text, match.start(), kind, match.group("type"), member, f"PatchHelper.{helper}", usings, all_types, simple_map))
        for match in harmony_patch_re.finditer(text):
            member_token = match.group("member")
            if member_token:
                member, _raw = parse_string_or_nameof(member_token)
                kind = "method"
            else:
                member = ""
                kind = "type"
            add_ref(make_ref(port_root, path, text, match.start(), kind, match.group("type"), member, "HarmonyPatch", usings, all_types, simple_map))
        for match in get_member_re.finditer(text):
            member, _raw = parse_string_or_nameof(match.group("member"))
            kind = MEMBER_KIND_FOR_GET[match.group("kind")]
            add_ref(make_ref(port_root, path, text, match.start(), kind, match.group("type"), member, match.group("kind"), usings, all_types, simple_map))
        for match in access_tools_re.finditer(text):
            member, _raw = parse_string_or_nameof(match.group("member"))
            kind = MEMBER_KIND_FOR_ACCESSTOOLS[match.group("kind")]
            add_ref(make_ref(port_root, path, text, match.start(), kind, strip_typeof(match.group("type")), member, f"AccessTools.{match.group('kind')}", usings, all_types, simple_map))
        for match in typeof_re.finditer(text):
            add_ref(make_ref(port_root, path, text, match.start(), "type", match.group("type"), "", "typeof", usings, all_types, simple_map))
        for match in get_type_string_re.finditer(text):
            type_name = match.group("type")
            if type_name.startswith("MegaCrit."):
                add_ref(make_ref(port_root, path, text, match.start(), "type", type_name, "", "Assembly.GetType", usings, all_types, simple_map))
    return refs


def members_named(type_info: TypeInfo | None, member_name: str, kind: str) -> list[MemberInfo]:
    if not type_info or not member_name:
        return []
    wanted = member_simple_name(member_name)
    keys = type_info.member_names.get(wanted, [])
    result = []
    for key in keys:
        member = type_info.members[key]
        if kind == "method" and member.kind in {"method", "constructor", "delegate"}:
            result.append(member)
        elif kind == member.kind:
            result.append(member)
        elif kind == "field" and member.kind == "event":
            result.append(member)
    return result


def evaluate_single_reference(ref: PortReference, type_name: str, old: SourceIndex, new: SourceIndex) -> tuple[str, str]:
    old_type = old.types.get(type_name)
    new_type = new.types.get(type_name)
    if old_type and not new_type:
        return "target_type_removed", type_name
    if new_type and not old_type:
        return "target_type_new_only", type_name
    if not old_type and not new_type:
        return "target_type_not_found", type_name
    if ref.reference_kind == "type":
        if old_type.signature != new_type.signature:
            return "type_signature_changed", type_name
        if old_type.body_hash != new_type.body_hash:
            return "type_implementation_changed", type_name
        return "unchanged", type_name
    old_members = members_named(old_type, ref.member_name, ref.reference_kind)
    new_members = members_named(new_type, ref.member_name, ref.reference_kind)
    if old_members and not new_members:
        return "target_member_removed", f"{type_name}.{ref.member_name}"
    if new_members and not old_members:
        return "target_member_new_only", f"{type_name}.{ref.member_name}"
    if not old_members and not new_members:
        return "target_member_not_found", f"{type_name}.{ref.member_name}"
    old_by_sig = {member.signature_id: member for member in old_members}
    new_by_sig = {member.signature_id: member for member in new_members}
    common = sorted(set(old_by_sig) & set(new_by_sig))
    if not common:
        return "target_member_signature_changed", f"{type_name}.{ref.member_name}"
    changed = [sig for sig in common if old_by_sig[sig].body_hash != new_by_sig[sig].body_hash]
    if changed:
        return "target_member_implementation_changed", f"{type_name}.{ref.member_name}"
    if len(old_members) != len(new_members):
        return "target_member_overloads_changed", f"{type_name}.{ref.member_name}"
    return "unchanged", f"{type_name}.{ref.member_name}"


def is_external_or_self_reference(ref: PortReference, self_type_names: set[str]) -> bool:
    type_expr = normalize_type_expr(strip_typeof(ref.type_expr))
    simple = type_expr.rsplit(".", 1)[-1]
    if not type_expr:
        return True
    if type_expr.startswith(EXTERNAL_TYPE_PREFIXES) or simple in EXTERNAL_SIMPLE_TYPES:
        return True
    if simple in self_type_names:
        return True
    if ref.reference_kind == "type" and not type_expr.startswith("MegaCrit."):
        return True
    return False


def is_dynamic_type_expression(ref: PortReference) -> bool:
    type_expr = normalize_type_expr(strip_typeof(ref.type_expr))
    if not type_expr or type_expr.startswith("MegaCrit."):
        return False
    simple = type_expr.rsplit(".", 1)[-1]
    return simple[:1].islower() or simple.endswith("Type")


def evaluate_port_references(refs: list[PortReference], old: SourceIndex, new: SourceIndex, self_type_names: set[str]) -> None:
    severity_order = {
        "target_type_removed": 0,
        "target_member_removed": 1,
        "target_member_signature_changed": 2,
        "target_member_not_found": 3,
        "target_type_not_found": 4,
        "target_member_implementation_changed": 5,
        "target_member_overloads_changed": 6,
        "type_signature_changed": 7,
        "type_implementation_changed": 8,
        "target_type_new_only": 9,
        "target_member_new_only": 10,
        "unchanged": 99,
        "dynamic_type_expression": 100,
        "external_or_self_reference": 101,
        "unresolved": 102,
    }
    for ref in refs:
        if not ref.resolved_types:
            if is_external_or_self_reference(ref, self_type_names):
                ref.status = "external_or_self_reference"
                ref.detail = "not an STS2 game-source target"
            elif is_dynamic_type_expression(ref):
                ref.status = "dynamic_type_expression"
                ref.detail = "runtime Type expression; inspect manually if this patch depends on private game members"
            else:
                ref.status = "unresolved"
                ref.detail = "type expression could not be resolved against either source index"
            continue
        results = [evaluate_single_reference(ref, type_name, old, new) for type_name in ref.resolved_types]
        status, detail = sorted(results, key=lambda item: severity_order.get(item[0], 50))[0]
        ref.status = status
        ref.detail = detail


def status_counts(values: Iterable[str]) -> dict[str, int]:
    counts: dict[str, int] = defaultdict(int)
    for value in values:
        counts[value] += 1
    return dict(sorted(counts.items()))


def write_json(result: AuditResult, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(asdict(result), ensure_ascii=False, indent=2), encoding="utf-8")


def write_port_refs_csv(refs: list[PortReference], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fields = ["status", "detail", "source_file", "line", "reference_kind", "type_expr", "member_name", "syntax", "resolved_types"]
    with path.open("w", encoding="utf-8", newline="") as fp:
        writer = csv.DictWriter(fp, fieldnames=fields)
        writer.writeheader()
        for ref in refs:
            row = asdict(ref)
            row["resolved_types"] = ";".join(ref.resolved_types)
            writer.writerow({field_name: row.get(field_name, "") for field_name in fields})


def write_member_changes_csv(changes: list[MemberChange], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fields = list(asdict(changes[0]).keys()) if changes else ["type", "member", "kind", "status"]
    with path.open("w", encoding="utf-8", newline="") as fp:
        writer = csv.DictWriter(fp, fieldnames=fields)
        writer.writeheader()
        for change in changes:
            writer.writerow(asdict(change))


def md_table(counts: dict[str, int]) -> list[str]:
    lines = ["| Status | Count |", "|---|---:|"]
    for status, count in sorted(counts.items(), key=lambda item: (-item[1], item[0])):
        lines.append(f"| `{status}` | {count} |")
    return lines


def ref_target(ref: PortReference) -> str:
    if ref.member_name:
        return f"{ref.type_expr}.{ref.member_name}"
    return ref.type_expr


def write_markdown(result: AuditResult, path: Path, limit: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    type_counts = status_counts(change.status for change in result.type_changes)
    member_counts = status_counts(change.status for change in result.member_changes)
    ref_counts = status_counts(ref.status for ref in result.port_references)
    risky_refs = [ref for ref in result.port_references if ref.status in RISKY_REFERENCE_STATUSES]
    status_rank = {
        "target_type_removed": 0,
        "target_member_removed": 1,
        "target_member_signature_changed": 2,
        "target_member_not_found": 3,
        "target_type_not_found": 4,
        "target_member_implementation_changed": 5,
        "target_member_overloads_changed": 6,
        "type_signature_changed": 7,
        "unresolved": 8,
    }
    risky_refs.sort(key=lambda ref: (status_rank.get(ref.status, 50), ref.source_file, ref.line))

    lines = [
        "# Port MOD AST Audit",
        "",
        f"Old source: `{result.old_source}`",
        f"New source: `{result.new_source}`",
        f"Port MOD: `{result.port_mod}`",
        "",
        "## Source Index",
        "",
        f"- Old: {result.old_file_count} C# files, {result.old_type_count} types",
        f"- New: {result.new_file_count} C# files, {result.new_type_count} types",
        f"- Old parse errors: {len(result.old_parse_errors)}",
        f"- New parse errors: {len(result.new_parse_errors)}",
        "",
        "## Type Changes",
        "",
        *md_table(type_counts),
        "",
        "## Member Changes",
        "",
        *md_table(member_counts),
        "",
        "## Port MOD Reference Status",
        "",
        *md_table(ref_counts),
        "",
        "## High-Risk Port MOD Matches",
        "",
    ]
    if risky_refs:
        lines.extend(["| Status | Port file | Target | Detail |", "|---|---|---|---|"])
        for ref in risky_refs[:limit]:
            lines.append(f"| `{ref.status}` | `{ref.source_file}:{ref.line}` | `{ref_target(ref)}` | `{ref.detail}` |")
        if len(risky_refs) > limit:
            lines.append(f"| ... | ... | ... | {len(risky_refs) - limit} more rows in `port_mod_refs.csv` |")
    else:
        lines.append("No risky port-mod target references were detected.")
    lines.extend(["", "## Changed Target Members", ""])
    interesting_members = [change for change in result.member_changes if change.status != "added"]
    interesting_members.sort(key=lambda change: (change.status, change.type, change.member))
    if interesting_members:
        lines.extend(["| Status | Type | Member | Old | New |", "|---|---|---|---|---|"])
        for change in interesting_members[:limit]:
            old_loc = f"{change.old_file}:{change.old_line}" if change.old_file else ""
            new_loc = f"{change.new_file}:{change.new_line}" if change.new_file else ""
            lines.append(f"| `{change.status}` | `{change.type}` | `{change.kind} {change.member}` | `{old_loc}` | `{new_loc}` |")
        if len(interesting_members) > limit:
            lines.append(f"| ... | ... | ... | ... | {len(interesting_members) - limit} more rows in `member_changes.csv` |")
    else:
        lines.append("No non-added member changes were detected.")
    if result.old_parse_errors or result.new_parse_errors:
        lines.extend(["", "## Parse Errors", ""])
        for label, errors in (("old", result.old_parse_errors), ("new", result.new_parse_errors)):
            for error in errors[:20]:
                lines.append(f"- `{label}` `{error.get('file')}`: {error.get('error')}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def build_audit(old_source: Path, new_source: Path, port_mod: Path) -> AuditResult:
    old = build_source_index(old_source)
    new = build_source_index(new_source)
    port_index = build_source_index(port_mod)
    type_changes, member_changes = diff_indexes(old, new)
    port_refs = scan_port_references(port_mod, old, new)
    evaluate_port_references(port_refs, old, new, set(port_index.simple_type_names))
    return AuditResult(
        old_source=str(old_source.resolve(strict=False)),
        new_source=str(new_source.resolve(strict=False)),
        port_mod=str(port_mod.resolve(strict=False)),
        old_type_count=len(old.types),
        new_type_count=len(new.types),
        old_file_count=old.file_count,
        new_file_count=new.file_count,
        type_changes=type_changes,
        member_changes=member_changes,
        port_references=port_refs,
        old_parse_errors=old.parse_errors,
        new_parse_errors=new.parse_errors,
    )


def print_summary(result: AuditResult, out_dir: Path) -> None:
    type_counts = status_counts(change.status for change in result.type_changes)
    member_counts = status_counts(change.status for change in result.member_changes)
    ref_counts = status_counts(ref.status for ref in result.port_references)
    print(f"Old source: {result.old_source} ({result.old_type_count} types)")
    print(f"New source: {result.new_source} ({result.new_type_count} types)")
    print(f"Type changes: {type_counts}")
    print(f"Member changes: {member_counts}")
    print(f"Port-mod references: {ref_counts}")
    print(f"Reports: {out_dir}")


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Audit C# AST-level source changes against port-mod Harmony/reflection targets.")
    parser.add_argument("--old-source", required=True, type=Path, help="Old STS2 C# source export root")
    parser.add_argument("--new-source", required=True, type=Path, help="New STS2 C# source export root")
    parser.add_argument("--port-mod", default=Path("port-mod/STS2AndroidPortCompat"), type=Path, help="Port MOD C# source root")
    parser.add_argument("--out", default=Path(".agent/reports/port-mod-ast-audit"), type=Path, help="Output report directory")
    parser.add_argument("--limit", default=160, type=int, help="Maximum rows per markdown section")
    parser.add_argument("--fail-on-risk", action="store_true", help="Exit non-zero when risky port-mod references are found")
    return parser.parse_args(argv)


def main(argv: Sequence[str]) -> int:
    args = parse_args(argv)
    for label, path in (("old source", args.old_source), ("new source", args.new_source), ("port-mod", args.port_mod)):
        if not path.exists():
            print(f"Missing {label}: {path}", file=sys.stderr)
            return 2
    result = build_audit(args.old_source, args.new_source, args.port_mod)
    out_dir = args.out
    out_dir.mkdir(parents=True, exist_ok=True)
    write_json(result, out_dir / "audit.json")
    write_port_refs_csv(result.port_references, out_dir / "port_mod_refs.csv")
    write_member_changes_csv(result.member_changes, out_dir / "member_changes.csv")
    write_markdown(result, out_dir / "summary.md", args.limit)
    print_summary(result, out_dir)
    if args.fail_on_risk:
        risky = [ref for ref in result.port_references if ref.status in RISKY_REFERENCE_STATUSES]
        return 1 if risky else 0
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
