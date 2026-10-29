#!/usr/bin/env python3
"""The phone's key for the TV's note (the iPhone Shortcuts), separate from the Mac's so it can be revoked alone.

    python3 scripts/phone_key.py revoke [--dry-run]   # make revoke-phone: only the Mac's key works from now on
    python3 scripts/phone_key.py new [--dry-run]      # make new-phone-key: a fresh phone key, Shortcuts rebuilt

The Worker's ADMIN_TOKEN is the list of keys that may write the note (comma separated): the Mac's worker_admin_token,
plus worker_phone_token when there is one (both in config.properties). No key is ever printed.
"""
import os
import re
import secrets
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG = os.path.join(ROOT, 'config.properties')
SHORTCUTS = os.path.join(ROOT, 'out', 'shortcuts')


def read_key(text, name):
    m = re.search(r'(?m)^%s=(.+)$' % name, text)
    return m.group(1).strip() if m else ''


def set_admin_tokens(tokens, dry):
    """ADMIN_TOKEN on the Worker, through wrangler's stdin (never an argument or the screen)."""
    print('Worker ADMIN_TOKEN → %d key(s): %s' % (len(tokens), ', '.join(['the Mac', 'the phone'][:len(tokens)])))
    if dry:
        return
    env = dict(os.environ, WRANGLER_SEND_METRICS='false')
    result = subprocess.run(['npx', '--yes', 'wrangler@4.150.0', 'secret', 'put', 'ADMIN_TOKEN'], cwd=os.path.join(ROOT, 'worker'),
                            input=','.join(tokens), capture_output=True, text=True, env=env)
    if result.returncode != 0 or 'Success' not in result.stdout + result.stderr:
        sys.exit('wrangler failed (needs Node 22 and the Cloudflare login): %s' % (result.stderr or result.stdout).strip()[-300:])


def write_config(text, dry):
    if dry:
        return
    fd = os.open(CONFIG, os.O_WRONLY | os.O_TRUNC)
    with os.fdopen(fd, 'w', encoding='utf-8') as f:
        f.write(text)


def main():
    args = sys.argv[1:]
    dry = '--dry-run' in args
    command = next((a for a in args if not a.startswith('--')), '')
    if command not in ('revoke', 'new'):
        sys.exit(__doc__)
    with open(CONFIG, encoding='utf-8') as f:
        text = f.read()
    mac = read_key(text, 'worker_admin_token')
    if not mac:
        sys.exit('No worker_admin_token in config.properties: the Mac\'s key is needed either way.')
    text = re.sub(r'(?m)^worker_phone_token=.*\n?', '', text)
    if dry:
        print('(dry run: nothing changes)')

    if command == 'revoke':
        set_admin_tokens([mac], dry)
        write_config(text, dry)
        print('config.properties: worker_phone_token removed')
        if os.path.isdir(SHORTCUTS):
            print('out/shortcuts: deleted')
            if not dry:
                shutil.rmtree(SHORTCUTS)
        print('Done: the phone\'s Shortcuts now get {"error":"admin token"}; make note still works.')
        return

    phone = secrets.token_urlsafe(32)
    set_admin_tokens([mac, phone], dry)
    write_config(text.rstrip('\n') + '\nworker_phone_token=%s\n' % phone, dry)
    print('config.properties: new worker_phone_token')
    if dry:
        print('Would rebuild the Shortcuts (scripts/make_shortcuts.py).')
        return
    subprocess.run([sys.executable, os.path.join(ROOT, 'scripts', 'make_shortcuts.py')], check=True)
    print('Done: AirDrop the two new Shortcuts to the phone (replace the old ones), then delete out/shortcuts.')


if __name__ == '__main__':
    main()
