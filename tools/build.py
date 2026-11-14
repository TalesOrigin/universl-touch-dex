#!/usr/bin/env python3
"""Compile plain Java to a single DEX. Never invokes an APK packaging tool."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent


def run(args):
    subprocess.run([str(a) for a in args], cwd=ROOT, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', default=os.getenv('ANDROID_SDK_ROOT') or os.getenv('ANDROID_HOME'))
    parser.add_argument('--build-tools', help='Android build-tools version (otherwise newest installed)')
    parser.add_argument('--test', action='store_true', help='Run JVM tests only; no Android SDK needed')
    args = parser.parse_args()
    java_home = os.getenv('JAVA_HOME')
    javac = str(Path(java_home) / 'bin/javac') if java_home else shutil.which('javac')
    java = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
    if not javac or not java:
        parser.error('JDK 11+ required: set JAVA_HOME or add java and javac to PATH')
    build = ROOT / 'build'
    classes = build / ('test-classes' if args.test else 'classes')
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir(parents=True)
    sources = sorted((ROOT / 'src/main/java').rglob('*.java'))
    if args.test:
        sources += sorted((ROOT / 'src/test/java').rglob('*.java'))
    headers = build / 'jni'
    headers.mkdir(parents=True, exist_ok=True)
    run([javac, '--release', '8', '-encoding', 'UTF-8', '-h', headers, '-d', classes, *sources])
    if args.test:
        run([java, '-cp', classes, 'io.universl.touch.TouchDexTest'])
        return
    if not args.sdk:
        parser.error('Set ANDROID_SDK_ROOT or pass --sdk (Android SDK build-tools required)')
    tools = Path(args.sdk) / 'build-tools'
    if args.build_tools:
        d8jar = tools / args.build_tools / 'lib/d8.jar'
    else:
        candidates = list(tools.glob('*/lib/d8.jar'))
        if not candidates:
            parser.error('No build-tools/*/lib/d8.jar found; install Android SDK build-tools')
        import re
        d8jar = max(candidates, key=lambda p: tuple(int(n) for n in re.findall(r'\d+', p.parents[1].name)))
    if not d8jar.is_file():
        parser.error('D8 not found: ' + str(d8jar))
    output = build / 'dex'
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)
    run([java, '-cp', d8jar, 'com.android.tools.r8.D8', '--release', '--min-api', '21',
         '--output', output, *sorted(classes.rglob('*.class'))])
    dex = output / 'classes.dex'
    if not dex.is_file() or dex.read_bytes()[:4] != b'dex\n':
        raise RuntimeError('D8 did not produce a valid DEX header')
    print('DEX:', dex)
    print('JNI headers:', headers)


if __name__ == '__main__':
    try:
        main()
    except (subprocess.CalledProcessError, OSError, RuntimeError) as exc:
        print('Build failed:', exc, file=sys.stderr)
        sys.exit(1)
