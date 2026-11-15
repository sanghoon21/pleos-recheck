"""Capture the installed APK's real UI using deterministic, explicitly fictional observations.
No production/physical device is touched. Default target is the dedicated task emulator.
"""
from pathlib import Path
import argparse
import json
import os
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description='Capture the ReCheck DEMO flow on an existing 1600x900 test emulator.')
parser.add_argument('--adb', default=os.environ.get('ADB') or shutil.which('adb'), help='Path to Android SDK adb (or set ADB / PATH).')
parser.add_argument('--serial', default='emulator-5554', help='Dedicated test emulator serial; DEMO records will be reset.')
args = parser.parse_args()
if not args.adb:
    parser.error('Set --adb to the installed Android SDK adb executable, or add adb to PATH.')
ADB = Path(args.adb)
OUT = ROOT / 'evidence'
OUT.mkdir(exist_ok=True)
serial = args.serial

def adb(*args):
    return subprocess.run([str(ADB), '-s', serial, *args], check=True, capture_output=True, text=True)

def tap(x, y, pause=.7):
    adb('shell', 'input', 'tap', str(x), str(y))
    time.sleep(pause)

screens = {}
def shot(name):
    adb('shell', 'screencap', '-p', '/sdcard/recheck.png')
    path = OUT / (name + '.png')
    adb('pull', '/sdcard/recheck.png', str(path))
    screens[name] = path.name
    print('Captured', name, flush=True)

adb('shell', 'am', 'force-stop', 'io.recheck.app')
adb('shell', 'am', 'start', '-n', 'io.recheck.app/.MainActivity', '--ez', 'reset_demo', 'true')
time.sleep(2)
shot('home1')
recorder = subprocess.Popen([str(ADB), '-s', serial, 'shell', 'screenrecord', '--size', '1600x900',
                             '--bit-rate', '8000000', '--time-limit', '120', '/sdcard/recheck-session.mp4'],
                            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                            creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
start = time.monotonic()
timeline = {}
def mark(name):
    timeline[name] = round(time.monotonic() - start, 2)

time.sleep(1)
mark('record1_dialog')
tap(350, 674)
tap(1090, 503)
mark('record1_start')
time.sleep(5.5)
shot('record1')
mark('record1_complete')
time.sleep(1)
tap(1340, 550)
time.sleep(1)
mark('compare1')
tap(740, 783)
tap(1090, 503)
mark('record2_start')
time.sleep(5.5)
shot('record2')
tap(1340, 550)
time.sleep(1)
shot('compare2')
mark('compare2')
time.sleep(1)
tap(740, 783)
tap(1090, 503)
mark('record3_start')
time.sleep(5.5)
shot('record3')
mark('record3_complete')
time.sleep(1)
tap(1340, 550)
time.sleep(1)
shot('compare3')
mark('compare3')
time.sleep(1)
tap(1290, 783)
shot('report')
mark('report')
time.sleep(1)
tap(1300, 676, 1.2)
shot('share')
mark('share')
adb('shell', 'input', 'keyevent', '4')
time.sleep(.7)
mark('force_stop')
adb('shell', 'am', 'force-stop', 'io.recheck.app')
time.sleep(1)
adb('shell', 'am', 'start', '-n', 'io.recheck.app/.MainActivity')
time.sleep(2)
shot('home3')
mark('reopened_without_seed_extras')
tap(820, 675)
shot('compare3_reopened')
time.sleep(1)
adb('shell', 'am', 'start', '-n', 'io.recheck.app/.MainActivity', '--es', 'screen', 'incomplete', '--ei', 'stage', '3')
time.sleep(1)
shot('incomplete')
mark('incomplete_fixture')
time.sleep(1)
adb('shell', 'am', 'start', '-n', 'io.recheck.app/.MainActivity', '--es', 'screen', 'unsupported')
time.sleep(1)
shot('unsupported')
mark('standard_android_without_vehicle_service')
time.sleep(1)
adb('shell', 'pkill', '-INT', 'screenrecord')
recorder.wait(timeout=10)
adb('pull', '/sdcard/recheck-session.mp4', str(OUT / 'recheck-session-actual-apk.mp4'))
(OUT / 'screenshots.json').write_text(json.dumps(screens, ensure_ascii=False, indent=2), encoding='utf-8')
(OUT / 'timeline.json').write_text(json.dumps(timeline, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(timeline, ensure_ascii=False), flush=True)
