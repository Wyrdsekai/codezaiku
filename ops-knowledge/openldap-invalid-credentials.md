match: openldap, ldap, ldapsearch, slapd
signature: ldap_bind: invalid credentials (49), acceptsecuritycontext error, data 52e, ldap_sasl_bind
push: rescue
status: candidate
# OpenLDAP bind authentication failure — fix procedure
1. Read the error: "ldap_bind: Invalid credentials (49)" means a wrong bind DN or password (AD adds "AcceptSecurityContext error, data 52e").
2. Confirm the app's expected bind DN and password from its deployment env or config (e.g. LDAP_ADMIN_PASSWORD / bindpw).
3. Test directly: ldapsearch -x -D "<bindDN>" -w "<pw>" -H ldap://<host> -b "<base>" -s base. A clean result confirms creds.
4. Fix: correct the app's bindDN/password to the tested values; or reset the entry with ldappasswd -x -D cn=admin,<base> -W -S "<userDN>".
5. Recheck the app log once — when the "Invalid credentials (49)" line stops, conclude (submit) immediately.
