"""Stage the app's source classpath for the ClojureScript build.

The Kotoba sources use the `.cljk` extension, which the ClojureScript compiler
does not read. Per the workspace rule, a `.cljk` file is handed to a
JVM/cljs build as the extension it had before the rename: the nearest
`cljk-origin.edn` names it, and `.cljc` is the default. Nothing is edited; files
are copied byte for byte into android/.build/stage/<n>/, and a manifest records
every source directory, its renames and a digest of the staged tree.

Usage: python3 -I android/stage.py <classpath-file>
"""
import hashlib, json, os, re, shutil, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'android', '.build', 'stage')
EXT = ('.cljk', '.cljc', '.cljs', '.clj', '.edn', '.js', '.json', '.wgsl', '.css')


def origin_map(start):
    d = start
    while True:
        f = os.path.join(d, 'cljk-origin.edn')
        if os.path.isfile(f):
            text = open(f, encoding='utf-8').read()
            return d, dict(re.findall(r'"([^"]+\.cljk)"\s+"(\.[a-z]+)"', text))
        parent = os.path.dirname(d)
        if parent == d:
            return start, {}
        d = parent


def stage(entries):
    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(OUT)
    manifest, h = [], hashlib.sha256()
    for n, src in enumerate(entries):
        src = src if os.path.isabs(src) else os.path.join(ROOT, src)
        if not os.path.isdir(src):
            continue
        base, origins = origin_map(src)
        dst = os.path.join(OUT, '%03d' % n)
        renames = 0
        for dirpath, _, files in os.walk(src):
            for name in sorted(files):
                if not name.endswith(EXT):
                    continue
                path = os.path.join(dirpath, name)
                rel = os.path.relpath(path, src)
                if name.endswith('.cljk'):
                    ext = origins.get(os.path.relpath(path, base), '.cljc')
                    if ext not in ('.cljc', '.cljs', '.clj'):
                        ext = '.cljc'
                    rel = rel[:-5] + ext
                    renames += 1
                out = os.path.join(dst, rel)
                if os.path.exists(out):   # a real .cljc/.cljs already holds this name
                    continue
                os.makedirs(os.path.dirname(out), exist_ok=True)
                shutil.copyfile(path, out)
                h.update(rel.encode() + b'\0' + open(out, 'rb').read())
        manifest.append({'source': os.path.relpath(src, ROOT) if src.startswith(ROOT) else src.replace(os.path.expanduser('~'), '~'),
                         'staged': os.path.relpath(dst, ROOT), 'renamedCljk': renames})
    return manifest, h.hexdigest()


if __name__ == '__main__':
    entries = [e for e in open(sys.argv[1]).read().strip().split(':') if e and not e.endswith('.jar')]
    manifest, digest = stage(entries)
    with open(os.path.join(ROOT, 'android', '.build', 'stage-manifest.json'), 'w') as f:
        json.dump({'stagedTreeSha256': digest, 'entries': manifest}, f, indent=1)
    print('staged', len(manifest), 'source dirs, tree sha256', digest)
