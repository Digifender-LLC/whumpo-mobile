"""Serial private-device verification and second capture iteration."""
from pathlib import Path
import subprocess,time
root=Path(__file__).resolve().parents[1]
while not (root/'artifacts/v10-alignment-ready.json').exists():time.sleep(1)
for script,args,label in [('verify_dialog.py',[],'native-dialog'),('verify_layout.py',[],'layout'),('verify_blur.py',[],'blur'),('verify_motion.py',[],'motion'),('capture_android.py',['2'],'capture-pass2'),('capture_polish_android.py',['2'],'polish-pass2'),('capture_large.py',['android'],'large')]:
 print('Running',script,flush=True)
 with (root/'artifacts'/('v10-android-'+label+'.log')).open('w') as f:subprocess.run([str(root/'.venv/bin/python'),str(root/'scripts'/script),*args],cwd=root,stdout=f,stderr=subprocess.STDOUT,check=True)
subprocess.run([str(root/'.venv/bin/python'),'scripts/compare_v10.py','2'],cwd=root,check=True)
