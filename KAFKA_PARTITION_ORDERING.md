# Ordering incremental git events on Kafka

How GitMirror Hub keeps one repository's webhooks in turn while still using many partitions. This paper describes the incremental webhook bus (`GIT_WEBHOOK_BUS_PROVIDER=kafka`). Full mirrors stay on `GIT_MESSAGING_PROVIDER` (`none` or `rabbitmq`) and are not part of this topic.

Setup for the cluster, worker, and environment variables is in [`INSTRUCTIONS-KAFKA-WEBHOOK.md`](INSTRUCTIONS-KAFKA-WEBHOOK.md).

## The problem

A mirror must apply the events for one repository in the order they were accepted. A later push has to see the earlier push. A delete has to follow the pushes that created the branch. If two consumers apply those events at the same time, the second one can fetch a history the first one has not pushed yet, or delete a ref the first one is still updating.

The naive fix is one partition and one consumer. That preserves order and also caps the whole fleet at one repository at a time. A quiet repository waits behind a large one. Adding pods does nothing, because they would all compete for that single partition.

## The design

Kafka orders records inside one partition. It does not order records across partitions. The bus uses that rule instead of collapsing the topic to one partition.

The Cloudflare worker `webhook-worker-kafka` accepts the SCM webhook, checks the HMAC, and produces a normalized event to `git.sync.incremental` (or `KAFKA_TOPIC`). The record key is the repository URL after a stable normalization:

- trim, lowercase, strip a trailing slash and a trailing `.git`
- drop the `https://`, `http://`, `ssh://`, or `git://` scheme
- rewrite `git@host:org/repo` to `host/org/repo`

`github.com/acme/origin` and `https://github.com/acme/origin.git` are the same key. Kafka hashes the key to a partition. Every later event for that repository, whatever the ref, uses the same key and therefore the same partition. Kafka appends them in produce order. One consumer in the group owns that partition, so it reads them in that same order, one after another.

A different repository is a different key. It hashes to some partition, often a different one. Those two streams never wait on each other.

Hub republishes with the same key. `KafkaWebhookPublisher` calls `RepoMappingService.normalizeRepoKey`, which is the same normalization the worker uses. A record that Hub puts back on the topic stays on the repository's partition.

The consumer group is `GIT_WEBHOOK_KAFKA_GROUP_ID` (default `git-mirror-hub`). Every pod in one deployment joins that group. Kafka's cooperative sticky assignor gives each partition to one pod. `GIT_WEBHOOK_KAFKA_LISTENER_CONCURRENCY` is how many partitions this process reads at once. It does not split one repository across threads. Offsets are committed manually after the event is handled, not when it is received.

```text
github.com/acme/origin     ──key──►  partition 2  ──one consumer──►  push, then delete, then the next push
github.com/acme/mirror     ──key──►  partition 5  ──another consumer──►  its own sequence
github.com/other/service   ──key──►  partition 2  ──same consumer as origin, after origin's current record──►
```

Two repositories that happen to hash to partition 2 share that partition's single ordered log. They stay in the order they were produced relative to each other. They do not run in parallel.

## What "in turn" means

For one repository, in turn means produce order on its partition:

1. The worker produces the record only after it has accepted the webhook.
2. The partition leader appends it after the previous record for that key.
3. The single consumer assigned that partition does not start the next record for that partition until it has finished the current one and acknowledged it.

Refs on the same repository are ordered with respect to each other because they share the key. `main` and `feature/x` on `acme/origin` cannot be applied concurrently by two Kafka threads.

For two repositories, there is no global turn. That is the point. Partition count is the ceiling on how many repository streams the cluster can apply at once. Listener concurrency and pod count decide how much of that ceiling one deployment actually uses. A topic with 6 partitions and one pod set to 4 listeners leaves 2 partitions unread. Raising listeners to 6, or adding a second pod in the same group, covers them. Listeners beyond the partition count do not create more parallelism.

## Why this is better than one partition

**Order without a global lock.** The repository that received the webhook is the unit of order. The bus does not take a lock across the fleet to get that. The partition is the lock.

**Quiet repositories stay fast.** A long sync of one repository occupies only its partition. Repositories on other partitions continue. One partition would have queued the entire topic behind that sync.

**Pods scale by partition, not by sharing one queue.** Additional Hub processes join the consumer group and receive partitions. Work moves with the assignment. The cooperative sticky assignor keeps a partition on the same pod across a rebalance when it can, so a healthy pod does not drop its repositories on every membership change.

**The key survives URL spelling.** Clone URLs with and without `.git`, and SSH versus HTTPS, land on one partition as long as both producers use the normalization above. A second producer that sent the raw clone URL would silently split the repository across two partitions.

**Retries stay on the same stream.** A republished event uses the normalized repository key, so it is appended to the same partition rather than jumping to another consumer.

**The pair is a separate problem, and it stays separate.** The two sides of a mirror are two URLs, so they are allowed to be two partitions and even two pods. Applying both at once is safe only because the pair lease (`pair_leases`, held by `GIT_UTILITY_INSTANCE_ID`) lets one job own that pair, and because an inbound event is an echo only when the other repository already has that tip. The bus does not pretend the two sides are one ordered log.

