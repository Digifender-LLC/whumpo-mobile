"""Owned simulator checks only; source stays frozen while native interactions run."""
from pathlib import Path
import subprocess,time,signal,sys,shutil,json
root=Path(__file__).resolve().parents[1];uuid=(root/'artifacts/ios-device.txt').read_text().strip();python=str(root/'.venv/bin/python')
def run(a,log=None,check=True):
 if log:
  with (root/'artifacts'/log).open('w') as f:return subprocess.run(a,cwd=root,check=check,stdout=f,stderr=subprocess.STDOUT)
 return subprocess.run(a,cwd=root,check=check)
def openfresh(size='large'):
 run(['xcrun','simctl','ui',uuid,'content_size',size]);run(['xcrun','simctl','terminate',uuid,'com.whumpo.expomock'],check=False);run(['node','scripts/ios-open.cjs','__development']);time.sleep(4);run(['node','scripts/ios-open.cjs','today'])
def test(method,tag):
 result=f'.native-build/v11-ios-{tag}.xcresult';run(['xcodebuild','-project','.native-build/ios-ui-check/WhumpoSmoke.xcodeproj','-scheme','WhumpoNativeSmoke','-destination','platform=iOS Simulator,id='+uuid,'-derivedDataPath','.native-build/ios-ui-derived','-resultBundlePath',result,'-only-testing:WhumpoNativeSmoke/WhumpoPolish/'+method,'CODE_SIGNING_ALLOWED=NO','test'],f'v11-ios-{tag}.log');return result
openfresh();result=test('testPolishFlow','polish-checked');run([python,'scripts/export_v11_ios.py','4',result],'v11-ios-polish-export-final.log')
openfresh('accessibility-large');result=test('testKeyboardLarge','large-checked');run([python,'scripts/export_v11_ios.py','4',result],'v11-ios-large-export-final.log');run([python,'scripts/capture_large.py','ios'],'v11-ios-large-menus.log')
openfresh();log=(root/'artifacts/v11-ios-record.log').open('w');video=root/'artifacts/v11-keyboard-ios.mp4';video.unlink(missing_ok=True)
rec=subprocess.Popen(['xcrun','simctl','io',uuid,'recordVideo','--codec=h264',str(video)],stdout=log,stderr=subprocess.STDOUT)
try:time.sleep(1);test('testKeyboardMotionOnly','motion-checked')
finally:rec.send_signal(signal.SIGINT);rec.wait(timeout=20);log.close()
run([python,'scripts/keyboard_contacts.py','ios',str(video)],'v11-keyboard-contact-ios.log')
for number,scene in [(36,'activity-loading'),(37,'activity-error')]:
 run(['node','scripts/ios-open.cjs',scene]);time.sleep(2);run(['xcrun','simctl','io',uuid,'screenshot',str(root/f'screenshots/ios/L2-{number}-{scene}.png')])
# Actual native typed state replaces the route-only, unfocused sample field frame.
shutil.copy2(root/'screenshots/ios/K-03-partner-keyboard-open.png',root/'screenshots/ios/L2-12-partner-composer.png')
print('PASS native iOS default flow, large keyboard, touched large menus and motion video',flush=True)
