#!/usr/bin/env python3
"""
Replace inline fully-qualified CodeZaiku class names with imports.

House style: `SecurityScan` with an import, never `org.codezaiku.ops.SecurityScan` inline. FQCNs
accumulate when code is added in a hurry and they make call sites unreadable.

Deliberately conservative — it refuses rather than guesses:
  * skips a file if a simple name would be AMBIGUOUS there (two packages exporting the same name,
    or a same-named class already imported from elsewhere)
  * never rewrites inside a string literal (Class.forName targets must stay fully qualified)
  * never touches the file's own package's classes (already in scope, no import needed — the FQCN
    is just noise, so it is shortened WITHOUT adding an import)
  * leaves javadoc {@link} alone (harmless, and rewriting risks breaking the reference)

Verify with a compile afterwards; that is the real test.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "core/src/main/java")
FQCN = re.compile(r"\borg\.codezaiku\.((?:[a-z][A-Za-z0-9]*\.)*)([A-Z][A-Za-z0-9]*)")
STRING = re.compile(r'"(?:\\.|[^"\\])*"')


def masked_spans(text):
    """Character ranges we must not rewrite: string literals, {@link ...} javadoc refs, and the
    import/package declarations themselves — rewriting an existing `import org.codezaiku.x.Foo;`
    into `import Foo;` is exactly the kind of self-inflicted breakage this script must not cause."""
    spans = [m.span() for m in STRING.finditer(text)]
    spans += [m.span() for m in re.finditer(r"\{@link[^}]*\}", text)]
    spans += [m.span() for m in re.finditer(r"^\s*(?:import|package)\s[^;]*;", text, re.M)]
    return spans


def in_span(pos, spans):
    return any(a <= pos < b for a, b in spans)


def process(path: pathlib.Path):
    src = path.read_text()
    pkg_m = re.search(r"^package\s+([\w.]+);", src, re.M)
    if not pkg_m:
        return None
    own_pkg = pkg_m.group(1)

    spans = masked_spans(src)
    hits = [m for m in FQCN.finditer(src) if not in_span(m.start(), spans)]
    if not hits:
        return None

    # simple name -> set of full names, to detect ambiguity within this file
    by_simple = {}
    for m in hits:
        full = m.group(0)
        by_simple.setdefault(m.group(2), set()).add(full)

    existing = set(re.findall(r"^import\s+(?:static\s+)?([\w.]+);", src, re.M))
    existing_simple = {e.rsplit(".", 1)[-1]: e for e in existing}

    needed, skipped = {}, []
    for simple, fulls in by_simple.items():
        if len(fulls) > 1:
            skipped.append(f"{simple} (ambiguous: {', '.join(sorted(fulls))})")
            continue
        full = next(iter(fulls))
        prior = existing_simple.get(simple)
        if prior and prior != full:
            skipped.append(f"{simple} (collides with existing import {prior})")
            continue
        needed[simple] = full

    if not needed:
        return ("skip", path, skipped)

    # rewrite right-to-left so offsets stay valid
    out = src
    for m in reversed(hits):
        simple = m.group(2)
        if simple not in needed:
            continue
        out = out[:m.start()] + simple + out[m.end():]

    # imports: only for classes OUTSIDE this file's own package
    to_import = sorted(f for s, f in needed.items()
                       if f.rsplit(".", 1)[0] != own_pkg and f not in existing)
    if to_import:
        lines = out.split("\n")
        idx = [i for i, l in enumerate(lines) if re.match(r"^import\s", l)]
        if idx:
            at = max(idx) + 1                      # after the last existing import
        else:
            pkg_i = next(i for i, l in enumerate(lines) if l.startswith("package "))
            lines.insert(pkg_i + 1, "")
            at = pkg_i + 2
        for imp in reversed(to_import):
            lines.insert(at, f"import {imp};")
        out = "\n".join(lines)

    if out != src:
        path.write_text(out)
        return ("done", path, (len(needed), len(to_import), skipped))
    return None


def main():
    changed = skipped_files = 0
    for f in sorted(ROOT.rglob("*.java")):
        r = process(f)
        if not r:
            continue
        kind, path, info = r
        rel = path.relative_to(ROOT)
        if kind == "done":
            n, imps, skips = info
            changed += 1
            print(f"  {rel}: {n} shortened, {imps} imports added"
                  + (f"  [skipped: {'; '.join(skips)}]" if skips else ""))
        else:
            skipped_files += 1
            print(f"  {rel}: SKIPPED — {'; '.join(info)}")
    print(f"\n{changed} file(s) rewritten, {skipped_files} skipped for ambiguity")


if __name__ == "__main__":
    main()
