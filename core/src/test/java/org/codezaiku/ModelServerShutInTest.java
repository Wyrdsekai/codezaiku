package org.codezaiku;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The model server with no way out, as ResearchZosho 0.8.1 has it. On Linux `model serve install` puts the model's container on a
 * Docker network that has no route to anything but the machine, proves it (the model answers through it, an outside address does
 * not answer from inside), and steps back to the earlier way when the proof does not hold. A stand-in answers for docker, for the
 * machine's routes and for the model, so every branch runs here with no docker and no network: the files for each card kind, each
 * step back, what the status says, an earlier version's install left alone and changed over when asked, and a folder the two
 * programs share.
 */
class ModelServerShutInTest {
    final ModelServer.Os realOs = ModelServer.os;
    final ModelServer.Runner realRunner = ModelServer.runner;
    final ModelServer.Downloader realDownloader = ModelServer.downloader;
    final Predicate<String> realHealth = ModelServer.health;
    final ModelServer.Asker realAsker = ModelServer.asker;
    final Function<String, String> realLoaded = ModelServer.loaded;
    final Map<String, String> realSums = new HashMap<>(ModelServer.sums);
    final String realHome = System.getProperty("user.home");
    Path realDrm = Gpu.SYS_DRM, realKfd = Gpu.SYS_KFD, realDev = Gpu.DEV;

    static final String SHA_X = "2d711642b726b04401627ca9fbac32f5c8530fb1903cc4db02258717921a4881";   // sha256 of "x", what the stand-in's downloads hold
    static final String NET = ModelServer.NETWORK, RANGE = "172.16.221.0/28", AT = "172.16.221.2";
    static final String HOME_ROUTES = "default via 192.168.1.1 dev eth0 proto dhcp src 192.168.1.20 metric 100\n192.168.1.0/24 dev eth0 proto kernel scope link src 192.168.1.20\n"
            + "172.17.0.0/16 dev docker0 proto kernel scope link src 172.17.0.1 linkdown\nlocal 127.0.0.0/8 dev lo table local proto kernel scope host src 127.0.0.1\n";
    /** ResearchZosho 0.8.1's ranges, as its ModelServer.RANGES lists them on 2026-11-25. */
    static final List<String> RZ_RANGES = List.of("172.16.211.0/28", "10.213.71.0/28", "172.16.93.0/28", "10.157.29.0/28", "192.168.251.0/28", "172.31.251.0/28");

    @TempDir Path home;

    /** The machine the stand-in describes: its routes, Docker's networks, what the proof's commands answer, what the model answers. */
    final class Machine {
        final List<List<String>> ran = new ArrayList<>();
        final Map<String, String> networks = new LinkedHashMap<>();   // name → "true|false subnet"
        final List<String> fetched = new ArrayList<>(), asked = new ArrayList<>();
        String routes = HOME_ROUTES;
        List<String> refused = List.of();              // ranges Docker will not give
        int control = 0, inside = 7, names = 2, ip = 0, pull = 0;
        boolean images = true, proxyUpWhenShut = true, started, bridgesRouted = true;
        String controlSaid = "13.33.109.46";
        String modelShut = "", modelOpen = "";       // "": answers; "!why": does not

        /** The proxy passes the model's requests to an address of a network, not to a port of this machine. */
        boolean shutNow() {
            try { String y = Files.readString(ModelServer.dir().resolve("config.yaml")); return y.contains("    proxy: http://" + AT + ":8080\n") || y.matches("(?s).*    proxy: http://(10|172|192)\\.\\d+\\.\\d+\\.\\d+:8080\n.*"); }
            catch (IOException e) { return false; }
        }

        ModelServer.Result run(List<String> cmd) {
            ran.add(cmd);
            String c = cmd.get(0), all = String.join(" ", cmd);
            if (c.equals("ip")) {   // the machine's routes, and one for each Docker network: its bridge is an interface of the machine (not so with rootless Docker)
                StringBuilder b = new StringBuilder(routes);
                if (bridgesRouted) networks.forEach((name, net) -> { String subnet = net.split(" ")[1]; if (!routes.contains(subnet + " dev br-")) b.append(subnet).append(" dev br-6bd65cf20e25 proto kernel scope link src ").append(subnet.replaceFirst("\\.\\d+/\\d+$", ".1")).append(" linkdown\n"); });
                return new ModelServer.Result(ip, b.toString());
            }
            if (c.equals("nvidia-smi")) return new ModelServer.Result(0, "NVIDIA RTX A4000, 16380\n");
            if (c.startsWith("curl")) return new ModelServer.Result(0, "HTTP/2 200\ncontent-length: 13780000000\n");
            if (c.equals("tar")) {
                try { Path into = Path.of(cmd.get(cmd.indexOf("-C") + 1)); Files.createDirectories(into); Files.writeString(into.resolve("llama-swap"), "x"); } catch (IOException e) { return new ModelServer.Result(1, e.toString()); }
                return new ModelServer.Result(0, "");
            }
            if (c.equals("systemctl")) { if (cmd.contains("enable") || cmd.contains("restart")) started = true; return new ModelServer.Result(0, cmd.contains("is-active") ? "active\n" : ""); }
            if (!c.equals("docker")) return new ModelServer.Result(0, "");
            String verb = cmd.get(1);
            if (verb.equals("info")) return new ModelServer.Result(0, "map[nvidia:{...} runc:{...}]");
            if (verb.equals("network")) {
                String op = cmd.get(2);
                if (op.equals("inspect")) {
                    String have = networks.get(cmd.get(3));
                    if (have == null) return new ModelServer.Result(1, "Error response from daemon: network " + cmd.get(3) + " not found\n");
                    return new ModelServer.Result(0, (all.contains(".IPAM") ? have : have.split(" ")[0]) + "\n");
                }
                if (op.equals("create")) {
                    String subnet = cmd.get(cmd.indexOf("--subnet") + 1), name = cmd.get(cmd.size() - 1);
                    boolean taken = refused.contains(subnet) || networks.values().stream().anyMatch(n -> n.endsWith(" " + subnet));
                    if (taken) return new ModelServer.Result(1, "Error response from daemon: invalid pool request: Pool overlaps with other one on this address space\n");
                    networks.put(name, cmd.contains("--internal") + " " + subnet);
                    return new ModelServer.Result(0, "6bd65cf20e25\n");
                }
                if (op.equals("rm")) return new ModelServer.Result(networks.remove(cmd.get(3)) != null ? 0 : 1, "");
            }
            if (verb.equals("image")) return new ModelServer.Result(images ? 0 : 1, images ? "sha256:abc\n" : "Error response from daemon: No such image\n");
            if (verb.equals("pull")) return new ModelServer.Result(pull, pull == 0 ? "" : "Error response from daemon: Get \"https://ghcr.io/v2/\": dial tcp: lookup ghcr.io: no such host\n");
            if (verb.equals("run") && cmd.contains("--entrypoint")) return new ModelServer.Result(control, control == 0 ? controlSaid : "curl: (6) Could not resolve host: huggingface.co\n");
            if (verb.equals("exec") && cmd.contains("curl"))
                return new ModelServer.Result(inside, inside == 7 ? "curl: (7) Failed to connect to 13.33.109.46 port 443 after 0 ms: Couldn't connect to server\n" : inside >= 125 ? "OCI runtime exec failed: exec failed: unable to start container process: exec: \"curl\": executable file not found in $PATH\n" : "");
            if (verb.equals("exec") && cmd.contains("getent")) return new ModelServer.Result(names, names == 0 ? "2600:9000:200b:c000:17:b174:6d00:93a1 huggingface.co\n" : "");
            return new ModelServer.Result(0, "");
        }

