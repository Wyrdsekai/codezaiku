match: solr
signature: no live solrservers, error creating solrcore, core is not available, could not load core
push: rescue
status: candidate
# Solr core/availability fault — fix procedure
1. "No live SolrServers available" means the client can reach Solr but no node is serving the collection;
   "Error CREATEing SolrCore"/"could not load core" means a specific core failed to load on startup.
2. See core state: `curl 'http://<solr>:8983/solr/admin/cores?action=STATUS'` — a core with an error, or a
   collection whose shards show "down" in `admin/collections?action=CLUSTERSTATUS`.
3. Read WHY it failed from solr.log (bad schema/config change, missing/locked dataDir, or a stale
   write.lock in the index dir). Fix that cause — revert the bad config, free the dataDir, remove a stale
   write.lock — then reload: `curl 'admin/cores?action=RELOAD&core=<c>'`.
4. Recheck the core is active and queries return, then conclude (submit) immediately.
