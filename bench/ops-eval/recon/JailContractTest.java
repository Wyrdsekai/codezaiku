import org.codezaiku.ops.ReconJail;
public class JailContractTest {
    static int pass=0, fail=0;
    static void chk(String what, boolean got, boolean want) {
        boolean ok = got==want;
        System.out.printf("  [%s] %-55s got=%-5s want=%s%n", ok?"ok":"FAIL", what, got, want);
        if(ok) pass++; else fail++;
    }
    public static void main(String[] a) {
        var eval = ReconJail.forEvalBox();
        // --- reads that MUST be allowed (stack knowledge) ---
        chk("read /etc/nginx/nginx.conf", eval.mayRead("/etc/nginx/nginx.conf"), true);
        chk("read /var/log/syslog", eval.mayRead("/var/log/syslog"), true);
        chk("read /app/config.py", eval.mayRead("/app/config.py"), true);
        // --- reads that MUST be denied (answer key / grader / secrets) ---
        chk("deny record.csv", eval.mayRead("/data/Telecom/record.csv"), false);
        chk("deny query.csv", eval.mayRead("/data/Telecom/query.csv"), false);
        chk("deny scoring_points.json", eval.mayRead("/x/scoring_points.json"), false);
        chk("deny /tests/test_outputs.py", eval.mayRead("/app/tests/test_outputs.py"), false);
        chk("deny solution/solve.sh", eval.mayRead("/task/solution/solve.sh"), false);
        chk("deny check.sh", eval.mayRead("/task/check.sh"), false);
        chk("deny reward.txt", eval.mayRead("/logs/verifier/reward.txt"), false);
        chk("deny /etc/shadow", eval.mayRead("/etc/shadow"), false);
        chk("deny id_rsa", eval.mayRead("/root/.ssh/id_rsa"), false);
        chk("deny .env", eval.mayRead("/app/.env"), false);
        // --- commands that MUST be allowed (read-only probes) ---
        chk("run cat os-release", eval.mayRun("cat /etc/os-release"), true);
        chk("run systemctl list-units", eval.mayRun("systemctl list-units --type=service"), true);
        chk("run ss -ltnp", eval.mayRun("ss -ltnp | awk '{print $4}'"), true);
        chk("run find README", eval.mayRun("find / -iname 'README*' | head -8"), true);
        // REGRESSION: benign stderr/dev-null redirects must be ALLOWED (the first live run refused every probe)
        chk("run cat 2>/dev/null", eval.mayRun("cat /etc/os-release 2>/dev/null | head -2"), true);
        chk("run ss 2>/dev/null|awk", eval.mayRun("ss -ltnp 2>/dev/null | awk '{print $4}'"), true);
        chk("run for..echo 2>/dev/null", eval.mayRun("for p in /etc/nginx/nginx.conf; do [ -f $p ] && echo $p; done 2>/dev/null"), true);
        chk("run cmd 2>&1", eval.mayRun("systemctl status nginx 2>&1 | head"), true);
        // REGRESSION: a '>' INSIDE quotes (awk NR>1, sed s/">"/) is not a redirect (refused the ports probe)
        chk("run awk NR>1 (quoted >)", eval.mayRun("ss -ltnp 2>/dev/null | awk 'NR>1{print $4}' | sed 's/\".*//'"), true);
        chk("run sed quoted >", eval.mayRun("cat f | sed 's/x>y/z/'"), true);
        chk("refuse UNquoted > file still", eval.mayRun("awk '{print $1}' data > /etc/out"), false);
        // --- commands that MUST be refused (mutating / escape) ---
        chk("refuse rm", eval.mayRun("rm -rf /tmp/x"), false);
        chk("refuse systemctl restart", eval.mayRun("systemctl restart nginx"), false);
        chk("refuse docker run", eval.mayRun("docker run -d x"), false);
        chk("refuse redirect", eval.mayRun("cat x > /etc/passwd"), false);
        chk("refuse cmd-subst", eval.mayRun("cat $(find / -name secret)"), false);
        chk("refuse cat&&rm", eval.mayRun("cat x && rm y"), false);
        chk("refuse sed -i", eval.mayRun("sed -i s/a/b/ file"), false);
        chk("refuse tee", eval.mayRun("echo x | tee /etc/hosts"), false);
        System.out.printf("%n  === jail: %d passed, %d FAILED ===%n", pass, fail);
        if(fail>0) System.exit(1);
    }
}
