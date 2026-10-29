"""Source-position joins shared by native LSP adapters, never a name resolver."""
import bisect
from pathlib import Path
import urllib.parse
import sys


def parameters(source, offset, encoding='UTF8',position_encoding='UTF16'):
    offsets=source.bounds[encoding];index=bisect.bisect_left(offsets,offset)
    if index==len(offsets) or offsets[index]!=offset:raise ValueError('LSP_POSITION_SPLITS_SOURCE_CODEPOINT')
    line,column=source.positions[index]
    if position_encoding=='UTF8':column=source.bounds['UTF8'][index]-source.bounds['UTF8'][bisect.bisect_left(source.positions,(line,1))]+1
    elif position_encoding!='UTF16':raise ValueError('NATIVE_LSP_POSITION_ENCODING_UNSUPPORTED')
    return dict(textDocument={'uri':source.physical.as_uri()},position={'line':line-1,'character':column-1})


def source_uri(output,uri):
    parsed=urllib.parse.urlparse(uri)
    if parsed.scheme!='file' or parsed.netloc not in ('','localhost'):return None
    path=urllib.parse.unquote(parsed.path)
    if sys.platform=='win32' and len(path)>2 and path[0]=='/' and path[2]==':':path=path[1:]
    return output.source_path(Path(path))


def definitions(output,locations,declarations,symbol,position_encoding='UTF16'):
    """Only a unique smallest native declaration containing the native position binds."""
    result={}
    for location in locations if isinstance(locations,list) else [locations] if locations else []:
        source=source_uri(output,location.get('targetUri',location.get('uri','')))
        if source is None:continue
        region=location.get('targetSelectionRange',location.get('range'))
        if region is None:raise ValueError('LSP_DEFINITION_RANGE_REQUIRED')
        pos=region['start'];point=(pos['line']+1,pos['character']+1);matches=[]
        if position_encoding=='UTF8':
            first=bisect.bisect_left(source.positions,(point[0],1))
            if first==len(source.positions):raise ValueError('NATIVE_LSP_DEFINITION_LINE')
            offset=source.bounds['UTF8'][first]+pos['character'];index=bisect.bisect_left(source.bounds['UTF8'],offset)
            if index==len(source.positions) or source.bounds['UTF8'][index]!=offset or source.positions[index][0]!=point[0]:raise ValueError('NATIVE_LSP_DEFINITION_COLUMN')
            point=source.positions[index]
        elif position_encoding!='UTF16':raise ValueError('NATIVE_LSP_POSITION_ENCODING_UNSUPPORTED')
        for declaration in declarations[source.path]:
            span=declaration['span']
            if span and (span['startLine'],span['startColumn'])<=point<(span['endLine'],span['endColumn']):matches.append(declaration)
        if not matches:continue
        smallest=min(d['span']['end']-d['span']['start'] for d in matches)
        matches=[d for d in matches if d['span']['end']-d['span']['start']==smallest]
        if len(matches)==1:
            value=symbol(source,matches[0]);result[value['id']]=value
    return list(result.values())
