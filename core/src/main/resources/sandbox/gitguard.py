# CodeZaiku's reader and restorer of the places in a project's git folders that git reads as instructions (config, hooks, info,
# alternates, remotes, worktree pointers, the .git entry itself). It never follows a link: reading reaches every folder from the
# project one folder at a time with O_NOFOLLOW (openat), so a link anywhere on the way, swapped in while it runs, stops it there and
# is recorded as a link; it never reads a file outside the project. Putting back ("apply") runs INSIDE the sandbox, with the job's
# own rules, so whatever a job did with links it can write nothing the job's commands could not.
#
#   gitguard.py read PROJECT            -> one JSON document on stdout: the git folders found and every guarded entry
#   gitguard.py apply PROJECT < OPS     -> applies a JSON list of operations, prints one JSON result
#
# Paths in and out are relative to PROJECT, with "/" between names.
import base64, json, os, stat, sys, time

MOST = 4 * 1024 * 1024                     # a guarded file larger than this is described, not carried
SKIP = {"node_modules", ".venv", "venv", "env", "target", "build", "dist", ".gradle", "__pycache__", ".tox", ".mypy_cache",
        ".pytest_cache", ".next", ".cache", "out", ".idea", ".vscode", "vendor", "bower_components", ".terraform"}
WALK_DEPTH, WALK_MOST = 4, 5000


ROOT_FD = None


def parent(rel):
    """A descriptor of REL's folder's parent, reached from the project one folder at a time with O_NOFOLLOW: None when a folder on
    the way is a link or missing. Nothing is ever reached through a link, whatever a job swaps while this runs."""
    fd = os.open(".", os.O_RDONLY | os.O_DIRECTORY, dir_fd=ROOT_FD)
    for name in rel.split("/")[:-1]:
        try:
            nfd = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
        except OSError:
            os.close(fd)
            return None
        os.close(fd)
        fd = nfd
    return fd


def real_dirs(root, rel):
    """Whether every folder from ROOT down to REL's parent is a real folder (no link)."""
    fd = parent(rel)
    if fd is None:
        return False
    os.close(fd)
    return True


def listdir(rel):
    if rel == "":
        return sorted(os.listdir(ROOT_FD))
    pfd = parent(rel)
    if pfd is None:
        return []
    try:
        dfd = os.open(rel.split("/")[-1], os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=pfd)
    except OSError:
        os.close(pfd)
        return []
    try:
        return sorted(os.listdir(dfd))
    finally:
        os.close(dfd)
        os.close(pfd)


def entry(root, rel):
    pfd = parent(rel)
    if pfd is None:
        return None
    name = rel.split("/")[-1]
    try:
        try:
            st = os.stat(name, dir_fd=pfd, follow_symlinks=False)
        except OSError:
            return None
        e = {"mode": stat.S_IMODE(st.st_mode), "ino": st.st_ino, "dev": st.st_dev, "mtime": st.st_mtime_ns, "size": st.st_size}
        if stat.S_ISLNK(st.st_mode):
            e["kind"] = "link"
            try:
                e["target"] = os.readlink(name, dir_fd=pfd)
            except OSError:
                e["target"] = ""
        elif stat.S_ISDIR(st.st_mode):
            e["kind"] = "dir"
        elif stat.S_ISREG(st.st_mode):
            e["kind"] = "file"
            if st.st_size <= MOST:
                try:
                    fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=pfd)
                    with os.fdopen(fd, "rb") as f:
                        e["b64"] = base64.b64encode(f.read(MOST + 1)).decode("ascii")
                except OSError:
                    e["unread"] = True
            else:
                e["big"] = True
        else:
            e["kind"] = "other"
        return e
    finally:
        os.close(pfd)


def tree(root, rel, out, depth):
    """REL and, where it is a real folder, everything under it to DEPTH."""
    e = entry(root, rel)
    if e is None:
        return
    out[rel] = e
    if e["kind"] == "dir" and depth > 0:
        for n in listdir(rel):
            tree(root, rel + "/" + n, out, depth - 1)


def gitdir_of_file(root, rel, content):
    """The folder a gitdir: file names, relative to ROOT, or None when it is outside or not one."""
    text = content.decode("utf-8", "replace").strip()
    if not text.startswith("gitdir:"):
        return None
    target = text[len("gitdir:"):].strip()
    base = os.path.dirname(os.path.join(root, rel))
    full = os.path.normpath(os.path.join(base, target))
    r = os.path.relpath(full, root)
    if r.startswith("..") or os.path.isabs(r):
        return None
    return r.replace(os.sep, "/")


def modules(root, rel, out, seen):
    """The git folders of submodules under REL (a modules folder): a folder with HEAD and config or objects."""
    names = listdir(rel)
    if rel.split("/")[-1] != "modules" and "HEAD" in names and ("config" in names or "objects" in names):
        guarded(root, rel, out, seen)
        return
    for n in names:
        e = entry(root, rel + "/" + n)
        if e is not None and e["kind"] == "dir":
            modules(root, rel + "/" + n, out, seen)


