#!/usr/bin/env python3
"""Manage eSIM profiles on the removable eUICC card (5ber/ifree lineage) through the phone.
No root, no app, no vendor servers: APDUs are injected via the IccBr helper (source and
build script in tools/iccbr/), run as shell through app_process64 with a live-binder
logical channel to the card's management applet.

Usage:
  python3 profilemgr.py list
  python3 profilemgr.py enable  <iccid>
  python3 profilemgr.py disable <iccid>
  python3 profilemgr.py delete  <iccid>
  python3 profilemgr.py nickname <iccid> <new name>
  python3 profilemgr.py probe [seconds] [interval]   # map the modem's write window

Env:
  ADB_DEVICE  adb serial (default: first 'device' in `adb devices`)
  CARD_SUB    subscription id of the card (default 22; the card re-enumerates after every
              SIM refresh, so this needs updating when the subscription id changes)
"""
import os
import subprocess
import sys
import time

CARD_SUB = int(os.environ.get("CARD_SUB", "22"))
SLOT, PORT = 0, 0
CARD_AID = "a0000005591010ffffffff8900050500"  # removable card's management applet
HELPER = "CLASSPATH=/data/local/tmp/iccbr.jar /system/bin/app_process64 /system/bin IccBr"


def adb_serial():
    dev = os.environ.get("ADB_DEVICE")
    if dev:
        return dev
    out = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout
    for line in out.splitlines()[1:]:
        if "\tdevice" in line:
            return line.split("\t")[0]
    sys.exit("no adb device found - is wireless debugging connected?")


def run_apdus(lines):
    inp = "\n".join(lines) + "\nquit\n"
    cmd = ["adb", "-s", adb_serial(), "shell",
           f"{HELPER} {CARD_SUB} {SLOT} {PORT} {CARD_AID} com.android.shell"]
    p = subprocess.run(cmd, input=inp, capture_output=True, text=True, timeout=120)
    return p.stdout + p.stderr


def apdu(cla, ins, p1, p2, p3, data=""):
    s = f"{cla:02X} {ins:02X} {p1:02X} {p2:02X} {p3:02X}"
    return (s + " " + data) if data else s


def encode_iccid(iccid):
    s = iccid.strip().upper()
    if len(s) % 2:
        s += "F"
    return "".join(s[i + 1] + s[i] for i in range(0, len(s), 2))


def decode_iccid(b):
    s = ""
    for x in b:
        s += "%X%X" % (x & 0xF, x >> 4)
    return s.rstrip("F")


def tlv_walk(buf):
    i, out = 0, []
    while i + 1 < len(buf):
        t = buf[i]
        i += 1
        if (t & 0x1F) == 0x1F:
            t = (t << 8) | buf[i]
            i += 1
        if i >= len(buf):
            break
        l = buf[i]
        i += 1
        if l & 0x80:
            n = l & 0x7F
            l = int.from_bytes(buf[i:i + n], "big")
            i += n
        out.append((t, buf[i:i + l]))
        i += l
    return out


def parse_profile_list(hexstr):
    raw = bytes.fromhex(hexstr.strip())
    if raw.endswith(b"\x90\x00"):
        raw = raw[:-2]
    profiles = []

    def scan(buf, inside_e3=False):
        for t, v in tlv_walk(buf):
            if t == 0xE3:
                p = {"iccid": None, "aid": None, "state": None, "name": None,
                     "provider": None, "cls": None, "nickname": None}
                for t2, v2 in tlv_walk(v):
                    if t2 == 0x5A:
                        p["iccid"] = decode_iccid(v2)
                    elif t2 in (0x10, 0x4F):
                        p["aid"] = v2.hex()
                    elif t2 == 0x9F70:
                        p["state"] = v2[0] if v2 else None
                    elif t2 == 0x90:
                        p["nickname"] = v2.decode("utf-8", "replace")
                    elif t2 == 0x91:
                        p["name"] = v2.decode("utf-8", "replace")
                    elif t2 == 0x92:
                        p["provider"] = v2.decode("utf-8", "replace")
                    elif t2 == 0x95:
                        p["cls"] = v2[0] if v2 else None
                profiles.append(p)
            else:
                scan(v)
    scan(raw)
    return profiles


def cmd_list():
    out = run_apdus([apdu(0x80, 0xE2, 0x91, 0x00, 0x03, "BF2D00")])
    line = [l for l in out.splitlines() if l.startswith("80 E2")]
    if not line:
        print("no response. raw output:\n" + out)
        return 1
    resp = line[-1].split("=>")[-1].strip()
    if "9000" not in resp:
        print("card returned: " + resp)
        return 1
    for p in parse_profile_list(resp):
        st = {0: "disabled", 1: "ENABLED", None: "?"}.get(p["state"], str(p["state"]))
        cls = {0: "test", 1: "provisioning", 2: "operational"}.get(p["cls"], "?")
        print(f"ICCID {p['iccid']}  [{st}]  name={p['name']}  class={cls}")
        print(f"      provider: {p['provider']}")
        if p["nickname"]:
            print(f"      nickname: {p['nickname']}")
    return 0


