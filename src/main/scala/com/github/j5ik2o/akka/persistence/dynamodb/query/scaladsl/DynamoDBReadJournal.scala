package com.github.j5ik2o.akka.persistence.dynamodb.query.scaladsl

import akka.NotUsed
import akka.actor.ExtendedActorSystem
import akka.pattern._
import akka.persistence.query._
import akka.persistence.query.scaladsl._
import akka.persistence.{ Persistence, PersistentRepr }
import akka.serialization.{ Serialization, SerializationExtension }
import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.{ ActorMaterializer, Attributes, Materializer }
import akka.util.Timeout
import com.github.j5ik2o.akka.persistence.dynamodb.config.{ JournalSequenceRetrievalConfig, PersistencePluginConfig }
import com.github.j5ik2o.akka.persistence.dynamodb.journal.{ ByteArrayJournalSerializer, JournalRow }
import com.github.j5ik2o.akka.persistence.dynamodb.query.JournalSequenceActor
import com.github.j5ik2o.akka.persistence.dynamodb.query.JournalSequenceActor.{ GetMaxOrderingId, MaxOrderingId }
import com.github.j5ik2o.akka.persistence.dynamodb.query.dao.ReadJournalDaoImpl
import com.github.j5ik2o.akka.persistence.dynamodb.serialization.FlowPersistentReprSerializer
import com.github.j5ik2o.akka.persistence.dynamodb.{ DynamoDbClientBuilderUtils, HttpClientUtils }
import com.github.j5ik2o.reactive.aws.dynamodb.akka.DynamoDBStreamClient
import com.github.j5ik2o.reactive.aws.dynamodb.{ DynamoDBAsyncClientV2, DynamoDBSyncClientV2 }
import com.typesafe.config.Config
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.dynamodb.{ DynamoDbAsyncClient, DynamoDbClient }

import scala.collection.immutable.Iterable
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

object DynamoDBReadJournal {
  final val Identifier = "dynamo-db-read-journal"

  private sealed trait FlowControl

  /** Keep querying - used when we are sure that there is more events to fetch */
  private case object Continue extends FlowControl

  /**
    * Keep querying with delay - used when we have consumed all events,
    * but want to poll for future events
    */
  private case object ContinueDelayed extends FlowControl

  /** Stop querying - used when we reach the desired offset  */
  private case object Stop extends FlowControl
}

