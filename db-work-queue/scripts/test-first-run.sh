#!/usr/bin/env bash
# Requires Python 3 for process/signal assertions; the first-run command itself does not.
set -euo pipefail
python3 - "$(cd "$(dirname "$0")" && pwd)/first-run.sh" <<'PY'
import os, pathlib, shutil, signal, subprocess, sys, tempfile, time
source = pathlib.Path(sys.argv[1])
assert source.is_file(), 'first-run.sh is not implemented'
with tempfile.TemporaryDirectory(prefix='workqueue-shell-test-') as directory:
    root = pathlib.Path(directory)
    (root/'scripts').mkdir(); (root/'work-queue-demo').mkdir(); (root/'bin').mkdir()
    shutil.copy2(source, root/'scripts/first-run.sh')
    (root/'work-queue-demo/compose.yml').touch()
    (root/'work-queue-demo/.env').write_text('DB2INST1_PASSWORD=' + 'a'*48 + '\n')
    stub = '''#!/usr/bin/env python3
import os, pathlib, signal, sys, time
root=pathlib.Path(os.environ['FIXTURE']); args=sys.argv[1:]; name=pathlib.Path(sys.argv[0]).name
with (root/'calls').open('a') as f: f.write(name+' '+repr(args)+'\\n')
case=os.environ.get('CASE','ok')
if name=='mvnw': sys.exit(7 if case=='build' else 0)
if name=='docker':
    if 'exec' in args: sys.exit(8 if case=='readiness' else 0)
    if 'up' in args and case=='up': sys.exit(9)
    sys.exit(0)
if args==['-version']: print('openjdk version "25.0.4"', file=sys.stderr); sys.exit(0)
mode=next(a.split('=',1)[1] for a in args if a.startswith('--demo.mode='))
if case==mode: sys.exit(10)
if mode=='seed':
    path=pathlib.Path(next(a.split('=',1)[1] for a in args if a.startswith('--demo.batch-file=')))
    path.write_text('1\\n2\\n')
    with (root/'batches').open('a') as f: f.write(str(path)+'\\n')
if mode=='worker':
    (root/'worker.pid').write_text(str(os.getpid()))
    signal.signal(signal.SIGTERM, lambda *args: sys.exit(0))
    while True: time.sleep(.1)
if mode=='verify':
    if case in ('signal', 'worker'): time.sleep(60)
    print('total=2 done=2 failed=0 pending=0 claimed=0 missing=0')
'''
    for name in ('java','docker','mvnw'):
        path=(root if name=='mvnw' else root/'bin')/name; path.write_text(stub); path.chmod(0o755)
    env=dict(os.environ, PATH=str(root/'bin')+os.pathsep+os.environ['PATH'], FIXTURE=str(root), FIRST_RUN_DB_TIMEOUT_SECONDS='1', SPRING_DATASOURCE_URL='jdbc:unsafe:inherited')
    def alive(pid):
        try: os.kill(pid,0); return True
        except ProcessLookupError: return False
    def check_cleanup():
        pid=root/'worker.pid'
        if pid.exists(): assert not alive(int(pid.read_text())), 'left worker running'
    for case in ('ok','ok','build','up','readiness','migrate','seed','worker','verify'):
        (root/'calls').write_text('')
        result=subprocess.run(['bash',str(root/'scripts/first-run.sh')],cwd='/',env=dict(env,CASE=case),capture_output=True,text=True,timeout=12)
        assert (result.returncode==0)==(case=='ok'), (case,result.returncode,result.stdout,result.stderr)
        check_cleanup()
        calls=(root/'calls').read_text()
        assert 'a'*48 not in calls+result.stdout+result.stderr, 'credential leak'
        assert '--spring.datasource.url=jdbc:db2://127.0.0.1:50000/WORKQ' in calls or case in ('build','up','readiness')
        assert 'clean' not in calls and 'DELETE' not in calls and '--volumes' not in calls
        if case=='build': assert "'up'" not in calls
        if case=='ok':
            assert calls.index("mvnw ") < calls.index("'up'") < calls.index('--demo.mode=migrate') < calls.index('--demo.mode=seed') < min(calls.index('--demo.mode=worker'), calls.index('--demo.mode=verify'))
    batches=(root/'batches').read_text().splitlines()
    assert len(set(batches))==len(batches), 'batch files reused'
    unrelated=subprocess.Popen(['sleep','120'])
    try:
        for sig in (signal.SIGTERM,signal.SIGINT):
            (root/'calls').write_text('')
            proc=subprocess.Popen(['bash',str(root/'scripts/first-run.sh')],env=dict(env,CASE='signal'),stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
            deadline=time.monotonic()+10
            while '--demo.mode=verify' not in (root/'calls').read_text():
                assert proc.poll() is None and time.monotonic()<deadline
                time.sleep(.05)
            proc.send_signal(sig); out,err=proc.communicate(timeout=8)
            assert proc.returncode==128+sig, (sig,proc.returncode,out,err)
            check_cleanup(); assert unrelated.poll() is None
    finally:
        unrelated.terminate(); unrelated.wait()
print('First-run harness: 11 scenarios passed (including repeat run, failures, SIGINT/SIGTERM, unrelated process).')
PY
