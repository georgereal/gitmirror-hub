import { applyFormat, selectFormat, validateEvent } from "./apply.js";
import { repoKey } from "./repoKey.js";

/**
 * Consume the source topic and produce canonical IncrementalGitEvent records.
 * Offsets advance after a produce or an intentional skip so one bad record does not stall the partition.
 */
export async function runBridge(settings, formats) {
  let Kafka;
  try {
    ({ Kafka } = await import("kafkajs"));
  } catch {
    throw new Error("kafkajs is not installed. Run npm install in kafka-event-format/ before bridge.");
  }
  const brokers = String(settings.bootstrapServers || "")
    .split(",")
    .map((part) => part.trim())
    .filter(Boolean);
  if (brokers.length === 0) {
    throw new Error("GIT_WEBHOOK_KAFKA_BOOTSTRAP_SERVERS is required for bridge");
  }
  if (!settings.consume) {
    throw new Error("consume topic is required (--consume or KAFKA_EVENT_FORMAT_CONSUME)");
  }
  const saslSsl = String(settings.securityProtocol || "PLAINTEXT").toUpperCase() === "SASL_SSL";
  const kafka = new Kafka({
    clientId: "kafka-event-format",
    brokers,
    ssl: saslSsl,
    sasl: saslSsl
      ? {
          mechanism: String(settings.saslMechanism || "plain").toLowerCase(),
          username: settings.saslUsername || "",
          password: settings.saslPassword || "",
        }
      : undefined,
  });
  const consumer = kafka.consumer({ groupId: settings.groupId || "kafka-event-format" });
  const producer = kafka.producer();
  const stats = { produced: 0, skipped: 0 };
  await consumer.connect();
  await producer.connect();
  await consumer.subscribe({ topic: settings.consume, fromBeginning: false });
  console.error(
    `bridge format=${settings.format} ${settings.consume} -> ${settings.produce} group=${settings.groupId || "kafka-event-format"}`
  );
  await consumer.run({
    eachMessage: async ({ partition, message }) => {
      const raw = message.value == null ? "" : message.value.toString("utf8");
      let record;
      try {
        record = JSON.parse(raw);
      } catch (err) {
        stats.skipped += 1;
        console.error(`skip partition=${partition} offset=${message.offset}: unreadable JSON (${err.message})`);
        return;
      }
      const hits = selectFormat(record, formats);
      if (hits.length === 0) {
        stats.skipped += 1;
        console.error(`skip partition=${partition} offset=${message.offset}: no format matched`);
        return;
      }
      if (hits.length > 1) {
        console.error(
          `partition=${partition} offset=${message.offset}: matched ${hits.map((hit) => hit.id).join(", ")}; using ${hits[0].id}`
        );
      }
      const event = applyFormat(record, hits[0]);
      const errors = validateEvent(event);
      if (errors.length > 0) {
        stats.skipped += 1;
        console.error(
          `skip partition=${partition} offset=${message.offset} via ${hits[0].id}: ${errors.join("; ")}`
        );
        return;
      }
      await producer.send({
        topic: settings.produce,
        messages: [{ key: repoKey(event.repoUrl), value: JSON.stringify(event) }],
      });
      stats.produced += 1;
      if (stats.produced % 100 === 0) {
        console.error(`produced=${stats.produced} skipped=${stats.skipped}`);
      }
    },
  });
}
