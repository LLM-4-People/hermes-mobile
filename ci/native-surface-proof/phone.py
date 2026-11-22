from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import contextlib,hashlib,json,re,subprocess,sys,threading,time,tarfile,io,urllib.parse
import os
dest=Path(os.environ["RUNNER_TEMP"]) / "native-surface-proof"
dest.mkdir(exist_ok=True)
app="org.llm4people.polishprobe"
serial=subprocess.check_output(["adb","get-serialno"],text=True).strip()
assert serial.startswith("emulator-") and os.environ.get("GITHUB_ACTIONS")=="true"
adb=["adb","-s",serial]
def run(*args):return subprocess.check_output(adb+list(args),timeout=230)
def sh(*args):return run('shell',*args).decode().strip()
def state():
    data={'user':sh('am','get-current-user'),'users':sh('pm','list','users'),'size':sh('wm','size'),'density':sh('wm','density'),'rotation':sh('wm','user-rotation'),'user_rotation':sh('settings','get','system','user_rotation'),'font':sh('settings','get','system','font_scale'),'night':sh('cmd','uimode','night'),'keyboard':sh('settings','get','secure','show_ime_with_hard_keyboard')}
    return data
def restore_setting(key,val):
    if val=='null':sh('settings','delete','system',key)
    else:sh('settings','put','system',key,val)
def phone_size(mode):
    w,h=(1200,720) if mode=='large' else (640,320) if mode=='paysage' else (320,640);r='1' if mode in ('large','paysage') else '0'
    sh('wm','size',f'{min(w,h)}x{max(w,h)}');sh('wm','density','160')
    sh('settings','put','system','user_rotation',r);sh('wm','user-rotation','lock',r)
    sh('cmd','uimode','night','yes' if mode in ('nuit','paysage') else 'no')
    sh('settings','put','system','font_scale','1.5' if mode in ('nuit','paysage') else '1.0')
    end=time.monotonic()+25
    while ('Override size: '+f'{min(w,h)}x{max(w,h)}') not in sh('wm','size') or 'Override density: 160' not in sh('wm','density'):
        if time.monotonic()>end:raise ValueError('viewport')
        time.sleep(.15)
