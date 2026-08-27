match: kerberos, kinit, krb5, gssapi
signature: clock skew too great, krb_ap_err_skew, clock skew too great while getting initial credentials
push: rescue
status: candidate
# Kerberos clock skew authentication failure — fix procedure
1. Read the error: "Clock skew too great" / "KRB_AP_ERR_SKEW" means the client clock differs from the KDC by more than the 5-minute tolerance.
2. Confirm the drift: run `date` on this host and compare to the KDC/DC; a gap over ~300s is the fault.
3. Fix by syncing time: chronyd -> `chronyc makestep` then `chronyc sources -v`; or ntpd -> `ntpd -gq`; point the sync source at the KDC/domain controller.
4. Retry auth: kinit <principal> should now succeed without the skew error.
5. Recheck once — when kinit returns a ticket (klist shows it) and the skew error is gone, conclude (submit) immediately.
