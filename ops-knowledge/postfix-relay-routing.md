match: postfix, smtp, postqueue, smtpd
signature: loops back to myself, relay access denied, mail for
push: rescue
status: candidate
# Postfix relay/routing rejection — fix procedure
1. Read the log: "mail for <domain> loops back to myself" (domain not in mydestination/no route) or "Relay access denied" (sender not permitted to relay).
2. Confirm current routing: postconf mydestination relay_domains mynetworks transport_maps.
3. Fix loops-back: add the local domain to mydestination, OR add a transport_maps entry ("<domain> smtp:[<real-mx>]") so it routes onward.
4. Fix relay-denied: add the trusted client subnet to mynetworks (or enable SASL auth), not a blanket open relay.
5. Reload: postfix reload; requeue held mail with postqueue -f.
6. Recheck the log — when delivery lines show "status=sent" and the errors stop, conclude (submit) immediately.
