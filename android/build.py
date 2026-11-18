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
    # android/src holds the bot/app shared code (awai.suji.*); it is staged too.
    cp = cp + ':' + os.path.join(HERE, 'src') + ':' + os.path.join(HERE, 'test')
    path = os.path.join(BUILD, 'cp.txt')
    open(path, 'w').write(cp)
    return path


def suji_commit():
    text = open(os.path.join(ROOT, 'deps.edn'), encoding='utf-8').read()
    m = re.search(r'io\.github\.cloud-itonami/suji\s*\{[^}]*?:git/sha "([0-9a-f]{40})"', text, re.S)
    if not m:
        sys.exit('deps.edn has no pinned cloud-itonami/suji sha')
    return m.group(1)


def shadow_build(suji):
    stage = os.path.join(BUILD, 'stage')
    dirs = sorted('stage/' + d for d in os.listdir(stage))
    defines = '{awai.suji.host/SUJI_COMMIT "%s" awai.suji.results/SUJI_COMMIT "%s"}' % (suji, suji)
    release = '{:compiler-options {:optimizations :simple}}'
    cfg = ('{:source-paths [%s]\n :dependencies [[re-frame "1.4.3"] [reagent "1.2.0"]]\n'
           ' :builds {:app {:target :browser :output-dir "web/js" :asset-path "js"\n'
           '                :modules {:main {:init-fn kami.app-suji.ui/init!}} :release %s}\n'
           '          :results {:target :browser :output-dir "web/results-js" :asset-path "results-js"\n'
           '                    :modules {:results {:init-fn awai.suji.results/init!}}\n'
           '                    :closure-defines %s :release %s}\n'
           '          :host {:target :node-script :main awai.suji.host/main :output-to "host/host.js"\n'
           '                 :closure-defines %s :release %s}\n'
           '          :test {:target :node-test :output-to "test/test.js" :ns-regexp "^awai\\\\.suji\\\\..*-test$"\n'
           '                 :release %s}}}\n') % (
        ' '.join('"%s"' % d for d in dirs), release, defines, release, defines, release, release)
    open(os.path.join(BUILD, 'shadow-cljs.edn'), 'w').write(cfg)
    shutil.copyfile(os.path.join(ROOT, 'package.json'), os.path.join(BUILD, 'package.json'))
    link = os.path.join(BUILD, 'node_modules')
    if not os.path.lexists(link):
        os.symlink(os.path.join(ROOT, 'node_modules'), link)
    for d in ('web', 'host', 'test'):
        shutil.rmtree(os.path.join(BUILD, d), ignore_errors=True)
    run(['npx', 'shadow-cljs', 'release', 'app', 'results', 'host', 'test'], cwd=BUILD)
    run(['node', os.path.join(BUILD, 'test', 'test.js')], cwd=BUILD)


RESULTS_LINK = ('<nav class="awai-suji-bot-link" aria-label="bot">'
                '<a class="dads-link" href="results.html">bot の結果を開く</a></nav>\n    ')
RESULTS_CSS = ('<style>.awai-suji-bot-link{display:flex;justify-content:flex-end;'
               'padding:8px 16px 0}</style>\n  </head>')


def page_variants(index):
    """index.html gains one link to the results page (outside #app, so React
    never touches it); results.html is the same document shell with the results
    module instead of the simulator."""
    for needle in ('<div id="app">', '<script src="js/main.js"></script>', '</head>', '<title>'):
        if index.count(needle) != 1:
            sys.exit('public/index.html: expected exactly one %r' % needle)
    linked = index.replace('</head>', RESULTS_CSS).replace('<div id="app">', RESULTS_LINK + '<div id="app">')
    m = re.search(r'<title>[^<]*</title>', index)
    if not m:
        sys.exit('public/index.html: no <title>')
    title = m.group(0)
    results = (index.replace(title, '<title>bot の結果 — 筋 suji</title>')
               .replace('<script src="js/main.js"></script>', '<script src="results-js/results.js"></script>'))
    results = re.sub(r'<noscript>.*?</noscript>', '<noscript>この画面は JavaScript で結果を計算し直します。</noscript>', results, flags=re.S)
    return linked, results


def assemble(kotoba_sha, suji):
    shutil.rmtree(DIST, ignore_errors=True)
    os.makedirs(os.path.join(DIST, 'js'))
    os.makedirs(os.path.join(DIST, 'results-js'))
    index = open(os.path.join(ROOT, 'public', 'index.html'), encoding='utf-8').read()
    linked, results = page_variants(index)
    open(os.path.join(DIST, 'index.html'), 'w', encoding='utf-8').write(linked)
    open(os.path.join(DIST, 'results.html'), 'w', encoding='utf-8').write(results)
    shutil.copyfile(os.path.join(BUILD, 'web', 'js', 'main.js'), os.path.join(DIST, 'js', 'main.js'))
    shutil.copyfile(os.path.join(BUILD, 'web', 'results-js', 'results.js'), os.path.join(DIST, 'results-js', 'results.js'))
    stage = json.load(open(os.path.join(BUILD, 'stage-manifest.json')))
    commit = subprocess.run(['git', '-C', ROOT, 'rev-parse', 'HEAD'], capture_output=True, text=True).stdout.strip()
    dirty = subprocess.run(['git', '-C', ROOT, 'status', '--porcelain', '--', 'src', 'public', 'deps.edn'],
                           capture_output=True, text=True).stdout.strip()
    files = {n: sha(os.path.join(DIST, n)) for n in ('index.html', 'results.html', 'js/main.js', 'results-js/results.js')}
    release = {'schema': 'awai.app.artifact.v1', 'id': 'network.awai.suji', 'version': '0.1.0',
               'host': 'awai.shell.host.v1', 'tauri': '3.0.0-alpha.6', 'externalOperations': 0,
               'source': {'repository': 'network-awai/app-itonami-suji', 'commit': commit,
                          'appSourceClean': dirty == '', 'kotobaSha': kotoba_sha,
                          'stagedTreeSha256': stage['stagedTreeSha256'],
                          'suji': {'repository': 'cloud-itonami/suji', 'commit': suji}},
               'files': files}
    json.dump(release, open(os.path.join(DIST, 'release.json'), 'w'), indent=2)
    print('dist:', files)
    print('host:', os.path.join(BUILD, 'host', 'host.js'), sha(os.path.join(BUILD, 'host', 'host.js')))


if __name__ == '__main__':
    os.makedirs(BUILD, exist_ok=True)
    if not os.path.isdir(os.path.join(ROOT, 'node_modules')):
        run(['npm', 'ci'], cwd=ROOT)
    kotoba_sha, libs = overrides()
    mono = monorepo_checkout(kotoba_sha)
    cp = classpath(mono, libs)
    run([sys.executable, '-I', os.path.join(HERE, 'stage.py'), cp])
    suji = suji_commit()
    shadow_build(suji)
    assemble(kotoba_sha, suji)
