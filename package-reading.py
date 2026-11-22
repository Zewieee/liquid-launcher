"""Package pinned Kuromoji Java resources where Android's class loader can read them."""
import hashlib
import json
from pathlib import Path
import sys
import zipfile

root = Path(__file__).resolve().parent
checksums = json.loads((root / 'libs/checksums.json').read_text(encoding='utf-8'))
for name, expected in checksums.items():
    assert hashlib.sha256((root / 'libs' / name).read_bytes()).hexdigest() == expected, name

if len(sys.argv) > 1:
    with zipfile.ZipFile(root / sys.argv[1], 'a') as apk:
        with zipfile.ZipFile(root / 'libs/kuromoji-ipadic-0.9.0.jar') as dictionary:
            resources = [n for n in dictionary.namelist()
                         if n.startswith('com/atilika/kuromoji/ipadic/') and n.endswith('.bin')]
            assert len(resources) == 8, len(resources)
            for name in resources:
                assert name not in apk.namelist(), name
                apk.writestr(name, dictionary.read(name), compress_type=zipfile.ZIP_DEFLATED)
    print('JAPANESE_DICTIONARY_RESOURCES_READY')