        void install() {
            ModelServer.os = ModelServer.Os.linux;
            ModelServer.runner = this::run;
            ModelServer.downloader = (url, dest) -> { fetched.add(url); Files.createDirectories(dest.getParent()); Files.writeString(dest, "x"); ModelServer.sums.put(dest.getFileName().toString(), SHA_X); };
            ModelServer.health = base -> started && (proxyUpWhenShut || !shutNow());
            ModelServer.loaded = base -> started ? "{\"running\":[]}" : "";   // what runs, as the stand-in proxy says it: no port of the machine is asked
            ModelServer.asker = new ModelServer.Asker() {
                @Override public String complete(String base, String model, int seconds) { asked.add("complete " + (shutNow() ? "shut" : "open")); return shutNow() ? modelShut : modelOpen; }
                @Override public void rest(String base) { asked.add("rest"); }
            };
        }

        boolean ranLine(String... words) { return ran.stream().anyMatch(c -> c.equals(List.of(words))); }
        long count(String... first) { return ran.stream().filter(c -> c.size() >= first.length && c.subList(0, first.length).equals(List.of(first))).count(); }
    }

    Machine machine;

    @BeforeEach void standIn() throws Exception {
        Path sys = Files.createTempDirectory("cz-sys");
        for (String d : List.of("drm", "kfd", "dev")) Files.createDirectories(sys.resolve(d));
        Gpu.SYS_DRM = sys.resolve("drm"); Gpu.SYS_KFD = sys.resolve("kfd"); Gpu.DEV = sys.resolve("dev");
        System.setProperty("user.home", home.toString());
        Config.invalidate();
        machine = new Machine();
        machine.install();
        ModelServer.SECOND_MS = 10;   // the stand-in proxy answers or not by the machine's state, never by the time: a wait for it need not last its seconds
    }

    @AfterEach void restore() {
        Gpu.SYS_DRM = realDrm; Gpu.SYS_KFD = realKfd; Gpu.DEV = realDev;
        ModelServer.sums.clear(); ModelServer.sums.putAll(realSums);
        ModelServer.os = realOs; ModelServer.runner = realRunner; ModelServer.downloader = realDownloader; ModelServer.health = realHealth; ModelServer.asker = realAsker; ModelServer.loaded = realLoaded; ModelServer.SECOND_MS = 1000;
        System.setProperty("user.home", realHome);
        Config.invalidate();
    }

    String install() {
        var out = new ByteArrayOutputStream();
        String r = ModelServer.install(null, "all", 20, false, new PrintStream(out, true));
        assertFalse(r.startsWith("!"), r + "\n" + out);
        return out.toString();
    }

    String file(String name) throws IOException { return Files.readString(ModelServer.dir().resolve(name)); }
    Map<String, String> plan() throws IOException { return ModelServer.readPlan(ModelServer.dir()); }
    Path models() { return home.resolve("models"); }

    /** `model serve status`, with what runs read from the stand-in proxy ({@link ModelServer#loaded}): no port of the machine is asked. */
    String status() { return ModelServer.status(); }

    /** The two files as the earlier way writes them for this machine's install: what a step back has to leave, to the letter. */
    Map<String, String> earlierFiles() {
        return ModelServer.filesOf(ModelServer.plan(ModelServer.Os.linux, ModelServer.rowFor(16), models().resolve("gpt-oss-20b-F16.gguf"), ModelServer.dir(), ModelServer.unitPath(ModelServer.Os.linux), "all", 20, false, "cuda"), "");
    }

    void assertEarlierWay() throws IOException {
        for (var f : earlierFiles().entrySet()) assertEquals(f.getValue(), file(f.getKey()), f.getKey() + " is the earlier way's, to the letter");
        assertTrue(file("run.sh").contains(" -p 127.0.0.1:$1:8080 ") && !file("run.sh").contains("--network"), file("run.sh"));
        assertNull(machine.networks.get(NET), "the network of the attempt is gone");
    }

    // ---- the files written ----

    @Test
    void theEarlierWaysFilesAreToTheLetterWhatEarlierVersionsWrote(@TempDir Path tmp) {
        Path dir = tmp.resolve("model"), models = tmp.resolve("models");
        var row = new ModelServer.Row("qwen3.5-4b-bf16", 0, "", "", "Qwen3.5-4B-bf16.gguf", "", 0, 65536, 2, "");
        var p = ModelServer.plan(ModelServer.Os.linux, row, models.resolve("Qwen3.5-4B-bf16.gguf"), dir, tmp.resolve("u.service"), "all", 20, false, "cuda", null);
        // the files the released 0.3.19 writes for this install, as read from its code on 2026-11-25; only the folders differ
        assertEquals("#!/bin/sh\n"
                + "# qwen3.5-4b-bf16 in llama.cpp, started by llama-swap on demand; $1 is the port it assigned. The flags are the measured ones.\n"
                + "exec docker run --rm --name codezaiku-model --gpus all -p 127.0.0.1:$1:8080 -v " + models + ":/m ghcr.io/ggml-org/llama.cpp:server-cuda \\\n"
                + "  -m /m/Qwen3.5-4B-bf16.gguf --host 0.0.0.0 --port 8080 --jinja -c 65536 --parallel 2 -ngl 99 --flash-attn on\n", p.runScript());
        assertEquals("# CodeZaiku: the model server on demand. llama-swap listens on 8211; the server starts on the first request\n"
                + "# and stops after ttl seconds idle. `codezaiku model serve status` reads it.\n"
                + "healthCheckTimeout: 600\nstartPort: 10001\nlogLevel: info\nmodels:\n"
                + "  \"qwen3.5-4b-bf16\":\n    cmd: " + dir.resolve("run.sh") + " ${PORT}\n    cmdStop: docker stop codezaiku-model\n    proxy: http://127.0.0.1:${PORT}\n    ttl: 1200\n"
                + "    aliases: [\"local-model\", \"Qwen3.5-4B-bf16.gguf\", \"/m/Qwen3.5-4B-bf16.gguf\"]\n", p.configYaml());
        assertEquals(p, ModelServer.plan(ModelServer.Os.linux, row, models.resolve("Qwen3.5-4B-bf16.gguf"), dir, tmp.resolve("u.service"), "all", 20, false, "cuda"), "the signature of before writes the same");
    }

