# Smoke test: several instances

Runs two Paprika instances against a MongoDB replica set with three nodes, on one storage directory as on a shared
volume, with nginx (round robin, no sticky sessions) in front of them, and checks what
[Running several instances](../docs/operations/going-to-production.md#running-several-instances) promises:

* nginx reaches both instances
* both instances start at once on an empty database, and the setup link either of them logs works on the other
* an admin session from A is valid on B, and a logout on A revokes it on B
* the second factor is set up on A and confirmed on B, signs in across instances, and each code is accepted once
* records written through A are read through B
* realtime: a stream on B is subscribed through A, receives a record written on A, and closes on a logout on A
* a file uploaded to A is served by B, and so is the image variant A renders; no temporary files remain
* a failover of the MongoDB primary: sign-in, writes and realtime work again on both instances
* a graceful shutdown of A: readiness 503, liveness 200, the realtime stream closes, the process exits without waiting
  for the shutdown timeout, and nginx keeps serving through B

```bash
./smoke-test/run.sh
```

Paprika is compiled from this repository and reads `config.yaml` of this directory, which holds generated test secrets
only. Requires Java 25, Docker with the image `nginx:1.27-alpine` and a `mongod` binary (`MONGOD`, defaults to the
one the embedded MongoDB of the test suite downloaded to `~/.embedmongo`). The ports 9200-9202 and 27217-27219 must be
free. Logs of all processes end up in `smoke-test/target/run`.
