match: localstack, aws, awscli, boto3
signature: could not connect to the endpoint url, endpointconnectionerror
push: rescue
status: candidate
platform: docker
# LocalStack endpoint not reachable — fix procedure
1. Read the error: "Could not connect to the endpoint URL" / EndpointConnectionError means the AWS client cannot reach LocalStack (default http://localhost:4566).
2. Confirm LocalStack is up and healthy: `curl -s http://localhost:4566/_localstack/health` should list services as "running"/"available"; `docker ps` shows the container.
3. Fix a down container: `localstack start -d` (or docker compose up -d); wait for the health endpoint to report ready.
4. Fix client config: pass `--endpoint-url http://localhost:4566` and a valid `--region` (e.g. us-east-1); from another container use the service name/LOCALSTACK_HOSTNAME, not localhost.
5. Recheck — when `aws --endpoint-url ... sts get-caller-identity` succeeds, conclude (submit) immediately.
