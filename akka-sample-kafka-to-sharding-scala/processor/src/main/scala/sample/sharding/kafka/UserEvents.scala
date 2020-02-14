package sample.sharding.kafka

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.cluster.sharding.external.ExternalShardAllocationStrategy
import akka.cluster.sharding.typed.{ClusterShardingSettings, ShardingMessageExtractor}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.kafka.{ConsumerSettings, KafkaClusterSharding, KafkaShardingNoEnvelopeExtractor}
import org.apache.kafka.common.serialization.StringDeserializer

import scala.concurrent.Future
import scala.concurrent.duration._

object UserEvents {

  val TypeKey: EntityTypeKey[UserEvents.Message] =
    EntityTypeKey[UserEvents.Message]("user-processing")

  sealed trait Message extends CborSerializable {
    def userId: String
  }
  sealed trait UserEvent extends Message
  case class UserAction(userId: String, description: String, replyTo: ActorRef[Done]) extends UserEvent
  case class UserPurchase(userId: String, product: String, quantity: Long, priceInPence: Long, replyTo: ActorRef[Done])
      extends UserEvent

  sealed trait UserQuery extends Message
  case class GetRunningTotal(userId: String, replyTo: ActorRef[RunningTotal]) extends UserQuery

  case class RunningTotal(totalPurchases: Long, amountSpent: Long) extends CborSerializable

  def apply(): Behavior[Message] = running(RunningTotal(0, 0))

  private def running(runningTotal: RunningTotal): Behavior[Message] = {
    Behaviors.setup { ctx =>
      Behaviors.receiveMessage[Message] {
        case UserAction(_, desc, ack) =>
          ctx.log.info("user event {}", desc)
          ack.tell(Done)
          Behaviors.same
        case UserPurchase(id, product, quantity, price, ack) =>
          ctx.log.info("user {} purchase {}, quantity {}, price {}", id, product, quantity, price)
          ack.tell(Done)
          running(
            runningTotal.copy(
              totalPurchases = runningTotal.totalPurchases + 1,
              amountSpent = runningTotal.amountSpent + (quantity * price)))
        case GetRunningTotal(id, replyTo) =>
          ctx.log.info("user {} running total queried", id)
          replyTo ! runningTotal
          Behaviors.same
      }
    }
  }

  /**
   * Asynchronously get the Akka Cluster Sharding [[ShardingMessageExtractor]]. Given a topic we can automatically
   * retrieve the number of partitions and use the same hashing algorithm used by the Apache Kafka
   * [[org.apache.kafka.clients.producer.internals.DefaultPartitioner]] (murmur2) with Akka Cluster Sharding.
   */
  def messageExtractor(system: ActorSystem[_]): Future[KafkaShardingNoEnvelopeExtractor[Message]] = {
    val processorConfig = ProcessorConfig(system.settings.config.getConfig("kafka-to-sharding-processor"))
    KafkaClusterSharding.messageExtractorNoEnvelope(
      system = system.toClassic,
      timeout = 10.seconds,
      groupId = processorConfig.groupId,
      topic = processorConfig.topics.head,
      entityIdExtractor = (msg: Message) => msg.userId,
      settings = ConsumerSettings(system.toClassic, new StringDeserializer, new StringDeserializer)
        .withBootstrapServers(processorConfig.bootstrapServers)
    )
  }

  def init(system: ActorSystem[_], messageExtractor: ShardingMessageExtractor[Message, Message]): ActorRef[Message] =
    ClusterSharding(system).init(
      Entity(TypeKey)(createBehavior = _ => UserEvents())
        .withAllocationStrategy(new ExternalShardAllocationStrategy(system, TypeKey.name))
        .withMessageExtractor(messageExtractor)
        .withSettings(ClusterShardingSettings(system)))

  def querySide(system: ActorSystem[_], messageExtractor: ShardingMessageExtractor[Message, Message]): ActorRef[UserQuery] =
    init(system, messageExtractor).narrow[UserQuery]
}