    @Test
    void withNoWayOutTheContainerIsOnTheNetworkAtItsAddressAndTheProxyPassesToIt(@TempDir Path tmp) {
        Path dir = tmp.resolve("model"), models = tmp.resolve("models");
        var shut = ModelServer.Shut.of(RANGE);
        assertEquals(AT, shut.modelAt(), "the range's second address; ResearchZosho's embeddings server takes the third in its own range");
        var row = ModelServer.rowFor(16);
        // every card kind, with and without --share and --gpu: the way out is the same three flags where the published port was
        for (String build : List.of("cuda", "rocm", "vulkan")) for (boolean share : List.of(false, true)) for (String gpus : List.of("all", "1")) {
            var open = ModelServer.plan(ModelServer.Os.linux, row, models.resolve(row.file()), dir, tmp.resolve("u"), gpus, 20, share, build, null);
            var p = ModelServer.plan(ModelServer.Os.linux, row, models.resolve(row.file()), dir, tmp.resolve("u"), gpus, 20, share, build, shut);
            String run = p.runScript(), y = p.configYaml(), what = build + " share=" + share + " gpus=" + gpus;
            String devices = open.runScript().replaceFirst("(?s).*--name codezaiku-model (.*) -p 127\\.0\\.0\\.1:\\$1:8080 .*", "$1");
            assertTrue(run.contains("exec docker run --rm --name codezaiku-model " + devices + " --network " + NET + " --ip " + AT + " --cap-drop NET_RAW -v " + models + ":/m ghcr.io/ggml-org/llama.cpp:" + ModelServer.imageOf(build)), what + ": " + run);
            assertFalse(run.contains(" -p "), what + ": no port of the machine is published: " + run);
            assertTrue(run.contains("\ndocker network inspect " + NET + " >/dev/null 2>&1 || docker network create --internal --subnet " + RANGE + " " + NET + " >/dev/null 2>&1\nexec docker run "), what + ": a network that was removed is made again before the start: " + run);
            assertTrue(y.contains("    cmd: " + dir.resolve("run.sh") + "\n    cmdStop: docker stop codezaiku-model\n    proxy: http://" + AT + ":8080\n"), what + ": " + y);
            assertFalse(y.contains("${PORT}"), what + ": the proxy assigns no port: " + y);
            assertEquals(open.unitText(), p.unitText(), what + ": the service is the same one, listening where it did (--share: every address)");
            assertTrue(p.unitText().contains("--listen " + (share ? "0.0.0.0" : "127.0.0.1") + ":8211"), p.unitText());
            // one text decides the difference, both ways: turned back, the files are the earlier way's to the letter
            assertEquals(ModelServer.filesOf(open, ""), ModelServer.toOpen(dir, ModelServer.filesOf(p, "")), what);
            assertEquals(ModelServer.filesOf(p, ""), ModelServer.toShut(dir, ModelServer.filesOf(open, ""), shut), what);
        }
        // where the server is a plain program there is no container to shut in: the plan is what it was
        for (ModelServer.Os os : List.of(ModelServer.Os.macos, ModelServer.Os.windows))
            assertEquals(ModelServer.plan(os, ModelServer.rowFor(11), models.resolve("Qwen3.5-9B-Q4_K_M.gguf"), dir, tmp.resolve("p"), "all", 20, false, ""),
                    ModelServer.plan(os, ModelServer.rowFor(11), models.resolve("Qwen3.5-9B-Q4_K_M.gguf"), dir, tmp.resolve("p"), "all", 20, false, "", shut), os.name());
        // files that are not of the shape this program writes are not turned at all
        var open = ModelServer.plan(ModelServer.Os.linux, row, models.resolve(row.file()), dir, tmp.resolve("u"), "all", 20, false, "cuda", null);
        Map<String, String> edited = new LinkedHashMap<>(ModelServer.filesOf(open, ""));
        edited.put("run.sh", edited.get("run.sh").replace("-p 127.0.0.1:$1:8080", "-p 8080:8080"));
        assertNull(ModelServer.toShut(dir, edited, shut), "a start script with another published port is the person's own");
        // what another program added to the config after the model stays as it was, both ways
        String theirs = "  \"embed\":\n    cmd: " + dir.resolve("run-embed.sh") + " ${PORT}\n    cmdStop: docker stop researchzosho-model-embed\n    proxy: http://127.0.0.1:${PORT}\n    checkEndpoint: /health\n    ttl: 300\n"
                + "groups:\n  \"embedding\":\n    swap: false\n    exclusive: false\n    persistent: true\n    members: [\"embed\"]\n";
        Map<String, String> turned = ModelServer.toShut(dir, ModelServer.filesOf(open, theirs), shut);
        assertTrue(turned.get("config.yaml").endsWith(theirs) && turned.get("config.yaml").contains("proxy: http://" + AT + ":8080\n"), turned.get("config.yaml"));
        assertEquals(ModelServer.filesOf(open, theirs), ModelServer.toOpen(dir, turned));
    }

    // ---- the free range ----

