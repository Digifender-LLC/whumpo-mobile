"""Audit cold-takeover documentation and current delivered evidence, without devices."""
from pathlib import Path
import json,re,glob,hashlib
ROOT=Path(__file__).resolve().parents[1]
errors=[];checked=[]
def require(path):
 p=ROOT/path
 if not p.exists():errors.append('Missing '+path)
 else:checked.append(path)
 return p
for name in ['HANDOFF.md','RESULT.md','DESIGN-LANGUAGE.md','DEPENDENCIES.md','README.md','AGENTS.md','package-lock.json','artifacts/android/app-debug.apk','artifacts/ios/Whumpo-simulator-debug.app.zip']:
 require(name)
for platform in ['android','ios']:
 for pattern in ['0[1-7]-*.png','L2-*.png','K-*.png']:
  files=list((ROOT/'screenshots'/platform).glob(pattern));expected=7 if pattern.startswith('0') else 37 if pattern.startswith('L2') else 8
  if len(files)!=expected:errors.append(f'{platform} {pattern}: expected {expected}, found {len(files)}')
 for num in range(1,9):
  if not list((ROOT/'screenshots'/platform).glob(f'K-{num:02d}-*.png')):errors.append(f'Missing {platform} K-{num:02d}')
for suffix in ['', '-ios']:
 for scene in ['today-keyboard','today-closed','settings']:require(f'compare/v10-vs-v11-{scene}{suffix}.png')
 for direction in ['open','close']:require(f'compare/keyboard-transition-{direction}-contact-sheet{suffix}.png')
for name in ['v11-keyboard-android.json','v11-keyboard-raster.json','v11-sidebar-fade-preserved.json','v11-cleanup.json']:
 p=require('artifacts/'+name)
 if p.exists() and not json.loads(p.read_text()).get('passed'):errors.append(name+' did not pass')
flow=json.loads(require('artifacts/flow-verification.json').read_text())
if not flow.get('passed'):
 cont=require('artifacts/v11-flow-continuation.json')
 if flow.get('status')!='blocked-uiautomator-idle' or not json.loads(cont.read_text()).get('passed'):errors.append('Unaccounted Android flow failure')
 for doc in ['RESULT.md','HANDOFF.md']:
  if 'UIAutomator' not in (ROOT/doc).read_text() or 'blocked' not in (ROOT/doc).read_text().lower():errors.append(doc+' omits known flow blocker')
p=require('artifacts/v11-native-provenance.json')
if p.exists():
 r=json.loads(p.read_text())
 if not r.get('native_only') or r.get('expo_go_used') or len(r.get('frames',{}))<100:errors.append('Invalid native provenance')
 for name,info in r.get('frames',{}).items():
  f=ROOT/name
  if not f.exists() or hashlib.sha256(f.read_bytes()).hexdigest()!=info['sha256']:errors.append('Changed frame '+name)
 for name,digest in r.get('source_hashes',{}).items():
  f=ROOT/name
  if not f.exists() or hashlib.sha256(f.read_bytes()).hexdigest()!=digest:errors.append('Changed native-source evidence '+name)
 for name,key in [('artifacts/android/app-debug.apk','android_installed_apk_sha256'),('artifacts/ios/Whumpo-simulator-debug.app.zip','ios_app_zip_sha256')]:
  if hashlib.sha256((ROOT/name).read_bytes()).hexdigest()!=r[key]:errors.append('Changed native artifact '+name)
# Only concrete delivered-evidence inline paths are required to survive cleanup.
for doc in ['RESULT.md','DESIGN-LANGUAGE.md']:
 text=(ROOT/doc).read_text()
 if re.search(r'(?:MEASUREMENTS|VALIDATION|CLEANUP|FINAL_NATIVE_CHECK|WRAP_CHECK)_PENDING',text):errors.append(doc+' contains unfinished placeholder')
 for item in re.findall(r'`([^`\n]+)`',text):
  if item.startswith(('artifacts/','compare/','screenshots/')) and not any(x in item for x in [' ', '|','<','PLATFORM','VIDEO','PASS']):
   if not glob.glob(str(ROOT/item)):errors.append(doc+' references missing evidence '+item)
   else:checked.append(item)
text=(ROOT/'HANDOFF.md').read_text()
for p in (ROOT/'scripts').iterdir():
 if p.is_file() and 'scripts/'+p.name not in text:errors.append('Undocumented script '+p.name)
for version in range(6,11):require(f'v{version}-snapshot');require(f'artifacts/v{version}-snapshot-sha256.json')
report={'passed':not errors,'full_android_flow_passed':flow.get('passed'),'checked_paths':sorted(set(checked)),'errors':errors,'scope':'Concrete delivered paths, document placeholders, complete script inventory, final reports; regenerated native/cache paths are intentionally absent.'}
(ROOT/'artifacts/v11-handoff-audit.json').write_text(json.dumps(report,indent=2))
print(json.dumps({'passed':report['passed'],'checked_paths':len(report['checked_paths']),'errors':errors},indent=2))
if errors:raise SystemExit(1)
