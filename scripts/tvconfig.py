#!/usr/bin/env python3
"""The LG TV's settings: tv.properties (tracked) with the secrets from config.properties (ignored).

    python3 scripts/tvconfig.py merge   # writes out/effective.properties for webos/build.py --config
    python3 scripts/tvconfig.py hash    # SHA-256 of the effective settings, secrets left out

The hash is over the parsed key=value pairs, sorted, so comments and spacing don't change it.
"""
import hashlib
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TRACKED = os.path.join(ROOT, 'tv.properties')
SECRETS = os.path.join(ROOT, 'config.properties')
OUT = os.path.join(ROOT, 'out', 'effective.properties')
SECRET_KEYS = ('tmdb_key', 'watchmode_key', 'plex_url', 'plex_token')


def read(path):
    values = {}
    if not os.path.exists(path):
        return values
    with open(path, encoding='utf-8-sig') as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith('#') and '=' in line:
                key, value = line.split('=', 1)
                values[key.strip()] = value.strip()
    return values


def settings():
    if not os.path.exists(TRACKED):
        sys.exit('tv.properties is missing.')
    tracked = read(TRACKED)
    leaked = [k for k in SECRET_KEYS if tracked.get(k)]
    if leaked:
        sys.exit('tv.properties is tracked by git: move %s to config.properties.' % ', '.join(leaked))
    secrets = read(SECRETS)
    tracked.update((k, secrets[k]) for k in SECRET_KEYS if secrets.get(k))
    return tracked


def digest(values):
    public = sorted((k, v) for k, v in values.items() if k not in SECRET_KEYS)
    return hashlib.sha256(''.join('%s=%s\n' % kv for kv in public).encode('utf-8')).hexdigest()


def main():
    command = sys.argv[1] if len(sys.argv) > 1 else ''
    values = settings()
    if command == 'merge':
        os.makedirs(os.path.dirname(OUT), exist_ok=True)
        fd = os.open(OUT, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, 'w', encoding='utf-8') as f:
            f.write('# Written by scripts/tvconfig.py from tv.properties and config.properties: edit those.\n')
            f.writelines('%s=%s\n' % kv for kv in sorted(values.items()))
        print(OUT)
    elif command == 'hash':
        print(digest(values))
    else:
        sys.exit(__doc__)


if __name__ == '__main__':
    main()
