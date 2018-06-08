package sparktest

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.serializer.KryoSerializer
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD

import scala.io.StdIn
import sparktest.Constants._
import geotrellis.spark.{MultibandTileLayerRDD, SpatialKey, TileLayerMetadata}
import geotrellis.spark.tiling.FloatingLayoutScheme
import geotrellis.spark.io.kryo.KryoRegistrator
import geotrellis.spark._
import geotrellis.spark.io.hadoop._
import geotrellis.raster.MultibandTile
import geotrellis.raster.resample.Bilinear
import geotrellis.raster._
import geotrellis.raster.io.geotiff.GeoTiff
import geotrellis.vector.ProjectedExtent
import geotrellis.util._
import org.apache.spark.sql.SparkSession

import SimpleTilingFramework._

object GeoTest {
  def createSparkContext(): Unit = {
    val conf = new org.apache.spark.SparkConf()
    conf.setMaster("local[*]")
    implicit val sc = geotrellis.spark.util.SparkUtils.createSparkContext("Test console", conf)
  }

  def time[R](f: => R): (R, Long) = {
    val t1 = System.nanoTime
    val ret = f
    val t2 = System.nanoTime
    (ret, t2 - t1)
  }

  def roundAt(p: Int)(n: Double): Double = { val s = math pow (10, p); (math round n * s) / s }

  def nanosecToSec(nanosecs: Long): Double = {
    nanosecs.asInstanceOf[Double]
    return roundAt(4)(nanosecs.asInstanceOf[Double]/1000000000.0)
  }

  def readGeoTiffToMultibandTileLayerRDD(raster_filepath: String)
                                        (implicit sc: SparkContext) :
  MultibandTileLayerRDD[SpatialKey] = {

    // Read GeoTiff file into Raster RDD
    println(">>> Reading GeoTiff")
    val input_rdd: RDD[(ProjectedExtent, MultibandTile)] =
      sc.hadoopMultibandGeoTiffRDD(raster_filepath)

    println(">>> Tiling GeoTiff")
    // Tiling layout to TILE_SIZE x TILE_SIZE grids
    val (_, raster_metadata) =
      TileLayerMetadata.fromRdd(input_rdd, FloatingLayoutScheme(TILE_SIZE))
    val tiled_rdd: RDD[(SpatialKey, MultibandTile)] =
      input_rdd
        .tileToLayout(raster_metadata.cellType, raster_metadata.layout, Bilinear)
    return MultibandTileLayerRDD(tiled_rdd, raster_metadata)
  }

  def main(args: Array[String]): Unit = {
    //Initialize
    println("\n\n>>> INITIALIZING <<<\n\n")

    implicit val sparkSession = SparkSession.builder.
      master("local")
      .appName("Thesis")
      .config("spark.serializer", classOf[KryoSerializer].getName)
      .config("spark.kryo.registrator", classOf[KryoRegistrator].getName)
      .config("spark.kryoserializer.buffer.max.mb", "800") // Prevent overflow
      .config("spark.ui.enabled", "true")
      .config("spark.executor.memory",   "14g")
      .getOrCreate()

    // Init spark context
    implicit val sc = sparkSession.sparkContext

    try {
//      run_test(args(0), args(1))(sc)
      val run_reps = args(0).toInt
      run_simple_read_tile_query( run_reps, args(1), args(2),args(3),args(4),args(5))(sparkSession)
      // Pause to wait to close the spark context,
      // so that you can check out the UI at http://localhost:4040
      println("Hit enter to exit.")
      StdIn.readLine()
    } finally {
      sc.stop()
    }

    def run_test(input_filepath : String, output_filepath : String)(implicit sc: SparkContext) = {
      //val filenameRdd = sc.binaryFiles('hdfs://nameservice1:8020/user/*.binary')

      // Read Geotiff and time it
      val time_idx = time{
        readGeoTiffToMultibandTileLayerRDD(input_filepath)
      }

      val mtl_rdd : MultibandTileLayerRDD[SpatialKey] = time_idx._1
      println("Time to INDEX: "+nanosecToSec(time_idx._2).toString())


      // Stich raster and time it
      val time_stitch = time{
        mtl_rdd.stitch()
      }

      val raster: Raster[MultibandTile] = time_stitch._1
      println("Time to STITCH: "+nanosecToSec(time_stitch._2).toString())

      val time_write = time {
        GeoTiff(raster, mtl_rdd.metadata.crs).write(output_filepath)
      }

      println("Time to WRITE: "+nanosecToSec(time_write._2).toString())

    }

    def run_simple_read_tile_query(run_reps: Int, src_raster_file_path: String, tile_out_path: String, meta_shp_path : String, qry_shp_path : String, output_gtif_path : String )
                                  (implicit spark_s: SparkSession): Unit = {
      for( run_rep <- 1 to run_reps) {
        simpleReadTileQuery(run_rep,src_raster_file_path, tile_out_path, meta_shp_path, qry_shp_path, output_gtif_path)
      }
    }
  }
}
