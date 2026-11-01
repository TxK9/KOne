"""Static evidence that UI localization follows this exact production protocol version."""
import ast
import json
from pathlib import Path
import re
import unittest

BASE=Path(__file__).resolve().parents[1]

class LocaleContractTest(unittest.TestCase):
    def test_every_current_system_error_has_an_explicit_translation(self):
        source=(BASE/'web/js/i18n.js').read_text()
        catalog=json.loads(source.split('export const EN = ',1)[1].split(';\n\nexport const LOCALES',1)[0])
        checked=set()
        for filename in ['server.py','fleet.py','customers.py','feedback.py','config_import.py','owner_updates.py']:
            tree=ast.parse((BASE/filename).read_text())
            for node in ast.walk(tree):
                if not isinstance(node,ast.Call) or not isinstance(node.func,ast.Name) or node.func.id not in {'APIError','FleetError','CustomerError','ConfigImportError'}:continue
                for part in ast.walk(node):
                    if isinstance(part,ast.Constant) and isinstance(part.value,str) and re.search('[\u3400-\u9fff]',part.value):
                        self.assertIn(part.value,catalog,filename+': '+part.value);checked.add(part.value)
        self.assertGreater(len(checked),180)

    def test_every_current_audit_title_is_recognized(self):
        source=(BASE/'web/js/i18n.js').read_text().split('const RECORD_TITLES = {',1)[1]
        titles=set()
        for filename in ['server.py','fleet.py','customers.py']:
            for node in ast.walk(ast.parse((BASE/filename).read_text())):
                if isinstance(node,ast.Call) and isinstance(node.func,ast.Attribute) and node.func.attr in {'record','audit'} and len(node.args)>1:
                    for part in ast.walk(node.args[1]):
                        if isinstance(part,ast.Constant) and isinstance(part.value,str):titles.add(part.value)
        for title in titles:self.assertIn("'"+title+"':",source)
        self.assertEqual(len(titles),25)