class Gateway(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def reply(self,status,data):
        b=json.dumps(data).encode();self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
    def do_GET(self):
        if self.path=='/__proof/ime':
            window=sh('dumpsys','window','displays')
            match=re.search(r'type=ime frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\][^\n]*? visible=true',window)
            return self.reply(200,{'top':int(match[2]),'bottom':int(match[4])} if match else {'top':0,'bottom':0})
        if self.headers.get('Authorization')!='Bearer fixture':return self.reply(401,{})
        path=urllib.parse.urlsplit(self.path).path
        if path=='/v1/capabilities': data={'features':{'run_tool_exclusions':True,'run_toolset_selection':True}}
        elif path=='/v1/toolsets':data={'data':[{'enabled':True,'name':'Fichiers','tools':['read_file','write_file']},{'enabled':True,'name':'Recherche','tools':['search_files']}]}
        elif path=='/api/model/options':data={'providers':[{'slug':'atelier','name':'Atelier','models':['Relecture locale','Rédaction'],'authenticated':True},{'slug':'local','name':'Personnel','models':['Modèle personnel'],'authenticated':True},{'slug':'custom-local-research-team-openai-compatible-staging','name':'Atelier de développement et d’assistance pour les équipes de recherche et de documentation','models':['Analyse locale'],'authenticated':True}]}
        elif path.endswith('/messages'): data={'messages':[],'data':[],'pagination':{'total':0}}
        elif path=='/api/sessions':data={'data':[],'has_more':False}
        elif path.startswith('/api/sessions/'):data={'session':{'id':path.rsplit('/',1)[1],'title':'Relecture de remise.py','source':'api_server','message_count':0}}
        else:data={'data':[]}
        return self.reply(200,data)
    def do_POST(self):
        if self.headers.get('Authorization')!='Bearer fixture' or self.path!='/api/sessions':return self.reply(403,{})
        data=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        return self.reply(201,{'session':{'id':data['id'],'source':'api_server','title':'Relecture de remise.py','message_count':0}})
base=Path(os.environ["RUNNER_TEMP"]) / "native-surface-inputs"
target=base/"app-debug.apk"
test=base/"app-debug-androidTest.apk"
out=dest
before=state();(out/"state-before.json").write_text(json.dumps(before,indent=2))
assert before["user"]=="0"
assert not sh("pm","list","packages",app)
assert sh("settings","get","global","hide_error_dialogs") in ("0","null")
g=ThreadingHTTPServer(('127.0.0.1',0),Gateway);threading.Thread(target=g.serve_forever,daemon=True).start()
port=str(g.server_address[1]);rev='tcp:'+port;report={}
with contextlib.ExitStack() as stack:
    stack.callback(g.server_close); stack.callback(g.shutdown)
    run('reverse','--no-rebind',rev,rev);stack.callback(run,'reverse','--remove',rev)
    if before['keyboard']!='1':
        sh('settings','put','secure','show_ime_with_hard_keyboard','1')
        if before['keyboard']=='null':stack.callback(sh,'settings','delete','secure','show_ime_with_hard_keyboard')
        else:stack.callback(sh,'settings','put','secure','show_ime_with_hard_keyboard',before['keyboard'])
    for name,apk in ((app,target),(app+'.test',test)):
        assert b'Success' in run('install','-t',str(apk));stack.callback(run,'uninstall',name)
    stack.callback(restore_setting,'user_rotation',before['user_rotation'])
    stack.callback(sh,'wm','user-rotation',*before['rotation'].split())
    for setting in ['density','size']:
        old=re.search(r'^Override (?:size|density): ([\dx]+)$',before[setting],re.M)
        stack.callback(sh,'wm',setting,old.group(1) if old else 'reset')
    stack.callback(restore_setting,'font_scale',before['font']);stack.callback(sh,'cmd','uimode','night',before['night'].split()[-1])
    if 'storage' in sys.argv[2:]:
        full='org.llm4people.hermes.profile.SessionSettingsStorageTest#testRejectedModelChoiceRestoresTheRadioAndCanBeRetriedThroughSearch'
        logs=run('shell','am','instrument','-w','-r','-e','class',full,app+'.test/android.test.InstrumentationTestRunner').decode()
        (out/'storage.log').write_text(logs)
        report['storage']='OK (1 test)' in logs;print('storage',report['storage'],flush=True)
        geometry='org.llm4people.hermes.profile.SessionSettingsStorageTest#testSearchAndFilteredModelRemainReachableInBothCompactKeyboardViewports'
        logs=run('shell','am','instrument','-w','-r','-e','class',geometry,app+'.test/android.test.InstrumentationTestRunner').decode()
        (out/'native-viewports.log').write_text(logs);report['native_viewports']='OK (1 test)' in logs;print('native_viewports',report['native_viewports'],flush=True)
    for mode in [v for v in sys.argv[2:] if v!='storage']:
        # Apply the requested Android display configuration to the isolated native app itself.
        # This avoids resizing the system home launcher between independent capture activities.
        run('shell','am','start','-W','-n',app+'/org.llm4people.hermes.MainActivity')
        phone_size(mode);print('stage',mode,flush=True)
        result=run('shell','am','instrument','-w','-r','-e','class','org.llm4people.hermes.journey.PolishAdverseTest','-e','mode',mode,'-e','server','http://127.0.0.1:'+port,app+'.test/org.llm4people.hermes.journey.PolishCaptureRunner').decode()
        (out/(mode+'.log')).write_text(result)
        for filename in sh('run-as',app,'ls','cache/captures').splitlines():
            if re.fullmatch(mode+r'(\.json|-[\w-]+\.png)',filename):
                (out/filename).write_bytes(run('exec-out','run-as',app,'cat','cache/captures/'+filename))
        print(mode,[(f.name,f.stat().st_size) for f in sorted(out.glob(mode+'-*png'))],flush=True)
        report[mode]='OK (1 test)' in result
        if not report[mode]:print(result[-950:],flush=True)
for attempt in range(4):
    sh('wm','user-rotation',*before['rotation'].split())
    time.sleep(.25)
    restore_setting('user_rotation',before['user_rotation'])
    time.sleep(.45)
    if sh('wm','user-rotation')==before['rotation'] and sh('settings','get','system','user_rotation')==before['user_rotation']:
        break
after=state()
if after!=before:
    time.sleep(.6);after=state()
(out/'state-after.json').write_text(json.dumps(after,indent=2))
assert before==after, {k:(before[k],after[k]) for k in before if before[k]!=after[k]}
meta={'production_source':sys.argv[1],'variant':'real production source/resources; isolated app ID, local test signing, Android test-only synthetic account, loopback settings and synthetic current-run prompts','physical_api':sh('getprop','ro.build.version.sdk'),'original_display_settings_restored':True,'both_lab_packages_removed':not bool(sh('pm','list','packages',app)),'input_method_capture':'actual visible IME inset; PNG draws secure synthetic app-owned windows only','modes':report,'app_sha256':hashlib.file_digest(target.open('rb'),'sha256').hexdigest(),'test_sha256':hashlib.file_digest(test.open('rb'),'sha256').hexdigest()}
(out/'receipt.json').write_text(json.dumps(meta,indent=2)+'\n')
assert all(report.values())
for mode in ('paysage','nuit'):
    if mode not in report: continue
    metrics=json.loads((out/(mode+'.json')).read_text())
    assert metrics['Relecture locale above keyboard']>=48 and metrics['Analyse locale above keyboard']>=48,metrics
    assert metrics['rejectedAttemptWithIme'] is True and metrics['retriedSelection'] is True,metrics
assert sh("settings","get","global","hide_error_dialogs") in ("0","null")
