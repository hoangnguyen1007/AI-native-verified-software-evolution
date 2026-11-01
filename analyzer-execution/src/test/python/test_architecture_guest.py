"""Architecture recipe/SDK transport controls, not native semantic certification."""
import hashlib
import importlib.util
import io
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from types import SimpleNamespace

RESOURCES=Path(__file__).resolve().parents[2]/'main/resources/polyglot'
sys.path.insert(0,str(RESOURCES))
from architecture_guest import unpack
import native_guest
import sdk_worker
import json

class ArchitectureGuestTest(unittest.TestCase):
    def test_sdk_scratch_budget_rejects_heavy_tools_before_creating_a_worker(self):
        from architecture_guest import scratch_budget
        self.assertLess(scratch_budget(451_000_000,87_000_000,12_000_000),1073741824)
        with self.assertRaisesRegex(ValueError,'ARCHITECTURE_SDK_RESOURCE_PROFILE_REQUIRED'):scratch_budget(730_000_000,152_000_000,12_000_000)

    def test_management_stops_waiting_when_the_scoped_supervisor_has_already_stopped_the_worker(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);attempt='0'*32;sdk_worker.management_abort(root,attempt)
            file=root/'supervisor-result.json';file.write_text(json.dumps({'reason':'OVERLAY_BUDGET_EXHAUSTED','state':{'attempt':attempt,'state':'Off'}}))
            with self.assertRaisesRegex(RuntimeError,'SDK_SUPERVISOR_STOPPED:OVERLAY_BUDGET_EXHAUSTED'):sdk_worker.management_abort(root,attempt)
            file.write_text(json.dumps({'reason':'OVERLAY_BUDGET_EXHAUSTED','state':{'attempt':'1'*32,'state':'Off'}}))
            with self.assertRaisesRegex(ValueError,'SDK_SUPERVISOR_SCOPE'):sdk_worker.management_abort(root,attempt)

    def test_malformed_sdk_archive_rejects_before_template_or_worker_creation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);archive=self.archive(root,[('package.json',b'{}',False)])
            request=dict(schema='architecture-request:m8.4-v1',language='frontendarchitecture',dialect='m8.4-v1',files=[dict(path='index.html',text='',sha256='sha256:'+hashlib.sha256(b'').hexdigest())])
            campaign=root/'campaign.json';campaign.write_text(json.dumps(dict(schema='authored-native-campaign:m8.4-v1',assets={'architecture':dict(path=str(archive),size=archive.stat().st_size,digest='sha256:'+hashlib.sha256(archive.read_bytes()).hexdigest())},contexts={'html':dict(language='frontendarchitecture',request=request)})))
            with self.assertRaisesRegex(ValueError,'ARCHITECTURE_SDK_ARCHIVE_PATH'):
                sdk_worker.register(SimpleNamespace(campaign=campaign,template=root/'absent-template'))
    def archive(self,root,entries):
        file=root/'input.tar.gz'
        with tarfile.open(file,'w:gz') as out:
            for name,body,link in entries:
                member=tarfile.TarInfo(name)
                if link:member.type=tarfile.SYMTYPE;member.linkname=body
                else:member.size=len(body)
                out.addfile(member,None if link else io.BytesIO(body))
        return file

    def test_pinned_data_is_unpacked_without_package_hooks(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);file=self.archive(root,[('package/package.json',b'{"scripts":{"install":"unexecuted"}}',False),('package/package-lock.json',b'{}',False)])
            target=unpack(file,root/'sdk');self.assertEqual(b'{}',(target/'package-lock.json').read_bytes())
            # Restore writable mode only for this test-owned temporary directory.
            for item in target.rglob('*'):item.chmod(0o700)
            target.chmod(0o700)

    def test_archive_escape_link_duplicate_and_missing_lock_are_rejected(self):
        for entries in [[('package/../outside',b'x',False)],[('package/link','../../outside',True)],[('package/a',b'x',False),('package/a',b'y',False)],[('package/a',b'x',False)]]:
            with self.subTest(entries=entries),tempfile.TemporaryDirectory() as temp:
                root=Path(temp)
                with self.assertRaises(ValueError):unpack(self.archive(root,entries),root/'sdk')
                self.assertFalse((root/'outside').exists())

    def test_fixed_architecture_recipe_requires_exact_tools_and_native_context_tuple(self):
        text='<p>authored</p>';request=dict(schema='architecture-request:m8.4-v1',language='frontendarchitecture',dialect='m8.4-v1',scope='sha256:'+'1'*64,files=[dict(path='index.html',text=text,sha256='sha256:'+hashlib.sha256(text.encode()).hexdigest())])
        campaign=dict(schema='authored-native-campaign:m8.4-v1',assets={'architecture':{}},contexts={'html':dict(language='frontendarchitecture',request=request)})
        native_guest.validate(campaign)
        campaign['assets']={}
        with self.assertRaisesRegex(ValueError,'EXACT_ASSET_MEMBERSHIP'):native_guest.validate(campaign)

    def test_asset_transfer_namespace_does_not_collide_with_control_directories(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);attempt='0'*32;(root/'setup.json').write_text(json.dumps({'attempt':attempt}));asset=root/'sdk';asset.write_bytes(b'authored SDK transport');opened=[]
            class Sftp:
                def __enter__(self):return self
                def __exit__(self,*args):pass
                def open(self,path,mode):opened.append((path,mode));return io.BytesIO()
            class Client:
                def open_sftp(self):return Sftp()
            pinned={'path':str(asset),**sdk_worker.base.handle(asset)}
            commands=[]
            def remote(command,*args):
                commands.append(command);return (pinned['digest'][7:]+'  captured\n').encode() if 'sha256sum' in command else b''
            campaign=dict(schema='authored-native-campaign:m8.4-v1',contexts={},assets={'architecture':pinned})
            sdk_worker.transfer_native_campaign(root,'/control',campaign,Client(),remote)
            self.assertEqual([('/work/'+attempt+'/controls/asset-architecture','wx')],opened)
            self.assertEqual('rm -- /work/'+attempt+'/controls/asset-architecture',commands[-1]);self.assertIn('sha256sum',commands[-2])
            commands.clear()
            def wrong_hash(command,*args):commands.append(command);return ('0'*64+'  changed\n').encode() if 'sha256sum' in command else b''
            with self.assertRaisesRegex(ValueError,'NATIVE_ASSET_TRANSFER_CHANGED'):sdk_worker.transfer_native_campaign(root,'/control',campaign,Client(),wrong_hash)
            self.assertFalse(any(command.startswith('rm ') for command in commands),'Keep staged bytes when the verified destination is unavailable')

    def test_native_route_recipe_requires_its_separately_pinned_asset_and_fixed_argv(self):
        text='export {}';request=dict(schema='architecture-request:m8.4-v1',language='frontendarchitecture',dialect='m8.4-v1',context={'routeModels':[{'framework':'next','directory':'app','mode':'app'}]},files=[dict(path='app/page.tsx',text=text,sha256='sha256:'+hashlib.sha256(text.encode()).hexdigest())])
        campaign=dict(schema='authored-native-campaign:m8.4-v1',assets={'architecture':{},'architecture-routing':{}},contexts={'next':dict(language='frontendarchitecture',request=request)})
        native_guest.validate(campaign)
        registration=dict(sdkRoot='/sdk',runtime={'python':{'path':'/sdk/python'}},preparedNative={'extras':{'architecture':'/pinned/base','architecture-routing':'/pinned/routing'},'contexts':{'next':{'language':'frontendarchitecture','request':{'path':'/captured/request'}}}})
        args=native_guest.command(registration,'/control','next')
        self.assertEqual(['/sdk/node/bin/node','/control/architecture/worker.cjs','/captured/request','/pinned/base','/pinned/routing'],args)
        campaign['assets'].pop('architecture-routing')
        with self.assertRaisesRegex(ValueError,'EXACT_ASSET_MEMBERSHIP'):native_guest.validate(campaign)

    def test_sdk_batch_transport_is_separate_from_small_control_metadata(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'campaign';path.write_text(json.dumps({'capturedDeclarations':'x'*1_100_000}))
            with self.assertRaisesRegex(ValueError,'METADATA_WORKER_REQUIRED'):sdk_worker.base.read(path)
            self.assertEqual(1_100_000,len(sdk_worker.read_campaign(path)['capturedDeclarations']))

    def test_sdk_assets_are_reused_by_exact_bytes_and_capture_storage_is_explicit(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);asset=root/'sdk.tar.gz';asset.write_bytes(b'fixed SDK archive');pin=dict(path=str(asset),**sdk_worker.base.handle(asset))
            registered=sdk_worker.pin_campaign_assets({'architecture':pin})
            self.assertEqual(asset.absolute(),Path(registered['architecture']['path']));self.assertEqual(pin['digest'],registered['architecture']['digest'])
            self.assertEqual(root/'capture',sdk_worker.capture_destination(root,{}))
            external=root/'evidence';external.mkdir();self.assertEqual(external/'capture',sdk_worker.capture_destination(root,{'captureRoot':str(external/'capture')}))
            asset.chmod(0o600);asset.write_bytes(b'changed SDK')
            with self.assertRaisesRegex(ValueError,'INPUT_CHANGED'):sdk_worker.pin_campaign_assets({'architecture':pin})

    def test_native_nuxt_configuration_has_an_explicit_asset_and_argument_role(self):
        text='export default {}';request=dict(schema='architecture-request:m8.4-v1',language='frontendarchitecture',dialect='m8.4-v1',context={'nativeNuxtConfig':True,'routeModels':[{'framework':'nuxt','directory':'app/pages','config':'nuxt.config.ts'}]},files=[dict(path='nuxt.config.ts',text=text,sha256='sha256:'+hashlib.sha256(text.encode()).hexdigest())])
        campaign=dict(schema='authored-native-campaign:m8.4-v1',assets={'architecture':{},'architecture-routing':{},'architecture-nuxt':{}},contexts={'nuxt':dict(language='frontendarchitecture',request=request)})
        native_guest.validate(campaign)
        registration=dict(sdkRoot='/sdk',runtime={'python':{'path':'/sdk/python'}},preparedNative={'extras':{'architecture':'/pinned/base','architecture-routing':'/pinned/routing','architecture-nuxt':'/pinned/nuxt'},'contexts':{'nuxt':{'language':'frontendarchitecture','request':{'path':'/captured/request'}}}})
        self.assertEqual(['/sdk/node/bin/node','/control/architecture/worker.cjs','/captured/request','/pinned/base','/pinned/routing','/pinned/nuxt'],native_guest.command(registration,'/control','nuxt'))
        campaign['assets'].pop('architecture-nuxt')
        with self.assertRaisesRegex(ValueError,'EXACT_ASSET_MEMBERSHIP'):native_guest.validate(campaign)

    def test_sdk_expansion_profiles_reject_oversized_headers_without_large_fixtures(self):
        for profile,size in [('base',268435457),('nuxt-config-v1',335544321)]:
            with self.subTest(profile=profile),tempfile.TemporaryDirectory() as temp:
                root=Path(temp);header=tarfile.TarInfo('package/oversized');header.size=size
                import gzip
                file=root/'header.tar.gz';file.write_bytes(gzip.compress(header.tobuf()+bytes(1024)))
                with self.assertRaisesRegex(ValueError,'ARCHIVE_WORKER_REQUIRED'):unpack(file,root/'sdk',profile)

    def test_native_vue_context_requires_its_sdk_without_shifting_optional_argument_roles(self):
        text='<template><p>Vue</p></template>';request=dict(schema='architecture-request:m8.4-v1',language='frontendarchitecture',dialect='m8.4-v1',profiles=[{'id':'vue','version':'3.5'}],context={'nativeVue':True},files=[dict(path='App.vue',text=text,sha256='sha256:'+hashlib.sha256(text.encode()).hexdigest())])
        campaign=dict(schema='authored-native-campaign:m8.4-v1',assets={'architecture':{},'architecture-vue':{}},contexts={'vue':dict(language='frontendarchitecture',request=request)})
        native_guest.validate(campaign)
        registry=dict(sdkRoot='/sdk',runtime={'python':{'path':'/sdk/python'}},preparedNative={'extras':{'architecture':'/pinned/base','architecture-vue':'/pinned/vue'},'contexts':{'vue':{'language':'frontendarchitecture','request':{'path':'/input/request'}}}})
        self.assertEqual(['/sdk/node/bin/node','/control/architecture/worker.cjs','/input/request','/pinned/base','','','/pinned/vue'],native_guest.command(registry,'/control','vue'))
        campaign['assets'].pop('architecture-vue')
        with self.assertRaisesRegex(ValueError,'EXACT_ASSET_MEMBERSHIP'):native_guest.validate(campaign)

    def test_only_architecture_transport_accepts_exact_binary_descriptor_bytes(self):
        import base64
        raw=b'\x0a\x00';source=dict(path='schema.desc',data=base64.b64encode(raw).decode('ascii'),sha256='sha256:'+hashlib.sha256(raw).hexdigest())
        request=dict(schema='architecture-request:m8.4-v1',language='frontendarchitecture',dialect='m8.4-v1',files=[source])
        campaign=dict(schema='authored-native-campaign:m8.4-v1',assets={'architecture':{}},contexts={'descriptor':dict(language='frontendarchitecture',request=request)})
        native_guest.validate(campaign);self.assertEqual(raw,native_guest.captured_bytes(source,'frontendarchitecture'))
        source['data']+='!'
        with self.assertRaises(ValueError):native_guest.validate(campaign)

if __name__=='__main__':unittest.main()
