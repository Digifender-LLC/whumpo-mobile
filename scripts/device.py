import os,subprocess,time,re,shlex,urllib.parse,xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
os.environ['ANDROID_ADB_SERVER_PORT']='5619'
ADB=str(Path.home()/'Library/Android/sdk/platform-tools/adb')
def adb(*args):
 for attempt in range(4):
  try:
   subprocess.run([ADB,'-P','5619','connect','localhost:5621'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=10)
   return subprocess.check_output([ADB,'-P','5619','-s','localhost:5621',*args],text=True,stderr=subprocess.STDOUT,timeout=30)
  except (subprocess.CalledProcessError,subprocess.TimeoutExpired):
   if attempt==3:raise
   time.sleep(.5)
def tap(x,y):adb('shell','input','tap',str(x),str(y));time.sleep(.5)
def nodes():
 for attempt in range(4):
  try:
   adb('shell','rm','-f','/sdcard/whumpo-expo-v11.xml')
   result=adb('shell','uiautomator','dump','/sdcard/whumpo-expo-v11.xml')
   if 'dumped to:' not in result:raise RuntimeError('UIAutomator did not write a fresh tree: '+result)
   return list(ET.fromstring(adb('shell','cat','/sdcard/whumpo-expo-v11.xml')).iter('node'))
  except (subprocess.CalledProcessError,RuntimeError,ET.ParseError):
   if attempt==3:raise
   time.sleep(1)
def find(text,exact=False):
 for n in nodes():
  if any(v==text if exact else text in v for v in [n.get('text',''),n.get('content-desc','')]):return n
 return None
def click(text,exact=False):
 matches=[n for n in nodes() if any(v==text if exact else text in v for v in [n.get('text',''),n.get('content-desc','')])]
 matches.sort(key=lambda n:n.get('clickable')!='true')
 n=matches[0] if matches else None;assert n is not None,'Missing '+text
 x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')));tap((x1+x2)//2,(y1+y2)//2)
APP_ID='com.whumpo.expomock'
def launch(path='today',restart=False):
 if restart:adb('shell','am','force-stop',APP_ID)
 adb('shell','am','start','-n',APP_ID+'/.MainActivity');time.sleep(.5)
 adb('shell','am','start','-a','android.intent.action.VIEW','-d','exp+whumpo-expo-mock://?__expo_url=http%3A%2F%2F10.0.2.2%3A8619',APP_ID);time.sleep(1)
 for attempt in range(30):
  ns=nodes()
  dismiss=next((n for n in ns if n.get('text')=='Got it'),None)
  if dismiss is not None:
   x1,y1,x2,y2=map(int,re.findall(r'\d+',dismiss.get('bounds')));tap((x1+x2)//2,(y1+y2)//2);continue
  if any(n.get('text')=='Development Build' for n in ns):
   recent=next((n for n in ns if n.get('text')=='http://10.0.2.2:8619'),None)
   if recent is not None:
    x1,y1,x2,y2=map(int,re.findall(r'\d+',recent.get('bounds')));tap((x1+x2)//2,(y1+y2)//2)
   else:adb('shell','am','start','-a','android.intent.action.VIEW','-d','exp+whumpo-expo-mock://?__expo_url=http%3A%2F%2F10.0.2.2%3A8619',APP_ID)
   time.sleep(2);continue
  if any(n.get('content-desc')=='Open Partner history' for n in ns):break
  time.sleep(1)
 else:raise AssertionError('Native Whumpo app did not become ready')
 click('Today',True)
 if path!='today':click({'more':'More','partner':'Partner','settings':'More'}[path],True)
 if path=='settings':click('Open settings',True)
 assert re.search(r'(?:topResumedActivity|ResumedActivity)[^\n]*com\.whumpo\.expomock',adb('shell','dumpsys','activity','activities')), 'Native app must be foreground'

def swipe():adb('shell','input','swipe','520','1740','520','650','400');time.sleep(.4)
def back():adb('shell','input','keyevent','4');time.sleep(.4)
def shot(path):
 time.sleep(.9) # Record a settled state after Android ripple/keyboard transitions.
 from PIL import Image
 out=ROOT/'screenshots/temp';out.mkdir(exist_ok=True)
 p=subprocess.check_output([str(ROOT/'.venv/bin/python'),str(ROOT/'scripts/emu_shot.py'),'5620',str(out)],text=True).strip();im=Image.open(p)
 if im.size==(1080,2400):im=im.crop((0,30,1080,2370))
 assert im.size==(1080,2340),im.size
 im.save(path);Path(p).unlink()
 print('Captured '+str(path),flush=True)
 if Path(path).name in ['02-more.png','07-more-200pct.png']:
  assert sum(n for n,c in im.convert('RGB').getcolors(im.width*im.height) if c==(48,48,48))>200000, 'More capture must contain the native tile grid'
