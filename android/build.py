"""Build the static files the Awai Android app ships (android/dist).

  python3 -I android/build.py

1. Resolve the app's ClojureScript classpath with tools.deps, reading the
   Kotoba libraries named in android/kotoba-overrides.edn from one checkout of
   the kotoba monorepo (see that file for why).
2. Stage the classpath with `.cljk` handed over as its original extension
   (android/stage.py).
3. Compile with shadow-cljs (`:simple` optimizations, the upstream release
   setting) into android/.build/web/js/main.js.
4. Copy public/index.html and main.js into android/dist and pin every file's
   sha256, the source commit, the Kotoba sha and the staged tree in release.json.

The app source (src/main, public/index.html) is used as is; nothing is edited.
"""
import hashlib, json, os, re, shutil, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
BUILD = os.path.join(HERE, '.build')
DIST = os.path.join(HERE, 'dist')
KOTOBA = 'https://github.com/kotoba-lang/kotoba.git'


def run(cmd, **kw):
    print('+', ' '.join(cmd) if isinstance(cmd, list) else cmd, flush=True)
    return subprocess.run(cmd, check=True, **kw)


def sha(path):
    return hashlib.sha256(open(path, 'rb').read()).hexdigest()


def overrides():
    text = open(os.path.join(HERE, 'kotoba-overrides.edn'), encoding='utf-8').read()
    m = re.search(r':kotoba-sha "([0-9a-f]{40})"', text)
    if not m:
        sys.exit('android/kotoba-overrides.edn has no :kotoba-sha')
    kotoba_sha = m.group(1)
    libs = dict(re.findall(r'^\s+([\w.-]+) "([\w.]+)"', text, re.M))
    return kotoba_sha, libs


def monorepo_checkout(kotoba_sha):
    # tools.deps checks the monorepo out under ~/.gitlibs when one lib from it is
    # requested; that checkout is then the single root for every override.
    seed = '{:deps {io.github.kotoba-lang/error {:git/url "%s" :git/sha "%s" :deps/root "lib/kotoba.error"}}}' % (KOTOBA, kotoba_sha)
    run(['clojure', '-Sdeps', seed, '-P'], cwd=BUILD)
    gitlibs = os.environ.get('GITLIBS', os.path.expanduser('~/.gitlibs'))
    path = os.path.join(gitlibs, 'libs', 'io.github.kotoba-lang', 'error', kotoba_sha)
    if not os.path.isdir(os.path.join(path, 'lib')):
        sys.exit('kotoba monorepo checkout not found at ' + path)
    head = subprocess.run(['git', '-C', path, 'rev-parse', 'HEAD'], capture_output=True, text=True).stdout.strip()
    if head and head != kotoba_sha:
        sys.exit('kotoba checkout is at %s, expected %s' % (head, kotoba_sha))
    return path


def classpath(mono, libs):
    od = ' '.join('io.github.kotoba-lang/%s {:local/root "%s/lib/%s"}' % (k, mono, v) for k, v in sorted(libs.items()))
    sdeps = '{:aliases {:awai-android {:override-deps {%s}}}}' % od
    cp = run(['clojure', '-Sdeps', sdeps, '-A:cljs:awai-android', '-Spath'], cwd=ROOT,
             capture_output=True, text=True).stdout.strip()
    path = os.path.join(BUILD, 'cp.txt')
    open(path, 'w').write(cp)
    return path


def shadow_build():
    stage = os.path.join(BUILD, 'stage')
    dirs = sorted('stage/' + d for d in os.listdir(stage))
    cfg = ('{:source-paths [%s]\n :dependencies [[re-frame "1.4.3"] [reagent "1.2.0"]]\n'
           ' :builds {:app {:target :browser :output-dir "web/js" :asset-path "js"\n'
           '                :modules {:main {:init-fn kami.app-suji.ui/init!}}\n'
           '                :release {:compiler-options {:optimizations :simple}}}}}\n') % ' '.join('"%s"' % d for d in dirs)
    open(os.path.join(BUILD, 'shadow-cljs.edn'), 'w').write(cfg)
    shutil.copyfile(os.path.join(ROOT, 'package.json'), os.path.join(BUILD, 'package.json'))
    link = os.path.join(BUILD, 'node_modules')
    if not os.path.lexists(link):
        os.symlink(os.path.join(ROOT, 'node_modules'), link)
    shutil.rmtree(os.path.join(BUILD, 'web'), ignore_errors=True)
    run(['npx', 'shadow-cljs', 'release', 'app'], cwd=BUILD)


def assemble(kotoba_sha):
    shutil.rmtree(DIST, ignore_errors=True)
    os.makedirs(os.path.join(DIST, 'js'))
    shutil.copyfile(os.path.join(ROOT, 'public', 'index.html'), os.path.join(DIST, 'index.html'))
    shutil.copyfile(os.path.join(BUILD, 'web', 'js', 'main.js'), os.path.join(DIST, 'js', 'main.js'))
    stage = json.load(open(os.path.join(BUILD, 'stage-manifest.json')))
    commit = subprocess.run(['git', '-C', ROOT, 'rev-parse', 'HEAD'], capture_output=True, text=True).stdout.strip()
    dirty = subprocess.run(['git', '-C', ROOT, 'status', '--porcelain', '--', 'src', 'public', 'deps.edn'],
                           capture_output=True, text=True).stdout.strip()
    files = {n: sha(os.path.join(DIST, n)) for n in ('index.html', 'js/main.js')}
    release = {'schema': 'awai.app.artifact.v1', 'id': 'network.awai.suji', 'version': '0.1.0',
               'host': 'awai.shell.host.v1', 'tauri': '3.0.0-alpha.6', 'externalOperations': 0,
               'source': {'repository': 'network-awai/app-itonami-suji', 'commit': commit,
                          'appSourceClean': dirty == '', 'kotobaSha': kotoba_sha,
                          'stagedTreeSha256': stage['stagedTreeSha256']},
               'files': files}
    json.dump(release, open(os.path.join(DIST, 'release.json'), 'w'), indent=2)
    print('dist:', files)


if __name__ == '__main__':
    os.makedirs(BUILD, exist_ok=True)
    if not os.path.isdir(os.path.join(ROOT, 'node_modules')):
        run(['npm', 'ci'], cwd=ROOT)
    kotoba_sha, libs = overrides()
    mono = monorepo_checkout(kotoba_sha)
    cp = classpath(mono, libs)
    run([sys.executable, '-I', os.path.join(HERE, 'stage.py'), cp])
    shadow_build()
    assemble(kotoba_sha)
