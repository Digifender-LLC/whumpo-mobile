"""Full native frame pass; deep links choose existing routes, never fabricate pixels/state."""
from nav_ui import *
from PIL import Image
import sys,shutil
os.chdir(ROOT);number=sys.argv[1]
def route(scene):
 adb('shell','am','start','-n',APP_ID+'/.MainActivity','-a','android.intent.action.VIEW','-d','whumpoexpo://'+scene);time.sleep(1)
adb('shell','settings','put','system','font_scale','1.0');adb('shell','pm','clear',APP_ID);launch('today',restart=True)
for i,scene in enumerate(['today','more','history','settings','partner'],1):route(scene);shot(f'screenshots/android/{i:02d}-'+('sidebar' if scene=='history' else scene)+'.png')
adb('shell','settings','put','system','font_scale','2.0');time.sleep(1);launch('today',restart=True);shot('screenshots/android/06-today-200pct.png');route('more');shot('screenshots/android/07-more-200pct.png');adb('shell','settings','put','system','font_scale','1.0');time.sleep(1);launch('today',restart=True)
for i,(scene,name) in enumerate(SCENES,1):
 if scene=='partner-composer':
  route('partner');click('Ask your Partner, draft only',True);adb('shell','input','text','Review%sthe%sstudio%sintroduction');shot(f'screenshots/android/L2-{i:02d}-{name}.png');back()
 else:route(scene);shot(f'screenshots/android/L2-{i:02d}-{name}.png')
route('partner-new');click('Review my website draft',True);click('Create draft in Partner',True);shot('screenshots/android/L2-32-draft-bubble.png');route('approval-detail');click('Approve change',True);click('Approve',True);shot('screenshots/android/L2-33-approved-detail.png');back();back();shot('screenshots/android/L2-34-today-one-approval.png')
for number2,scene in [(36,'activity-loading'),(37,'activity-error')]:route(scene);shot(f'screenshots/android/L2-{number2}-{scene}.png')
folder=ROOT/'compare'/('v11-pass'+number)/'android';folder.mkdir(parents=True,exist_ok=True)
for f in (ROOT/'screenshots/android').glob('*.png'):shutil.copy2(f,folder/f.name)
print('Captured complete Android native pass',number,flush=True)
