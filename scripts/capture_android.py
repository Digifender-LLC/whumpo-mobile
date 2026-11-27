"""Native Android main and second-level capture pass, using only the private emulator."""
from nav_ui import *
from PIL import Image
import shutil,sys
os.chdir(ROOT);number=sys.argv[1]
adb('shell','pm','clear',APP_ID);adb('shell','settings','put','system','font_scale','1.0');time.sleep(1);launch('today',restart=True)
shot('screenshots/android/01-today.png');click('More',True);shot('screenshots/android/02-more.png')
click('Today',True);click('Open Partner history',True);shot('screenshots/android/03-sidebar.png');click('Open settings',True);time.sleep(1);shot('screenshots/android/04-settings.png');back();root_tab('Partner');shot('screenshots/android/05-partner.png')
adb('shell','settings','put','system','font_scale','2.0');time.sleep(1);launch('today',restart=True);shot('screenshots/android/06-today-200pct.png');click('More',True);shot('screenshots/android/07-more-200pct.png');adb('shell','settings','put','system','font_scale','1.0');time.sleep(1);launch('today',restart=True)
for i,(scene,name) in enumerate(SCENES,1):
 (open_scene(scene) if scene=='partner-composer' else warm_link(scene));shot(f'screenshots/android/L2-{i:02d}-{name}.png');print('Captured L2',i,scene,flush=True)
 if scene=='partner-composer':back() # dismiss keyboard before returning to tabs
# Actual UI outcomes, not state-setting screenshot links.
warm_link('reviews');time.sleep(.7)
warm_link('partner-new');click('Review my website draft',True);click('Create draft in Partner',True);shot('screenshots/android/L2-32-draft-bubble.png')
warm_link('approval-detail');click('Approve change',True);click('Approve',True);shot('screenshots/android/L2-33-approved-detail.png');back();back();shot('screenshots/android/L2-34-today-one-approval.png')
folder=ROOT/'compare'/('v11-pass'+number)/'android';folder.mkdir(parents=True,exist_ok=True)
for f in (ROOT/'screenshots/android').glob('*.png'):shutil.copy2(f,folder/f.name)
print('Captured Android pass',number,flush=True)
