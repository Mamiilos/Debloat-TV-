#!/usr/bin/env python3
"""The note on the TV's home screen, through the Worker (it shows within 2 minutes; no reinstall).

    python3 scripts/note.py "Bins out tonight"   # set it (make note MSG="...")
    python3 scripts/note.py --clear               # remove it (make note-clear)

Uses worker_url from tv.properties and worker_admin_token from config.properties (the Worker's ADMIN_TOKEN; the TV
never has it). The token goes in a header, never on a command line.
"""
import json
import os
import sys
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tvconfig  # noqa: E402


def main():
    args = sys.argv[1:]
    if not args:
        sys.exit(__doc__)
    values = tvconfig.settings()
    url, token = values.get('worker_url', ''), values.get('worker_admin_token', '')
    if not url or not token:
        sys.exit('Needs worker_url in tv.properties and worker_admin_token in config.properties.')
    clear = args == ['--clear']
    req = urllib.request.Request(url.rstrip('/') + '/v1/notes', method='DELETE' if clear else 'POST',
                                 data=None if clear else json.dumps({'text': ' '.join(args)}).encode(),
                                 headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json',
                                          'User-Agent': 'freemytv-note'})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            body = json.load(r)
    except urllib.error.HTTPError as e:
        sys.exit('The Worker said %s: %s' % (e.code, e.read().decode('utf-8', 'replace')[:200]))
    print('Note cleared.' if clear else 'Note set: "%s" (on the TV within 2 minutes).' % body.get('text', ''))


if __name__ == '__main__':
    main()