    @Test
    void theNetworkTakesTheFirstRangeNoRouteOfTheMachineOverlapsAndDockerAccepts() {
        assertArrayEquals(new long[]{0xAC10DD00L, 0xAC10DD0FL}, ModelServer.range(RANGE));
        assertNull(ModelServer.range("172.16.221.0/40")); assertNull(ModelServer.range("default")); assertNull(ModelServer.range("300.1.1.1"));
        var used = ModelServer.routed(HOME_ROUTES + "100.64.0.5 dev tailscale0 table 52\n0.0.0.0/0 via 10.9.0.1 dev wg0 table 51820\n");
        assertEquals(10, used.size(), "every range and address the routes name (the destinations, the gateways, the machine's own addresses), and not 0.0.0.0/0, which is the default written another way");
        // a home machine: the first of the list is free
        var made = ModelServer.makeNetwork();
        assertEquals(RANGE, made.shut().subnet()); assertNull(made.why());
        assertEquals("true " + RANGE, machine.networks.get(NET), "made with --internal and that range");
        assertTrue(machine.ranLine("docker", "network", "create", "--internal", "--subnet", RANGE, NET), machine.ran.toString());
        // one this program made is used as it is, whatever the routes say now (its own route is among them)
        machine.ran.clear(); machine.routes = HOME_ROUTES + RANGE + " dev br-6bd65cf20e25 proto kernel scope link src 172.16.221.1 linkdown\n";
        assertEquals(RANGE, ModelServer.makeNetwork().shut().subnet());
        assertEquals(0, machine.count("docker", "network", "create"), "nothing is made twice");
        // a tunnel routes all of 172.16: the next range that nothing overlaps
        machine.networks.clear(); machine.routes = HOME_ROUTES + "172.16.0.0/12 via 10.8.0.1 dev tun0\n10.8.0.0/24 dev tun0 proto kernel scope link src 10.8.0.6\n";
        assertEquals("10.213.81.0/28", ModelServer.makeNetwork().shut().subnet());
        // a virtual machine's network holds one address of a range: that range is not taken either
        machine.networks.clear(); machine.routes = HOME_ROUTES + "172.16.221.8 dev vmnet1 scope link\n";
        assertEquals("10.213.81.0/28", ModelServer.makeNetwork().shut().subnet());
        // Docker refuses a range one of its own networks holds: the next
        machine.networks.clear(); machine.routes = HOME_ROUTES; machine.refused = List.of(RANGE, "10.213.81.0/28");
        assertEquals("172.16.103.0/28", ModelServer.makeNetwork().shut().subnet());
        // nothing free, or Docker refuses every one: nothing is made, and the reason is words
        machine.networks.clear(); machine.refused = ModelServer.RANGES;
        made = ModelServer.makeNetwork();
        assertNull(made.shut()); assertTrue(made.why().startsWith("Docker did not make its network (Error response from daemon: invalid pool request"), made.why());
        machine.refused = List.of(); machine.routes = "10.0.0.0/8 dev tun0\n172.16.0.0/12 dev tun1\n192.168.0.0/16 dev tun2\n";
        assertEquals("no address range was free for its network", ModelServer.makeNetwork().why());
        assertTrue(machine.networks.isEmpty());
        // rootless Docker, or Docker in a virtual machine: the network is made, the machine has no route to it, and it is taken away again
        machine.routes = HOME_ROUTES; machine.bridgesRouted = false;
        made = ModelServer.makeNetwork();
        assertNull(made.shut()); assertTrue(made.why().startsWith("this machine has no route to its own Docker networks (rootless Docker, or Docker in a virtual machine)"), made.why());
        assertTrue(machine.networks.isEmpty() && machine.ranLine("docker", "network", "rm", NET), "nothing is left behind: " + machine.networks);
        machine.bridgesRouted = true;
        // the routes cannot be read: no range is guessed
        machine.ip = 127; machine.routes = "";
        assertTrue(ModelServer.makeNetwork().why().startsWith("the routes of this machine could not be read"), ModelServer.makeNetwork().why());
        machine.ip = 0; machine.routes = HOME_ROUTES;
        // a network of that name that is not one without a way out is not this program's, and is left alone
        machine.networks.put(NET, "false 172.30.0.0/16");
        made = ModelServer.makeNetwork();
        assertNull(made.shut()); assertTrue(made.why().contains("is already there and is not one CodeZaiku made"), made.why());
        assertEquals("false 172.30.0.0/16", machine.networks.get(NET));
    }

    // ---- two programs, one machine ----

    @Test
    void besideResearchZoshosNetworkThisOneHasAnotherNameAndAnotherRange() {
        assertEquals("codezaiku-model-shut", NET);
        assertNotEquals("researchzosho-model-shut", NET);
        for (String mine : ModelServer.RANGES) for (String theirs : RZ_RANGES)
            assertFalse(ModelServer.overlaps(ModelServer.range(mine), List.of(ModelServer.range(theirs))), mine + " overlaps ResearchZosho's " + theirs);
        // ResearchZosho's network is there, on its first range: this one is made on this program's first, beside it, and both stay
        machine.networks.put("researchzosho-model-shut", "true 172.16.211.0/28");
        var made = ModelServer.makeNetwork();
        assertEquals(RANGE, made.shut().subnet());
        assertEquals("true 172.16.211.0/28", machine.networks.get("researchzosho-model-shut"));
        assertEquals("true " + RANGE, machine.networks.get(NET));
        // a range of this program's that a route of the machine names (another program's network made on it by hand) is passed over
        machine.networks.remove(NET); machine.networks.put("someone-else", "true " + RANGE);
        assertEquals("10.213.81.0/28", ModelServer.makeNetwork().shut().subnet());
    }

    // ---- the install proves itself ----

