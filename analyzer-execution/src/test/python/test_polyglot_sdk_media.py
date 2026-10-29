import importlib.util
import io
import gzip
import json
import lzma
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest

SOURCE=Path(__file__).resolve().parents[2]/'main/resources/polyglot/sdk_media.py'
spec=importlib.util.spec_from_file_location('polyglot_sdk_media',SOURCE)
media=importlib.util.module_from_spec(spec);spec.loader.exec_module(media)


class ToolMediaControls(unittest.TestCase):
    def test_debian_sdk_extracts_data_only_and_rejects_a_missing_payload(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);payload=io.BytesIO()
            with tarfile.open(fileobj=payload,mode='w:xz') as archive:
                entry=tarfile.TarInfo('./usr/bin/php8.1');entry.size=6;entry.mode=0o755;archive.addfile(entry,io.BytesIO(b'native'))
            def ar(name,data):
                header=(name+'/').ljust(16)+'0'.ljust(12)+'0'.ljust(6)+'0'.ljust(6)+'100644'.ljust(8)+str(len(data)).ljust(10)+'`\n'
                return header.encode('ascii')+data+(b'\n' if len(data)%2 else b'')
            package=root/'php.deb';package.write_bytes(b'!<arch>\n'+ar('debian-binary',b'2.0\n')+ar('control.tar.xz',b'installer scripts are never evaluated')+ar('data.tar.xz',payload.getvalue()))
            item=dict(source='php.deb',prefix='php',format='deb',**media.handle(package));result=media.build(root,[item],root/'tools.iso',1048576);self.assertEqual(1,result['entries']);self.assertEqual(6,result['logicalBytes'])
            import zstandard
            compressed=zstandard.ZstdCompressor().compress(lzma.decompress(payload.getvalue()));zstd=root/'php-zstd.deb';zstd.write_bytes(b'!<arch>\n'+ar('debian-binary',b'2.0\n')+ar('data.tar.zst',compressed));item=dict(source='php-zstd.deb',prefix='php',format='deb',**media.handle(zstd));self.assertEqual(1,media.build(root,[item],root/'zstd.iso',1048576)['entries'])
            bad=root/'bad.deb';bad.write_bytes(b'!<arch>\n'+ar('debian-binary',b'2.0\n'));item=dict(source='bad.deb',prefix='php',format='deb',**media.handle(bad))
            with self.assertRaisesRegex(ValueError,'DATA_REQUIRED'):media.build(root,[item],root/'bad.iso',1048576)
    def test_selected_sdk_component_and_compressed_native_binary_keep_pinned_membership(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);archive=root/'rust.tar'
            with tarfile.open(archive,'w') as out:
                for name,data in [('root/install.sh',b'never execute'),('root/compiler/manifest.in',b'installer metadata'),('root/compiler/bin/rustc',b'native tool')]:
                    info=tarfile.TarInfo(name);info.size=len(data);info.mode=0o755;out.addfile(info,io.BytesIO(data))
            binary=root/'ra.gz';binary.write_bytes(gzip.compress(b'native analyzer'))
            items=[dict(source='rust.tar',prefix='rust',format='tar',strip=2,subtree='root/compiler',skipMembers=['manifest.in'],**media.handle(archive)),dict(source='ra.gz',prefix='rust-analyzer/bin/rust-analyzer',format='gzip-file',outputBytes=15,mode=0o555,**media.handle(binary))]
            result=media.build(root,items,root/'tools.iso',1048576);self.assertEqual(2,result['entries']);self.assertEqual(26,result['logicalBytes'])
    def test_paths_are_logical_and_cannot_escape_the_captured_toolset(self):
        self.assertEqual('sdk/include/Foo.h',media.logical('sdk/include/Foo.h'))
        for name in ('/host/home','../private','a/../../secret','a\\b','C:/home','a//b','a/./b','a\x00b'):
            with self.assertRaises(ValueError):media.logical(name)
        self.assertEqual('sdk/lib/tool.so.1',media.link_target('sdk/bin/tool','../lib/tool.so.1'))
        with self.assertRaises(ValueError):media.link_target('sdk/bin/tool','../../../home/private')
        with self.assertRaises(ValueError):media.link_target('sdk/bin/tool','/home/private')

    def test_archive_escape_and_mutated_pinned_bytes_never_produce_media(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);archive=root/'bad.tar'
            with tarfile.open(archive,'w') as out:
                info=tarfile.TarInfo('../secret');info.size=3;out.addfile(info,io.BytesIO(b'bad'))
            item=dict(source='bad.tar',prefix='sdk',format='tar',strip=0,**media.handle(archive))
            with self.assertRaises(ValueError):media.build(root,[item],root/'bad.iso',max_bytes=1048576)
            self.assertFalse((root/'bad.iso').exists())
            archive.write_bytes(b'changed')
            with self.assertRaises(ValueError):media.build(root,[item],root/'changed.iso',max_bytes=1048576)
            self.assertFalse((root/'changed.iso').exists())

    def test_readonly_media_preserves_case_links_and_exact_tools_without_exposing_seed(self):
        import pycdlib
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);archive=root/'sdk.tar'
            with tarfile.open(archive,'w') as out:
                info=tarfile.TarInfo('.');info.type=tarfile.DIRTYPE;out.addfile(info)
                for name,data,mode in [('tool/bin/run',b'public-native-runtime',0o755),('tool/include/Foo.h',b'A',0o644),('tool/include/foo.h',b'B',0o644)]:
                    info=tarfile.TarInfo(name);info.size=len(data);info.mode=mode;out.addfile(info,io.BytesIO(data))
                info=tarfile.TarInfo('tool/bin/alias');info.type=tarfile.SYMTYPE;info.linkname='run';out.addfile(info)
            item=dict(source='sdk.tar',prefix='sdk',format='tar',strip=1,**media.handle(archive));output=root/'toolset.iso'
            result=media.build(root,[item],output,max_bytes=1048576);self.assertEqual(4,result['entries']);self.assertEqual(media.handle(output),result['media'])
            iso=pycdlib.PyCdlib();iso.open(str(output))
            try:
                for name,expected in [('Foo.h',b'A'),('foo.h',b'B')]:
                    data=io.BytesIO();iso.get_file_from_iso_fp(data,rr_path='/sdk/include/'+name);self.assertEqual(expected,data.getvalue())
                self.assertTrue(iso.get_record(rr_path='/sdk/bin/alias').is_symlink())
                with self.assertRaises(pycdlib.pycdlibexception.PyCdlibInvalidInput):iso.get_record(rr_path='/guest-host-ed25519')
            finally:iso.close()


if __name__=='__main__':unittest.main()
