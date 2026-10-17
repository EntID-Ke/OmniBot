#!/usr/bin/env python3
"""Summarize host ACP timing from logcat without copying message/log bodies."""
import argparse
import json
import re
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("log", type=Path)
args = parser.parse_args()
pattern = re.compile(r"ACP timing agent=([\w.-]+) stage=([\w-]+) session=([\w-]+) turn=([\w-]+) elapsedMs=(\d+)")
turns = {}
for line in args.log.read_text(errors="replace").splitlines():
    match = pattern.search(line)
    if not match:
        continue
    agent, stage, session, turn, elapsed = match.groups()
    stages = turns.setdefault((agent, session, turn), {})
    stages.setdefault(stage, int(elapsed))

def difference(stages, start, end):
    if start not in stages or end not in stages:
        return None
    elapsed = stages[end] - stages[start]
    return elapsed if elapsed >= 0 else None

results = []
for (agent, session, turn), stages in turns.items():
    tools = []
    for stage in stages:
        if not stage.startswith("tool_") or not stage.endswith("_start"):
            continue
        end = stage.removesuffix("_start") + "_end"
        tools.append({"idHash": stage.split("_")[1], "durationMs": difference(stages, stage, end)})
    terminal = next((key for key in stages if key.startswith("terminal_")), None)
    results.append({
        "agent": agent, "sessionSuffix": session, "turnSuffix": turn,
        "promptPreparationMs": difference(stages, "turn_reserved", "prompt_sent"),
        "promptToFirstUpdateMs": difference(stages, "prompt_sent", "first_update"),
        "promptToFirstTextMs": difference(stages, "prompt_sent", "first_text"),
        "totalHostTurnMs": difference(stages, "turn_reserved", terminal) if terminal else None,
        "terminalStage": terminal, "tools": tools,
    })
print(json.dumps({
    "scope": "host ACP timing; model/network/Flutter rendering are not separated",
    "turns": results,
}, ensure_ascii=False, indent=2))
raise SystemExit(0 if results else 2)