def send_profile_op(tag, iccid_hex, extra=""):
    ident = "5A0A" + iccid_hex
    body = ident + extra
    if len(body) % 2:
        body += "0"
    data = tag + "%02X" % (len(body) // 2) + body
    out = run_apdus([apdu(0x80, 0xE2, 0x91, 0x00, len(data) // 2, data)])
    line = [l for l in out.splitlines() if l.startswith("80 E2")]
    if not line:
        print("no response. raw:\n" + out)
        return 1
    resp = line[-1].split("=>")[-1].strip()
    print("card: " + resp)
    if "9000" not in resp:
        return 1
    if "800101" in resp or resp.endswith("9000"):
        print("done.")
        return 0
    return 1


WRITE_CANARY = "80 E2 91 00 14 BF29110000000000000000000000000000000000"
# A deliberately invalid ES10c write (padded BF29): harmless - it cannot change a profile -
# but it exercises the exact APDU path the modem write-blocks with 6F00.


def open_session():
    cmd = ["adb", "-s", adb_serial(), "shell",
           f"{HELPER} {CARD_SUB} {SLOT} {PORT} {CARD_AID} com.android.shell"]
    return subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, bufsize=1)


def cmd_probe(seconds=120.0, interval=1.0):
    """Timeline of write attempts on one live session.

    Toggle the built-in eSIM (Settings -> SIM cards) while this runs: the modem's write
    block (6F00) should lift for a while - this prints when the window opens and closes.
    """
    t0 = time.monotonic()

    def elapsed():
        return time.monotonic() - t0

    def log(msg):
        print(f"[t+{elapsed():6.1f}s] {msg}")

    print(f"canary: {WRITE_CANARY}")
    print(f"probing every {interval:g}s, up to {seconds:g}s - toggle the built-in eSIM to open the window")
    timeline = []  # (t, kind, detail)
    proc = None
    try:
        while elapsed() < seconds:
            if proc is None or proc.poll() is not None:
                if proc is not None:
                    log("-- session died --")
                    timeline.append((elapsed(), "dead", "session died"))
                proc = open_session()
                channel = False
                for line in proc.stdout:
                    line = line.rstrip()
                    if line:
                        log(line)
                    if "CHANNEL=" in line:
                        channel = not line.endswith("-1")
                        break
                    if "[!]" in line:
                        break
                if not channel:
                    timeline.append((elapsed(), "dead", "no channel"))
                    proc = None
                    time.sleep(interval)
                    continue

            proc.stdin.write(WRITE_CANARY + "\n")
            proc.stdin.flush()
            response = None
            for line in proc.stdout:
                line = line.rstrip()
                if line.startswith(WRITE_CANARY[:11]):
                    response = line.split("=>", 1)[1].strip() if "=>" in line else line
                    break
                if line:
                    log(line)
            if response is None or response == "null":
                timeline.append((elapsed(), "dead", response or "no response"))
                proc = None
                continue

            low = response.lower()
            if "6f00" in low:
                kind, what = "blocked", "BLOCKED (6F00)"
            elif response.startswith("ERR"):
                kind, what = "dead", "error"
            else:
                kind, what = "ok", "open (write path)"
            timeline.append((elapsed(), kind, response))
            log(f"write -> {response}   [{what}]")
            time.sleep(interval)
    except KeyboardInterrupt:
        print()
        log("interrupted")
    finally:
        if proc is not None and proc.poll() is None:
            try:
                proc.stdin.write("quit\n")
                proc.stdin.flush()
                proc.wait(timeout=5)
            except Exception:
                proc.kill()

    print("-- summary --")
    start = None
    for t, kind, _ in timeline:
        if kind == "ok" and start is None:
            start = t
        elif kind != "ok" and start is not None:
            print(f"  write path open  t+{start:6.1f}s .. t+{t:6.1f}s  (~{t - start:.0f}s)")
            start = None
    if start is not None:
        print(f"  write path open  t+{start:6.1f}s .. t+{elapsed():6.1f}s  (~{elapsed() - start:.0f}s)")
    if not any(k == "ok" for _, k, _ in timeline):
        print("  no writable window observed")
    ok = sum(1 for _, k, _ in timeline if k == "ok")
    blocked = sum(1 for _, k, _ in timeline if k == "blocked")
    dead = sum(1 for _, k, _ in timeline if k == "dead")
    print(f"  {len(timeline)} probes: {ok} open, {blocked} blocked, {dead} no-session")
    return 0


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    c = sys.argv[1]
    if c == "list":
        return cmd_list()
    if c == "probe":
        seconds = float(sys.argv[2]) if len(sys.argv) > 2 else 120.0
        interval = float(sys.argv[3]) if len(sys.argv) > 3 else 1.0
        return cmd_probe(seconds, interval)
    if c in ("enable", "disable", "delete") and len(sys.argv) == 3:
        tag = {"enable": "BF31", "disable": "BF32", "delete": "BF33"}[c]
        return send_profile_op(tag, encode_iccid(sys.argv[2]))
    if c == "nickname" and len(sys.argv) == 4:
        nn = sys.argv[3].encode("utf-8").hex().upper()
        return send_profile_op("BF29", encode_iccid(sys.argv[2]),
                               "90%02X%s" % (len(nn) // 2, nn))
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main())