    @Test
    void anInstallIsSetUpWithNoWayOutProvesItAndSaysSo() throws Exception {
        String out = install();
        assertEquals("true " + RANGE, machine.networks.get(NET));
        assertTrue(file("run.sh").contains("--network " + NET + " --ip " + AT + " --cap-drop NET_RAW") && !file("run.sh").contains(" -p ") && file("config.yaml").contains("proxy: http://" + AT + ":8080\n"), file("run.sh") + file("config.yaml"));
        // the proof, in its order: the control, the proxy let go, the model, the request from inside its container, the name
        assertEquals(List.of("rest", "complete shut", "rest"), machine.asked, "the model alone on the card, and the card left free at the end, as an install always left it");
        List<String> proof = machine.ran.stream().filter(c -> c.get(0).equals("docker") && (c.get(1).equals("exec") || c.contains("--entrypoint"))).map(c -> String.join(" ", c)).toList();
        assertEquals(List.of(
                "docker run --rm --entrypoint curl ghcr.io/ggml-org/llama.cpp:server-cuda -4 -sS -o /dev/null -m 15 -A codezaiku.org -w %{remote_ip} https://huggingface.co/",
                "docker exec codezaiku-model curl -sS -o /dev/null -m 8 -k -A codezaiku.org https://13.33.109.46/",
                "docker exec codezaiku-model getent hosts huggingface.co"), proof);
        assertTrue(out.contains("The model server has no way out to the network: its container is on a Docker network with no route to anything but this machine. Tried just now: the model answered through it, and an outside address that another container reached could not be reached from inside."), out);
        assertFalse(out.contains("could not be set up") || out.contains("looks names up"), out);
        assertEquals("shut", plan().get("network")); assertEquals(RANGE, plan().get("subnet")); assertNotNull(plan().get("network_tried"));
        assertEquals("gpt-oss-20b", plan().get("name"), "the plan is whole, as ResearchZosho reads it where the two share this folder: " + plan());
        assertEquals(0, machine.count("systemctl", "--user", "restart"), "nothing was stepped back");
        // the address every program uses for the model is the proxy's, as before
        assertEquals("http://127.0.0.1:8211", Config.get("CODEZAIKU_DRIVE"));
        assertEquals("gpt-oss-20b", Config.get("CODEZAIKU_MODEL"));
        String status = status();
        assertTrue(status.contains("  network: The model server has no way out to the network: its container is on a Docker network with no route to anything but this machine (" + NET + "). Tried on " + plan().get("network_tried") + ": the model answered through it, and an outside address could not be reached from inside.\n"), status);
        // given again, the install is as wanted: nothing is asked, nothing restarted
        machine.asked.clear();
        install();
        assertTrue(machine.asked.isEmpty() && machine.count("systemctl", "--user", "restart") == 0, machine.asked.toString());
        // uninstall takes the network with it
        ModelServer.health = base -> false;
        assertFalse(ModelServer.uninstall(true).startsWith("!"));
        assertNull(machine.networks.get(NET), "the network goes with the install that made it");
        assertTrue(machine.ranLine("docker", "stop", "codezaiku-model"), machine.ran.toString());
    }

    @Test
    void whenDockerLooksNamesUpForTheContainerTheInstallAndTheStatusSayIt() throws Exception {
        machine.names = 0;
        String out = install();
        assertTrue(out.contains("has no way out to the network") && out.contains("Docker on this machine still looks names up for the model server, which a program could use to send a little information out. Docker 29 does not."), out);
        assertEquals("looked-up", plan().get("network_names"));
        assertTrue(status().contains("still looks names up for the model server"), status());
    }

    // ---- or steps back ----

    @Test
    void whenTheModelDoesNotAnswerThroughItTheEarlierWayIsSetUpAndSaid() throws Exception {
        machine.modelShut = "!no answer in 660 seconds";
        String out = install();
        assertEarlierWay();
        assertTrue(machine.ranLine("docker", "stop", "codezaiku-model") && machine.ranLine("docker", "network", "rm", NET), "the attempt's container and network are taken away: " + machine.ran);
        assertTrue(machine.ranLine("systemctl", "--user", "restart", "codezaiku-model"), "the proxy reads the earlier files: " + machine.ran);
        assertTrue(out.contains("The model server could not be set up with no way out to the network on this machine, because the model did not answer through it (no answer in 660 seconds). It is set up the way earlier versions did, so it can reach the network. To try again: codezaiku model serve install"), out);
        assertTrue(out.contains("Set up the earlier way, the model answered."), out);
        assertEquals(List.of("rest", "complete shut", "complete open", "rest"), machine.asked, "asked again the earlier way, so the person reads that it answers");
        assertEquals("open", plan().get("network")); assertEquals("unproven", plan().get("network_why"));
        String status = status();
        assertTrue(status.contains("  network: The model server can reach the network: on " + plan().get("network_tried") + " it could not be set up with no way out, because the model did not answer through it (no answer in 660 seconds). codezaiku model serve install tries again.\n"), status);
        assertEquals("http://127.0.0.1:8211", Config.get("CODEZAIKU_DRIVE"), "the install is finished all the same");
        // `model serve install` again tries again, and when the model answers this time the install changes over
        machine.modelShut = ""; machine.asked.clear();
        String again = install();
        assertTrue(again.contains("Setting the model server of this machine up again with no way out to the network.") && again.contains("has no way out to the network"), again);
        assertEquals("shut", plan().get("network"));
        assertTrue(file("run.sh").contains("--network " + NET), file("run.sh"));
    }

    @Test
    void whenTheModelAnswersNeitherWayTheCauseIsSaidToLieElsewhere() throws Exception {
        machine.modelShut = "!it returned HTTP 502: upstream command exited prematurely"; machine.modelOpen = "!it returned HTTP 502: upstream command exited prematurely";
        String out = install();
        assertEarlierWay();
        assertTrue(out.contains("Set up the earlier way, the model did not answer either (it returned HTTP 502: upstream command exited prematurely), so the cause lies elsewhere. Its log: journalctl --user -u codezaiku-model"), out);
    }

    @Test
    void whenAnOutsideAddressAnswersFromInsideTheEarlierWayIsSetUpAndSaid() throws Exception {
        machine.inside = 0;
        String out = install();
        assertEarlierWay();
        assertTrue(out.contains("because an outside address answered from inside the container codezaiku-model. It is set up the way earlier versions did"), out);
        assertTrue(status().contains("The model server can reach the network: on "), status());
        // any answer of the outside counts, a refused handshake too (curl's 35): only "could not connect" and "no answer in time" are a wall
        ModelServer.health = base -> false; ModelServer.uninstall(true); machine.install(); machine.started = false;
        machine.inside = 35;
        assertTrue(install().contains("because an outside address answered from inside the container codezaiku-model."));
        assertEarlierWay();
        ModelServer.health = base -> false; ModelServer.uninstall(true); machine.install(); machine.started = false;
        machine.inside = 28;
        assertTrue(install().contains("has no way out to the network"), "no answer in time is a wall");
    }

