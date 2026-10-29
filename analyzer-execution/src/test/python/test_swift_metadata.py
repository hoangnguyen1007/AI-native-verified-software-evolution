"""Pinned native Swift debug-wire mapping, never a Swift parser or type solver."""
from pathlib import Path
import sys
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'main/resources/polyglot'))
from swift_engine import declaration_location,ast_wire,index_commands


class SwiftMetadataControls(unittest.TestCase):
    def test_each_primary_index_pass_keeps_every_module_file_in_original_order(self):
        files=[Path('/captured/main.swift'),Path('/captured/models.swift')];commands=index_commands(Path('/sdk/swiftc'),'Fixture',['-sdk','/sdk/headers'],'/tmp/private',Path('/private/unit'),files);self.assertEqual(2,len(commands))
        for i,command in enumerate(commands):
            self.assertEqual(1,command.count('-primary-file'));self.assertEqual(str(files[i]),command[command.index('-primary-file')+1]);self.assertEqual([str(p) for p in files],[v for v in command if v in [str(p) for p in files]]);self.assertEqual(1,command.count('-index-unit-output-path'))
        self.assertNotEqual(commands[0][commands[0].index('-index-unit-output-path')+1],commands[1][commands[1].index('-index-unit-output-path')+1])
    def test_generic_specialization_keeps_the_native_declaration_position(self):
        # Shape observed in the real Swift 6.0.3 captured AST. The expectation is
        # the compiler's explicit source pointer, not a matched source name.
        header='declref_expr type="(Int) -> Int" decl="Fixture.(file).twice@/captured/service.swift:1:6 [with (substitution_map generic_signature=<T where T : Copyable, T : Escapable> T -> Int)]" function_ref=single'
        self.assertEqual(('/captured/service.swift',1,6),declaration_location(header))
    def test_unlocated_platform_or_unresolved_refs_cannot_invent_source_positions(self):
        self.assertIsNone(declaration_location('declref_expr decl="Swift.(file).Int extension.*"'))
        self.assertIsNone(declaration_location('unresolved_decl_ref_expr name="twice"'))
        self.assertEqual(('/captured/a.swift',12,34),declaration_location('declref_expr decl="Fixture.(file).run@/captured/a.swift:12:34"'))
    def test_native_ast_quoted_parentheses_are_attributes_not_extra_children(self):
        nodes=ast_wire('(source_file "a.swift" (func_decl "run()" interface type="() -> Int" (declref_expr decl="Fixture.twice@/captured/a.swift:1:6 [with (substitution_map T -> Int)]")))')
        self.assertEqual(1,len(nodes));self.assertEqual(1,len(nodes[0]['children']));self.assertEqual(1,len(nodes[0]['children'][0]['children']))
        with self.assertRaises(ValueError):ast_wire('(source_file "truncated"')


if __name__=='__main__':unittest.main()
