from pathlib import Path
import json,subprocess,shutil,sys
root=Path(__file__).resolve().parents[1];number=sys.argv[1];result=sys.argv[2];out=root/'.native-build'/('v11-export-'+number)
if out.exists():shutil.rmtree(out)
subprocess.run(['xcrun','xcresulttool','export','attachments','--path',result,'--output-path',str(out)],check=True)
names=set()
for group in json.loads((out/'manifest.json').read_text()):
 for a in group['attachments']:
  name=a.get('suggestedHumanReadableName','').split('_0_')[0]
  if not name.startswith(('K-','L2-','P-','A-')):continue
  for dest in [root/'screenshots/ios',root/'compare'/('v11-pass'+number)/'ios']:
   dest.mkdir(parents=True,exist_ok=True);shutil.copy2(out/a['exportedFileName'],dest/(name+'.png'))
  names.add(name)
print('Exported',sorted(names))
