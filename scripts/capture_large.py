"""Native large-text inspection of every touched screen; navigation links change routes only."""
from pathlib import Path
import sys,subprocess,time,shutil
ROOT=Path(__file__).resolve().parents[1];platform=sys.argv[1];out=ROOT/'screenshots'/platform
pages=['settings','reviews','approval-detail','website','activity','account','partner','about','pinned-tab','approval-confirmation']
if platform=='android':
 from nav_ui import *
 adb('shell','settings','put','system','font_scale','2.0');launch('today',restart=True)
 for i,page in enumerate(pages,1):warm_link(page);time.sleep(1);shot(str(out/f'A-{i:02d}-{page}-large.png'))
 adb('shell','settings','put','system','font_scale','1.0');launch('today',restart=True)
else:
 uuid=(ROOT/'artifacts/ios-device.txt').read_text().strip()
 subprocess.run(['xcrun','simctl','ui',uuid,'content_size','accessibility-large'],check=True);subprocess.run(['xcrun','simctl','terminate',uuid,'com.whumpo.expomock']);time.sleep(1)
 for i,page in enumerate(pages,1):
  subprocess.run(['node','scripts/ios-open.cjs',page],cwd=ROOT,check=True);time.sleep(3);subprocess.run(['xcrun','simctl','io',uuid,'screenshot',str(out/f'A-{i:02d}-{page}-large.png')],check=True)
 subprocess.run(['xcrun','simctl','ui',uuid,'content_size','large'],check=True)
print('Captured',platform,'all touched large-text menus')
