#!/usr/bin/env python3
"""Export selected debug-device conversations as a private render seed.

The generated seed may contain real local chat content. It is written under
.codex-artifacts by default and is ignored by git.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import sqlite3
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


DEFAULT_PACKAGE = "com.univcp.android.debug"
DEFAULT_ASSISTANT_NAME = "UIka"
DEFAULT_OUT_DIR = Path(".codex-artifacts/device-render-seed")


@dataclass
class AssistantRef:
    id: str
    name: str
    system_prompt: str


def run_adb(serial: str | None, args: list[str], stdout_path: Path | None = None) -> str:
    command = ["adb"]
    if serial:
        command += ["-s", serial]
    command += args
    if stdout_path is not None:
        with stdout_path.open("wb") as output:
            subprocess.run(command, stdout=output, check=True)
        return ""
    return subprocess.check_output(command, text=True, encoding="utf-8", errors="replace")


def fetch_app_file(serial: str | None, package: str, remote_path: str, local_path: Path) -> None:
    local_path.parent.mkdir(parents=True, exist_ok=True)
    run_adb(
        serial,
        ["exec-out", "run-as", package, "cat", remote_path],
        stdout_path=local_path,
    )


def discover_single_device() -> str | None:
    output = run_adb(None, ["devices"])
    devices = [
        line.split()[0]
        for line in output.splitlines()[1:]
        if line.strip().endswith("\tdevice")
    ]
    if len(devices) == 1:
        return devices[0]
    return None


def load_assistants(settings_pb: Path) -> list[AssistantRef]:
    text = settings_pb.read_bytes().decode("utf-8", errors="ignore")
    decoder = json.JSONDecoder()
    for match in re.finditer(r'\[\{"id":"', text):
        try:
            value, _ = decoder.raw_decode(text[match.start() :])
        except json.JSONDecodeError:
            continue
        if isinstance(value, list) and value and isinstance(value[0], dict) and "name" in value[0]:
            return [
                AssistantRef(
                    id=str(item.get("id", "")),
                    name=str(item.get("name", "")),
                    system_prompt=str(item.get("systemPrompt", "")),
                )
                for item in value
                if item.get("id")
            ]
    return []


def resolve_assistant(
    assistants: list[AssistantRef],
    assistant_id: str | None,
    assistant_name: str | None,
) -> AssistantRef:
    if assistant_id:
        for assistant in assistants:
            if assistant.id == assistant_id:
                return assistant
        return AssistantRef(id=assistant_id, name=assistant_name or assistant_id, system_prompt="")

    target = (assistant_name or DEFAULT_ASSISTANT_NAME).casefold()
    matches = [assistant for assistant in assistants if assistant.name.casefold() == target]
    if len(matches) == 1:
        return matches[0]
    if not matches:
        known = ", ".join(f"{item.name or '<blank>'}:{item.id}" for item in assistants)
        raise SystemExit(f"Assistant name not found: {assistant_name or DEFAULT_ASSISTANT_NAME}. Known: {known}")
    raise SystemExit(f"Assistant name is ambiguous: {assistant_name}")


def seed_part_from_ui_part(part: dict[str, Any]) -> dict[str, Any] | None:
    part_type = part.get("type")
    if part_type == "text":
        return {"type": "text", "text": part.get("text") or ""}
    if part_type == "reasoning":
        return {
            "type": "reasoning",
            "text": part.get("reasoning") or part.get("text") or "",
            "createdAt": epoch_millis(part.get("createdAt")),
            "finishedAt": epoch_millis(part.get("finishedAt")) if part.get("finishedAt") else None,
        }
    if part_type == "image":
        return {"type": "image", "url": part.get("url") or ""}
    if part_type == "document":
        return {
            "type": "document",
            "url": part.get("url") or "",
            "fileName": part.get("fileName") or "document",
            "mime": part.get("mime") or "application/octet-stream",
        }
    if part_type == "tool":
        return {
            "type": "tool",
            "toolCallId": part.get("toolCallId") or part.get("id") or "",
            "toolName": part.get("toolName") or part.get("name") or "tool",
            "input": part.get("input") or "",
            "output": [converted for child in part.get("output") or [] if (converted := seed_part_from_ui_part(child))],
            "approvalState": str(part.get("approvalState") or "auto").lower(),
        }
    return None


def epoch_millis(value: Any) -> int:
    if isinstance(value, (int, float)):
        return int(value)
    if isinstance(value, str):
        digits = re.sub(r"\D", "", value)
        if len(digits) >= 13:
            return int(digits[:13])
    return 0


def seed_message_from_ui_message(message: dict[str, Any], node_id: str, fallback_time: int) -> dict[str, Any]:
    created_at = epoch_millis(message.get("createdAt")) or fallback_time
    updated_at = epoch_millis(message.get("finishedAt")) or created_at
    parts = [
        converted
        for part in message.get("parts") or []
        if (converted := seed_part_from_ui_part(part)) is not None
    ]
    return {
        "id": message.get("id") or node_id,
        "nodeId": node_id,
        "role": str(message.get("role") or "assistant").lower(),
        "createdAt": created_at,
        "updatedAt": updated_at,
        "parts": parts,
    }


def build_seed(
    db_path: Path,
    assistant: AssistantRef,
    include_alternatives: bool,
    limit: int | None,
    conversation_title: str | None,
) -> dict[str, Any]:
    conn = sqlite3.connect(db_path)
    rows = conn.execute(
        """
        select c.id, c.title, c.create_at, c.update_at, c.suggestions,
               mn.id, mn.node_index, mn.select_index, mn.messages
        from ConversationEntity c
        join message_node mn on mn.conversation_id = c.id
        where c.assistant_id = ?
        order by c.update_at desc, mn.node_index asc
        """,
        (assistant.id,),
    ).fetchall()

    by_conversation: dict[str, dict[str, Any]] = {}
    for (
        conversation_id,
        title,
        create_at,
        update_at,
        suggestions_json,
        node_id,
        _node_index,
        select_index,
        messages_json,
    ) in rows:
        conversation = by_conversation.setdefault(
            conversation_id,
            {
                "id": conversation_id,
                "assistantSeedId": assistant.id,
                "title": title,
                "createdAt": create_at,
                "updatedAt": update_at,
                "suggestions": json.loads(suggestions_json or "[]"),
                "messages": [],
            },
        )
        messages = json.loads(messages_json)
        selected = messages if include_alternatives else messages[int(select_index) : int(select_index) + 1]
        for offset, message in enumerate(selected):
            exported_node_id = node_id if len(selected) == 1 else f"{node_id}-{offset}"
            conversation["messages"].append(
                seed_message_from_ui_message(message, exported_node_id, fallback_time=update_at),
            )

    conversations = list(by_conversation.values())
    if conversation_title:
        needle = conversation_title.casefold()
        conversations = [
            conversation
            for conversation in conversations
            if needle in str(conversation.get("title") or "").casefold()
        ]
    if limit is not None:
        conversations = conversations[:limit]

    return {
        "version": 1,
        "assistants": [
            {
                "id": assistant.id,
                "name": f"Device Seed {assistant.name or assistant.id}",
                "systemPrompt": assistant.system_prompt or f"{{{{{assistant.name or 'assistant'}}}}}",
            },
        ],
        "conversations": conversations,
    }


def metadata_report(seed: dict[str, Any]) -> dict[str, Any]:
    counters = {
        "assistantTextParts": 0,
        "textChars": 0,
        "richRoots": 0,
        "styleTags": 0,
        "buttons": 0,
        "images": 0,
        "tables": 0,
        "svgs": 0,
        "backdropFilterParts": 0,
        "filterBlurParts": 0,
        "mixBlendParts": 0,
        "maskParts": 0,
        "clipPathParts": 0,
        "animationParts": 0,
        "transitionParts": 0,
        "dynamicRuntimeParts": 0,
    }
    patterns = {
        "richRoots": re.compile(r'<\s*(div|section|article)\b[^>]*\bid\s*=\s*(["\'])(vcp-root|response-root|vcp-[^"\']*-widget)\2', re.I | re.S),
        "styleTags": re.compile(r"<\s*style\b", re.I),
        "buttons": re.compile(r"<\s*button\b", re.I),
        "images": re.compile(r"<\s*img\b", re.I),
        "tables": re.compile(r"<\s*table\b", re.I),
        "svgs": re.compile(r"<\s*svg\b", re.I),
        "backdropFilterParts": re.compile(r"backdrop-filter\s*:", re.I),
        "filterBlurParts": re.compile(r"\bfilter\s*:[^;{}]*blur\s*\(", re.I | re.S),
        "mixBlendParts": re.compile(r"mix-blend-mode\s*:", re.I),
        "maskParts": re.compile(r"(-webkit-)?mask(-image)?\s*:", re.I),
        "clipPathParts": re.compile(r"clip-path\s*:", re.I),
        "animationParts": re.compile(r"@keyframes|\banimation\s*:", re.I),
        "transitionParts": re.compile(r"\btransition\s*:", re.I),
        "dynamicRuntimeParts": re.compile(r"<\s*(script|canvas|iframe|object|embed|video|audio)\b|requestAnimationFrame\s*\(|setInterval\s*\(|\bTHREE\s*\.|\bWebGLRenderer\b|\bmermaid\s*\.", re.I | re.S),
    }
    for conversation in seed["conversations"]:
        for message in conversation["messages"]:
            if message["role"] != "assistant":
                continue
            for part in message["parts"]:
                if part.get("type") != "text":
                    continue
                text = part.get("text", "")
                counters["assistantTextParts"] += 1
                counters["textChars"] += len(text)
                for key, pattern in patterns.items():
                    if key.endswith("Parts"):
                        counters[key] += 1 if pattern.search(text) else 0
                    else:
                        counters[key] += len(pattern.findall(text))
    return counters


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="adb device serial. Defaults to the only connected device.")
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--assistant-name", default=DEFAULT_ASSISTANT_NAME)
    parser.add_argument("--assistant-id")
    parser.add_argument("--out-dir", type=Path, default=DEFAULT_OUT_DIR)
    parser.add_argument("--include-alternatives", action="store_true")
    parser.add_argument("--limit", type=int, help="Limit exported conversations.")
    parser.add_argument("--conversation-title", help="Only export conversations whose title contains this text.")
    args = parser.parse_args()

    serial = args.serial or discover_single_device()
    if not serial:
        raise SystemExit("Multiple/no adb devices found. Pass --serial.")

    out_dir = args.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)
    db_path = out_dir / "rikka_hub"
    fetch_app_file(serial, args.package, "databases/rikka_hub", db_path)
    fetch_app_file(serial, args.package, "databases/rikka_hub-wal", out_dir / "rikka_hub-wal")
    fetch_app_file(serial, args.package, "databases/rikka_hub-shm", out_dir / "rikka_hub-shm")
    settings_pb = out_dir / "settings.preferences_pb"
    fetch_app_file(serial, args.package, "files/datastore/settings.preferences_pb", settings_pb)

    assistants = load_assistants(settings_pb)
    assistant = resolve_assistant(assistants, args.assistant_id, args.assistant_name)
    seed = build_seed(
        db_path,
        assistant,
        include_alternatives=args.include_alternatives,
        limit=args.limit,
        conversation_title=args.conversation_title,
    )
    suffix = f"_{args.conversation_title}" if args.conversation_title else ""
    safe_suffix = re.sub(r"[^\w.-]+", "_", suffix, flags=re.UNICODE)
    seed_path = out_dir / f"device_{assistant.name or assistant.id}{safe_suffix}_render_seed.local.json"
    report_path = out_dir / f"device_{assistant.name or assistant.id}{safe_suffix}_render_seed.report.json"
    seed_path.write_text(json.dumps(seed, ensure_ascii=False, indent=2), encoding="utf-8")
    report_path.write_text(json.dumps(metadata_report(seed), ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"serial={serial}")
    print(f"assistant={assistant.name}:{assistant.id}")
    print(f"conversations={len(seed['conversations'])}")
    print(f"seed={seed_path}")
    print(f"report={report_path}")
    if shutil.which("adb") is None:
        print("warning: adb was not found on PATH", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
