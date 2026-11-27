"""Sequence remaining private-Android checks after the active flow verifier exits."""
import os,time,json,subprocess,argparse
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];os.chdir(ROOT)
parser=argparse.ArgumentParser();parser.add_argument('--flow-pid',type=int);args=parser.parse_args()
flow_pid=args.flow_pid
while flow_pid:
 try:os.kill(flow_pid,0)
 except ProcessLookupError:break
 time.sleep(1)
assert json.loads(Path('artifacts/flow-verification.json').read_text())['passed'], 'Full native flow must pass first'
py=str(ROOT/'.venv/bin/python')
for script,args,log in [
 ('verify_layout.py',[],'v9-layout.log'),
 ('verify_motion.py',[],'v9-motion.log'),
 ('verify_blur.py',[],'v9-blur.log'),
 ('capture_android.py',['3'],'v9-android-capture-pass3.log'),
 ('compare_v9.py',['3'],'v9-final-comparisons.log'),
 ('measure.py',[],'v9-measurements.md'),
 ('contrast.py',[],'v9-contrast.log')]:
 print('Running',script,flush=True)
 with (ROOT/'artifacts'/log).open('w') as f:subprocess.run([py,str(ROOT/'scripts'/script),*args],stdout=f,stderr=subprocess.STDOUT,check=True)
print('All final native checks and captures passed',flush=True)
