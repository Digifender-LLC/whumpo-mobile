from pathlib import Path
import subprocess,time,signal,shutil
root=Path(__file__).resolve().parents[1];uuid=(root/'artifacts/ios-device.txt').read_text().strip();python=str(root/'.venv/bin/python')
def run(a,log=None,check=True):
 if log:
  with (root/'artifacts'/log).open('w') as f:return subprocess.run(a,cwd=root,check=check,stdout=f,stderr=subprocess.STDOUT)
 return subprocess.run(a,cwd=root,check=check)
def fresh(size):
 run(['xcrun','simctl','ui',uuid,'content_size',size]);run(['xcrun','simctl','terminate',uuid,'com.whumpo.expomock'],check=False);run(['node','scripts/ios-open.cjs','__development']);time.sleep(4);run(['node','scripts/ios-open.cjs','today'])
def test(method,tag):
 result=f'.native-build/v11-ios-{tag}.xcresult';run(['xcodebuild','-project','.native-build/ios-ui-check/WhumpoSmoke.xcodeproj','-scheme','WhumpoNativeSmoke','-destination','platform=iOS Simulator,id='+uuid,'-derivedDataPath','.native-build/ios-ui-derived','-resultBundlePath',result,'-only-testing:WhumpoNativeSmoke/WhumpoPolish/'+method,'CODE_SIGNING_ALLOWED=NO','test'],f'v11-ios-{tag}.log');run([python,'scripts/export_v11_ios.py','5',result],f'v11-ios-{tag}-export.log')
p=root/'artifacts/v11-ios-capture-pass5.log'
while not p.exists() or 'Captured iOS pass 5' not in p.read_text():time.sleep(2)
fresh('accessibility-large');test('testKeyboardLarge','large-delivered');run([python,'scripts/capture_large.py','ios'],'v11-ios-large-menus-final.log')
fresh('large');video=root/'artifacts/v11-keyboard-ios.mp4';video.unlink(missing_ok=True)
with (root/'artifacts/v11-ios-record-delivered.log').open('w') as f:
 rec=subprocess.Popen(['xcrun','simctl','io',uuid,'recordVideo','--codec=h264',str(video)],stdout=f,stderr=subprocess.STDOUT)
 try:time.sleep(1);test('testKeyboardMotionOnly','motion-delivered')
 finally:rec.send_signal(signal.SIGINT);rec.wait(timeout=20)
run([python,'scripts/keyboard_contacts.py','ios',str(video)],'v11-keyboard-contact-ios-delivered.log')
shutil.copy2(root/'screenshots/ios/K-03-partner-keyboard-open.png',root/'screenshots/ios/L2-12-partner-composer.png')
print('PASS final native iOS large text, keyboard Return, endpoints and motion recording',flush=True)