def guarded(root, g, out, seen):
    """Every guarded entry of the git folder G (relative to ROOT), its submodules' and its linked worktrees' pointers."""
    if g in seen:
        return
    seen.add(g)
    out[g] = entry(root, g) or {"kind": "missing"}
    if out[g].get("kind") != "dir":
        return
    for name in ("config", "config.worktree", "commondir", "objects/info/alternates", "objects/info/http-alternates"):
        e = entry(root, g + "/" + name)
        if e is not None:
            out[g + "/" + name] = e
    for d in ("hooks", "info", "remotes", "branches"):
        tree(root, g + "/" + d, out, 3)
    wt = g + "/worktrees"
    e = entry(root, wt)
    if e is not None:
        out[wt] = e
        if e["kind"] == "dir":
            for n in listdir(wt):
                w = wt + "/" + n
                out[w] = entry(root, w)
                if out[w] and out[w]["kind"] == "dir":
                    for name in ("commondir", "gitdir", "config.worktree"):
                        x = entry(root, w + "/" + name)
                        if x is not None:
                            out[w + "/" + name] = x
    mods = g + "/modules"
    e = entry(root, mods)
    if e is not None:
        out[mods] = e
        if e["kind"] == "dir":
            modules(root, mods, out, seen)


def read(root):
    out, seen, dotgits, bare = {}, set(), [], []
    top = entry(root, ".git")
    if top is not None:
        dotgits.append(".git")
    # .git entries in the project's folders (a repository a job makes in a folder, a submodule's checkout), and bare repositories
    visited = 0
    stack = [("", 0)]
    while stack and visited < WALK_MOST:
        rel, depth = stack.pop()
        names = listdir(rel)
        visited += 1
        for n in names:
            r = (rel + "/" + n) if rel else n
            if n == ".git":
                if r != ".git":
                    dotgits.append(r)
                continue
            if depth + 1 > WALK_DEPTH or n in SKIP:
                continue
            e = entry(root, r)
            if e is not None and e["kind"] == "dir":
                inner = set(listdir(r))
                if {"HEAD", "config", "objects"} <= inner:
                    bare.append(r)                       # a bare repository in a folder of the project
                else:
                    stack.append((r, depth + 1))
    for d in dotgits:
        e = entry(root, d)
        if e is None:
            continue
        out[d] = e
        if e["kind"] == "dir":
            guarded(root, d, out, seen)
        elif e["kind"] == "file" and "b64" in e:
            g = gitdir_of_file(root, d, base64.b64decode(e["b64"]))
            if g is not None:
                guarded(root, g, out, seen)
    for g in bare:
        guarded(root, g, out, seen)
    top_dirs = {}
    for n in listdir(""):
        try:
            st = os.stat(n, dir_fd=ROOT_FD, follow_symlinks=False)
            if stat.S_ISDIR(st.st_mode):
                top_dirs[n] = [st.st_dev, st.st_ino]
        except OSError:
            pass
    return {"gitdirs": sorted(seen), "dotgits": dotgits, "entries": out, "walked": visited, "complete": visited < WALK_MOST, "top": top_dirs}


def remove(p):
    st = os.lstat(p)
    if stat.S_ISDIR(st.st_mode):
        for n in os.listdir(p):
            remove(os.path.join(p, n))
        os.rmdir(p)
    else:
        os.unlink(p)


def apply(root, ops):
    results = []
    for op in ops:
        rel = op.get("path", "")
        p = os.path.join(root, rel)
        try:
            if ".." in rel.split("/") or rel.startswith("/"):
                raise OSError("not a path of the project")
            if op["op"] != "swapback" and not real_dirs(root, rel):
                raise OSError("a folder on the way is a link or missing")
            if op["op"] == "write":
                tmp = os.path.join(os.path.dirname(p), ".cz-put-back-%d-%d" % (os.getpid(), time.time_ns()))
                fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
                with os.fdopen(fd, "wb") as f:
                    f.write(base64.b64decode(op["b64"]))
                os.chmod(tmp, op.get("mode", 0o644))
                try:
                    st = os.lstat(p)
                    if stat.S_ISDIR(st.st_mode):
                        remove(p)
                except OSError:
                    pass
                os.rename(tmp, p)                       # replaces a file or a link itself, never what a link points at
            elif op["op"] == "remove":
                try:
                    remove(p)
                except FileNotFoundError:
                    pass
            elif op["op"] == "mkdir":
                try:
                    st = os.lstat(p)
                    if not stat.S_ISDIR(st.st_mode):
                        os.unlink(p)
                        os.mkdir(p, op.get("mode", 0o755))
                except FileNotFoundError:
                    os.mkdir(p, op.get("mode", 0o755))
            elif op["op"] == "link":
                try:
                    remove(p)
                except FileNotFoundError:
                    pass
                os.symlink(op["target"], p)
            elif op["op"] == "swapback":
                # the project's .git is no longer the folder it was: find that folder among the top-level entries by its inode
                found = None
                for n in os.listdir(root):
                    st = os.lstat(os.path.join(root, n))
                    if stat.S_ISDIR(st.st_mode) and st.st_ino == op["ino"] and st.st_dev == op["dev"] and n != ".git":
                        found = n
                if found is None:
                    raise OSError("the folder .git was is not among the project's top-level folders")
                aside = ".git.put-aside-%d" % int(time.time())
                if os.path.lexists(os.path.join(root, ".git")):
                    os.rename(os.path.join(root, ".git"), os.path.join(root, aside))
                os.rename(os.path.join(root, found), os.path.join(root, ".git"))
                op["aside"] = aside
                op["from"] = found
            results.append({"path": rel, "op": op["op"], "ok": True, "aside": op.get("aside"), "from": op.get("from")})
        except OSError as e:
            results.append({"path": rel, "op": op["op"], "ok": False, "error": str(e)})
    return results


def main():
    global ROOT_FD
    if len(sys.argv) < 3:
        sys.exit(2)
    root = os.path.abspath(sys.argv[2])
    ROOT_FD = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
    if sys.argv[1] == "read":
        json.dump(read(root), sys.stdout)
    elif sys.argv[1] == "apply":
        json.dump(apply(root, json.load(sys.stdin)), sys.stdout)
    else:
        sys.exit(2)


main()
