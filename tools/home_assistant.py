#!/usr/bin/env python3
"""Sends your Home Assistant token to the TV, for Free My TV's Home Assistant buttons (in the bottom drawer).

    python3 tools/home_assistant.py 192.168.1.50            # asks for the token (it isn't shown), checks it, sends it
    python3 tools/home_assistant.py                         # the TV adb is already connected to
    python3 tools/home_assistant.py 192.168.1.50 --forget   # takes the token off the TV

The address and the entities are in config.properties (home_assistant_url, home_assistant_entities) and go into the
build; the token never does, since any app on the TV can read an installed APK. It goes through adb into the app's
private files (only adb may write there), and stays until --forget or until Free My TV is uninstalled. Create it in
Home Assistant: your profile > Security > Long-lived access tokens; a Home Assistant user of its own that isn't an
administrator is safest. Instead of typing it, it can come in the HOME_ASSISTANT_TOKEN environment variable.

Before sending, it checks the token with your server and lists what each entity becomes on the TV; after, it asks the
TV whether the token is there (never what it is). Android 9 or newer: Android 8's adb shell can't write to the app.
"""
import getpass
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
URI = 'content://io.freemytv.homeassistant/token'
# What the app shows (HomeAssistant.DOMAINS, SAFE_COVERS, MAX): keep in step.
DOMAINS = ('light', 'switch', 'fan', 'input_boolean', 'cover', 'scene', 'script', 'automation', 'button',
           'input_button')
SAFE_COVERS = ('awning', 'blind', 'curtain', 'shade', 'shutter')
MAX = 8
SERIAL = []   # -s <ip>:5555 once connected, so a phone or another TV on adb doesn't get in the way


def adb(*args):
    return subprocess.run(['adb', *SERIAL, *args], capture_output=True, text=True)


def prop(key):
    path = os.path.join(ROOT, 'config.properties')
    value = ''
    if os.path.exists(path):
        with open(path, encoding='utf-8') as f:
            for line in f:
                k, sep, v = line.partition('=')
                if sep and k.strip() == key:
                    value = v.strip().strip('"')
    return value


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """The app follows none (the token would go along), so neither does the check."""
    def redirect_request(self, *args):
        return None


def ha_get(url, token, path):
    req = urllib.request.Request(url + path, headers={'Authorization': 'Bearer ' + token, 'User-Agent': 'FreeMyTV'})
    with urllib.request.build_opener(NoRedirect).open(req, timeout=10) as r:
        return json.loads(r.read().decode('utf-8'))


def check(url, token, entities):
    """True when the server takes the token; prints what each entity becomes."""
    try:
        ha_get(url, token, '/api/')
    except urllib.error.HTTPError as e:
        print('Home Assistant refused the token (HTTP 401).' if e.code == 401
              else f'Home Assistant answered HTTP {e.code} at {url}/api/.')
        return False
    except (urllib.error.URLError, OSError) as e:
        print(f'Could not reach Home Assistant at {url}: {getattr(e, "reason", e)}')
        return False
    shown = 0
    for entity in entities:
        domain = entity.split('.', 1)[0]
        if domain not in DOMAINS:
            print(f'  {entity}: left out (only {", ".join(DOMAINS)})')
            continue
        if shown == MAX:
            print(f'  {entity}: left out (at most {MAX})')
            continue
        try:
            state = ha_get(url, token, '/api/states/' + entity)
        except urllib.error.HTTPError as e:
            print(f'  {entity}: ' + ('not found in Home Assistant' if e.code == 404 else f'HTTP {e.code}'))
            continue
        attributes = state.get('attributes') or {}
        dc = str(attributes.get('device_class') or '').lower()
        if domain == 'cover' and dc not in SAFE_COVERS:
            print(f'  {entity}: left out ({"a " + dc if dc else "a cover without a class"}; only {", ".join(SAFE_COVERS)})')
            continue
        shown += 1
        print(f'  {entity}: "{attributes.get("friendly_name") or entity}", {state.get("state")}')
    if shown == 0:
        print('None of home_assistant_entities can be a button: the drawer would stay without them.')
    return True


