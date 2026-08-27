match: mongo, mongodb
signature: authentication failed, bad auth, not authorized, code 18, unauthorized
push: rescue
status: validated
platform: kubernetes
# MongoDB auth fault — fix procedure
1. The app's auth error names the db and user it tried — read them from the failing app pod's logs.
2. Find the credentials before touching the db: root/app users and the db's init script normally live in
   the namespace's configmaps/secrets (mounted at init) — `kubectl get cm -n <ns>`, then read the mongo-
   related ones. Legacy mongo images ship `mongo`, not `mongosh` — if one is missing, use the other.
3. Restore access as root: createUser with the user/password the app expects, or grantRolesToUser
   readWrite on its db — `kubectl exec <mongo-pod> -- mongo admin -u <root> -p <pwd> --eval "..."`.
   Re-running the init script from step 2 also works.
4. Recheck the app logs once — when the auth errors stop, conclude (submit) immediately.
