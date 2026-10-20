#!/usr/bin/env python3
"""Create a pinned NoCloud configuration ISO for one owned VM. Requires pycdlib 1.22.0."""
import argparse
import hashlib
import io
import ipaddress
import json
from pathlib import Path
import re
import sys
import pycdlib

parser = argparse.ArgumentParser()
parser.add_argument("--attempt", required=True)
parser.add_argument("--mac", required=True)
parser.add_argument("--address", required=True)
parser.add_argument("--public-key", required=True, type=Path)
parser.add_argument("--output", required=True, type=Path)
args = parser.parse_args()
if not re.fullmatch("[a-f0-9]{32}", args.attempt) or not re.fullmatch("(?:[0-9a-f]{2}:){5}[0-9a-f]{2}", args.mac):
    raise SystemExit("INVALID_WORKER_ID")
address = str(ipaddress.IPv4Interface(args.address))
public_key = args.public_key.read_text(encoding="utf-8").strip()
if not re.fullmatch(r"ssh-ed25519 [A-Za-z0-9+/=]+(?: [^\r\n]+)?", public_key):
    raise SystemExit("INVALID_CONTROLLER_PUBLIC_KEY")
runner = Path(__file__).with_name("runner.py").read_text(encoding="utf-8")
user_data = {"users": [
    {"name": "evoctl", "groups": ["sudo"], "sudo": ["ALL=(ALL) NOPASSWD:ALL"],
     "shell": "/bin/bash", "lock_passwd": True, "ssh_authorized_keys": [public_key]},
    {"name": "evobuild", "shell": "/bin/bash", "lock_passwd": True}],
    "ssh_pwauth": False, "ssh_deletekeys": False, "disable_root": True,
    "write_files": [{"path": "/usr/local/lib/evo/runner.py", "owner": "root:root", "permissions": "0700", "content": runner}],
    "runcmd": [["usermod", "-L", "nguyen"], ["chmod", "0700", "/home/nguyen", "/home/evoctl"],
               ["mkdir", "-p", "/work"], ["chmod", "0755", "/work"]]}
metadata = {"instance-id": "evo-" + args.attempt, "local-hostname": "evo-worker"}
network = {"version": 2, "ethernets": {"evo": {"match": {"macaddress": args.mac}, "set-name": "evo0",
            "addresses": [address], "dhcp4": False, "dhcp6": False, "optional": True}}}
iso = pycdlib.PyCdlib()
iso.new(interchange_level=3, joliet=3, rock_ridge="1.09", vol_ident="cidata")
for name, iso_name, value in [("user-data", "USERDATA", "#cloud-config\n" + json.dumps(user_data)),
                               ("meta-data", "METADATA", json.dumps(metadata)),
                               ("network-config", "NETWORK", json.dumps(network))]:
    raw = (value + "\n").encode("utf-8")
    iso.add_fp(io.BytesIO(raw), len(raw), iso_path="/" + iso_name + ".;1", rr_name=name, joliet_path="/" + name)
if args.output.exists():
    raise SystemExit("SEED_ALREADY_EXISTS")
args.output.parent.mkdir(parents=True, exist_ok=True)
iso.write(str(args.output))
iso.close()
print(json.dumps({"schema": "hyperv-worker-seed:m8.4-v1", "attempt": args.attempt,
                  "sha256": hashlib.sha256(args.output.read_bytes()).hexdigest(), "bytes": args.output.stat().st_size}))
