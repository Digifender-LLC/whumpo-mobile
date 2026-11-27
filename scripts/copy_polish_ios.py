"""Export real keyboard/approval XCTest attachments for a native v10 pass."""
from pathlib import Path
import json,shutil,subprocess,sys
root=Path(__file__).resolve().parents[1];number=sys.argv[1];result=sys.argv[2];out=root/'.native-build'/('v10-polish-export-'+number)
if out.exists():shutil.rmtree(out)
subprocess.run(['xcrun','xcresulttool','export','attachments','--path',result,'--output-path',str(out)],check=True)
done=set()
for group in json.loads((out/'manifest.json').read_text()):
 for a in group['attachments']:
  name=a.get('suggestedHumanReadableName','').split('_0_')[0]
  if not name.startswith(('P-','L2-','A-')):continue
  for dest in [root/'screenshots/ios',root/'compare'/('v10-pass'+number)/'ios']:
   dest.mkdir(exist_ok=True,parents=True);shutil.copy2(out/a['exportedFileName'],dest/(name+'.png'))
  done.add(name)
assert len([n for n in done if n.startswith('P-')])==10 or len([n for n in done if n.startswith('A-')])==3,done
print('Exported',len(done),'native iOS polish states')
