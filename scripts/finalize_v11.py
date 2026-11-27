"""Bind all delivered frames to installed native clients and final application source."""
from device import *
from PIL import Image
import hashlib,json,shutil
os.chdir(ROOT)
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
flow=json.loads(Path('artifacts/flow-verification.json').read_text())
continuation=json.loads(Path('artifacts/v11-flow-continuation.json').read_text()) if Path('artifacts/v11-flow-continuation.json').exists() else {}
if not flow['passed']:
 assert flow.get('status')=='blocked-uiautomator-idle' and continuation.get('passed'), 'Unrecorded native flow failure'
 print('KNOWN LIMIT: full Android UIAutomator flow blocked; continuation verified. Native provenance is not an all-tests-pass claim.',flush=True)
for name in ['v11-keyboard-android.json','v11-keyboard-raster.json','v11-sidebar-fade-preserved.json']:
 assert json.loads(Path('artifacts',name).read_text())['passed'],name
installed=adb('shell','pm','path',APP_ID).splitlines()[0].removeprefix('package:').strip();digest=adb('shell','sha256sum',installed).split()[0]
assert digest==sha('artifacts/android/app-debug.apk');assert 'versionName=1.5.0' in adb('shell','dumpsys','package',APP_ID)
uuid=Path('artifacts/ios-device.txt').read_text().strip();container=Path(subprocess.check_output(['xcrun','simctl','get_app_container',uuid,APP_ID,'app'],text=True).strip());built=ROOT/'.native-build/ios-debug/Whumpo.app'
for binary in ['Whumpo','Whumpo.debug.dylib']:assert sha(container/binary)==sha(built/binary),binary
frames={}
for platform in ['android','ios']:
 source=ROOT/'screenshots'/platform;final=ROOT/'compare/v11-final'/platform;final.mkdir(parents=True,exist_ok=True)
 assert len(list(source.glob('0[1-7]-*.png')))==7
 for prefix in ['K-01-','K-02-','K-03-','K-04-','K-05-','K-06-','K-07-']:
  assert any(f.name.startswith(prefix) for f in source.glob('*.png')),(platform,prefix)
 assert len(list(source.glob('L2-*.png')))>=34
 for f in sorted(source.glob('*.png')):
  im=Image.open(f).convert('RGB');crop=f.name.startswith('K-05-');expected=(1080,2340) if platform=='android' else (1206,2622)
  assert crop or im.size==expected,(f,im.size)
  # Reject development error/refresh overlays, not intentionally red semantic cards.
  top=im.crop((0,0,im.width,min(280,im.height)));colors=top.getcolors(top.width*top.height)
  red=sum(n for n,c in colors if c[0]>200 and c[1]<120 and c[2]<160);blue=sum(n for n,c in colors if c[2]>180 and 65<c[1]<180 and c[0]<80)
  assert red<20000 and blue<20000,(f,'development overlay',red,blue)
  shutil.copy2(f,final/f.name)
  large=f.name.startswith(('06','07','A-','K-07','K-08'));frames[str(f.relative_to(ROOT))]={'sha256':sha(f),'dimensions':im.size,'native_package':APP_ID,'font_scale':(2 if platform=='android' else 2.143) if large else 1,'native_crop':crop}
source={str(f):sha(f) for f in [Path('App.tsx'),Path('app.json'),Path('package.json'),Path('package-lock.json'),*sorted(Path('src').glob('*.tsx')),*sorted(Path('src').glob('*.ts')),*sorted(Path('assets/fonts').glob('*.ttf'))]}
r={'verification':{'full_android_flow_passed':flow['passed'],'android_flow_status':flow.get('status','passed'),'continuation_passed':continuation.get('passed')},'version':'1.5.0','native_only':True,'expo_go_used':False,'android_installed_apk_sha256':digest,'ios_installed_binary_matches_build':True,'ios_app_zip_sha256':sha('artifacts/ios/Whumpo-simulator-debug.app.zip'),'private_ports':[5619,5620,5621,8619],'ios_disposable_device':uuid,'frames':frames,'source_hashes':source,'runtime':'Rebuilt native development clients with final local Metro JavaScript. Not standalone release binaries.'}
Path('artifacts/v11-native-provenance.json').write_text(json.dumps(r,indent=2));subprocess.run(['python3','scripts/verify_snapshots.py'],check=True);print('PASS',len(frames),'native frames, installed binaries, source and immutable snapshots',flush=True)
