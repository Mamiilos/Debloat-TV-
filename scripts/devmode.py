#!/usr/bin/env python3
"""When the TV's Developer Mode runs out, in ms since 1970 (for About this TV), or nothing when it can't tell.

    python3 scripts/devmode.py 192.168.0.4

Reads the session token on the TV over SSH (the key webos/install.py saved) and asks LG's CheckDevModeSession,
which only reports the time left (HHH:MM:SS); it never extends the session. The token isn't printed or kept.
"""
import json
import os
import re
import sys
import time
import urllib.request

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'webos'))
import install  # noqa: E402


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    ip = sys.argv[1]
    key = install.key_path(ip)
    if not os.path.exists(key):
        return
    token = (install.ssh(ip, key, 'cat /var/luna/preferences/devmode_enabled').stdout or '').strip()
    if not re.match(r'^[0-9A-Za-z]{16,128}$', token):
        print('devmode: no session token on the TV', file=sys.stderr)
        return
    try:
        url = 'https://developer.lge.com/secure/CheckDevModeSession.dev?sessionToken=' + token
        with urllib.request.urlopen(url, timeout=10) as r:
            answer = json.load(r)
    except Exception as e:
        print('devmode: LG didn\'t answer (%s)' % type(e).__name__, file=sys.stderr)
        return
    left = re.match(r'^(\d+):(\d\d):(\d\d)$', str(answer.get('errorMsg', '')))
    if answer.get('result') != 'success' or not left:
        print('devmode: LG said %s' % answer.get('result'), file=sys.stderr)
        return
    h, m, s = (int(x) for x in left.groups())
    print(int((time.time() + h * 3600 + m * 60 + s) * 1000))


if __name__ == '__main__':
    main()
