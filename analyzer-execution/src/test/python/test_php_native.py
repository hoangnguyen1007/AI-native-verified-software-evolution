"""Actual PHPStan 2.1.20 / PHP 8.1 native controls, over authored fixture data only."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

WORKSPACE=Path(__file__).resolve().parents[4];SOURCE=WORKSPACE/'analyzer-execution/src/main/resources/polyglot/php_engine.py'


def handle(file):return dict(size=file.stat().st_size,digest='sha256:'+hashlib.sha256(file.read_bytes()).hexdigest())


class PhpNativeControls(unittest.TestCase):
    def test_duplicate_parent_context_cannot_promote_parameters_to_exact_bindings(self):
        rows=self.analyze({'a.php':'<?php namespace Fixture; function duplicate(int $n) {}\n','b.php':'<?php namespace Fixture; function duplicate(string $n) {}\n'});parameters=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and any(s['kind']=='PARAMETER' for s in r['fields']['symbols'])];self.assertEqual(2,len(parameters));self.assertTrue(all(f['status']=='AMBIGUOUS' for f in parameters),[f['status'] for f in parameters])
    def test_native_promoted_property_keeps_real_parameter_lineage_and_parameter_identity(self):
        files={'main.php':'<?php namespace Fixture; class C { function __construct(public int $count) {} function read():int{return $this->count;} }\n'};rows=self.analyze(files);questions={r['key']:r['fields'] for r in rows if r.get('kind')=='CENSUS'};params=[s for r in rows if r.get('kind')=='OBSERVATION' and questions[r['key']]['category']=='A02' for s in r['fields']['symbols'] if s['kind']=='PARAMETER'];self.assertEqual(['count'],[s['name'] for s in params]);promoted=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and questions[r['key']]['role']=='promoted-property'];self.assertEqual(1,len(promoted));self.assertEqual('RESOLVED',promoted[0]['status']);self.assertEqual('PROMOTES_PROPERTY',promoted[0]['relation']);prop=promoted[0]['symbols'][0];self.assertEqual('GENERATED',prop['origin']);self.assertIsNone(prop['span']);self.assertEqual('PROMOTION_PARAMETER_ORIGIN',prop['locationPrecision']);self.assertEqual('PHP_CONSTRUCTOR_PROPERTY_PROMOTION',prop['generationLineage']['mechanism']);self.assertEqual('public int $count',files['main.php'][prop['generationLineage']['sourceSpan']['start']:prop['generationLineage']['sourceSpan']['end']])
    def test_missing_heritage_and_duplicate_declarations_do_not_create_exact_bindings(self):
        rows=self.analyze({'main.php':'<?php class Child extends Missing {}\n'});questions={r['key'] for r in rows if r.get('kind')=='CENSUS' and r['fields']['category']=='A05'};heritage=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and r['key'] in questions];self.assertEqual(1,len(heritage));self.assertEqual('UNRESOLVED',heritage[0]['status']);self.assertFalse(heritage[0]['targets']);self.assertTrue(rows[-1]['diagnostics'])
        rows=self.analyze({'a.php':'<?php namespace Fixture; class Duplicate {}\n','b.php':'<?php namespace Fixture; class Duplicate {}\n'});duplicates=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and any(s['name']=='Duplicate' for s in r['fields']['symbols'])];self.assertTrue(duplicates);self.assertTrue(all(f['status']=='AMBIGUOUS' for f in duplicates));self.assertTrue(rows[-1]['diagnostics'])
    def test_group_imports_and_static_reflection_never_execute_source(self):
        files={'types.php':'<?php namespace Lib; file_put_contents(__DIR__."/evo-would-execute", "NO"); class Base {} interface I {} trait T {}\n','main.php':'<?php namespace App; use Lib\\{Base as B,I,T}; class Child extends B implements I { use T; }\n'};rows=self.analyze(files);questions={r['key'] for r in rows if r.get('kind')=='CENSUS' and r['fields']['category']=='A09' and r['fields']['role']=='import'};imports=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and r['key'] in questions];self.assertEqual(3,len(imports));self.assertTrue(all(f['status']=='RESOLVED' for f in imports));self.assertEqual({'Base','I','T'},{s['name'] for f in imports for s in f['symbols']})
    def test_native_class_trait_interface_attribute_and_property_relations(self):
        files={'main.php':'<?php namespace Fixture; interface I {} trait T {} class Base {} #[\\Attribute] class Mark {} #[Mark] class Child extends Base implements I { use T; public int $counter=0; function run(): int {$this->counter+=1; return $this->counter;} }\n'};rows=self.analyze(files);categories={r['key']:r['fields']['category'] for r in rows if r.get('kind')=='CENSUS'};facts=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION'];declared={s['name']:s for r in rows if r.get('kind')=='OBSERVATION' and categories[r['key']]=='A02' for s in r['fields']['symbols']};self.assertTrue({'I','T','Base','Mark','Child','counter'}.issubset(declared));heritage=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and categories[r['key']]=='A05'];self.assertEqual({'EXTENDS','IMPLEMENTS','USES_TRAIT'},{f.get('relation') for f in heritage})
        for fact in heritage:self.assertEqual('RESOLVED',fact['status']);self.assertEqual(declared['Child']['id'],fact['owner']);self.assertEqual(1,len(fact['targets']))
        attributes=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and categories[r['key']]=='A08' and r['fields']['role']=='attribute'];self.assertTrue(any(f['status']=='RESOLVED' and any(s['name']=='Mark' for s in f['symbols']) for f in attributes));access=[f for f in facts if any(s['name']=='counter' for s in f['symbols']) and f.get('access')];self.assertEqual(['READ_WRITE','READ'],[f['access'] for f in access]);self.assertTrue(all(f['status']=='RESOLVED' for f in access))
    def test_native_import_aliases_keep_exact_class_and_function_targets(self):
        files={'types.php':'<?php namespace Lib; class Base {} interface I {} trait T {} function twice(int $n):int{return $n*2;}\n','main.php':'<?php namespace App; use Lib\\Base as ParentType; use Lib\\I; use Lib\\T; use function Lib\\twice as doubleIt; class Child extends ParentType implements I { use T; function run():int{return doubleIt(2);} }\n'};rows=self.analyze(files);categories={r['key']:r['fields']['category'] for r in rows if r.get('kind')=='CENSUS'};imports=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and categories[r['key']]=='A09' and r['fields']['role']=='import'];self.assertEqual(4,len(imports));self.assertEqual({'Base','I','T','twice'},{s['name'] for f in imports for s in f['symbols']});self.assertTrue(all(f['status']=='RESOLVED' for f in imports));self.assertTrue(all(s['owner']=='Lib' for f in imports for s in f['symbols']));call=self.call(rows,files,'doubleIt(2)');self.assertEqual('RESOLVED',call['status']);self.assertEqual('twice',call['symbols'][0]['name'])
    def analyze(self,files):
        php=WORKSPACE/'.evo/tools/m845-php-win-8.1.31/sdk/php.exe';analyzer=WORKSPACE/'.evo/tools/m845-native-extra-20261027/phpstan-2.1.20.phar';engine=SOURCE.parent/'php/php_engine.php'
        if not php.is_file() or not analyzer.is_file():raise RuntimeError('Pinned real PHP 8.1 / PHPStan SDK required')
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/'src';source.mkdir();work=root/'state'
            for name,text in files.items():(source/name).write_bytes(text.encode('utf8'))
            request=dict(schema='native-php-request:m8.4-v1',language='php',dialect='8.1',scope='sha256:'+'1'*64,tool='sha256:'+hashlib.sha256(engine.read_bytes()).hexdigest(),provider={'id':'frontend.php','version':'8.1-phpstan2.1.20-m8.4-v3'},repositoryScope='urn:test:authored-php',packageScope='fixture',sourceRoot=str(source),workRoot=str(work),runtime=handle(php),analyzer=handle(analyzer),engine=handle(engine),files=[dict(path=name,text=text,sha256='sha256:'+hashlib.sha256(text.encode('utf8')).hexdigest()) for name,text in sorted(files.items())])
            file=root/'request.json';file.write_text(json.dumps(request),encoding='utf8');result=subprocess.run([sys.executable,'-I',str(SOURCE),'--request',str(file),'--php',str(php),'--analyzer',str(analyzer),'--engine',str(engine)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=210)
            detail=(work/'php-native.stderr').read_bytes()[-8192:] if result.returncode and (work/'php-native.stderr').is_file() else result.stderr[-8192:]
            if result.returncode and (work/'php-native.json').is_file():detail+=(work/'php-native.json').read_bytes()[:4096]
            self.assertEqual(0,result.returncode,detail.decode(errors='replace'));self.assertFalse((source/'evo-would-execute').exists(),'Native reflection must not evaluate source');rows=[json.loads(line) for line in result.stdout.decode('utf8').splitlines()];self.assertEqual({f'A{i:02}' for i in range(1,13)},{r['fields']['category'] for r in rows if r.get('kind')=='CENSUS'});self.assertEqual(rows[-1]['census'],rows[-1]['observations']);return rows
    def call(self,rows,files,written):return next(r['fields'] for r in rows if r.get('kind')=='OBSERVATION' and r['fields']['role']=='call' and r['fields']['span'] and files[r['fields']['span']['path']].encode('utf-16-le')[r['fields']['span']['start']*2:r['fields']['span']['end']*2].decode('utf-16-le')==written)
    def test_native_namespaced_crossfile_target_and_original_crlf_nonbmp_span(self):
        files={'service.php':'<?php namespace Fixture; function twice(int $n): int { return $n*2; }\n','main.php':'<?php namespace Fixture;\r\nfunction run(): int { $marker="😀"; return twice(4); }\r\n'};rows=self.analyze(files);call=self.call(rows,files,'twice(4)');self.assertEqual('RESOLVED',call['status']);self.assertEqual('twice',call['symbols'][0]['name']);self.assertEqual('PROJECT',call['symbols'][0]['origin']);self.assertEqual('Fixture',call['symbols'][0]['owner'])
    def test_missing_and_dynamic_calls_keep_uncertainty_and_native_diagnostics(self):
        files={'main.php':'<?php function run($target) { missing(3); $target->work(); }\n'};rows=self.analyze(files)
        for written in ('missing(3)','$target->work()'):
            call=self.call(rows,files,written);self.assertEqual('UNRESOLVED',call['status']);self.assertFalse(call['targets'])
        self.assertTrue(any(d['severity']=='ERROR' and d['code']=='function.notFound' for d in rows[-1]['diagnostics']), rows[-1]['diagnostics'])
    def test_native_parser_failure_is_accounted_in_every_category(self):
        rows=self.analyze({'main.php':'<?php function broken( {\n'});observed=[r['fields'] for r in rows if r.get('kind')=='OBSERVATION'];self.assertTrue(any(r['status']=='ERROR' for r in observed));self.assertFalse(any(r['status']=='RESOLVED' and r['targets'] for r in observed))


if __name__=='__main__':unittest.main()