    @Test
    void aProofThatCannotBeRunIsNoProofAndTheEarlierWayIsSetUp() throws Exception {
        // no container on the machine reaches the outside address: a silence from inside would mean nothing
        machine.control = 6;
        String out = install();
        assertEarlierWay();
        assertTrue(out.contains("because no container on this machine reached an outside address just now, so it could not be tried (curl: (6) Could not resolve host: huggingface.co)"), out);
        assertEquals(List.of("complete open", "rest"), machine.asked, "the model was not loaded for a proof that could not be made; it is asked the earlier way");
        // the control answered, and said no number
        ModelServer.health = base -> false; ModelServer.uninstall(true); machine.install(); machine.started = false;
        machine.control = 0; machine.controlSaid = "";
        assertTrue(install().contains("so it could not be tried"));
        // the check has no curl to run inside the container: not "could not connect"
        ModelServer.health = base -> false; ModelServer.uninstall(true); machine.install(); machine.started = false;
        machine.controlSaid = "13.33.109.46"; machine.inside = 127;
        out = install();
        assertTrue(out.contains("because the check could not be run inside the container codezaiku-model (OCI runtime exec failed"), out);
        assertEarlierWay();
    }

    @Test
    void whenNoNetworkCanBeMadeOrTheImageCannotBeFetchedTheInstallIsTheEarlierWayFromTheStart() throws Exception {
        machine.refused = ModelServer.RANGES;
        String out = install();
        assertEarlierWay();
        assertTrue(out.contains("The model server could not be set up with no way out to the network on this machine, because Docker did not make its network (Error response from daemon: invalid pool request"), out);
        assertTrue(machine.asked.isEmpty(), "nothing to prove: the model is not loaded by the install, as in every version before: " + machine.asked);
        assertEquals(0, machine.count("systemctl", "--user", "restart"));
        assertTrue(status().contains("because Docker did not make its network"), status());
        ModelServer.health = base -> false; ModelServer.uninstall(true); machine.install(); machine.started = false;
        machine.refused = List.of(); machine.images = false; machine.pull = 1;
        out = install();
        assertTrue(out.contains("Fetching ghcr.io/ggml-org/llama.cpp:server-cuda") && out.contains("because a program it runs could not be fetched (ghcr.io/ggml-org/llama.cpp:server-cuda: Error response from daemon: Get"), out);
        assertEarlierWay();
        // fetched, the image is there before the proof asks anything
        ModelServer.health = base -> false; ModelServer.uninstall(true); machine.install(); machine.started = false;
        machine.pull = 0;
        out = install();
        assertTrue(machine.ranLine("docker", "pull", "ghcr.io/ggml-org/llama.cpp:server-cuda") && out.contains("has no way out to the network"), out);
    }

    @Test
    void whenTheProxyDoesNotComeUpWithTheNewFilesTheEarlierOnesArePutBack() throws Exception {
        machine.proxyUpWhenShut = false;
        String out = install();
        assertEarlierWay();
        assertTrue(out.contains("because the proxy did not answer at http://127.0.0.1:8211. It is set up the way earlier versions did"), out);
        assertEquals("open", plan().get("network"));
        assertEquals("http://127.0.0.1:8211", Config.get("CODEZAIKU_DRIVE"));
    }

    @Test
    void aTestThatStandsInForTheMachineHasToStandInForTheModelToo() {
        ModelServer.asker = realAsker;   // the real one would ask whatever answers on this machine's port
        String r = ModelServer.install(null, "all", 20, false, new PrintStream(new ByteArrayOutputStream()));
        assertTrue(r.startsWith("!a test that stands in for the machine has to stand in for the model's answers too"), r);
    }

    // ---- the setting ----

    @Test
    void theSettingChoosesTheEarlierWayOnPurposeAndInstallGivenAgainAppliesAChange() throws Exception {
        Config.set("model_network", "open");   // the name a person types; CODEZAIKU_MODEL_NETWORK is the same setting
        Config.invalidate();
        String out = install();
        assertEarlierWay();
        assertTrue(machine.networks.isEmpty() && machine.asked.isEmpty() && machine.count("docker", "network", "create") == 0, "no network, no proof: " + machine.ran);
        assertTrue(out.contains("The setting model_network is open, so the model server is set up able to reach the network, as earlier versions did."), out);
        assertEquals("asked", plan().get("network_why"));
        assertTrue(status().contains("  network: The model server can reach the network, because the setting model_network is open. With it closed or unset, codezaiku model serve install sets it up with no way out.\n"), status());
        install();
        assertTrue(machine.asked.isEmpty(), "given again with the setting open, it is as wanted");
        // closed: the status says how to get it, and `model serve install` changes it over
        Config.set(ModelServer.MODEL_NETWORK, "closed");
        Config.invalidate();
        assertTrue(status().contains("The model server can reach the network: the setting model_network was open when it was set up. codezaiku model serve install sets it up with no way out."), status());
        out = install();
        assertTrue(out.contains("has no way out to the network") && file("run.sh").contains("--network " + NET) && "shut".equals(plan().get("network")), out);
        assertFalse(plan().containsKey("network_why"), "the plan says one thing: " + plan());
        // open again: the status says the setting waits for `model serve install`, which turns the files back and takes the network away
        Config.set(ModelServer.MODEL_NETWORK, "open");
        Config.invalidate();
        assertTrue(status().contains("has no way out to the network") && status().contains("The setting model_network is open now; codezaiku model serve install applies it."), status());
        out = install();
        assertTrue(out.contains("The setting model_network is open, so the model server is set up able to reach the network"), out);
        assertEarlierWay();
        assertEquals("asked", plan().get("network_why"));
        assertFalse(plan().containsKey("subnet"));
    }

    // ---- an earlier version's install ----

    /** The install as an earlier version left it: its two files, its unit, no plan file, the drive pointed at it; a flag the person added by hand. */
    Map<String, String> installOfEarlier() throws Exception {
        Path dir = ModelServer.dir(); Files.createDirectories(dir);
        var old = ModelServer.plan(ModelServer.Os.linux, ModelServer.rowFor(16), models().resolve("gpt-oss-20b-F16.gguf"), dir, ModelServer.unitPath(ModelServer.Os.linux), "all", 20, false, "cuda");
        Map<String, String> files = new LinkedHashMap<>(ModelServer.filesOf(old, ""));
        files.put("run.sh", files.get("run.sh").replace(" -ngl 99 ", " -ngl 99 --no-webui "));   // a flag the person added by hand
        ModelServer.writeFiles(dir, files);
        Files.createDirectories(old.unit().getParent()); Files.writeString(old.unit(), old.unitText());
        Files.writeString(dir.resolve("previous"), "\n\n");
        Config.set("CODEZAIKU_DRIVE", ModelServer.URL);
        machine.started = true;
        return files;
    }

