package org.codezaiku.exec;

import org.codezaiku.Config;
import org.codezaiku.tools.GivenAddresses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a sandboxed command may reach where its network is closed, decided by host and port: the registries, the project's own
 * remotes as they were when the job began, the hosts the person names; and what the proxy reads of a request. Nothing here starts
 * a process or opens a connection.
 */
class NetRulesTest {

    @TempDir Path home;
    @TempDir Path project;
    String realHome;

    @BeforeEach
    void before() {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Config.invalidate();
    }

    @AfterEach
    void after() {
        NetRules.given(null);
        System.setProperty("user.home", realHome);
        Config.invalidate();
    }

    @Test
    void theRegistriesAreLetInOnHttpsAndNothingElseIs() {
        NetRules r = NetRules.at(project);
        for (String h : List.of("pypi.org", "files.pythonhosted.org", "registry.npmjs.org", "repo.maven.apache.org", "index.crates.io",
                "static.crates.io", "proxy.golang.org", "sum.golang.org", "plugins.gradle.org", "services.gradle.org"))
            assertTrue(r.decide(h, 443).let(), h + ": " + r.decide(h, 443));
        assertTrue(r.decide("PyPI.org.", 443).let(), "a name as the tools may write it");
        assertFalse(r.decide("pypi.org", 80).let(), "a registry on another port is not");
        for (String h : List.of("upload.pypi.org", "example.com", "evil.pypi.org.example.com", "github.com", "1.1.1.1"))
            assertFalse(r.decide(h, 443).let(), h + " is not let in: " + r.decide(h, 443));
        assertEquals("not let in", r.decide("example.com", 443).rule());
    }

    @Test
    void theProjectsRemotesAreNotLetInButNamedAsTheyWereWhenTheJobBegan() throws Exception {
        Files.createDirectories(project.resolve(".git"));
        Files.writeString(project.resolve(".git/config"), """
                [core]
                	url = https://not-a-remote.example/
                [remote "origin"]
                	url = https://git.example.org/team/app.git
                	fetch = +refs/heads/*:refs/remotes/origin/*
                [remote "mirror"]
                	url = http://mirror.example.net:8080/app.git
                [remote "ssh"]
                	url = git@ssh-only.example.com:team/app.git
                """);
        Files.writeString(project.resolve(".gitmodules"), "[submodule \"lib\"]\n\tpath = lib\n\turl = https://modules.example.io/lib.git\n");
        NetRules r = NetRules.at(project);
        assertTrue(r.remote("git.example.org", 443) && r.remote("mirror.example.net", 8080) && r.remote("modules.example.io", 443), r.remotes().toString());
        assertFalse(r.remote("ssh-only.example.com", 443), "an ssh remote cannot pass an HTTP proxy: not named either");
        assertFalse(r.remote("not-a-remote.example", 443), "only remote and submodule sections count");
        NetRules.Decision d = r.decide("git.example.org", 443);
        assertFalse(d.let(), "the owner's rule: a project's own remote is not let in by default");
        assertEquals("the project's git remote: not let in by default", d.rule());
        Config.set("sandbox.hosts", "git.example.org");
        assertTrue(r.decide("git.example.org", 443).let(), "one setting lets it in");

        // a remote the job adds afterwards is not even named: the remotes were read when the job began
        Files.writeString(project.resolve(".git/config"), "[remote \"added\"]\n\turl = https://added-later.example/x.git\n", StandardOpenOption.APPEND);
        assertFalse(r.remote("added-later.example", 443));
    }

    @Test
    void thePersonLetsAHostInWithOneSetting() throws Exception {
        NetRules r = NetRules.at(project);
        assertFalse(r.decide("data.example.com", 443).let());
        Config.set("sandbox.hosts", "data.example.com, api.example.org:8443, *.cdn.example.net");
        assertTrue(r.decide("data.example.com", 443).let() && r.decide("data.example.com", 80).let(), "a bare host: 443 and 80");
        assertEquals("sandbox.hosts", r.decide("data.example.com", 443).rule());
        assertFalse(r.decide("data.example.com", 8443).let(), "not other ports");
        assertTrue(r.decide("api.example.org", 8443).let() && !r.decide("api.example.org", 443).let(), "host:port: that port");
        assertTrue(r.decide("a.cdn.example.net", 443).let() && !r.decide("cdn.example.net", 443).let() && !r.decide("xcdn.example.net", 443).let(),
                "a wildcard: names under it only");
        assertTrue(r.named("data.example.com", 443) && !r.named("pypi.org", 443), "named by the person: only these may lead to this machine");
        r.letIn("once.example.org", 443);
        assertEquals("let in by the person", r.decide("once.example.org", 443).rule());
        r.letOut("once.example.org", 443);
        assertFalse(r.decide("once.example.org", 443).let(), "and out again when the chat ends");
    }

    @Test
    void aHostThePersonGaveIsLetInAndOneASearchGaveIsNot() {
        long n = System.nanoTime();
        NetRules r = NetRules.at(project);
        String named = "named-" + n + ".example.org", found = "found-" + n + ".example.org";
        assertFalse(r.decide(named, 443).let());
        GivenAddresses.personSaid("the data is at https://" + named + "/data.csv");
        GivenAddresses.received("1. A result — https://" + found + "/page");
        assertEquals("named by the person", r.decide(named, 443).rule(), "by the rule of given addresses, not a stand-in");
        assertFalse(r.decide(found, 443).let(), "a search's result gives web_fetch an address and a command nothing");
        NetRules.given((p, host, port) -> { throw new IllegalStateException("broken rule"); });
        assertFalse(r.decide(named, 443).let(), "a rule that fails gives nothing");
    }

