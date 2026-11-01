"""Verify the existing wrapper's checksum guard with an official local archive.

The archive digest must match the reviewed repository pin. Isolated wrapper copies
test rejection and acceptance without modifying the checkout or using shared caches.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('archive', type=Path)
args = parser.parse_args()
repo = Path(__file__).resolve().parents[1]
properties = (repo / 'android/gradle/wrapper/gradle-wrapper.properties').read_text()
expected = re.search(r'^distributionSha256Sum=([0-9a-f]{64})$', properties, re.M).group(1)
with args.archive.open('rb') as source:
    actual = hashlib.file_digest(source, 'sha256').hexdigest()
assert actual == expected, 'Select the reviewed official distribution archive'

with tempfile.TemporaryDirectory(prefix='kone-wrapper-checksum-') as temporary:
    work = Path(temporary)
    shutil.copy2(repo / 'android/gradlew', work / 'gradlew')
    target = work / 'gradle/wrapper'
    target.mkdir(parents=True)
    shutil.copy2(repo / 'android/gradle/wrapper/gradle-wrapper.jar', target / 'gradle-wrapper.jar')
    for name, checksum in [('reject-mismatch', '0' * 64), ('accept-reviewed', expected)]:
        text = re.sub(r'^distributionUrl=.*$', lambda _: 'distributionUrl=' + args.archive.resolve().as_uri(), properties, flags=re.M)
        text = re.sub(r'^distributionSha256Sum=.*$', 'distributionSha256Sum=' + checksum, text, flags=re.M)
        (target / 'gradle-wrapper.properties').write_text(text)
        result = subprocess.run(['bash', 'gradlew', '--version'], cwd=work,
            env={**os.environ, 'GRADLE_USER_HOME': str(work / name)},
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=120)
        if name == 'reject-mismatch':
            assert result.returncode != 0, 'Mismatched checksum was accepted'
            assert 'verification of gradle distribution failed' in result.stdout.lower(), result.stdout
            assert checksum in result.stdout and actual in result.stdout
        else:
            assert result.returncode == 0, result.stdout
            assert 'Gradle 9.7.0' in result.stdout
        print(json.dumps({'case': name, 'child_exit_status': result.returncode, 'passed': True}))
print('PASS wrapper checksum rejection and reviewed archive acceptance')