    @Test
    void anInstallOfAnEarlierVersionIsLeftAsItIsAndTheStatusSaysHowToChangeIt() throws Exception {
        Map<String, String> before = installOfEarlier();
        var out = new ByteArrayOutputStream();
        assertEquals(0, ModelServer.command(new String[]{"status"}, 0, new PrintStream(out, true)));
        for (var f : before.entrySet()) assertEquals(f.getValue(), file(f.getKey()), f.getKey() + " untouched");
        assertFalse(Files.exists(ModelServer.dir().resolve("plan.properties")), "nothing written");
        assertTrue(machine.networks.isEmpty() && machine.asked.isEmpty() && machine.count("systemctl", "--user", "restart") == 0 && machine.count("docker") == 0, machine.ran.toString());
        assertTrue(out.toString().contains("  network: The model server can reach the network: an earlier version of CodeZaiku set it up. codezaiku model serve install sets it up with no way out.\n"), out.toString());
    }

    @Test
    void modelServeInstallGivenAgainChangesAnEarlierInstallOverAndKeepsWhatThePersonWrote() throws Exception {
        Map<String, String> before = installOfEarlier();
        String out = install();
        assertTrue(out.contains("Setting the model server of this machine up again with no way out to the network. A model that is loaded now is unloaded for it.") && out.contains("has no way out to the network"), out);
        assertFalse(out.contains("already answers"), out);
        String run = file("run.sh");
        assertTrue(run.contains(" -ngl 99 --no-webui ") && run.contains("--network " + NET + " --ip " + AT + " --cap-drop NET_RAW") && !run.contains(" -p "), "only the way out changed; the person's flag stays: " + run);
        assertTrue(machine.fetched.isEmpty(), "nothing is downloaded for it: " + machine.fetched);
        assertTrue(machine.ranLine("systemctl", "--user", "restart", "codezaiku-model"));
        assertEquals("shut", plan().get("network"));
        // the plan the earlier version never wrote is read back from its files and written whole
        assertEquals("gpt-oss-20b", plan().get("name")); assertEquals(models().resolve("gpt-oss-20b-F16.gguf").toString(), plan().get("file")); assertEquals("2", plan().get("parallel")); assertEquals("20", plan().get("idle_minutes"));
        assertEquals(before, ModelServer.toOpen(ModelServer.dir(), Map.of("config.yaml", file("config.yaml"), "run.sh", run)), "turned back, the files are the ones the earlier version wrote");
    }

    @Test
    void whenTheChangeOverDoesNotHoldTheInstallGetsBackExactlyTheFilesItHad() throws Exception {
        Map<String, String> before = installOfEarlier();
        machine.modelShut = "!it returned HTTP 502: upstream command exited prematurely";
        String out = install();
        for (var f : before.entrySet()) assertEquals(f.getValue(), file(f.getKey()), f.getKey() + " is what it was before, to the letter");
        assertNull(machine.networks.get(NET));
        assertTrue(out.contains("because the model did not answer through it (it returned HTTP 502: upstream command exited prematurely). It is set up the way earlier versions did") && out.contains("Set up the earlier way, the model answered."), out);
        assertEquals(2, machine.count("systemctl", "--user", "restart"), "once to read the new files, once to read the old ones again");
        assertEquals("unproven", plan().get("network_why"));
        // no free range: nothing of the install is touched, and the proxy is not restarted
        machine.ran.clear(); machine.refused = ModelServer.RANGES;
        out = install();
        assertTrue(out.contains("It stays as it was, able to reach the network. To try again: codezaiku model serve install"), out);
        assertEquals(0, machine.count("systemctl", "--user", "restart"));
        for (var f : before.entrySet()) assertEquals(f.getValue(), file(f.getKey()));
    }

    @Test
    void anInstallCutShortBeforeItsProofClaimsNothingAndIsProvenWhenGivenAgain() throws Exception {
        install();
        ModelServer.rewriteNet(ModelServer.dir(), ModelServer.shutProperties(ModelServer.Shut.of(RANGE), false, false));   // as the install leaves the plan before the proof
        assertFalse(plan().containsKey("network_tried"));
        assertTrue(status().contains("  network: The model server is set up with no way out to the network, but setting it up stopped before that was tried. codezaiku model serve install tries it.\n"), status());
        machine.asked.clear();
        String out = install();
        assertTrue(out.contains("has no way out to the network") && plan().containsKey("network_tried"), out);
        assertEquals(List.of("rest", "complete shut", "rest"), machine.asked);
    }

    @Test
    void theStatusDoesNotClaimWhatTheFilesOrTheNetworkNoLongerAre() throws Exception {
        install();
        machine.networks.put(NET, "false " + RANGE);   // made again by hand as an ordinary network
        assertTrue(status().contains("  network: The model server can reach the network: it was set up with no way out, and its start script or its Docker network (" + NET + ") is no longer as CodeZaiku made it. codezaiku model serve install sets it up with no way out.\n"), status());
        machine.networks.put(NET, "true " + RANGE);
        assertTrue(status().contains("has no way out to the network"));
        machine.networks.remove(NET);   // removed (a prune): the start script makes it again, and the claim is of how the model starts
        assertTrue(status().contains("has no way out to the network"));
        Files.writeString(ModelServer.dir().resolve("run.sh"), file("run.sh").replace(" --network " + NET + " --ip " + AT + " --cap-drop NET_RAW", " -p 127.0.0.1:8080:8080"));
        assertTrue(status().contains("is no longer as CodeZaiku made it"), status());
        // where the server is a plain program, it says that
        ModelServer.os = ModelServer.Os.macos;
        assertTrue(status().contains("  network: On this system the model server is an ordinary program, not in a container, so nothing keeps it from the network.\n"), status());
        ModelServer.os = ModelServer.Os.windows;
        assertTrue(status().contains("an ordinary program, not in a container"), status());
    }

    // ---- the folder two programs share ----

