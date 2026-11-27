"""Final native iOS capture after the last one-line alignment refinement."""
from pathlib import Path
import subprocess,time,shutil
root=Path(__file__).resolve().parents[1]
while not (root/'artifacts/v10-alignment-ready.json').exists():time.sleep(1)
for command,log in [(['scripts/run_ios_polish.sh','10'],'v10-ios-polish-export10.log'),([str(root/'.venv/bin/python'),'scripts/capture_ios.py','10'],'v10-ios-capture-pass10.log'),([str(root/'.venv/bin/python'),'scripts/capture_large.py','ios'],'v10-ios-large10.log'),([str(root/'.venv/bin/python'),'scripts/finish_ios_large_v10.py','10'],'v10-ios-finalize10.log')]:
 with (root/'artifacts'/log).open('w') as f:subprocess.run(command,cwd=root,stdout=f,stderr=subprocess.STDOUT,check=True)
shutil.copy2(root/'screenshots/ios/P-02-partner-typing.png',root/'screenshots/ios/L2-12-partner-composer.png');shutil.copy2(root/'screenshots/ios/L2-12-partner-composer.png',root/'compare/v10-pass10/ios/L2-12-partner-composer.png')
subprocess.run([str(root/'.venv/bin/python'),'scripts/compare_v10.py','10'],cwd=root,check=True)
print('PASS final native iOS default/large captures')
