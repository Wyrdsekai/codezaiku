package org.codezaiku.chat;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Who may use the chat in the browser, and as what. The owner acts: types, answers the chat's questions, adds and removes projects and
 * people. A viewer watches: the pages and the transcripts, nothing that changes a project. People are kept in {@code accounts} under the
 * web folder, one a line, with a salted PBKDF2 hash of their password; a signed-in browser carries a session the server signed with a
 * secret of its own, kept beside them. The token the server prints stays the owner's master key, so a box with no accounts works as before.
 */
public final class WebAccounts {
    public static final String OWNER = "owner", VIEWER = "viewer";
    public record Person(String name, String role) { public boolean owner() { return OWNER.equals(role); } }
    private record Row(String name, String role, byte[] salt, byte[] hash) { }

    private final Path dir;
    private byte[] secret;

    public WebAccounts(Path dir) throws IOException { this.dir = dir; }

    Path file() { return dir.resolve("accounts"); }

    private synchronized byte[] secret() throws IOException {
        if (secret != null) return secret;
        Path f = dir.resolve("secret");
        if (Files.isRegularFile(f)) { String t = Files.readString(f, StandardCharsets.UTF_8).strip(); if (t.length() == 64) return secret = HexFormat.of().parseHex(t); }
        byte[] s = new byte[32]; new SecureRandom().nextBytes(s);
        Files.createDirectories(dir); Files.writeString(f, HexFormat.of().formatHex(s) + "\n", StandardCharsets.UTF_8);
        try { Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException | IOException ignored) { }
        return secret = s;
    }

    private synchronized List<Row> rows() throws IOException {
        List<Row> out = new ArrayList<>();
        if (!Files.isRegularFile(file())) return out;
        for (String l : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
            String[] p = l.split("\t");
            if (p.length == 4) out.add(new Row(p[0], p[1], HexFormat.of().parseHex(p[2]), HexFormat.of().parseHex(p[3])));
        }
        return out;
    }

    private synchronized void write(List<Row> rows) throws IOException {
        Files.createDirectories(dir);
        StringBuilder b = new StringBuilder();
        for (Row r : rows) b.append(r.name()).append('\t').append(r.role()).append('\t').append(HexFormat.of().formatHex(r.salt())).append('\t').append(HexFormat.of().formatHex(r.hash())).append('\n');
        Files.writeString(file(), b.toString(), StandardCharsets.UTF_8);
        try { Files.setPosixFilePermissions(file(), PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException | IOException ignored) { }
    }

    static byte[] hash(String password, byte[] salt) {
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(password.toCharArray(), salt, 120_000, 256)).getEncoded(); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    /** The people, in the order they were added. */
    public List<Person> people() throws IOException { List<Person> out = new ArrayList<>(); for (Row r : rows()) out.add(new Person(r.name(), r.role())); return out; }
    public boolean any() throws IOException { return !rows().isEmpty(); }

    /** A person added or changed: a name of letters, digits, dots and dashes; a password of eight characters or more; owner or viewer. */
    public synchronized void put(String name, String password, String role) throws IOException {
        if (name == null || !name.matches("[A-Za-z0-9._-]{1,40}")) throw new IllegalArgumentException("a name is letters, digits, dots and dashes, up to forty");
        if (password == null || password.length() < 8) throw new IllegalArgumentException("a password is eight characters or more");
        if (!OWNER.equals(role) && !VIEWER.equals(role)) throw new IllegalArgumentException("a role is owner or viewer");
        byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
        List<Row> all = rows(); all.removeIf(r -> r.name().equalsIgnoreCase(name)); all.add(new Row(name, role, salt, hash(password, salt)));
        write(all);
    }

    public synchronized boolean remove(String name) throws IOException { List<Row> all = rows(); boolean had = all.removeIf(r -> r.name().equalsIgnoreCase(name)); if (had) write(all); return had; }

    /** The person these words sign in, or null. */
    public Person signIn(String name, String password) throws IOException {
        if (name == null || password == null) return null;
        for (Row r : rows()) if (r.name().equalsIgnoreCase(name.strip())) return MessageDigest.isEqual(r.hash(), hash(password, r.salt())) ? new Person(r.name(), r.role()) : null;
        return null;
    }

    private String mac(String s) throws IOException {
        try { Mac m = Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(secret(), "HmacSHA256")); return HexFormat.of().formatHex(m.doFinal(s.getBytes(StandardCharsets.UTF_8))); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    /** A session for this person, good for {@code days}: what the cookie carries. */
    public String session(Person p, int days) throws IOException {
        long exp = System.currentTimeMillis() / 1000 + days * 86400L;
        String body = URLEncoder.encode(p.name(), StandardCharsets.UTF_8) + "." + p.role() + "." + exp;
        return body + "." + mac(body);
    }

    /** The person a session names, when it is signed by this server and not past its day; null otherwise. */
    public Person check(String session) throws IOException {
        if (session == null) return null;
        int dot = session.lastIndexOf('.'); if (dot <= 0) return null;
        String body = session.substring(0, dot), sig = session.substring(dot + 1);
        if (!MessageDigest.isEqual(sig.getBytes(StandardCharsets.UTF_8), mac(body).getBytes(StandardCharsets.UTF_8))) return null;
        String[] p = body.split("\\."); if (p.length != 3) return null;
        try { if (Long.parseLong(p[2]) < System.currentTimeMillis() / 1000) return null; } catch (NumberFormatException e) { return null; }
        String role = p[1].toLowerCase(Locale.ROOT); if (!OWNER.equals(role) && !VIEWER.equals(role)) return null;
        return new Person(URLDecoder.decode(p[0], StandardCharsets.UTF_8), role);
    }
}
