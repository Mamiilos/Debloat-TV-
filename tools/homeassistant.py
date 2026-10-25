"""Puts Home Assistant entities in Free My TV's bottom drawer: lights, switches, fans and covers toggle with OK,
scenes and scripts run, sensors show their value.

    python3 tools/homeassistant.py --url http://192.168.1.10:8123 light.living_room switch.tv_lamp sensor.indoor_temp
    python3 tools/homeassistant.py --url ... "light.desk=Desk lamp"     # a name of your own
    python3 tools/homeassistant.py --off                                 # takes it all off the TV

The long-lived token (Home Assistant: your profile -> Security -> Long-lived access tokens) is asked for here, or read
from HA_TOKEN. It goes from this computer to the app over adb (HaInbox), never on the screen or into the APK.
Use the address the TV reaches: an IP is safer than homeassistant.local, which not every TV resolves.
"""
import argparse
import getpass
import json
import os
import shutil
import subprocess
import sys
import urllib.error
import urllib.request

URI = 'content://io.freemytv.ha/config'
SERIAL = []   # -s <ip>:5555 once connected, so a phone or another TV on adb doesn't get in the way


def adb(*args, **kw):
    return subprocess.run(['adb', *SERIAL, *args], capture_output=True, text=True, **kw)


def ha_get(url, token, path):
    req = urllib.request.Request(url + path, headers={'Authorization': 'Bearer ' + token})
    with urllib.request.urlopen(req, timeout=8) as r:
        return json.load(r)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('entities', nargs='*', help='entity ids, in drawer order (entity_id=Name for a name of your own)')
    p.add_argument('--url', help='Home Assistant address, e.g. http://192.168.1.10:8123')
    p.add_argument('--tv', help="the TV's IP (default: the TV adb is already connected to)")
    p.add_argument('--off', action='store_true', help='remove Home Assistant from the TV')
    a = p.parse_args()

    if not shutil.which('adb'):
        sys.exit('adb not found: install the Android platform tools (see the README).')
    if a.tv:
        target = a.tv if ':' in a.tv else a.tv + ':5555'
        r = adb('connect', target)
        if 'connected' not in r.stdout:
            sys.exit(f'Could not connect to {target}: {r.stdout.strip() or r.stderr.strip()}')
        SERIAL.extend(['-s', target])
    if 'package:' not in adb('shell', 'pm', 'list', 'packages', 'io.freemytv').stdout:
        sys.exit('No TV with Free My TV connected: give its IP with --tv (adb devices lists the connected ones).')

    if a.off:
        adb('shell', 'content', 'delete', '--uri', URI)
        print('Home Assistant is off the TV: the drawer drops it the next time it opens.')
        return
    if not a.url or not a.entities:
        p.error('give --url and at least one entity id')

    url = a.url.rstrip('/')
    token = os.environ.get('HA_TOKEN') or getpass.getpass('Long-lived access token (not shown): ')
    entities = []
    for e in a.entities:
        eid, _, name = e.partition('=')
        entities.append({'id': eid.strip(), 'name': name.strip()})

    # Checked from here first: a typo shows now, not as an empty button on the TV.
    try:
        ha_get(url, token, '/api/')
    except urllib.error.HTTPError as e:
        sys.exit('Home Assistant refused the token.' if e.code == 401 else f'Home Assistant answered HTTP {e.code}.')
    except Exception as e:
        sys.exit(f'Could not reach {url}: {e}')
    for e in entities:
        try:
            s = ha_get(url, token, '/api/states/' + e['id'])
            print(f"  {e['id']}: {e['name'] or s.get('attributes', {}).get('friendly_name', '')} ({s.get('state')})")
        except urllib.error.HTTPError as err:
            sys.exit(f"{e['id']}: not found in Home Assistant (HTTP {err.code})." if err.code == 404 else
                     f"{e['id']}: HTTP {err.code}.")

    data = json.dumps({'url': url, 'token': token, 'entities': entities}).encode()
    r = subprocess.run(['adb', *SERIAL, 'exec-in', 'content', 'write', '--uri', URI], input=data, capture_output=True)
    if r.returncode != 0:
        sys.exit('The TV refused it: is this Free My TV new enough? ' + r.stderr.decode(errors='replace').strip())
    print(f'Sent {len(entities)} entities. Open the drawer (down from the shelf) to see them.')


if __name__ == '__main__':
    main()