    /** ResearchZosho's embeddings server, as its update adds it beside this program's model where the two share a home: its entry, its group, its start script with a published port. */
    String researchZoshosEmbed(Path dir) throws IOException {
        Files.writeString(dir.resolve("run-embed.sh"), "#!/bin/sh\nexec docker run --rm --name researchzosho-model-embed --gpus all -p 127.0.0.1:$1:80 -v /data:/data ghcr.io/huggingface/text-embeddings-inference:89-1.8 \\\n  --model-id Qwen/Qwen3-Embedding-0.6B\n");
        return "  \"embed\":\n    cmd: " + dir.resolve("run-embed.sh") + " ${PORT}\n    cmdStop: docker stop researchzosho-model-embed\n    proxy: http://127.0.0.1:${PORT}\n    checkEndpoint: /health\n    ttl: 300\n"
                + "    aliases: [\"Qwen/Qwen3-Embedding-0.6B\", \"text-embedding\"]\n"
                + "groups:\n  \"embedding\":\n    swap: false\n    exclusive: false\n    persistent: true\n    members: [\"embed\"]\n";
    }

    @Test
    void inAFolderBothProgramsShareOnlyThisProgramsModelChangesAndResearchZoshosLinesStay() throws Exception {
        installOfEarlier();
        Path dir = ModelServer.dir();
        String theirs = researchZoshosEmbed(dir), embedScript = file("run-embed.sh");
        Files.writeString(dir.resolve("config.yaml"), file("config.yaml") + theirs);
        String theirPlan = "# ResearchZosho: what `model install` was told; a later release rewrites config.yaml from this\nname=gpt-oss-20b\nfile=" + models().resolve("gpt-oss-20b-F16.gguf")
                + "\nctx=32768\nparallel=2\nextra=\ngpus=all\nidle_minutes=10\nshare=false\nchosen=suggested\n";
        Files.writeString(dir.resolve("plan.properties"), theirPlan);
        String out = install();
        assertTrue(out.contains("has no way out to the network"), out);
        String y = file("config.yaml");
        assertTrue(y.endsWith(theirs), "ResearchZosho's entry and group are as they were: " + y);
        assertTrue(y.contains("proxy: http://" + AT + ":8080\n"), y);
        assertEquals(embedScript, file("run-embed.sh"), "its start script is its own");
        assertTrue(file("plan.properties").startsWith(theirPlan), "its plan lines stay, and the lines of the way out follow: " + file("plan.properties"));
        assertEquals("shut", plan().get("network")); assertEquals(RANGE, plan().get("subnet"));
        assertEquals("http://127.0.0.1:8211", Config.get("CODEZAIKU_DRIVE"), "the address both programs use is the proxy's, unchanged");
        String status = status();
        assertTrue(status.contains("has no way out to the network") && status.contains(" The proxy also starts embed, which another program added; it can reach the network, and CodeZaiku leaves it as it is.\n"), status);
    }

    @Test
    void anotherProgramsModelServerInTheFolderIsLeftAsItIsAndDescribedFromItsStartScript() throws Exception {
        // ResearchZosho set its own model server up in the folder the two share, with no way out: its names, its network, its service
        Path dir = ModelServer.dir(); Files.createDirectories(dir);
        String run = "#!/bin/sh\ndocker network inspect researchzosho-model-shut >/dev/null 2>&1 || docker network create --internal --subnet 172.16.211.0/28 researchzosho-model-shut >/dev/null 2>&1\n"
                + "exec docker run --rm --name researchzosho-model --gpus all --network researchzosho-model-shut --ip 172.16.211.2 --cap-drop NET_RAW -v /m:/m ghcr.io/ggml-org/llama.cpp:server-cuda \\\n  -m /m/x.gguf --host 0.0.0.0 --port 8080\n";
        String cfg = "models:\n  \"qwen3.8-27b\":\n    cmd: " + dir.resolve("run.sh") + "\n    cmdStop: docker stop researchzosho-model\n    proxy: http://172.16.211.2:8080\n    ttl: 600\n";
        String plan = "name=qwen3.8-27b\nnetwork=shut\nsubnet=172.16.211.0/28\nnetwork_tried=2026-11-23\n";
        Files.writeString(dir.resolve("run.sh"), run); Files.writeString(dir.resolve("config.yaml"), cfg); Files.writeString(dir.resolve("plan.properties"), plan);
        Path theirUnit = ModelServer.unitPath(ModelServer.Os.linux).resolveSibling("researchzosho-model.service");
        Files.createDirectories(theirUnit.getParent()); Files.writeString(theirUnit, "[Service]\nExecStart=" + dir.resolve("llama-swap") + " --config " + dir.resolve("config.yaml") + "\n");
        machine.started = true;
        String out = install();
        assertTrue(out.contains("a model proxy already answers at http://127.0.0.1:8211; using it"), out);
        assertEquals(run, file("run.sh")); assertEquals(cfg, file("config.yaml")); assertEquals(plan, file("plan.properties"));
        assertTrue(machine.ran.isEmpty() && machine.networks.isEmpty() && machine.asked.isEmpty(), "nothing run, no network, no proof: " + machine.ran);
        assertTrue(status().contains("  network: Another program set the model server up, and CodeZaiku leaves it as it is. By its start script the model is on a Docker network of its own (researchzosho-model-shut), with no port of this machine published.\n"), status());
        // set up by it the earlier way, it can reach the network, and that is what is said; `model serve install` does not turn it
        String openRun = "#!/bin/sh\nexec docker run --rm --name researchzosho-model --gpus all -p 127.0.0.1:$1:8080 -v /m:/m ghcr.io/ggml-org/llama.cpp:server-cuda \\\n  -m /m/x.gguf --host 0.0.0.0 --port 8080\n";
        String openCfg = "models:\n  \"qwen3.8-27b\":\n    cmd: " + dir.resolve("run.sh") + " ${PORT}\n    cmdStop: docker stop researchzosho-model\n    proxy: http://127.0.0.1:${PORT}\n    ttl: 600\n";
        Files.writeString(dir.resolve("run.sh"), openRun); Files.writeString(dir.resolve("config.yaml"), openCfg); Files.writeString(dir.resolve("plan.properties"), "name=qwen3.8-27b\n");
        assertTrue(status().contains("  network: The model server can reach the network: another program set it up, and CodeZaiku leaves it as it is.\n"), status());
        machine.ran.clear();
        assertTrue(install().contains("a model proxy already answers at http://127.0.0.1:8211; using it"));
        assertEquals(openRun, file("run.sh")); assertEquals(openCfg, file("config.yaml")); assertEquals("name=qwen3.8-27b\n", file("plan.properties"));
        assertTrue(machine.networks.isEmpty() && machine.asked.isEmpty() && machine.count("docker") == 0, "its files are its own: " + machine.ran);
    }
}
