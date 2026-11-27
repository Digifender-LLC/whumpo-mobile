"""Dispose only this preview's private devices/services and its own build caches."""
from pathlib import Path
import subprocess,os,signal,json,shutil,plistlib,time,socket
ROOT=Path(__file__).resolve().parents[1];os.chdir(ROOT);report={'project':str(ROOT),'actions':[]}
def record(s):report['actions'].append(s);print(s,flush=True)
def run(a,check=True):return subprocess.run(a,check=check,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT).stdout
# Native provenance must be finalized before removing installed applications/builds.
assert json.loads((ROOT/'artifacts/v11-native-provenance.json').read_text())['native_only']
adb=str(Path.home()/'Library/Android/sdk/platform-tools/adb')
run([adb,'-P','5619','connect','localhost:5621'])
restore=[['wm','size','reset'],['wm','density','reset']]
for key,scope in [('font_scale','system'),('animator_duration_scale','global'),('transition_animation_scale','global')]:
 value=(ROOT/'artifacts'/f'v11-initial-{key}.txt').read_text().strip()
 restore.append(['settings','delete',scope,key] if value=='null' else ['settings','put',scope,key,value])
for args in restore:
 run([adb,'-P','5619','connect','localhost:5621'])
 run([adb,'-P','5619','-s','localhost:5621','shell',*args])
report['android_restored']=run([adb,'-P','5619','-s','localhost:5621','shell','wm','size'])+run([adb,'-P','5619','-s','localhost:5621','shell','wm','density'])
report['android_settings_restored']={}
for key,scope in [('font_scale','system'),('animator_duration_scale','global'),('transition_animation_scale','global')]:
 actual=run([adb,'-P','5619','-s','localhost:5621','shell','settings','get',scope,key]).strip()
 expected=(ROOT/'artifacts'/f'v11-initial-{key}.txt').read_text().strip()
 assert actual==expected,(key,actual,expected)
 report['android_settings_restored'][key]=actual
record('Reset wm size/density and verified prior read-only AVD font/animation values')
run([adb,'-P','5619','-s','localhost:5621','uninstall','com.whumpo.expomock'])
import sys
sys.path.insert(0,str(ROOT/'scripts'))
from emulator_console import console
try:console('kill')
except (ConnectionResetError,BrokenPipeError):pass
record('Stopped own read-only emulator on console5620')
run([adb,'-P','5619','kill-server']);record('Stopped private adb5619')
uuid=(ROOT/'artifacts/ios-device.txt').read_text().strip()
run(['xcrun','simctl','shutdown',uuid],False);run(['xcrun','simctl','delete',uuid]);record('Shut down and deleted own disposable iOS simulator '+uuid)
# Only project-scoped Gradle daemons and exact project Watchman root.
run([str(ROOT/'android/gradlew'),'--stop'],False)
# Kotlin daemons are stopped only when their classpath is inside this project cache.
for line in run(['ps','-axo','pid=,command=']).splitlines():
 parts=line.strip().split(None,1)
 if len(parts)==2 and str(ROOT/'.native-cache') in parts[1] and 'KotlinCompileDaemon' in parts[1]:
  os.kill(int(parts[0]),signal.SIGTERM);record('Stopped own cached Kotlin daemon '+parts[0])
try:
 watches=json.loads(run(['watchman','--no-spawn','--no-local','watch-list'])).get('roots',[])
 if str(ROOT) in watches:run(['watchman','--no-spawn','--no-local','watch-del',str(ROOT)]);record('Removed exact project Watchman watch')
except (FileNotFoundError,json.JSONDecodeError,subprocess.CalledProcessError):pass
# Only Xcode DerivedData whose recorded workspace belongs to this exact project.
base=Path.home()/'Library/Developer/Xcode/DerivedData'
for f in base.glob('*/info.plist'):
 try:
  with f.open('rb') as h:info=plistlib.load(h)
  workspace=info.get('WorkspacePath','')
  if workspace.startswith(str(ROOT)+'/'):
   shutil.rmtree(f.parent);record('Removed own DerivedData '+f.parent.name)
 except (OSError,plistlib.InvalidFileException):pass
paths=['node_modules','.expo','.venv','.native-cache','.native-build','android/.gradle','android/.kotlin','android/build','android/app/build','android/app/.cxx','android/.cxx','ios/Pods','ios/build','screenshots/temp','scripts/__pycache__']
for rel in paths:
 path=ROOT/rel
 if path.exists():shutil.rmtree(path);record('Removed '+rel)
# No calls to the shared adb server, other emulator consoles, or global SDK caches.
time.sleep(2)
ports={}
for port in [5619,5620,5621,8619]:
 s=socket.socket();s.settimeout(.5);ports[str(port)]=s.connect_ex(('127.0.0.1',port))!=0;s.close()
assert all(ports.values()),ports
report['ports_closed']=ports
report['simulator_deleted']=uuid not in run(['xcrun','simctl','list','devices','--json'])
assert report['simulator_deleted']
report['disk']=run(['df','-h','/System/Volumes/Data'])
report['passed']=True
(ROOT/'artifacts/v11-cleanup.json').write_text(json.dumps(report,indent=2))
print('PASS private devices, ports and project caches cleaned')
