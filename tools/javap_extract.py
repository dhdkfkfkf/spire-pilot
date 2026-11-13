"""Extract one member body from a javap -p -c dump (UTF-8).

Usage:
    python tools/javap_extract.py research/javap/<dump>.txt <substring> [--max N] [--all]

The dumps are large (AbstractPlayer is ~460KB); reading them whole costs more context than
the one answer needs, so this prints only the member whose header line contains <substring>.
"""
import sys

def main():
    path, needle = sys.argv[1], sys.argv[2]
    limit, all_hits = 400, "--all" in sys.argv
    if "--max" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--max") + 1])
    lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
    # javap indents a member header by exactly two spaces; bytecode/attribute lines by four or
    # more. That distinction is the whole parser.
    headers = [i for i, ln in enumerate(lines)
               if ln.startswith("  ") and not ln.startswith("    ") and ln.strip()]
    hits = [i for i in headers if needle in lines[i]]
    if not hits:
        print("NO MATCH for %r among %d members" % (needle, len(headers)))
        return 1
    if not all_hits:
        hits = hits[:1]
    for i in hits:
        print("=" * 78)
        out = [lines[i]]
        j = i + 1
        while j < len(lines) and (lines[j].startswith(" ") or lines[j].strip() == ""):
            out.append(lines[j])
            j += 1
            if len(out) >= limit:
                out.append("... (truncated at %d lines)" % limit)
                break
        print("\n".join(out))
    return 0

if __name__ == "__main__":
    sys.exit(main())
