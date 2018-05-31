package filodb.coordinator.queryengine2

import java.util.{SplittableRandom, UUID}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import akka.actor.ActorRef
import com.typesafe.scalalogging.StrictLogging
import monix.eval.Task

import filodb.coordinator.ShardMapper
import filodb.coordinator.client.QueryCommands.QueryOptions
import filodb.core.binaryrecord.BinaryRecord
import filodb.core.binaryrecord2.RecordBuilder
import filodb.core.metadata.Dataset
import filodb.core.query.{ColumnFilter, Filter}
import filodb.query._
import filodb.query.exec._

/**
  * FiloDB Query Engine is the facade for execution of FiloDB queries.
  * It is meant for use inside FiloDB nodes to execute materialized
  * ExecPlans as well as from the client to execute LogicalPlans.
  */
class QueryEngine(dataset: Dataset,
                  shardMapperFunc: => ShardMapper)
                   extends StrictLogging {

  /**
    * This is the facade to trigger orchestration of the ExecPlan.
    * It sends the ExecPlan to the destination where it will be executed.
    */
  def dispatchExecPlan(execPlan: ExecPlan)
                      (implicit sched: ExecutionContext,
                       timeout: FiniteDuration): Task[QueryResponse] = {
    execPlan.dispatcher.dispatch(execPlan)
  }

  /**
    * Converts a LogicalPlan to the ExecPlan
    */
  def materialize(rootLogicalPlan: LogicalPlan,
                  options: QueryOptions): ExecPlan = {
    val queryId = UUID.randomUUID().toString
    val materialized = walkLogicalPlanTree(rootLogicalPlan, queryId, System.currentTimeMillis(), options) match {
      case Seq(justOne) =>
        justOne
      case many =>
        val targetActor = pickDispatcher(many)
        DistConcatExec(queryId, targetActor, many)
    }
    logger.debug(s"Materialized logical plan: $rootLogicalPlan to \n${materialized.printTree()}")
    materialized
  }

  private def shardsFromFilters(filters: Seq[ColumnFilter],
                                options: QueryOptions): Seq[Int] = {
    val shardColumns = dataset.options.shardKeyColumns
    require(shardColumns.nonEmpty || options.shardOverrides.nonEmpty,
      s"Dataset ${dataset.ref} does not have shard columns defined, and shard overrides were not mentioned")

    if (shardColumns.nonEmpty) {
      val shardColValues = shardColumns.map { shardCol =>
        // So to compute the shard hash we need shardCol == value filter (exact equals) for each shardColumn
        filters.find(f => f.column == shardCol) match {
          case Some(ColumnFilter(_, Filter.Equals(filtVal: String))) => filtVal
          case Some(ColumnFilter(_, filter)) =>
            throw new BadQueryException(s"Found filter for shard column $shardCol but " +
              s"$filter cannot be used for shard key routing")
          case _ =>
            throw new BadQueryException(s"Could not find filter for shard key column " +
              s"$shardCol, shard key hashing disabled")
        }
      }
      logger.debug(s"For shardColumns $shardColumns, extracted filter values $shardColValues successfully")
      val shardHash = RecordBuilder.shardKeyHash(shardColumns, shardColValues)
      shardMapperFunc.queryShards(shardHash, options.shardKeySpread)
    } else {
      options.shardOverrides.get
    }
  }

  private def dispatcherForShard(shard: Int): PlanDispatcher = {
    val targetActor = shardMapperFunc.coordForShard(shard)
    if (targetActor == ActorRef.noSender) throw new RuntimeException("Not all shards available") // TODO fix this
    ActorPlanDispatcher(targetActor)
  }

  /**
    * Walk logical plan tree depth-first and generate execution plans starting from the bottom
    *
    * @return ExecPlans that answer the logical plan provided
    */
  private def walkLogicalPlanTree(logicalPlan: LogicalPlan,
                                  queryId: String,
                                  submitTime: Long,
                                  options: QueryOptions): Seq[ExecPlan] = {
    logicalPlan match {
      case lp: RawSeries =>                   materializeRawSeries(queryId, submitTime, options, lp)
      case lp: PeriodicSeries =>              materializePeriodicSeries(queryId, submitTime, options, lp)
      case lp: PeriodicSeriesWithWindowing => materializePeriodicSeriesWithWindowing(queryId, submitTime, options, lp)
      case lp: ApplyInstantFunction =>        materializeApplyInstantFunction(queryId, submitTime, options, lp)
      case lp: Aggregate =>                   materializeAggregate(queryId, submitTime, options, lp)
      case lp: BinaryJoin =>                  materializeBinaryJoin(queryId, submitTime, options, lp)
      case lp: ScalarVectorBinaryOperation => materializeScalarVectorBinOp(queryId, submitTime, options, lp)
    }
  }

  private def materializeScalarVectorBinOp(queryId: String,
                                           submitTime: Long,
                                           options: QueryOptions,
                                           lp: ScalarVectorBinaryOperation): Seq[ExecPlan] = {
    val vectors = walkLogicalPlanTree(lp.vector, queryId, submitTime, options)
    vectors.foreach(_.addRangeVectorTransformer(ScalarOperationMapper(lp.operator, lp.scalar, lp.scalarIsLhs)))
    vectors
  }

  private def materializeBinaryJoin(queryId: String,
                                    submitTime: Long,
                                    options: QueryOptions,
                                    lp: BinaryJoin): Seq[ExecPlan] = {
    val lhs = walkLogicalPlanTree(lp.lhs, queryId, submitTime, options)
    val rhs = walkLogicalPlanTree(lp.rhs, queryId, submitTime, options)
    val targetActor = pickDispatcher(lhs ++ rhs)
    Seq(BinaryJoinExec(queryId, targetActor, lhs, rhs, lp.operator, lp.cardinality, lp.on, lp.ignoring))
  }

  private def materializeAggregate(queryId: String,
                                   submitTime: Long,
                                   options: QueryOptions,
                                   lp: Aggregate): Seq[ExecPlan] = {
    val toReduce = walkLogicalPlanTree(lp.vectors, queryId, submitTime, options) // Now we have one exec plan per shard
    toReduce.foreach(_.addRangeVectorTransformer(AggregateMapReduce(lp.operator, lp.params, lp.without, lp.by)))
    // One could do another level of aggregation per node too. Ignoring for now
    val reduceDispatcher = pickDispatcher(toReduce)
    val reducer = ReduceAggregateExec(queryId, reduceDispatcher, toReduce, lp.operator, lp.params)
    reducer.addRangeVectorTransformer(AggregatePresenter(lp.operator, lp.params))
    Seq(reducer)
  }

  private def materializeApplyInstantFunction(queryId: String,
                                              submitTime: Long,
                                              options: QueryOptions,
                                              lp: ApplyInstantFunction): Seq[ExecPlan] = {
    val vectors = walkLogicalPlanTree(lp.vectors, queryId, submitTime, options)
    vectors.foreach(_.addRangeVectorTransformer(InstantVectorFunctionMapper(lp.function, lp.functionArgs)))
    vectors
  }

  private def materializePeriodicSeriesWithWindowing(queryId: String,
                                                     submitTime: Long,
                                                    options: QueryOptions,
                                                    lp: PeriodicSeriesWithWindowing): Seq[ExecPlan] = {
    val rawSeries = walkLogicalPlanTree(lp.rawSeries, queryId, submitTime, options)
    rawSeries.foreach(_.addRangeVectorTransformer(PeriodicSamplesMapper(lp.start, lp.step,
      lp.end, Some(lp.window), Some(lp.function), lp.functionArgs)))
    rawSeries
  }

  private def materializePeriodicSeries(queryId: String,
                                        submitTime: Long,
                                       options: QueryOptions,
                                       lp: PeriodicSeries): Seq[ExecPlan] = {
    val rawSeries = walkLogicalPlanTree(lp.rawSeries, queryId, submitTime, options)
    rawSeries.foreach(_.addRangeVectorTransformer(PeriodicSamplesMapper(lp.start, lp.step, lp.end,
      None, None, Nil)))
    rawSeries
  }

  private def materializeRawSeries(queryId: String,
                                   submitTime: Long,
                                   options: QueryOptions,
                                   lp: RawSeries): Seq[ExecPlan] = {
    shardsFromFilters(lp.filters, options).map { shard =>
      val dispatcher = dispatcherForShard(shard)
      SelectRawPartitionsExec(queryId, submitTime, options.itemLimit, dispatcher, dataset.ref, shard,
        lp.filters, toRowKeyRange(lp.rangeSelector), lp.columns)
    }
  }

  private def toRowKeyRange(rangeSelector: RangeSelector): RowKeyRange = {
    rangeSelector match {
      case IntervalSelector(from, to) => RowKeyInterval(BinaryRecord(dataset, from),
                                                        BinaryRecord(dataset, to))
      case AllChunksSelector => AllChunks
      case EncodedChunksSelector => EncodedChunks
      case WriteBufferSelector => WriteBuffers
      case _ => ???
    }
  }

  /**
    * Picks one dispatcher randomly from child exec plans passed in as parameter
    */
  private def pickDispatcher(children: Seq[ExecPlan]): PlanDispatcher = {
    val childTargets = children.map(_.dispatcher).toSet
    childTargets.iterator.drop(QueryEngine.random.nextInt(childTargets.size)).next
  }
}

object QueryEngine {
  val random = new SplittableRandom()
}
