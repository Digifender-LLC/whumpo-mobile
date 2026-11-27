"""Explicit continuation after the documented Android UIAutomator idle blocker.
Not a replacement or passing claim for the full verify_flow.py run.
Start from Today after the first approval in that failed run.
"""
from pathlib import Path
import json
root=Path(__file__).resolve().parents[1];source=(root/'scripts/verify_flow.py').read_text()
# Reuse assertions/helpers but not the full run's reset/actions or report target.
prefix=source.split("Path('artifacts/flow-verification.json').unlink",1)[0]
scope={'__file__':str(root/'scripts/verify_flow.py')};exec(prefix,scope)
scope['shot']('screenshots/android/L2-34-today-one-approval.png');scope['shot']('screenshots/android/P-04-today-after-one-approval.png')
# The existing circular Reviews header target is stable at this viewport. No data
# is injected: this is the same visible button normally located through UIA.
scope['tap'](954,194)
scope['check']('1 decision needs you','Needs your decision','Approved')
scope['log'].append('Entered Reviews using its visible header target after UIAutomator could not idle on Today; Today copy was visually reviewed from native captures, not automatically asserted here.')
tail=source.split("click('Review 1 decision',True);",1)[1].replace("Path('artifacts/flow-verification.json')","Path('artifacts/v11-flow-continuation.json')")
exec(tail,scope)
p=root/'artifacts/v11-flow-continuation.json';r=json.loads(p.read_text());r['scope']='Continuation after one-approval Today UIAutomator idle blocker; full verify_flow.py remains blocked.';p.write_text(json.dumps(r,indent=2))