    @Test
    void thisMachineAndTheLinkAreNotAddressesToConnectACommandTo() throws Exception {
        assertNotNull(NetRules.unusable(InetAddress.getByName("127.0.0.1")));
        assertNotNull(NetRules.unusable(InetAddress.getByName("::1")));
        assertNotNull(NetRules.unusable(InetAddress.getByName("::ffff:127.0.0.1")));
        assertNotNull(NetRules.unusable(InetAddress.getByName("169.254.169.254")), "the cloud metadata address");
        assertNotNull(NetRules.unusable(InetAddress.getByName("0.0.0.0")));
        assertNull(NetRules.unusable(InetAddress.getByName("192.0.2.10")));
    }

    // ── what the proxy reads ────────────────────────────────────────────────────────────────────────────────────────

    private static NetProxy.Request read(String head) throws Exception {
        return NetProxy.Request.read(new ByteArrayInputStream(head.getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    void theProxyReadsWhereARequestGoesAndWhichCommandSentIt() throws Exception {
        String auth = "Basic " + Base64.getEncoder().encodeToString("cz:42".getBytes(StandardCharsets.UTF_8));
        NetProxy.Request c = read("CONNECT pypi.org:443 HTTP/1.1\r\nHost: pypi.org:443\r\nProxy-Authorization: " + auth + "\r\n\r\n");
        assertTrue(c.connect);
        assertEquals("pypi.org", c.host);
        assertEquals(443, c.port);
        assertEquals(42, NetProxy.idFrom(c.proxyAuth));
        NetProxy.Request six = read("CONNECT [2001:db8::1]:8443 HTTP/1.1\r\n\r\n");
        assertEquals("2001:db8::1", six.host);
        assertEquals(8443, six.port);
        NetProxy.Request plain = read("GET http://repo.example.org/a/b?c=1 HTTP/1.1\r\nHost: repo.example.org\r\nProxy-Connection: keep-alive\r\n"
                + "Proxy-Authorization: " + auth + "\r\nAccept: */*\r\n\r\n");
        assertEquals("repo.example.org", plain.host);
        assertEquals(80, plain.port);
        String sent = new String(plain.originHead(), StandardCharsets.ISO_8859_1);
        assertTrue(sent.startsWith("GET /a/b?c=1 HTTP/1.1\r\n"), sent);
        assertTrue(sent.contains("Accept: */*") && sent.endsWith("Connection: close\r\n\r\n"), sent);
        assertFalse(sent.contains("Proxy-"), "the proxy's own headers are not sent on: " + sent);
        assertNull(read("\u0016\u0003\u0001garbage\r\n\r\n").host, "not a proxy request: no host");
        assertNull(read("GET https://pypi.org/ HTTP/1.1\r\n\r\n").host, "https in absolute form is not how a client asks a proxy");
        assertNull(NetProxy.idFrom("Basic " + Base64.getEncoder().encodeToString("someone:else".getBytes(StandardCharsets.UTF_8))));
        assertNull(NetProxy.idFrom("Bearer x"));
    }

    @Test
    void aClosedNetworksCommandIsToldToUseTheProxyWhateverThePersonsOwnProxyWas() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("HTTPS_PROXY", "http://corporate.example:3128");
        env.put("npm_config_https_proxy", "http://corporate.example:3128");
        env.put("JAVA_TOOL_OPTIONS", "-Duser.home=/run/home");
        SandboxEnv.proxied(env, 41234, 7);
        for (String name : SandboxEnv.PROXY_NAMES) assertEquals("http://cz:7@127.0.0.1:41234", env.get(name), name);
        assertEquals("localhost,127.0.0.1,::1", env.get("no_proxy"));
        assertEquals("-Duser.home=/run/home -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=41234 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=41234",
                env.get("JAVA_TOOL_OPTIONS"));
        assertEquals("basic", env.get("GIT_HTTP_PROXY_AUTHMETHOD"), "git sends the command's number without waiting to be asked");
    }

    @Test
    void mavenIsToldOfTheProxyInItsOwnSettingsAndAFileOfAnyoneElsesIsLeftAlone() throws Exception {
        Path runHome = Files.createDirectories(home.resolve("run-home"));
        SandboxEnv.mavenSettings(runHome, 3128);
        String written = Files.readString(runHome.resolve(".m2/settings.xml"));
        assertTrue(written.startsWith(SandboxEnv.MAVEN_MARK) && written.contains("<host>127.0.0.1</host><port>3128</port>")
                && written.contains("<protocol>https</protocol>"), written);
        SandboxEnv.mavenSettings(runHome, 40000);
        assertTrue(Files.readString(runHome.resolve(".m2/settings.xml")).contains("<port>40000</port>"), "ours is rewritten when the port changes");
        Files.writeString(runHome.resolve(".m2/settings.xml"), "<settings><mirrors/></settings>\n");
        SandboxEnv.mavenSettings(runHome, 3128);
        assertEquals("<settings><mirrors/></settings>\n", Files.readString(runHome.resolve(".m2/settings.xml")), "someone else's is left as it is");
    }
}
