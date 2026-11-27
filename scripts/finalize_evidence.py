"""v10 installed native binary, source and final-frame provenance before cleanup."""
from device import *
from PIL import Image
import hashlib,json
os.chdir(ROOT)
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
for tag in ['polish','large']:
 assert json.loads(Path('artifacts',f'v10-ios-{tag}-summary.json').read_text())['result']=='Passed'
for check in ['v10-native-dialog.json','flow-verification.json','v10-send-raster-android.json','v10-send-raster-ios.json','v10-layout-verification.json','motion-verification.json','blur-verification.json','v10-sidebar-fade-preserved.json']:
 assert json.loads(Path('artifacts',check).read_text())['passed'],check
installed=adb('shell','pm','path',APP_ID).splitlines()[0].removeprefix('package:').strip();installed_sha=adb('shell','sha256sum',installed).split()[0]
assert installed_sha==sha('artifacts/android/app-debug.apk');assert 'versionName=1.4.0' in adb('shell','dumpsys','package',APP_ID)
uuid=Path('artifacts/ios-device.txt').read_text().strip();container=Path(subprocess.check_output(['xcrun','simctl','get_app_container',uuid,APP_ID,'app'],text=True).strip());built=ROOT/'.native-build/ios-debug/Whumpo.app'
for binary in ['Whumpo','Whumpo.debug.dylib']:assert sha(container/binary)==sha(built/binary),binary
frames={}
for platform in ['android','ios']:
 files=sorted((ROOT/'screenshots'/platform).glob('*.png'))
 assert len(list((ROOT/'screenshots'/platform).glob('0[1-7]-*.png')))==7
 assert len(list((ROOT/'screenshots'/platform).glob('L2-*.png')))==34
 assert len(list((ROOT/'screenshots'/platform).glob('P-*.png')))==10
 for f in files:
  im=Image.open(f);assert im.size==((1080,2340) if platform=='android' else (1206,2622)),(f,im.size)
  saved=ROOT/'compare'/('v10-pass3' if platform=='android' else 'v10-pass10')/platform/f.name;assert saved.exists() and sha(f)==sha(saved),(f,'must match final pass')
  large=f.name.startswith(('06','07','A-'));frames[str(f.relative_to(ROOT))]={'sha256':sha(f),'dimensions':im.size,'native_package':APP_ID,'font_scale':(2 if platform=='android' else 2.143) if large else 1}
source={str(f):sha(f) for f in [Path('App.tsx'),Path('app.json'),Path('package.json'),Path('package-lock.json'),*sorted(Path('src').glob('*.tsx')),*sorted(Path('src').glob('*.ts')),*sorted(Path('assets/fonts').glob('*.ttf')),*sorted(Path('assets').glob('*.png'))]}
result={'version':'1.4.0','native_only':True,'expo_go_used':False,'android_installed_apk_sha256':installed_sha,'ios_installed_binary_matches_build':True,'ios_app_zip_sha256':sha('artifacts/ios/Whumpo-simulator-debug.app.zip'),'adb_server_port':5619,'emulator_console_port':5620,'metro_port':8619,'ios_disposable_device':uuid,'frames':frames,'source_hashes':source,'runtime':'Rebuilt native development clients; local Metro supplies JS. Not standalone release binaries.'}
Path('artifacts/v10-native-provenance.json').write_text(json.dumps(result,indent=2));subprocess.run(['python3','scripts/verify_snapshots.py'],check=True);print('PASS',len(frames),'native frames, installed binaries and frozen snapshots')
