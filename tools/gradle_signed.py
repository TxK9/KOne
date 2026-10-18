#!/usr/bin/env python3
"""Run Gradle in android/ with the existing authorized release signing, never printing a secret.

The signing values are read from a properties file outside the repository (storeFile,
storePassword, keyAlias, keyPassword) and handed to Gradle through the LOCAL_PROPERTIES
environment variable that app/build.gradle.kts reads. Nothing is written to disk.

Usage: tools/gradle_signed.py <logfile> <gradle args...>
Environment:
  KONE_SIGNING  required path to the authorized external signing properties
  KONE_JAVA_HOME  JDK used to run Gradle (falls back to JAVA_HOME)
  KONE_GRADLE_EXTRA  more Gradle arguments, one per line, put before the given ones. A sandboxed
      session without network or the system temp folder builds with
      "--offline" and "-Dorg.gradle.jvmargs=-Xmx8192m -Dfile.encoding=UTF-8
      -Djava.io.tmpdir=<dir> -Dorg.sqlite.tmpdir=<dir>" (Room's code generator needs the temp dir).
"""
import base64
import os
import subprocess
import sys
from pathlib import Path

ANDROID = Path(__file__).resolve().parent.parent / 'android'
signing_source = os.environ.get('KONE_SIGNING')
JAVA_HOME = os.environ.get('KONE_JAVA_HOME') or os.environ.get('JAVA_HOME')
if not signing_source or not JAVA_HOME:
    raise SystemExit('Set KONE_SIGNING and KONE_JAVA_HOME (or JAVA_HOME) before building.')
SIGNING = Path(signing_source).expanduser().resolve()

values = {}
for line in SIGNING.read_text().splitlines():
    if '=' in line and not line.lstrip().startswith('#'):
        key, value = line.split('=', 1)
        values[key.strip()] = value.strip()
key_file = (SIGNING.parent / values['storeFile']).resolve()
if not key_file.is_file():
    raise SystemExit('Signing key not found beside the configured properties file.')


def escaped(value):
    value = value.replace('\\', '\\\\').replace('\n', '\\n').replace('\r', '\\r')
    return ''.join('\\u%04x' % ord(c) if ord(c) > 127 else c for c in value)


local = '\n'.join([
    'KEYSTORE_PATH=' + escaped(str(key_file)),
    'KEYSTORE_PASS=' + escaped(values['storePassword']),
    'ALIAS_NAME=' + escaped(values['keyAlias']),
    'ALIAS_PASS=' + escaped(values['keyPassword']),
])
env = os.environ.copy()
env['JAVA_HOME'] = JAVA_HOME
env['LOCAL_PROPERTIES'] = base64.b64encode(local.encode()).decode()
log, args = sys.argv[1], sys.argv[2:]
extra = [line.strip() for line in os.environ.get('KONE_GRADLE_EXTRA', '').splitlines() if line.strip()]
with open(log, 'w') as out:
    result = subprocess.run(['./gradlew', *extra, *args, '--no-daemon'], cwd=ANDROID, env=env, stdout=out, stderr=subprocess.STDOUT)
raise SystemExit(result.returncode)
