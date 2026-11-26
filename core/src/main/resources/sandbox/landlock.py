# Runs one command under Landlock: the file system beyond the folders named is closed to it and to everything it starts.
#
#   landlock.py [--ro PATH]... [--rw PATH]... [--dev PATH]... [--no-unix] [--fds SOFT,HARD]
#               [--net-join PID:INODE | --net-port PORT | --no-inet] -- COMMAND [ARG]...
#   landlock.py --net-keep SOCKET TOKEN [--net-host]
#   landlock.py --probe
#
# --ro PATH    read and execute beneath PATH (a folder, or one file)
# --rw PATH    everything beneath PATH
# --dev PATH   read, write and ioctl on a device file, or on the device files beneath a folder
# --no-unix    creating a UNIX-domain socket is refused too (a seccomp filter): Landlock does not stop a connect to a socket
#              file, so without this the desktop session bus, the Docker socket and the like stay reachable
# --fds S,H    lower the limit of open files to S (soft) and H (hard) where it is higher: a program that cannot list
#              /proc/self/fd closes every possible descriptor when it starts another, and at 524288 that is 55 ms a start
# --net-join PID:INODE
#              the command runs in the user and network namespaces of the keeper PID, whose network namespace has this
#              inode: loopback only, and the keeper's port on it leads to CodeZaiku's proxy
# --net-port PORT
#              where there is no namespace: a TCP connect is allowed to PORT only (Landlock, ABI 4 and later), binding a TCP
#              port is refused, and an IPv4 or IPv6 socket has to be a TCP stream (seccomp): no UDP, ICMP, raw, MPTCP or
#              SCTP, which Landlock's TCP rule does not see; families other than UNIX, IPv4, IPv6 and netlink are refused
# --no-inet    no IPv4 or IPv6 socket at all (seccomp)
# --net-keep SOCKET TOKEN [--net-host] [--net-port PORT]
#              the keeper: make a user and a network namespace (with --net-host: none), listen on 127.0.0.1 at PORT (in a
#              namespace of its own every port is free) or a port the kernel picks, print "ready <port> <network namespace
#              inode>", and pass each connection to the UNIX socket SOCKET, after a first line "TOKEN\n", until standard
#              input closes
# --probe      print what this kernel gives, as "landlock=<abi> arch=<machine> filter=<yes|no> netns=<yes|no:errno>", and
#              exit 0 when there is Landlock
#
# Signals and abstract sockets stay inside the sandbox where the kernel can scope them (Landlock ABI 6). A rule for a
# path that is not on this machine is skipped. Exit 125: the sandbox could not be applied, and the command was not run.
# Nothing here mounts anything or changes a path: the namespaces made are a user namespace and a network namespace.
import ctypes, os, resource, stat, struct, sys

FAIL = 125
CREATE, ADD_RULE, RESTRICT = 444, 445, 446      # the same numbers on every architecture
X86_64, AARCH64, I386 = 0xC000003E, 0xC00000B7, 0x40000003


def die(msg):
    os.write(2, ("sandbox: " + msg + "\n").encode())
    os._exit(FAIL)


def machine_arch():
    m = os.uname().machine
    return X86_64 if m == "x86_64" else AARCH64 if m in ("aarch64", "arm64") else None


