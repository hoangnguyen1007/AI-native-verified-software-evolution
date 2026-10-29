"""Native-only image template registration is distinct from Core MVP acceptance."""
from pathlib import Path
import sys
import tempfile
import uuid
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'main/resources/polyglot'))
import authored_worker as base
import sdk_template


class SdkTemplateControls(unittest.TestCase):
    def test_pins_scope_and_template_bytes_must_all_match(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/'template';root.mkdir();catalog=Path(directory)/'catalog';catalog.mkdir();attempt=str(uuid.uuid4());base.write(root/'setup.json',dict(attempt=attempt,acceptanceScope=sdk_template.SCOPE));base.write(root/'profile.json',dict(acceptanceScope=sdk_template.SCOPE,qualified=False))
            for name in ('provision-request.json','controller-config.json','known_hosts'):(root/name).write_bytes(b'{}')
            record=dict(schema='polyglot-sdk-template-registration:m8.4-v1',root=str(root),attempt=attempt,scope=sdk_template.SCOPE,files={name:base.digest(root/name) for name in base.TEMPLATE_FILES});base.write(catalog/(attempt+'.json'),record);self.assertEqual(sdk_template.SCOPE,sdk_template.validate(root,catalog)['acceptanceScope']);(root/'profile.json').write_text('{"qualified":true}')
            with self.assertRaises(ValueError):sdk_template.validate(root,catalog)


if __name__=='__main__':unittest.main()
