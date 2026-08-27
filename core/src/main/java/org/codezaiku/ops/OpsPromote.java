package org.codezaiku.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AUTO-PROMOTION — a candidate card that keeps working earns validated status. Each time a CANDIDATE card's
 * fix is applied and the objective check VERIFIES, its {@code reuses:} counter is incremented; at the
 * threshold the card is flipped {@code candidate → validated} (so it may thereafter AUTO-remediate, not just
 * propose). This is the evidence bar for "the fix is reliable" — the same repeated-verified-success signal
 * a K-run A/B gives, accrued in production/trial over time. Applies to LEARNED cards (recon successes) and
 * hand-authored candidates alike; validated cards are left untouched.
 */
public final class OpsPromote {
    private static final Logger log = LoggerFactory.getLogger(OpsPromote.class);

    private OpsPromote() { }

    /**
     * Record one verified reuse of the candidate card at {@code path}; promote it to validated at
     * {@code threshold}. No-op (never throws) when the card is missing, already validated, or unreadable.
     */
    public static void recordReuse(String path, int threshold) {
        if (path == null || path.isBlank()) return;
        Path p = Path.of(path);
        if (!Files.exists(p)) return;
        // SERIALISE, AND WRITE ATOMICALLY. This is a read-modify-write on a file that other runs may be
        // updating at the same moment — `serve` dispatches concurrent /fix requests and `watch` runs
        // unattended. Measured with 20 concurrent promotions of one card: 19 counts lost (harmless, it
        // under-counts) but the CARD ITSELF was destroyed — body gone, status line gone, no longer
        // lint-clean, which means it is rejected at load and the fault class silently loses its
        // procedure. An in-process lock orders our own threads; the temp-file + ATOMIC_MOVE means a
        // reader (or a crash) never sees a half-written card either way.
        synchronized (PROMOTE_LOCK) {
            recordReuseLocked(p, threshold);
        }
    }

    private static final Object PROMOTE_LOCK = new Object();

    private static void recordReuseLocked(Path p, int threshold) {
        try {
            String[] lines = Files.readString(p).split("\n", -1);
            int statusIdx = -1, reusesIdx = -1, bodyStart = lines.length, reuses = 0;
            for (int i = 0; i < lines.length; i++) {
                String low = lines[i].toLowerCase(Locale.ROOT);
                if (low.startsWith("# ")) { bodyStart = i; break; }        // header ends at the first '# '
                if (low.startsWith("status:")) statusIdx = i;
                if (low.startsWith("reuses:")) {
                    reusesIdx = i;
                    try { reuses = Integer.parseInt(lines[i].substring(7).strip()); } catch (Exception ignored) { }
                }
            }
            String status = statusIdx >= 0 ? lines[statusIdx].substring(7).strip().toLowerCase(Locale.ROOT) : "candidate";
            if (status.equals("validated")) return;                        // already earned — nothing to do
            reuses++;
            boolean promote = reuses >= threshold;

            List<String> out = new ArrayList<>();
            boolean wroteReuses = false, wroteStatus = statusIdx >= 0;
            for (int i = 0; i < lines.length; i++) {
                if (i == bodyStart && !wroteReuses) { out.add("reuses: " + reuses); wroteReuses = true; }
                if (i == bodyStart && promote && !wroteStatus) { out.add("status: validated"); wroteStatus = true; }
                if (i == statusIdx) { out.add(promote ? "status: validated" : lines[i]); continue; }
                if (i == reusesIdx) { out.add("reuses: " + reuses); wroteReuses = true; continue; }
                out.add(lines[i]);
            }
            if (!wroteReuses) out.add("reuses: " + reuses);
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.writeString(tmp, String.join("\n", out));
            try {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            }

            if (promote) System.out.println("[ops-promote] PROMOTED " + p.getFileName()
                    + " candidate→VALIDATED after " + reuses + " verified reuses — it may now auto-remediate");
            else System.out.println("[ops-promote] " + p.getFileName() + " verified reuse " + reuses + "/"
                    + threshold + " (stays candidate → propose-only)");
            log.info("ops-promote: {} reuses={} promote={}", p.getFileName(), reuses, promote);
        } catch (Exception e) {
            log.warn("ops-promote failed for {}: {}", p, e.getMessage());
        }
    }
}
