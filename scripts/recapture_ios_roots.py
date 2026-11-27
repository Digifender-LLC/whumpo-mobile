from pathlib import Path
import subprocess,time,shutil
root=Path(__file__).resolve().parents[1]
log=root/'artifacts/v10-ios-finalize10.log'
while not log.exists() or 'PASS final native iOS capture' not in log.read_text():time.sleep(1)
# Reuse the exact readiness checks; execute definitions only, without the full capture loop.
s=(root/'scripts/capture_ios.py').read_text().split("run('ui',device,'appearance'")[0]
import sys
sys.argv=['capture_ios.py','10']
ns={'__file__':str(root/'scripts/capture_ios.py')};exec(s,ns)
for size,name in [('large','01-today'),('accessibility-large','06-today-200pct')]:
 ns['run']('ui',ns['device'],'content_size',size);ns['run']('terminate',ns['device'],'com.whumpo.expomock');subprocess.run(['node','scripts/ios-open.cjs','__development'],cwd=root,check=True);time.sleep(4);ns['open_page']('today');ns['shot'](name);shutil.copy2(root/'screenshots/ios'/(name+'.png'),root/'compare/v10-pass10/ios'/(name+'.png'))
ns['run']('ui',ns['device'],'content_size','large')
subprocess.run([str(root/'.venv/bin/python'),'scripts/compare_v10.py','10'],cwd=root,check=True)
print('PASS settled Today roots; body surfaces and full-opacity type checked')
