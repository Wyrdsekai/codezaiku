package org.codezaiku.drive.aws;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import java.net.URLDecoder;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
/**
 * AWS Signature Version 4, for one request: the headers to add so that AWS accepts it as coming from the holder of the
 * credentials. Written here, in a page of code, so that using somebody's AWS account needs no AWS library. It follows AWS's
 * published algorithm, and the tests hold it against signatures made by AWS's own signer.
 */
public final class SigV4 {

    private SigV4() { }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /**
     * The headers to send with the request: x-amz-date, x-amz-security-token when the credentials are temporary, and Authorization.
     * {@code uri} is the address exactly as it is sent, with its path already percent-encoded. {@code contentType} may be null for a request without a body.
     */
    public static Map<String, String> sign(String method, URI uri, String contentType, byte[] body, String region, String service, AwsCredentials.Keys keys, Instant when) {
        String amzDate = STAMP.format(when), day = amzDate.substring(0, 8);
        TreeMap<String, String> headers = new TreeMap<>();
        if (contentType != null) headers.put("content-type", contentType);
        headers.put("host", uri.getPort() > 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost());
        headers.put("x-amz-date", amzDate);
        if (keys.sessionToken() != null && !keys.sessionToken().isBlank()) headers.put("x-amz-security-token", keys.sessionToken());
        StringBuilder canonicalHeaders = new StringBuilder();
        headers.forEach((k, v) -> canonicalHeaders.append(k).append(':').append(v.strip().replaceAll("\\s+", " ")).append('\n'));
        String signed = String.join(";", headers.keySet());
        String canonical = method + "\n" + canonicalPath(uri.getRawPath()) + "\n" + canonicalQuery(uri.getRawQuery()) + "\n" + canonicalHeaders + "\n" + signed + "\n" + hex(sha256(body == null ? new byte[0] : body));
        String scope = day + "/" + region + "/" + service + "/aws4_request";
        String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + hex(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
        byte[] k = hmac(hmac(hmac(hmac(("AWS4" + keys.secretKey()).getBytes(StandardCharsets.UTF_8), day), region), service), "aws4_request");
        TreeMap<String, String> out = new TreeMap<>();
        out.put("x-amz-date", amzDate);
        if (headers.containsKey("x-amz-security-token")) out.put("x-amz-security-token", keys.sessionToken());
        out.put("Authorization", "AWS4-HMAC-SHA256 Credential=" + keys.accessKeyId() + "/" + scope + ", SignedHeaders=" + signed + ", Signature=" + hex(hmac(k, toSign)));
        return out;
    }

    /** Every service but S3 signs the path encoded once more than it is sent: a model id's %3A is signed as %253A. */
    static String canonicalPath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return "/";
        List<String> out = new ArrayList<>();
        for (String segment : rawPath.split("/", -1)) out.add(encode(segment));
        return String.join("/", out);
    }

    static String canonicalQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return "";
        TreeMap<String, List<String>> sorted = new TreeMap<>();
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String k = decode(eq < 0 ? pair : pair.substring(0, eq)), v = eq < 0 ? "" : decode(pair.substring(eq + 1));
            sorted.computeIfAbsent(encode(k), x -> new ArrayList<>()).add(encode(v));
        }
        List<String> out = new ArrayList<>();
        sorted.forEach((k, vs) -> vs.stream().sorted().forEach(v -> out.add(k + "=" + v)));
        return String.join("&", out);
    }

    /** RFC 3986: letters, digits, - _ . ~ stay; everything else is %XX of its UTF-8 bytes. */
    public static String encode(String s) {
        StringBuilder b = new StringBuilder();
        for (byte x : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (x & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') b.append(c);
            else b.append('%').append(String.format("%02X", x & 0xff));
        }
        return b.toString();
    }

    private static String decode(String s) { return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8); }

    private static byte[] sha256(byte[] data) {
        try { return MessageDigest.getInstance("SHA-256").digest(data); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static byte[] hmac(byte[] key, String data) {
        try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(data.getBytes(StandardCharsets.UTF_8)); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    private static String hex(byte[] b) { return HexFormat.of().formatHex(b); }
}
