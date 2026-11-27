"""Refresh evidence after the intrinsic resting-card regression fix."""
from pathlib import Path
import subprocess,time,signal,shutil,json
root=Path(__file__).resolve().parents[1];uuid=(root/'artifacts/ios-device.txt').read_text().strip();python=str(root/'.venv/bin/python')
def run(a,log=None,check=True):
 if log:
  with (root/'artifacts'/log).open('w') as f:return subprocess.run(a,cwd=root,check=check,stdout=f,stderr=subprocess.STDOUT)
 return subprocess.run(a,cwd=root,check=check)
def fresh(size='large'):
 run(['xcrun','simctl','ui',uuid,'content_size',size]);run(['xcrun','simctl','terminate',uuid,'com.whumpo.expomock'],check=False);run(['node','scripts/ios-open.cjs','__development']);time.sleep(4);run(['node','scripts/ios-open.cjs','today'])
def test(method,tag):
 result=f'.native-build/v11-ios-{tag}.xcresult';run(['xcodebuild','-project','.native-build/ios-ui-check/WhumpoSmoke.xcodeproj','-scheme','WhumpoNativeSmoke','-destination','platform=iOS Simulator,id='+uuid,'-derivedDataPath','.native-build/ios-ui-derived','-resultBundlePath',result,'-only-testing:WhumpoNativeSmoke/WhumpoPolish/'+method,'CODE_SIGNING_ALLOWED=NO','test'],f'v11-ios-{tag}.log');run([python,'scripts/export_v11_ios.py','7',result],f'v11-ios-{tag}-export.log')
 summary=json.loads(subprocess.check_output(['xcrun','xcresulttool','get','test-results','summary','--path',str(root/result)],text=True));assert summary['failedTests']==0 and summary['result']=='Passed';(root/'artifacts'/f'v11-ios-{tag}-summary.json').write_text(json.dumps(summary,indent=2))
fresh();run([python,'scripts/capture_ios.py','7'],'v11-ios-capture-pass7.log')
fresh();test('testKeyboardFlow','core-wrap')
fresh();test('testPolishFlow','polish-wrap')
fresh('accessibility-large');test('testKeyboardLarge','large-wrap');run([python,'scripts/capture_large.py','ios'],'v11-ios-large-menus-final.log')
fresh();video=root/'artifacts/v11-keyboard-ios.mp4';video.unlink(missing_ok=True)
with (root/'artifacts/v11-ios-record-final.log').open('w') as f:
 rec=subprocess.Popen(['xcrun','simctl','io',uuid,'recordVideo','--codec=h264',str(video)],stdout=f,stderr=subprocess.STDOUT)
 try:time.sleep(1);test('testKeyboardMotionOnly','motion-wrap')
 finally:rec.send_signal(signal.SIGINT);rec.wait(timeout=20)
run([python,'scripts/keyboard_contacts.py','ios',str(video)],'v11-keyboard-contact-ios-delivered.log')
shutil.copy2(root/'screenshots/ios/K-03-partner-keyboard-open.png',root/'screenshots/ios/L2-12-partner-composer.png')
print('PASS final-source native iOS main, menus, outcomes, large text and motion',flush=True)
