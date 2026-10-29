"""Authored Index Store protocol controls; no claim of a native SDK execution."""
from pathlib import Path
import sys
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'main/resources/polyglot'))
from swift_indexstore import definitions,relations,BASE_OF,OVERRIDE_OF,EXTENDED_BY


class SwiftIndexProtocolControls(unittest.TestCase):
    def symbol(self,usr,name='Base',kind=7):return dict(usr=usr,name=name,kind=kind,language=100)
    def index(self):
        a=self.symbol('native:A');b=self.symbol('native:B');child=self.symbol('native:Child','Child')
        return dict(occurrences=[dict(document=doc,line=1,column=position,symbol=symbol,roles=2,relations=[]) for doc,position,symbol in [('a.swift',1,a),('b.swift',1,b),('b.swift',10,child)]]+[dict(document='b.swift',line=2,column=2,symbol=b,roles=4,relations=[dict(roles=BASE_OF,symbol=child)])])
    def test_same_display_name_never_substitutes_for_native_usr_and_definition_position(self):
        index=self.index();bindings=definitions(index,lambda doc,line,column,kind:dict(id=doc+':'+str(column),name='Base' if column==1 else 'Child'));pairs=relations(index,'b.swift',100,110,lambda doc,line,col:100+col if line==2 else col,bindings,'heritage');self.assertEqual(1,len(pairs));self.assertEqual(['b.swift:1'],[s['id'] for s in pairs[0]['targets']]);self.assertEqual(['b.swift:10'],[s['id'] for s in pairs[0]['owners']])
    def test_missing_native_definition_stays_empty_and_outside_positions_are_excluded(self):
        index=self.index();bindings=definitions(index,lambda doc,line,column,kind:None);pairs=relations(index,'b.swift',100,110,lambda doc,line,col:100+col,bindings,'heritage');self.assertEqual([],pairs[0]['targets']);self.assertEqual([],pairs[0]['owners']);self.assertEqual([],relations(index,'b.swift',200,210,lambda doc,line,col:100+col,bindings,'heritage'))
    def test_override_direction_is_owner_to_base_and_extension_direction_is_related_owner(self):
        index=self.index();row=index['occurrences'][-1];bindings=definitions(index,lambda doc,line,column,kind:dict(id=doc+':'+str(column)));row['relations'][0]['roles']=OVERRIDE_OF;pairs=relations(index,'b.swift',100,110,lambda doc,line,col:100+col,bindings,'override');self.assertEqual('b.swift:1',pairs[0]['owners'][0]['id']);self.assertEqual('b.swift:10',pairs[0]['targets'][0]['id']);row['relations'][0]['roles']=EXTENDED_BY;pairs=relations(index,'b.swift',100,110,lambda doc,line,col:100+col,bindings,'extension');self.assertEqual('b.swift:10',pairs[0]['owners'][0]['id']);self.assertEqual('b.swift:1',pairs[0]['targets'][0]['id'])


if __name__=='__main__':unittest.main()