class DynamoDBReadJournal(config: Config, configPath: String)(implicit system: ExtendedActorSystem)
    extends ReadJournal
    with CurrentPersistenceIdsQuery
    with PersistenceIdsQuery
    with CurrentEventsByPersistenceIdQuery
    with EventsByPersistenceIdQuery
    with CurrentEventsByTagQuery
    with EventsByTagQuery {
  private val logger                = LoggerFactory.getLogger(getClass)
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val mat: Materializer    = ActorMaterializer()(system)

  protected val persistencePluginConfig: PersistencePluginConfig =
    PersistencePluginConfig.fromConfig(config)

  val journalSequenceRetrievalConfiguration = JournalSequenceRetrievalConfig(config)

  private val bufferSize = persistencePluginConfig.bufferSize

  private val asyncHttpClientBuilder = HttpClientUtils.asyncBuilder(persistencePluginConfig)
  private val syncHttpClientBuilder  = HttpClientUtils.syncBuilder(persistencePluginConfig)
  private val dynamoDbAsyncClientBuilder =
    DynamoDbClientBuilderUtils.asyncBuilder(persistencePluginConfig, asyncHttpClientBuilder.build())
  private val dynamoDbSyncClientBuilder =
    DynamoDbClientBuilderUtils.syncBuilder(persistencePluginConfig, syncHttpClientBuilder.build())

  protected val javaAsyncClient: DynamoDbAsyncClient = dynamoDbAsyncClientBuilder.build()
  protected val javaSyncClient: DynamoDbClient       = dynamoDbSyncClientBuilder.build()

  protected val asyncClient: DynamoDBAsyncClientV2 = DynamoDBAsyncClientV2(javaAsyncClient)
  protected val syncClient: DynamoDBSyncClientV2   = DynamoDBSyncClientV2(javaSyncClient)
  protected val streamClient: DynamoDBStreamClient = DynamoDBStreamClient(asyncClient)
  private val serialization: Serialization         = SerializationExtension(system)

  private val readJournalDao = new ReadJournalDaoImpl(asyncClient, syncClient, serialization, persistencePluginConfig)

  private val writePluginId = config.getString("write-plugin")
  private val eventAdapters = Persistence(system).adaptersFor(writePluginId)

  private val serializer: FlowPersistentReprSerializer[JournalRow] =
    new ByteArrayJournalSerializer(serialization, persistencePluginConfig.tagSeparator)

  private val delaySource =
    Source.tick(persistencePluginConfig.refreshInterval, 0.seconds, 0).take(1)

  private val logLevels = Attributes.logLevels(onElement = Attributes.LogLevels.Info,
                                               onFailure = Attributes.LogLevels.Error,
                                               onFinish = Attributes.LogLevels.Info)

  val refreshInterval = 100 milliseconds

  private lazy val journalSequenceActor = system.systemActorOf(
    JournalSequenceActor.props(readJournalDao, journalSequenceRetrievalConfiguration),
    s"$configPath.akka-persistence-dynamodb-journal-sequence-actor"
  )

  override def currentPersistenceIds(): Source[String, NotUsed] = readJournalDao.allPersistenceIdsSource(Long.MaxValue)

  override def persistenceIds(): Source[String, NotUsed] =
    Source
      .repeat(0).flatMapConcat(
        _ => Source.tick(refreshInterval, 0.seconds, 0).take(1).flatMapConcat(_ => currentPersistenceIds())
      )
      .statefulMapConcat[String] { () =>
        var knownIds = Set.empty[String]
        def next(id: String): Iterable[String] = {
          val xs = Set(id).diff(knownIds)
          knownIds += id
          xs
        }
        id =>
          next(id)
      }.log("persistenceIds::persistenceId").withAttributes(logLevels)

  private def adaptEvents(repr: PersistentRepr): Vector[PersistentRepr] = {
    val adapter = eventAdapters.get(repr.payload.getClass)
    adapter.fromJournal(repr.payload, repr.manifest).events.map(repr.withPayload).toVector
  }

  private def currentJournalEventsByPersistenceId(persistenceId: String,
                                                  fromSequenceNr: Long,
                                                  toSequenceNr: Long): Source[PersistentRepr, NotUsed] =
    readJournalDao
      .getMessages(persistenceId, fromSequenceNr, toSequenceNr, Long.MaxValue).log("messages")
      .via(serializer.deserializeFlowWithoutTags).withAttributes(logLevels)

  override def currentEventsByPersistenceId(persistenceId: String,
                                            fromSequenceNr: Long,
                                            toSequenceNr: Long): Source[EventEnvelope, NotUsed] =
    currentJournalEventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr)
      .log("PersistentRepr")
      .mapConcat(adaptEvents).log("adaptEvents")
      .map(repr => EventEnvelope(Sequence(repr.sequenceNr), repr.persistenceId, repr.sequenceNr, repr.payload))
      .withAttributes(logLevels)

  override def eventsByPersistenceId(persistenceId: String,
                                     fromSequenceNr: Long,
                                     toSequenceNr: Long): Source[EventEnvelope, NotUsed] =
    Source
      .unfoldAsync[Long, Seq[EventEnvelope]](Math.max(1, fromSequenceNr)) { from: Long =>
        def nextFromSeqNr(xs: Seq[EventEnvelope]): Long = {
          if (xs.isEmpty) from else xs.map(_.sequenceNr).max + 1
        }
        from match {
          case x if x > toSequenceNr => Future.successful(None)
          case _ =>
            delaySource
              .flatMapConcat { _ =>
                currentJournalEventsByPersistenceId(persistenceId, from, toSequenceNr)
                  .take(bufferSize)
              }
              .mapConcat(adaptEvents)
              .map(repr => EventEnvelope(Sequence(repr.sequenceNr), repr.persistenceId, repr.sequenceNr, repr.payload))
              .runWith(Sink.seq).map { xs =>
                val newFromSeqNr = nextFromSeqNr(xs)
                Some((newFromSeqNr, xs))
              }
        }
      }.mapConcat(_.toVector)

  private def currentJournalEventsByTag(tag: String,
                                        offset: Long,
                                        max: Long,
                                        latestOrdering: MaxOrderingId): Source[EventEnvelope, NotUsed] = {
    if (latestOrdering.maxOrdering < offset) Source.empty
    else {
      readJournalDao
        .eventsByTag(tag, offset, latestOrdering.maxOrdering, max)
        .via(serializer.deserializeFlow)
        .mapConcat {
          case (repr, _, ordering) =>
            adaptEvents(repr).map(r => EventEnvelope(Sequence(ordering), r.persistenceId, r.sequenceNr, r.payload))
        }
    }
  }

  private def eventsByTag(tag: String,
                          offset: Long,
                          terminateAfterOffset: Option[Long]): Source[EventEnvelope, NotUsed] = {

    import DynamoDBReadJournal._
    implicit val askTimeout: Timeout = Timeout(journalSequenceRetrievalConfiguration.askTimeout)
    val batchSize                    = persistencePluginConfig.bufferSize

    Source
      .unfoldAsync[(Long, FlowControl), Seq[EventEnvelope]]((offset, Continue)) {
        case (from, control) =>
          def retrieveNextBatch() = {
            for {
              queryUntil <- journalSequenceActor.ask(GetMaxOrderingId).mapTo[MaxOrderingId]
              xs         <- currentJournalEventsByTag(tag, from, batchSize, queryUntil).runWith(Sink.seq)
            } yield {

              val hasMoreEvents = xs.size == batchSize
              val control =
                terminateAfterOffset match {
                  // we may stop if target is behind queryUntil and we don't have more events to fetch
                  case Some(target) if !hasMoreEvents && target <= queryUntil.maxOrdering => Stop
                  // We may also stop if we have found an event with an offset >= target
                  case Some(target) if xs.exists {
                        _.offset match {
                          case Sequence(value)  => value >= target
                          case TimeBasedUUID(_) => true
                          case NoOffset         => true
                        }
                      } =>
                    Stop

                  // otherwise, disregarding if Some or None, we must decide how to continue
                  case _ =>
                    if (hasMoreEvents) Continue else ContinueDelayed
                }

              val nextStartingOffset = if (xs.isEmpty) {
                /* If no events matched the tag between `from` and `maxOrdering` then there is no need to execute the exact
                 * same query again. We can continue querying from `maxOrdering`, which will save some load on the db.
                 * (Note: we may never return a value smaller than `from`, otherwise we might return duplicate events) */
                math.max(from, queryUntil.maxOrdering)
              } else {
                // Continue querying from the largest offset
                xs.map { ee =>
                  val Sequence(v) = ee.offset
                  v
                }.max
              }
              Some((nextStartingOffset, control), xs)
            }
          }

          control match {
            case Stop     => Future.successful(None)
            case Continue => retrieveNextBatch()
            case ContinueDelayed =>
              akka.pattern.after(refreshInterval, system.scheduler)(retrieveNextBatch())
          }

      }.mapConcat(_.toVector)
  }

  def currentEventsByTag(tag: String, offset: Long): Source[EventEnvelope, NotUsed] =
    Source
      .fromFuture(readJournalDao.maxJournalSequence())
      .flatMapConcat { maxOrderingInDb =>
        eventsByTag(tag, offset, terminateAfterOffset = Some(maxOrderingInDb))
      }

  override def currentEventsByTag(tag: String, offset: Offset): Source[EventEnvelope, NotUsed] = {
    offset match {
      case NoOffset =>
        currentEventsByTag(tag, 0)
      case Sequence(value) =>
        currentEventsByTag(tag, value)
    }
  }

  override def eventsByTag(tag: String, offset: Offset): Source[EventEnvelope, NotUsed] = {
    offset match {
      case NoOffset =>
        eventsByTag(tag, 0, terminateAfterOffset = None)
      case Sequence(value) =>
        eventsByTag(tag, value, terminateAfterOffset = None)
    }
  }
}