def socket_filter(no_unix=True, inet=None):
    """The seccomp program: socket(AF_UNIX), a datagram socketpair(AF_UNIX) and io_uring_setup are refused, the rest runs.

    A datagram pair is refused because a datagram socket can send to any socket file by name; a stream pair cannot.
    io_uring is refused because it opens and connects sockets without the calls this filter sees. A 32-bit entry on
    x86-64 is filtered by its own numbers, and its old socketcall() loses socket and socketpair altogether.

    inet "tcp": an IPv4 or IPv6 socket has to be a stream of protocol 0 or TCP, and only the UNIX, IPv4, IPv6 and netlink
    families may be made; inet "none": no IPv4 or IPv6 socket at all. A datagram, ICMP, raw, MPTCP or SCTP socket is what
    a Landlock port rule does not see.
    """
    ALLOW, EACCES, ENOSYS = 0x7FFF0000, 0x00050000 | 13, 0x00050000 | 38
    LD, AND, JEQ, RET = 0x20, 0x54, 0x15, 0x06
    prog = [
        ("", LD, 4), ("", JEQ, X86_64, "x64", None), ("", JEQ, AARCH64, "a64", None), ("", JEQ, I386, "x86", None), ("", RET, ALLOW),
        ("x64", LD, 0), ("", AND, 0xBFFFFFFF), ("", JEQ, 41, "sock", None), ("", JEQ, 53, "pair", None), ("", JEQ, 425, "nosys", None), ("", RET, ALLOW),
        ("a64", LD, 0), ("", JEQ, 198, "sock", None), ("", JEQ, 199, "pair", None), ("", JEQ, 425, "nosys", None), ("", RET, ALLOW),
        ("x86", LD, 0), ("", JEQ, 359, "sock", None), ("", JEQ, 360, "pair", None), ("", JEQ, 425, "nosys", None), ("", JEQ, 102, "call", None), ("", RET, ALLOW),
        ("call", LD, 16), ("", JEQ, 1, "deny", None), ("", JEQ, 8, "deny", None), ("", RET, ALLOW),
        ("sock", LD, 16),
    ]
    if no_unix: prog.append(("", JEQ, 1, "deny", None))
    if inet == "tcp":
        prog += [("", JEQ, 2, "inet", None), ("", JEQ, 10, "inet", None), ("", JEQ, 1, "sock_ok", None), ("", JEQ, 16, "sock_ok", None), ("", RET, EACCES),
                 ("inet", LD, 24), ("", AND, 0xF), ("", JEQ, 1, None, "deny"), ("", LD, 32), ("", JEQ, 0, "sock_ok", None), ("", JEQ, 6, "sock_ok", None),
                 ("", RET, EACCES)]
    elif inet == "none":
        prog += [("", JEQ, 2, "deny", None), ("", JEQ, 10, "deny", None)]
    prog.append(("sock_ok", RET, ALLOW))
    if no_unix:
        prog += [("pair", LD, 16), ("", JEQ, 1, None, "pair_ok"), ("", LD, 24), ("", AND, 0xF), ("", JEQ, 2, "deny", None), ("pair_ok", RET, ALLOW)]
    else:
        prog.append(("pair", RET, ALLOW))
    prog += [
        ("deny", RET, EACCES),
        ("nosys", RET, ENOSYS),
    ]
    at = {ins[0]: i for i, ins in enumerate(prog) if ins[0]}
    out = []
    for i, ins in enumerate(prog):
        jt = jf = 0
        if ins[1] == JEQ:
            jt = at[ins[3]] - i - 1 if ins[3] else 0
            jf = at[ins[4]] - i - 1 if ins[4] else 0
        out.append((ins[1], jt, jf, ins[2]))
    return out


def apply_socket_filter(libc, no_unix=True, inet=None):
    class Ins(ctypes.Structure):
        _fields_ = [("code", ctypes.c_uint16), ("jt", ctypes.c_uint8), ("jf", ctypes.c_uint8), ("k", ctypes.c_uint32)]

    class Prog(ctypes.Structure):
        _fields_ = [("len", ctypes.c_uint16), ("filter", ctypes.POINTER(Ins))]

    ins = socket_filter(no_unix, inet)
    arr = (Ins * len(ins))(*[Ins(*i) for i in ins])
    prog = Prog(len(ins), arr)
    libc.prctl.argtypes = [ctypes.c_int, ctypes.c_ulong, ctypes.c_void_p, ctypes.c_ulong, ctypes.c_ulong]
    if libc.prctl(22, 2, ctypes.byref(prog), 0, 0) != 0:      # PR_SET_SECCOMP, SECCOMP_MODE_FILTER
        die("the socket filter was refused (errno %d)" % ctypes.get_errno())


def lower_fds(spec):
    try:
        soft, hard = (int(x) for x in spec.split(","))
        now_soft, now_hard = resource.getrlimit(resource.RLIMIT_NOFILE)
        if now_hard == resource.RLIM_INFINITY or now_hard > hard: now_hard = hard
        if now_soft == resource.RLIM_INFINITY or now_soft > soft: now_soft = soft
        resource.setrlimit(resource.RLIMIT_NOFILE, (min(now_soft, now_hard), now_hard))
    except (ValueError, OSError):
        pass                                 # the limit stays as it was: slower starts, nothing less closed


# ── the job's network ────────────────────────────────────────────────────────────────────────────────────────────────

CLONE_NEWUSER, CLONE_NEWNET = 0x10000000, 0x40000000


def write_file(path, text):
    fd = os.open(path, os.O_WRONLY | os.O_CLOEXEC)
    try: os.write(fd, text.encode())
    finally: os.close(fd)


