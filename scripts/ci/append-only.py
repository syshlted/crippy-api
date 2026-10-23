#!/usr/bin/env python3
"""The contract's append-only rule (AGENTS.md L2), checked against a release.

    scripts/ci/append-only.py <git ref>              # the contract at <ref> against the working tree
    scripts/ci/append-only.py --dirs <old> <new>     # two aidl directories, for testing the script

A method's transaction code is its position in its interface, and a parcelable's fields are read in
order, so once a release has shipped:

- every interface's methods at the release are a prefix of its methods now, each unchanged;
- every parcelable's fields at the release are a prefix of its fields now, each unchanged;
- every constant keeps its value, but for VERSION, which rises as methods are appended and never falls;
- no interface or parcelable is removed, and none changes kind.

What is new is allowed: methods and fields appended at the end, constants added, files added. Exit 1
names every breach; exit 0 says what was compared.
"""

import argparse
import pathlib
import re
import subprocess
import sys
import tempfile

AIDL_DIR = "encri-contract/src/main/aidl"

COMMENT = re.compile(r"//[^\n]*|/\*.*?\*/", re.S)
DECLARATION = re.compile(r"\b(oneway\s+)?(interface|parcelable)\s+(\w+)\s*\{(.*)\}", re.S)
CONSTANT = re.compile(r"^const\s+\w+\s+(\w+)\s*=\s*(.+)$")
VERSION = "VERSION"


def statements(body):
    """The body's statements, each with its whitespace collapsed, in order."""
    return [" ".join(part.split()) for part in body.split(";") if part.strip()]


def parse(text):
    """(kind, name, members, constants) of one .aidl file, or None for one that declares nothing."""
    code = COMMENT.sub(" ", text)
    found = DECLARATION.search(code)
    if not found:
        return None
    oneway, kind, name, body = found.groups()
    if oneway:
        kind = "oneway " + kind
    members, constants = [], {}
    for statement in statements(body):
        constant = CONSTANT.match(statement)
        if constant:
            constants[constant.group(1)] = constant.group(2)
        else:
            members.append(statement)
    return kind, name, members, constants


def load(root):
    """Every declaration under root, by its path relative to root."""
    root = pathlib.Path(root)
    contract = {}
    for path in sorted(root.rglob("*.aidl")):
        parsed = parse(path.read_text())
        if parsed:
            contract[str(path.relative_to(root))] = parsed
    return contract


def compare(old, new):
    """Every way new breaks what old promised, as sentences."""
    breaches = []
    for path, (kind, name, members, constants) in old.items():
        if path not in new:
            breaches.append(f"{path}: {kind} {name} is gone")
            continue
        new_kind, new_name, new_members, new_constants = new[path]
        if (new_kind, new_name) != (kind, name):
            breaches.append(f"{path}: {kind} {name} is now {new_kind} {new_name}")
        what = "method" if "interface" in kind else "field"
        for index, member in enumerate(members):
            if index >= len(new_members):
                breaches.append(f"{path}: {what} {index + 1}, `{member}`, is gone")
            elif new_members[index] != member:
                breaches.append(f"{path}: {what} {index + 1} was `{member}` and is `{new_members[index]}`")
        for constant, value in constants.items():
            if constant not in new_constants:
                breaches.append(f"{path}: constant {constant} is gone")
            elif constant == VERSION and int(new_constants[constant]) < int(value):
                breaches.append(f"{path}: {VERSION} fell from {value} to {new_constants[constant]}")
            elif constant != VERSION and new_constants[constant] != value:
                breaches.append(f"{path}: constant {constant} was {value} and is {new_constants[constant]}")
    return breaches


def at_ref(ref):
    """The contract as it was at ref, from git."""
    listing = subprocess.run(
        ["git", "ls-tree", "-r", "--name-only", ref, AIDL_DIR], check=True, capture_output=True, text=True
    ).stdout.split()
    staging = pathlib.Path(tempfile.mkdtemp(prefix="contract-"))
    for name in listing:
        if not name.endswith(".aidl"):
            continue
        text = subprocess.run(["git", "show", f"{ref}:{name}"], check=True, capture_output=True, text=True).stdout
        target = staging / pathlib.Path(name).relative_to(AIDL_DIR)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
    return load(staging)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("ref", nargs="?", help="the release to compare against, such as a v* tag")
    parser.add_argument("--dirs", nargs=2, metavar=("OLD", "NEW"), help="compare two aidl directories instead")
    args = parser.parse_args()
    if args.dirs:
        old, new, against = load(args.dirs[0]), load(args.dirs[1]), args.dirs[0]
    elif args.ref:
        old, new, against = at_ref(args.ref), load(AIDL_DIR), args.ref
    else:
        parser.error("name a ref, or --dirs")
    if not old:
        print(f"append-only: nothing to compare: {against} holds no contract", file=sys.stderr)
        return 1
    breaches = compare(old, new)
    for breach in breaches:
        print(f"append-only: {breach}", file=sys.stderr)
    if breaches:
        print(f"append-only: the contract at {against} is not a prefix of this one (L2)", file=sys.stderr)
        return 1
    methods = sum(len(members) for kind, _, members, _ in old.values() if "interface" in kind)
    print(f"append-only: {len(old)} declarations and {methods} methods at {against} unchanged and in place")
    return 0


if __name__ == "__main__":
    sys.exit(main())
