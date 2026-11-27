"""Keep only native non-history outcome/keyboard frames from the final XCTest."""
from pathlib import Path
import json,shutil,subprocess
root=Path(__file__).resolve().parents[1];out=root/'artifacts/ios-native-final-attachments';
if out.exists():shutil.rmtree(out)
out.mkdir()
subprocess.run(['xcrun','xcresulttool','export','attachments','--path',str(root/'.native-build/v9-ios-suite-final.xcresult'),'--output-path',str(out)],check=True)
wanted={'L2-12-partner-composer','L2-32-draft-bubble','L2-33-approved-detail','L2-34-today-one-approval'};done=set()
for group in json.loads((out/'manifest.json').read_text()):
 for a in group['attachments']:
  name=a.get('suggestedHumanReadableName','').split('_0_')[0]
  if name in wanted:
   source=out/a['exportedFileName']
   for dest in [root/'screenshots/ios',root/'compare/v9-pass5/ios']:
    dest.mkdir(exist_ok=True,parents=True);shutil.copy2(source,dest/(name+'.png'))
   done.add(name)
assert done==wanted,(done,wanted)
print('Copied',len(done),'actual native keyboard/outcome frames; no test-mode history frames delivered')
