"""Finish the final iOS large-text interaction after route capture releases the device."""
from pathlib import Path
import subprocess,time,shutil,json,sys
root=Path(__file__).resolve().parents[1];number=sys.argv[1] if len(sys.argv)>1 else '4';log=root/('artifacts/v10-ios-large'+number+'.log');uuid=(root/'artifacts/ios-device.txt').read_text().strip()
while not log.exists() or 'Captured ios all touched large-text menus' not in log.read_text():time.sleep(1)
run=lambda *a:subprocess.run(a,cwd=root,check=True)
run('xcrun','simctl','ui',uuid,'content_size','accessibility-large');subprocess.run(['xcrun','simctl','terminate',uuid,'com.whumpo.expomock']);run('node','scripts/ios-open.cjs','__development');time.sleep(5)
with (root/('artifacts/v10-ios-large-ui'+number+'.log')).open('w') as f:subprocess.run(['xcodebuild','-project','.native-build/ios-ui-check/WhumpoSmoke.xcodeproj','-scheme','WhumpoNativeSmoke','-destination','platform=iOS Simulator,id='+uuid,'-derivedDataPath','.native-build/ios-ui-derived','-resultBundlePath','.native-build/v10-ios-large'+number+'.xcresult','-only-testing:WhumpoNativeSmoke/WhumpoPolish/testLargeReviews','CODE_SIGNING_ALLOWED=NO','test'],cwd=root,stdout=f,stderr=subprocess.STDOUT,check=True)
run(str(root/'.venv/bin/python'),'scripts/copy_polish_ios.py',number,'.native-build/v10-ios-large'+number+'.xcresult');run('xcrun','simctl','ui',uuid,'content_size','large')
for p in (root/'screenshots/ios').glob('*.png'):shutil.copy2(p,root/'compare'/('v10-pass'+number)/'ios'/p.name)
run(str(root/'.venv/bin/python'),'scripts/verify_send_raster.py','ios');run(str(root/'.venv/bin/python'),'scripts/compare_v10.py',number)
for tag,path in [('polish','.native-build/v10-ios-polish-'+number+'.xcresult'),('large','.native-build/v10-ios-large'+number+'.xcresult')]:
 result=subprocess.check_output(['xcrun','xcresulttool','get','test-results','summary','--path',path],cwd=root,text=True);(root/'artifacts'/('v10-ios-'+tag+'-summary.json')).write_text(result)
print('PASS final native iOS capture and default/large UI checks')
