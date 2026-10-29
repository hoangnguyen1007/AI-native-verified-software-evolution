"""Regression for the rejected architecture campaign's Clang provider pins."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

RESOURCES=Path(__file__).resolve().parents[2]/'main/resources/polyglot'
sys.path.insert(0,str(RESOURCES))
import build_native_campaign as builder
from clang_engine import PROVIDER

class NativeCampaignProvider(unittest.TestCase):
    def test_direct_relation_revisions_are_pinned_separately_from_frozen_earlier_exports(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);cases=root/'cases.json';media=root/'media.json';cases.write_text(json.dumps(dict(schema='authored-native-cases:m8.4-v1',cases={lang:dict(language=lang,files={name:text},expected={}) for lang,name,text in [('dart','main.dart','class C {}'),('php','main.php','<?php class C {}'),('ruby','main.rb','class C; end'),('rust','lib.rs','struct C;')]},replays={})));media.write_text(json.dumps(dict(manifest=dict(digest='sha256:'+'0'*64,size=1))));builder.build(root,cases,root/'bundle',media);campaign=json.loads((root/'bundle/campaign.json').read_text());self.assertEqual({'dart':'m8.4-v3','php':'m8.4-v3','ruby':'m8.4-v2','rust':'m8.4-v4'},{name:item['request']['provider']['version'] for name,item in campaign['contexts'].items()})
    def test_prepared_clang_requests_bind_the_actual_frontend_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);cases=root/'cases.json';media=root/'media.json'
            cases.write_text(json.dumps(dict(schema='authored-native-cases:m8.4-v1',cases={lang:dict(language=lang,files={name:'int x;'},expected={}) for lang,name in [('c','main.c'),('cpp','main.cpp'),('objectivec','main.m')]},replays={})))
            media.write_text(json.dumps(dict(manifest=dict(digest='sha256:'+'0'*64,size=1))))
            builder.build(root,cases,root/'bundle',media)
            campaign=json.loads((root/'bundle/campaign.json').read_text())
            for context in campaign['contexts'].values():self.assertEqual(PROVIDER,context['request']['provider'])

if __name__=='__main__':unittest.main()