def own_network(libc):
    """A user namespace that maps this account to itself, and a network namespace with loopback up and nothing else.

    Returns None when made, else the reason. Nothing is mounted. The process stays itself: same uid, same files.
    """
    import fcntl, socket
    uid, gid = os.getuid(), os.getgid()
    if libc.unshare(CLONE_NEWUSER | CLONE_NEWNET) != 0: return "unshare: errno %d" % ctypes.get_errno()
    try:
        write_file("/proc/self/setgroups", "deny")
        write_file("/proc/self/uid_map", "%d %d 1" % (uid, uid))
        write_file("/proc/self/gid_map", "%d %d 1" % (gid, gid))
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            flags = struct.unpack("16sh14x", fcntl.ioctl(s.fileno(), 0x8913, struct.pack("16sh14x", b"lo", 0)))[1]    # SIOCGIFFLAGS
            fcntl.ioctl(s.fileno(), 0x8914, struct.pack("16sh14x", b"lo", flags | 1))                                  # SIOCSIFFLAGS, IFF_UP
        finally:
            s.close()
    except OSError as e:
        return "%s: errno %d" % (getattr(e, "filename", None) or "loopback", e.errno or 0)
    return None


def join_network(libc, spec):
    """Into the keeper's user and network namespaces; its network namespace has to be the one CodeZaiku recorded."""
    try:
        pid, inode = spec.split(":")
        user = os.open("/proc/%d/ns/user" % int(pid), os.O_RDONLY | os.O_CLOEXEC)
        net = os.open("/proc/%d/ns/net" % int(pid), os.O_RDONLY | os.O_CLOEXEC)
    except (ValueError, OSError) as e:
        die("the job's network is gone (%s); the command was not run" % e)
    if os.fstat(net).st_ino != int(inode): die("the job's network is gone (another process has its number); the command was not run")
    if libc.setns(user, CLONE_NEWUSER) != 0: die("the job's user namespace could not be joined (errno %d)" % ctypes.get_errno())
    if libc.setns(net, CLONE_NEWNET) != 0: die("the job's network could not be joined (errno %d)" % ctypes.get_errno())
    os.close(user)
    os.close(net)


def keep(libc, sock_path, token, host, port):
    """The keeper: the job's network, and the one way out of it, to CodeZaiku's proxy through a socket file."""
    import socket, threading
    if not host:
        why = own_network(libc)
        if why: die("no network of its own (%s)" % why)
    ls = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    ls.bind(("127.0.0.1", port or 0))
    ls.listen(128)
    os.write(1, b"ready %d %d\n" % (ls.getsockname()[1], os.stat("/proc/self/ns/net").st_ino))
    null = os.open(os.devnull, os.O_RDWR)
    os.dup2(null, 1)
    os.dup2(null, 2)

    def watch():                         # CodeZaiku holds the other end: when it closes or exits, the keeper ends
        while os.read(0, 4096): pass
        os._exit(0)

    def pump(a, b):
        try:
            while True:
                data = a.recv(65536)
                if not data: break
                b.sendall(data)
        except OSError:
            pass
        try: b.shutdown(socket.SHUT_WR)
        except OSError: pass

    def relay(c):
        u = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            u.connect(sock_path)
            u.sendall(token.encode() + b"\n")
        except OSError:
            c.close(); u.close(); return
        t = threading.Thread(target=pump, args=(c, u), daemon=True)
        t.start()
        pump(u, c)
        t.join()
        c.close(); u.close()

    threading.Thread(target=watch, daemon=True).start()
    while True:
        try: c, _ = ls.accept()
        except OSError: continue
        threading.Thread(target=relay, args=(c,), daemon=True).start()


def probe_network(libc):
    """Whether this machine gives a program a network of its own, tried in a child that then ends."""
    r, w = os.pipe()
    pid = os.fork()
    if pid == 0:
        os.close(r)
        why = own_network(libc)
        if why is None:
            try:
                import socket
                s = socket.socket(); s.bind(("127.0.0.1", 0)); s.listen(1); s.close()
            except OSError as e:
                why = "listen: errno %d" % (e.errno or 0)
        os.write(w, ("yes" if why is None else "no:" + why.replace(" ", "")).encode())
        os._exit(0)
    os.close(w)
    said = os.read(r, 200).decode() or "no:the-trial-ended-early"
    os.close(r)
    os.waitpid(pid, 0)
    return said


