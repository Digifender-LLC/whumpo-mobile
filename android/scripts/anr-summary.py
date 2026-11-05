#!/usr/bin/env python3
"""Project am_anr events to fixed labels; never retain raw event payloads."""
import json
import re
import sys

MAX_BYTES = 65536
MAX_EVENTS = 100
PROCESSES = {
    "com.whumpo.android.local": "app-local",
    "com.whumpo.android": "app-deployed",
    "com.android.systemui": "system-ui",
    "system_server": "system-server",
    "com.google.android.gms": "play-services",
    "com.google.android.gms.persistent": "play-services-persistent",
}
EVENT = re.compile(r"\[([0-9]{1,10}),([0-9]{1,10}),([^,\r\n]{1,255}),(-?[0-9]{1,10}),(.*)\]")


def unavailable(reason):
    return {"version": 1, "status": "unavailable", "reason": reason}


def summarize(raw):
    if len(raw) > MAX_BYTES:
        return unavailable("limit")
    try:
        lines = raw.decode("utf-8").splitlines()
    except UnicodeDecodeError:
        return unavailable("format")
    events = []
    for line in lines:
        if not line.strip() or line in ("--------- beginning of events", "--------- switch to events"):
            continue
        match = EVENT.fullmatch(line)
        if not match:
            return unavailable("format")
        user, pid, process, flags, reason = match.groups()
        if not (0 <= int(user) <= 2147483647 and 1 <= int(pid) <= 4194304
                and -2147483648 <= int(flags) <= 2147483647):
            return unavailable("format")
        events.append({
            "process": PROCESSES.get(process, "other"),
            "pid": int(pid),
            "reasonKind": "input-dispatch" if reason.startswith("Input dispatching timed out") else "other",
        })
        if len(events) > MAX_EVENTS:
            return unavailable("limit")
    return {"version": 1, "status": "captured", "events": events}


if __name__ == "__main__":
    print(json.dumps(summarize(sys.stdin.buffer.read(MAX_BYTES + 1)), separators=(",", ":")))