## What this does not guarantee

**Produce order is not Git history order.** If GitHub delivers the webhook for commit 2 before the webhook for commit 1, Kafka stores them in that order and Hub applies them in that order. The partition cannot reconstruct an order the producer never had.

**At least once, not exactly once.** A crash after the git work and before the offset commit delivers the same record again. The peer-tip check and the pair lease have to tolerate that second delivery. The bus will not drop it for you.

**One repository is only as parallel as one partition.** More listeners, more pods, and more partitions do not make one repository's pushes concurrent. A repository that receives a burst of webhooks is a queue of one.

**Hash collisions share a lane.** Unrelated repositories can land on the same partition. The slow one delays the others on that partition only. It does not delay the rest of the topic.

**A pair can run on two partitions at once.** Origin and mirror are different keys. Both events can be in flight. The lease serializes the git work for that pair. The second side waits or loses the lease race. That wait is not a Kafka ordering failure. It is the pair contending with itself.

**Full mirrors are outside this topic.** `GIT_MESSAGING_PROVIDER=none` runs full mirrors on in-process workers. `rabbitmq` runs them on the Rabbit listeners. Those paths have their own concurrency. They do not inherit partition order.

## Issues to expect later

**Raising the partition count splits a repository.** Kafka chooses the partition as `hash(key) % partitionCount`. Increasing `GIT_WEBHOOK_KAFKA_INCREMENTAL_PARTITIONS` on a topic that already has data changes the mapping. New events for a repository can append to a different partition from the events still unconsumed on the old one. Those two partitions are then ordered separately, which is the failure this design exists to prevent. Add partitions only with a plan for in-flight records, or create a new topic and move producers and the consumer group together. Do not treat the count as a live tuning knob.

**A hot repository cannot be split later without the same break.** If one repository dominates the topic, its partition is the bottleneck. The usual Kafka remedy, a finer key, would let two refs of that repository run out of order. Speeding that repository up means a faster consumer, not a second partition, unless the product deliberately chooses a new ordering unit (for example one key per ref) and accepts concurrent refs.

**Normalization drift between producers.** The worker and `RepoMappingService.normalizeRepoKey` match today. A corporate publisher, a second worker, or a partial rewrite that keys on `full_name`, a numeric repo id, or the URL with `.git` left on will fork the stream. Both halves will look healthy. Order will simply be gone. Any new producer has to call the same key function.

**A missing key hashes to a single junk partition.** A produce with a null or empty key does not join the repository's stream. Those records pile onto one partition and can interleave with whichever repositories hashed there.

**Long work and `max.poll.interval.ms`.** The listener thread does the incremental sync before it acknowledges. `GIT_WEBHOOK_KAFKA_MAX_POLL_INTERVAL_MS` defaults to 10 minutes. A sync that runs longer looks like a dead consumer. The group revokes the partition and another pod starts the same record. Combined with at-least-once delivery, two pods can briefly believe they own the repository. The pair lease is what stops the second git push. If the lease TTL (`git-utility.cluster.lease-ttl-seconds`, default 90 seconds) expires during a long push, the second pod can take the pair while the first is still writing.

**Rebalance pauses the repositories on the moving partitions.** A deploy, a crash, or a pod that joins the group revokes partitions. Events already in those partitions wait until the new owner fetches them. Order is kept. Latency is not. Sticky assignment limits how many partitions move. It does not make a rebalance free.

**One consumer group shared by two deployments.** Staging and production on the same `GIT_WEBHOOK_KAFKA_GROUP_ID` split the partitions between them. Each side then applies a random subset of repositories and never sees the others. Each environment needs its own group, or its own topic.

**Retention can pass an unconsumed partition.** The topic is a log, not a queue with a dead-letter policy for "too old." A consumer stopped longer than the topic retention loses the tail. Those webhooks do not replay from Kafka. Catch-up is a full mirror or a later webhook, not the expired record.

**Webhook retries from the SCM sit behind newer events.** GitHub retries a failed delivery. If later pushes were already produced, the retried older delivery appends after them. Hub then applies an older event last. Idempotent handling (the tip already matches, the ref is already gone) is what makes that safe. A handler that always force-pushes the payload will roll the repository backward.

**Listener concurrency set above the partition count looks like scale and is not.** The extra threads stay idle. The metric that matters is assigned partitions versus lag, not thread count.

**The two sides of a busy pair thrash the lease.** Both partitions are healthy and ordered. Each webhook still tries to sync the pair. Under a burst on both origin and mirror, most of the work is lease wait and echo skips. That shows up as Kafka lag on both partitions even though the git work is serialized. Raising listeners does not clear it.

## Operating rules

- Key every produce with the normalized repository URL. Do not key on the pair, the branch, or the delivery id.
- Keep the worker and the Hub publisher on one normalization.
- Size partitions for how many repositories should run at once. Size listeners and pods to cover those partitions. Do not raise either number to speed up a single repository.
- Change partition count only when the topic can be recreated or the in-flight log can be drained.
- Give each deployment its own consumer group.
- Treat a redelivered record and a reversed SCM retry as normal. The apply step has to notice that the other side already has the tip.
