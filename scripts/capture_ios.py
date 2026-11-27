"""Native iOS main and second-level captures on the one disposable simulator."""
from pathlib import Path
import subprocess,sys,time,shutil
from PIL import Image
ROOT=Path(__file__).resolve().parents[1];device=(ROOT/'artifacts/ios-device.txt').read_text().strip();number=sys.argv[1];out=ROOT/'screenshots/ios';out.mkdir(exist_ok=True)
def run(*a):return subprocess.run(['xcrun','simctl',*a],check=a[0]!='terminate',stdout=subprocess.DEVNULL)
def open_page(page):subprocess.run(['node','scripts/ios-open.cjs',page],cwd=ROOT,check=True);time.sleep(2)
def shot(name):
 for attempt in range(20):
  run('io',device,'screenshot',str(out/(name+'.png')))
  im=Image.open(out/(name+'.png')).convert('RGB');counts=im.getcolors(im.width*im.height)
  surface=sum(n for n,col in counts if max(abs(v-48) for v in col)<=2)
  white=sum(n for n,col in counts if min(col)>240)
  blue=sum(n for n,col in im.crop((0,0,im.width,int(im.height*.15))).getcolors(im.width*im.height) if col[2]>180 and 65<col[1]<180 and col[0]<80)
  red=sum(n for n,col in im.crop((0,int(im.height*.75),im.width,im.height)).getcolors(im.width*im.height) if col[0]>180 and col[1]<120 and col[2]<160)
  body=im.crop((0,round(160*3),im.width,round(700*3))).getcolors(im.width*im.height)
  body_white=sum(n for n,col in body if min(col)>240)
  card=sum(n for n,col in body if col==(33,33,33))
  scene_ready=(card>30000 and body_white>2000) if name.startswith(('01-','06-')) else body_white>2000 if name.startswith('05-') else True
  if surface>2000 and white>1000 and blue<100 and red<100 and scene_ready:return
  time.sleep(1)
 raise AssertionError('iOS scene not ready: '+name)
run('ui',device,'appearance','dark');run('ui',device,'content_size','large');run('terminate',device,'com.whumpo.expomock');time.sleep(1)
for i,page in enumerate(['today','more','history','settings','partner'],1):open_page(page);shot(f'{i:02d}-'+('sidebar' if page=='history' else page))
run('ui',device,'content_size','accessibility-large');time.sleep(1);run('terminate',device,'com.whumpo.expomock');open_page('today');shot('06-today-200pct');open_page('more');shot('07-more-200pct');run('ui',device,'content_size','large');time.sleep(1)
scenes=[('reviews','reviews'),('approval-detail','approval-detail'),('website','website'),('activity','activity'),('organization','organization'),('account','account'),('members','members'),('pinned-tab','pinned-tab'),('reminders','approval-reminders'),('privacy','privacy'),('help','help'),('partner-composer','partner-composer'),('org-dropdown','org-dropdown-in-sidebar'),('approval-confirmation','approval-confirmation'),('about','about'),('appearance','appearance'),('checklist','checklist'),('review-changes','review-changes'),('quiet-hours','quiet-hours'),('sign-in-methods','sign-in-methods'),('work','work'),('project','project'),('partner-new','partner-empty'),('attachments','attachments'),('signout-confirmation','signout-confirmation'),('export-confirmation','export-confirmation'),('contact-support','contact-support'),('history-empty','history-empty'),('review-error','review-error'),('review-loading','review-loading'),('preview-states','preview-states')]
for i,(page,name) in enumerate(scenes,1):open_page(page);shot(f'L2-{i:02d}-{name}')
p=ROOT/'compare'/('v11-pass'+number)/'ios';p.mkdir(exist_ok=True,parents=True)
for f in out.glob('*.png'):shutil.copy2(f,p/f.name)
print('Captured iOS pass '+number,flush=True)
