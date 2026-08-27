match: s3, aws, localstack, object storage
signature: nosuchkey, the specified key does not exist, 404 not found, delete marker, an error occurred (nosuchkey)
push: rescue
status: candidate
# S3 object 404 on a versioned bucket — a delete marker hides it — fix procedure
1. A GET returns NoSuchKey/404 for an object the app expects, but the bucket has VERSIONING enabled. The
   object was not truly deleted — a DELETE placed a delete marker as the current version, hiding the data;
   every prior version is still stored.
2. Confirm: `aws s3api list-object-versions --bucket <b> --prefix <key>` shows the object's prior
   Version(s) AND a DeleteMarker whose IsLatest=true — note that marker's VersionId.
3. Restore by REMOVING the delete marker: `aws s3api delete-object --bucket <b> --key <key> --version-id
   <delete-marker-version-id>`. The previous version becomes current again — no data is recreated. (Do not
   re-upload guessed content; recover the real prior version.)
4. Confirm a GET returns the object with its real content, then conclude.
