#!/usr/bin/env python3
"""Sends 4K wallpapers to the TV for the Apple TV theme (theme=appletv).

    python3 tools/walls4k.py 192.168.1.50 Mojave-Day.jpg Wallpapers/
    python3 tools/walls4k.py 192.168.1.50 --list
    python3 tools/walls4k.py 192.168.1.50 --clear

Android draws its interface at 1920x1080 on most 4K TVs and stretches it to the panel, so a picture in the home
screen can never be sharper than that. Video goes to the panel by another way, at the panel's own size: each picture
becomes a still 4K HEVC video (3840x2160, 10-bit, BT.709), which the home screen shows on the TV's video layer behind
its tiles, and a 1920x1080 JPEG, for the frosted glass and for the moment before the video shows.

Each picture is cropped to 16:9 around its center. Start from the largest you have (the macOS wallpapers come at 5K
and 6K): a smaller one is scaled up, and says so. A time of day in the name (dawn, day, dusk, night, as in
"10-14-Day-6k.jpg") stays in it, and the TV shows that picture only then; the others at any time, one per hour.
Needs ffmpeg (with libx265) and adb. The files go to the app's own folder on the TV
(Android/data/io.freemytv/files/walls4k); nothing else on the TV changes.
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

FOLDER = '/sdcard/Android/data/io.freemytv/files/walls4k'
KINDS = ('.jpg', '.jpeg', '.png', '.webp', '.heic', '.heif', '.tif', '.tiff')
PHASE = re.compile(r'(?:^|[^a-z])(dawn|day|dusk|night)(?:[^a-z]|$)')
SERIAL = []


def adb(*args):
    return subprocess.run(['adb', *SERIAL, *args], capture_output=True, text=True)


def pictures(paths):
    for p in paths:
        if os.path.isdir(p):
            for name in sorted(os.listdir(p)):
                if name.lower().endswith(KINDS) and not name.startswith('.'):
                    yield os.path.join(p, name)
        elif os.path.isfile(p) and p.lower().endswith(KINDS):
            yield p
        else:
            print(f'Skipped {p}: not a picture or a folder.')


def name_for(path):
    """dawn-tahoe-beach-dawn, or any-golden-gate: the time of day first, then the picture's name."""
    stem = os.path.splitext(os.path.basename(path))[0].lower()
    m = PHASE.search(stem)
    slug = re.sub(r'[^a-z0-9]+', '-', stem).strip('-')[:60] or 'wallpaper'
    phase = m.group(1) if m else 'any'
    return slug if slug.startswith(phase + '-') else f'{phase}-{slug}'


def size_of(path):
    r = subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries', 'stream=width,height',
                        '-of', 'csv=p=0', path], capture_output=True, text=True)
    try:
        w, h = r.stdout.strip().split(',')[:2]
        return int(w), int(h)
    except ValueError:
        return None


def crop(w, h, extra=''):
    # Cover the frame, then cut the middle: the same as the home screen does with photos.
    return f'scale={w}:{h}:force_original_aspect_ratio=increase:flags=lanczos{extra},crop={w}:{h}'


def convert(src, out_dir, name):
    video, still = os.path.join(out_dir, name + '.mp4'), os.path.join(out_dir, name + '.jpg')
    # Two frames a second apart: one would do, but some decoders show nothing from a single-frame stream. sRGB in,
    # BT.709 out with the range and tags set, so the TV doesn't read it as BT.601 and shift the colors.
    run = subprocess.run([
        'ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-loop', '1', '-i', src,
        '-vf', crop(3840, 2160, ':out_color_matrix=bt709:out_range=tv') + ',format=yuv420p10le',
        '-t', '2', '-r', '1', '-c:v', 'libx265', '-preset', 'slow', '-crf', '12',
        # No B-frames: a still needs none, and with them the MP4 muxer refuses some pictures (pts/dts pair unsupported).
        '-x265-params', 'log-level=error:bframes=0:colorprim=bt709:transfer=bt709:colormatrix=bt709:range=limited',
        '-color_primaries', 'bt709', '-color_trc', 'bt709', '-colorspace', 'bt709', '-color_range', 'tv',
        '-tag:v', 'hvc1', '-movflags', '+faststart', '-an', video], capture_output=True, text=True)
    if run.returncode != 0:
        print('   ' + run.stderr.strip().splitlines()[-1] if run.stderr.strip() else '')
        return None
    run = subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-i', src, '-vf', crop(1920, 1080),
                          '-frames:v', '1', '-q:v', '2', still], capture_output=True, text=True)
    return (video, still) if run.returncode == 0 else None


def main():
    args = sys.argv[1:]
    if not args or args[0] in ('-h', '--help'):
        print(__doc__)
        return 0
    for tool in ('adb', 'ffmpeg', 'ffprobe'):
        if shutil.which(tool) is None:
            print(f'{tool} not found. macOS: brew install ffmpeg android-platform-tools')
            return 1
    if re.match(r'^[\d.]+(:\d+)?$', args[0]):
        serial = args[0] if ':' in args[0] else args[0] + ':5555'
        adb('connect', serial)
        SERIAL.extend(['-s', serial])
        args = args[1:]
    if adb('get-state').stdout.strip() != 'device':
        print('The TV is not connected: accept "Allow USB debugging?" on the TV, then run again.')
        return 1
    if args == ['--list']:
        print(adb('shell', 'ls', '-l', FOLDER).stdout or 'None yet.')
        return 0
    if args == ['--clear']:
        adb('shell', 'rm', '-rf', FOLDER)
        print('Removed the 4K wallpapers from the TV. The home screen goes back to My Photos or its own sky.')
        return 0
    if adb('shell', 'mkdir', '-p', FOLDER).returncode != 0:
        print('This TV keeps adb out of the app\'s folder, so 4K wallpapers can\'t be sent to it this way.')
        return 1
    sent = 0
    with tempfile.TemporaryDirectory() as tmp:
        for src in pictures(args):
            size = size_of(src)
            if size is None:
                print(f'Skipped {src}: ffmpeg can\'t read it.')
                continue
            name = name_for(src)
            # Covering 3840x2160 needs this much scaling; above 1 the picture is stretched.
            up = max(3840 / size[0], 2160 / size[1])
            note = f' (only {size[0]}x{size[1]}: scaled up {up:.1f}x, softer than true 4K)' if up > 1.01 else ''
            print(f'{os.path.basename(src)} -> {name}{note}')
            files = convert(src, tmp, name)
            if not files:
                print('   ffmpeg failed; skipped.')
                continue
            for f in files:
                # Under a .part name first: the TV never picks a file that is still arriving.
                part = f'{FOLDER}/{os.path.basename(f)}.part'
                if adb('push', f, part).returncode != 0 or \
                        adb('shell', 'mv', '-f', part, f'{FOLDER}/{os.path.basename(f)}').returncode != 0:
                    print(f'   Couldn\'t send {os.path.basename(f)}.')
                    break
            else:
                sent += 1
    print(f'Sent {sent} 4K wallpaper(s). The Apple TV home screen shows them from its next minute.')
    return 0 if sent else 1


if __name__ == '__main__':
    sys.exit(main())
