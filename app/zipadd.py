"""Zip helper for hosts without the `zip` CLI (for example Windows Git Bash).

Usage:
  python zipadd.py create <archive> <directory>
  python zipadd.py add    <archive> <directory> [prefix]

`create` writes a new archive from the directory tree. `add` rebuilds the archive
with the directory tree appended: rebuilding instead of appending keeps every
local header consistent with the central directory, which Android's zip reader
otherwise reports as "header mismatch". The compression method of existing
entries is preserved, because resources.arsc must stay stored and aligned.
"""
import os
import sys
import zipfile

FIXED_TIME = (1980, 1, 1, 0, 0, 0)


def walk(root, prefix):
    root = os.path.abspath(root)
    if not os.path.isdir(root):
        raise SystemExit("not a directory: " + root)
    result = []
    for base, _, files in os.walk(root):
        for name in sorted(files):
            path = os.path.join(base, name)
            relative = os.path.relpath(path, root).replace(os.sep, "/")
            result.append((prefix + relative if prefix else relative, path))
    return result


def create(archive, root, prefix):
    if os.path.exists(archive):
        os.remove(archive)
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as output:
        for name, path in walk(root, prefix):
            output.write(path, name)


def add(archive, root, prefix):
    existing = []
    with zipfile.ZipFile(archive) as source:
        for info in source.infolist():
            existing.append((info.filename, source.read(info.filename), info.compress_type))
    temporary = archive + ".tmp"
    with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED) as output:
        for name, data, method in existing:
            output.writestr(zipfile.ZipInfo(name, FIXED_TIME), data, compress_type=method)
        for name, path in walk(root, prefix):
            with open(path, "rb") as handle:
                output.writestr(zipfile.ZipInfo(name, FIXED_TIME), handle.read(), compress_type=zipfile.ZIP_DEFLATED)
    os.replace(temporary, archive)


def main(argv):
    if len(argv) < 4:
        raise SystemExit(__doc__)
    action, archive, root = argv[1], argv[2], argv[3]
    prefix = argv[4] if len(argv) > 4 else ""
    if action == "create":
        create(archive, root, prefix)
    elif action == "add":
        add(archive, root, prefix)
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