def main():
    args = sys.argv[1:]
    ro, rw, dev, no_unix, probe, fds = [], [], [], False, False, None
    join, port, no_inet, keeper, host = None, None, False, None, False
    while args and args[0] != "--":
        k = args.pop(0)
        if k == "--no-unix": no_unix = True
        elif k == "--probe": probe = True
        elif k == "--fds" and args: fds = args.pop(0)
        elif k in ("--ro", "--rw", "--dev") and args: {"--ro": ro, "--rw": rw, "--dev": dev}[k].append(args.pop(0))
        elif k == "--net-join" and args: join = args.pop(0)
        elif k == "--net-port" and args: port = int(args.pop(0))
        elif k == "--no-inet": no_inet = True
        elif k == "--net-keep" and len(args) >= 2: keeper = (args.pop(0), args.pop(0))
        elif k == "--net-host": host = True
        else: die("unknown option " + k)
    if args: args.pop(0)
    libc = ctypes.CDLL(None, use_errno=True)
    sc = libc.syscall
    sc.restype = ctypes.c_long
    if keeper: keep(libc, keeper[0], keeper[1], host, port)
    abi = sc(CREATE, None, ctypes.c_size_t(0), ctypes.c_uint32(1))
    if probe:
        os.write(1, ("landlock=%d arch=%s filter=%s netns=%s\n" % (abi if abi > 0 else 0, os.uname().machine, "yes" if machine_arch() else "no",
                                                                  probe_network(libc))).encode())
        os._exit(0 if abi >= 1 else FAIL)
    if abi < 1: die("this kernel has no Landlock (errno %d)" % ctypes.get_errno())
    if not args: die("no command")
    if port is not None and abi < 4: die("this kernel's Landlock has no network rules (they came with version 4)")
    if join: join_network(libc, join)
    fs = (1 << 13) - 1
    if abi >= 2: fs |= 1 << 13          # moving and linking between folders
    if abi >= 3: fs |= 1 << 14          # truncate
    if abi >= 5: fs |= 1 << 15          # ioctl on device files
    scoped = 3 if abi >= 6 else 0       # abstract UNIX sockets and signals stay inside the sandbox
    net = 3 if port is not None else 0  # binding a TCP port, and connecting to one: refused but for the rule below
    attr = (ctypes.c_uint64 * 3)(fs, net, scoped)
    size = 24 if abi >= 6 else 16 if abi >= 4 else 8
    ruleset = sc(CREATE, ctypes.byref(attr), ctypes.c_size_t(size), ctypes.c_uint32(0))
    if ruleset < 0: die("the rule set was refused (errno %d)" % ctypes.get_errno())
    if port is not None:
        rule = ctypes.create_string_buffer(struct.pack("=QQ", 2, port))                  # landlock_net_port_attr: connect, this port
        if sc(ADD_RULE, ctypes.c_int(ruleset), ctypes.c_int(2), rule, ctypes.c_uint32(0)) != 0:
            die("the rule for port %d was refused (errno %d)" % (port, ctypes.get_errno()))
    FILE = (1 | 2 | 4 | (1 << 14) | (1 << 15)) & fs       # what a rule on a file, as against a folder, may carry
    READ = 1 | 4 | 8
    DEVICE = (2 | 4 | 8 | (1 << 15)) & fs
    for paths, access in ((ro, READ), (rw, fs), (dev, DEVICE)):
        for p in paths:
            try:
                fd = os.open(p, os.O_PATH | os.O_CLOEXEC)
            except OSError:
                continue                 # a path that is not on this machine needs no rule
            try:
                allowed = access if stat.S_ISDIR(os.fstat(fd).st_mode) else access & FILE
                rule = ctypes.create_string_buffer(struct.pack("=Qi", allowed, fd))      # landlock_path_beneath_attr, packed
                if allowed and sc(ADD_RULE, ctypes.c_int(ruleset), ctypes.c_int(1), rule, ctypes.c_uint32(0)) != 0:
                    die("the rule for %s was refused (errno %d)" % (p, ctypes.get_errno()))
            finally:
                os.close(fd)
    if fds: lower_fds(fds)
    if libc.prctl(38, 1, 0, 0, 0) != 0: die("no_new_privs was refused")          # PR_SET_NO_NEW_PRIVS
    if sc(RESTRICT, ctypes.c_int(ruleset), ctypes.c_uint32(0)) != 0: die("the rules could not be applied (errno %d)" % ctypes.get_errno())
    os.close(ruleset)
    inet = "tcp" if port is not None else "none" if no_inet else None
    if no_unix or inet:
        if machine_arch() is None: die("no socket filter for " + os.uname().machine)
        apply_socket_filter(libc, no_unix, inet)
    try:
        os.execvp(args[0], args)
    except OSError as e:
        os.write(2, ("sandbox: %s: %s\n" % (args[0], e.strerror)).encode())
        os._exit(127 if e.errno == 2 else 126)


main()
