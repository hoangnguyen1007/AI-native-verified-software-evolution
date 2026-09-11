"""Bounded passive source acquisition. No target code, redirects or credentials."""
import hashlib
import argparse
import json
from pathlib import Path
import urllib.request
import urllib.parse

ROOT = Path(__file__).resolve().parent
CACHE = ROOT / '.cache' / 'sources'
ALLOW = {'docs.spring.io', 'github.com', 'raw.githubusercontent.com',
         'www.archunit.org', 'logicng.org', 'projectlombok.org'}

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--lock', default='sources.lock.json')
    args = parser.parse_args()
    assert Path(args.lock).name == args.lock
    lock = ROOT / args.lock
    CACHE.mkdir(parents=True, exist_ok=True)
    sources = json.loads((ROOT / 'sources.json').read_text('utf-8'))['sources']
    old = json.loads(lock.read_text('utf-8')) if lock.exists() else None
    rows = []
    total = 0
    opener = urllib.request.build_opener(NoRedirect)
    for source in sources:
        row = dict(source)
        uri = urllib.parse.urlsplit(row['url'])
        assert uri.scheme == 'https' and uri.hostname in ALLOW
        assert not uri.username and not uri.password and not uri.query
        cached = CACHE / (row['id'] + '.txt')
        try:
            remaining = 20_000_000 - total
            assert remaining > 0, 'TOTAL_BYTE_LIMIT'
            if cached.exists():
                assert not cached.is_symlink() and cached.stat().st_size <= min(4_000_000, remaining)
                data = cached.read_bytes()
            else:
                req = urllib.request.Request(row['url'], headers={'User-Agent': 'M4-R0-evidence/1.0'})
                with opener.open(req, timeout=15) as response:
                    data = response.read(min(4_000_000, remaining) + 1)
                assert len(data) <= min(4_000_000, remaining), 'SOURCE_BYTE_LIMIT'
                data.decode('utf-8')
                cached.write_bytes(data)
            total += len(data)
            assert total <= 20_000_000, 'TOTAL_BYTE_LIMIT'
            row.update(status='ACQUIRED', bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
        except Exception as error:
            row.update(status='UNAVAILABLE', reason=type(error).__name__)
        rows.append(row)
    result = {'schema': 'm4-r0-source-lock-v1', 'sources': rows}
    if old is not None and old != result:
        raise SystemExit('Source lock differs; retain old evidence and create a separately versioned acquisition.')
    if old is None:
        lock.write_text(json.dumps(result, sort_keys=True, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({'sources': len(rows), 'acquired': sum(r['status'] == 'ACQUIRED' for r in rows),
                      'bytes': total, 'unavailable': [r['id'] for r in rows if r['status'] != 'ACQUIRED']}))

if __name__ == '__main__':
    main()
