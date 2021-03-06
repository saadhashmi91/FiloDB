package filodb.stress

import org.apache.spark.sql.{SaveMode, SparkSession}

import filodb.core.Perftools
import filodb.spark._

/**
 * Batch ingestion of a single dataset (NYC Taxi) intended to represent typical ingestion schemas
 * with multi-column partition and row keys.  Not a stress test per se, but intended for performance
 * profiling of a realistic schema.  The GDELT dataset is not as realistic due to a single ID column
 * and the more ordered nature of its sources, whereas the NYC Taxi data is more randomly ordered.
 * The keys have been picked for accuracy, and longer row keys put more stress on ingest performance.
 *
 * To prepare, download the first month's worth of data from http://www.andresmh.com/nyctaxitrips/
 * Also, run this to initialize the filo-stress keyspace:
 *   `filo-cli --database filostress --command init`
 *
 * Recommended to run this with the first million rows only as a first run to make sure everything works.
 * Test at different memory settings - but recommend minimum 4G.
 *
 * Also, if you run this locally, run it using local-cluster to test clustering effects.
 */
object BatchIngestion extends App {
  val taxiCsvFile = args(0)

  def puts(s: String): Unit = {
    // scalastyle:off
    println(s)
    // scalastyle:on
  }

  val tableName = sys.props.getOrElse("stress.tablename", "nyc_taxi")
  val keyspaceName = sys.props.getOrElse("stress.keyspace", "filostress")

  // Setup SparkContext, etc.
  val sess = SparkSession.builder.appName("FiloDB BatchIngestion")
                                 .config("spark.filodb.cassandra.keyspace", keyspaceName)
                                 .config("spark.sql.shuffle.partitions", "4")
                                 .config("spark.scheduler.mode", "FAIR")
                                 .getOrCreate
  val sc = sess.sparkContext

  val csvDF = sess.read.format("com.databricks.spark.csv").
                   option("header", "true").option("inferSchema", "true").
                   load(taxiCsvFile)

  val csvLines = csvDF.count()

  val ingestMillis = Perftools.timeMillis {
    puts("Starting batch ingestion...")
    csvDF.write.format("filodb.spark").
      option("dataset", tableName).
      option("row_keys", "pickup_datetime,hack_license,pickup_longitude").
      option("partition_columns", "medallion").
      mode(SaveMode.Overwrite).save()
    puts("Batch ingestion done.")
  }

  puts(s"\n ==> Batch ingestion took $ingestMillis ms\n")

  val df = sess.filoDataset(tableName)
  df.createOrReplaceTempView(tableName)

  val count = df.count()
  if (count == csvLines) { puts(s"Count matched $count for dataframe $df") }
  else                   { puts(s"Expected $csvLines rows, but actually got $count for dataframe $df") }

  // clean up!
  FiloDriver.shutdown()
  FiloExecutor.shutdown()
  sc.stop()
}