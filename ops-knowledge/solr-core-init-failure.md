match: solr, solrcloud, lucene
signature: init failure, error loading class, not available due to init failure, solrcore, could not load conf, caused by
push: rescue
status: candidate
# Solr core init failure from a bad config — fix procedure
1. A core that fails to initialize returns HTTP 500 "SolrCore '<core>' is not available due to init failure"
   for EVERY query. The message (also in `cores?action=STATUS` → initFailures and the logs) names the cause:
   e.g. "Error loading class '...'", a missing field/type, or an XML parse error.
2. The cause is a bad directive in that core's conf (`/var/solr/data/<core>/conf/solrconfig.xml` or
   managed-schema.xml) — usually a recent edit. Read the conf and find the class/field/handler it names.
3. Revert it: remove or correct the offending directive, then RELOAD the core
   (`/solr/admin/cores?action=RELOAD&core=<core>`). Do NOT delete or recreate the core — that discards its
   indexed documents; the data dir is intact, only the config load failed.
4. Confirm a query returns 200 with the core's documents, then conclude.
