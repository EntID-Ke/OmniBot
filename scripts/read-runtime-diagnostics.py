#!/usr/bin/env python3
"""Read only newly received logs from the private local SQLite archive."""
import argparse
import importlib.util
import json
import os
import sys
from pathlib import Path

sys.dont_write_bytecode = True

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--output", type=Path, required=True, help="Private receiver output directory")
parser.add_argument("--after", type=int, default=0, help="Exclusive sequence cursor from the previous read")
parser.add_argument("--limit", type=int, default=1000)
parser.add_argument("--save", type=Path, required=True, help="Save the new records privately; only counts are printed")
args = parser.parse_args()
spec = importlib.util.spec_from_file_location("receiver", Path(__file__).with_name("runtime-diagnostic-receiver.py"))
receiver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(receiver)
records = receiver.read_new_logs(args.output, args.after, args.limit)
result = {"after": args.after, "nextCursor": records[-1]["sequence"] if records else args.after,
          "newCount": len(records), "records": records}
os.umask(0o077)
args.save.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
with args.save.open("x", encoding="utf-8") as stream:
    json.dump(result, stream, ensure_ascii=False, indent=2)
print(json.dumps({key: result[key] for key in ("after", "nextCursor", "newCount")}))