def status():
    """(offered, present) as the app answers it, or None when the Free My TV there can't say (too old, or none)."""
    r = adb('shell', 'content', 'query', '--uri', URI)
    row = dict(kv.split('=', 1) for kv in r.stdout.replace('Row: 0 ', '').strip().split(', ') if '=' in kv)
    if 'offered' not in row or 'present' not in row:
        return None
    return row['offered'] == '1', row['present'] == '1'


def wait_present(want):
    """The app keeps the token once the write closes, a moment after adb returns: ask a few times."""
    for _ in range(10):
        s = status()
        if s and s[1] == want:
            return True
        time.sleep(0.5)
    return False


def main():
    args = sys.argv[1:]
    if args and args[0] in ('-h', '--help'):
        print(__doc__.strip())
        return 0
    forget = '--forget' in args
    args = [a for a in args if a != '--forget']
    if not shutil.which('adb'):
        print('adb not found: install the Android platform tools (see the README).')
        return 1
    if args:
        ip = args.pop(0)
        target = ip if ':' in ip else ip + ':5555'
        r = adb('connect', target)
        if 'connected' not in r.stdout:
            print(f'Could not connect to {target}: {(r.stdout + r.stderr).strip()}')
            return 1
        SERIAL[:] = ['-s', target]
    listed = adb('shell', 'pm', 'list', 'packages', 'io.freemytv')
    if listed.returncode != 0:
        print('No TV connected: give its IP address first (adb devices lists the connected ones).')
        return 1
    if 'package:io.freemytv' not in listed.stdout.split():
        print('Free My TV is not installed on this TV.')
        return 1
    # exec-in returns 0 whatever the TV printed: only the app's own answer tells what happened.
    sdk = adb('shell', 'getprop', 'ro.build.version.sdk').stdout.strip()
    if not forget and sdk.isdigit() and int(sdk) < 28:
        print('This TV runs Android 8, whose adb can\'t write to the app (content write came with Android 9): '
              'the Home Assistant buttons need Android 9 or newer.')
        return 1
    state = status()
    if state is None:
        print('The Free My TV on this TV is too old for this: build and install it again (see Install), then run this again.')
        return 1
    if not state[0]:
        print('The Free My TV on this TV was built without home_assistant_url and home_assistant_entities: set them in '
              'config.properties, build and install it again, then run this again.')
        return 1

    if forget:
        adb('shell', 'content', 'delete', '--uri', URI)
        if not wait_present(False):
            print('The token is still on the TV: run this again.')
            return 1
        print('The Home Assistant token is off the TV: its buttons stay Unavailable until you send one again.')
        return 0

    url = prop('home_assistant_url').rstrip('/')
    entities = [e.strip().lower() for e in prop('home_assistant_entities').split(',') if e.strip()]
    if not url or not entities:
        print('Set home_assistant_url and home_assistant_entities in config.properties first, then build and install.')
        return 1
    token = os.environ.get('HOME_ASSISTANT_TOKEN', '').strip() or getpass.getpass('Long-lived access token (not shown): ').strip()
    if not token:
        print('No token given.')
        return 1
    print(f'Checking with {url}...')
    if not check(url, token, entities):
        return 1
    data = token.encode('utf-8')
    # The old one goes first, so "there" afterwards can only mean this one arrived.
    if state[1]:
        adb('shell', 'content', 'delete', '--uri', URI)
        if not wait_present(False):
            print('Could not replace the token already on the TV: run this again.')
            return 1
    # With its size, so the app keeps only a whole one; exec-in, so the token never shows in a command line.
    r = subprocess.run(['adb', *SERIAL, 'exec-in', 'content', 'write', '--uri', f'{URI}?size={len(data)}'],
                       input=data, capture_output=True)
    if r.returncode != 0 or not wait_present(True):
        err = (r.stdout + r.stderr).decode('utf-8', 'replace').strip()
        print('The token didn\'t arrive on the TV' + (f': {err}' if err else '') + '. Run this again; `adb logcat -s FreeMyTV` '
              'says why ("token not received").')
        return 1
    print('The token is on the TV. There: Home Settings > Home Assistant > On, then open the bottom drawer.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
