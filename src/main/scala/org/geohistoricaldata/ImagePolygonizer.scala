package org.geohistoricaldata

import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.metadata.ImageMetadata
import org.locationtech.jts.geom.{Coordinate, GeometryFactory, LineString, LinearRing, Polygon, PrecisionModel}
import org.locationtech.jts.operation.linemerge.LineMerger
import org.locationtech.jts.operation.overlay.snap.GeometrySnapper
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier

import java.awt.Rectangle
import java.io.File
import java.util.Calendar
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._

object ImagePolygonizer {
  def apply(imageFile: File, outputLines: File, outputPolygons: File,
            tileSize: Int, threshold: Int,
            densifyParameter: Option[Double], simplifyTolerance: Option[Double], tolerance: Double, transformResult: Boolean) = {
    val factory = new GeometryFactory(new PrecisionModel(100.0))

    def getLines(imageFile: File, tileSize: Int, threshold: Int, densifyParameter: Option[Double], simplifyTolerance: Option[Double], tolerance: Double) = {
      val meta = ImageMetadata.fromFile(imageFile)
      val imageWidth = meta.tags().find(_.getName.equalsIgnoreCase("Image Width")).get.getRawValue.toInt
      val imageHeight = meta.tags().find(_.getName.equalsIgnoreCase("Image Height")).get.getRawValue.toInt
      meta.tags().foreach { tag =>
        println(tag)
      }
      val xTiles = imageWidth / tileSize + (if (imageWidth % tileSize > 0) 1 else 0)
      val yTiles = imageHeight / tileSize + (if (imageHeight % tileSize > 0) 1 else 0)
      println(s"$xTiles x $yTiles tiles")
      val loader = ImmutableImage.loader()
      val borders = ArrayBuffer[Polygon]()
      val lines = ArrayBuffer[LineString]()
//      val segments = ArrayBuffer[LineString]()
//      val coordinates = ArrayBuffer[Coordinate]()
      val sharedBorderCoordinates = ArrayBuffer[Coordinate]()
//      val connectingCoordinates = ArrayBuffer[Coordinate]()
      for {
        x <- 0 until xTiles
        y <- 0 until yTiles
      } {
        println(s"Tile ($x x $y)")
        val (xx, yy) = (x * tileSize, y * tileSize)
        val tile = loader.sourceRegion(new Rectangle(xx, yy, tileSize, tileSize)).fromFile(imageFile)
        val array = ArrayBuffer[Polygon]()
        tile.forEach { pixel =>
          if (pixel.average() > threshold) {
            val (xxx, yyy) = (xx + pixel.x, yy + pixel.y)
            val (c0, c1, c2, c3) = (new Coordinate(xxx, yyy), new Coordinate(xxx, yyy + 1), new Coordinate(xxx + 1, yyy + 1), new Coordinate(xxx + 1, yyy))
            array += factory.createPolygon(Array(c0, c1, c2, c3, c0))
          }
        }
        val res = factory.createGeometryCollection(array.toArray).union()
        // separate the polygons inside the tile from the ones shared between tiles
        val (border, inside) = (0 until res.getNumGeometries).map(i => res.getGeometryN(i).asInstanceOf[Polygon]).partition {
          _.getCoordinates.exists(c => (c.x.toInt == xx) || (c.x.toInt == xx + tileSize) || (c.y.toInt == yy) || (c.y.toInt == yy + tileSize))
        }
        borders ++= border.toArray.map(_.asInstanceOf[Polygon])
        val (c0, c1, c2, c3) = (new Coordinate(xx, yy), new Coordinate(xx, yy + tileSize), new Coordinate(xx + tileSize, yy + tileSize), new Coordinate(xx + tileSize, yy))
        val sharedBCoordinates = CenterLinesBuilder.getSharedCoordinates(inside.toArray, border.toArray)
        val edges /*(edges, theSegments, points)*/ = CenterLinesBuilder.getLinesFromPolygons(inside.toArray, factory.createPolygon(Array(c0, c1, c2, c3, c0)), sharedBCoordinates, densifyParameter, simplifyTolerance, tolerance)
        lines ++= edges
//        segments ++= theSegments
//        coordinates ++= points
        sharedBorderCoordinates ++= sharedBCoordinates
//        connectingCoordinates ++= CenterLinesBuilder.getConnectingCoordinates(inside.toArray)
      }
      val (c0, c1, c2, c3) = (new Coordinate(0, 0), new Coordinate(0, imageHeight), new Coordinate(imageWidth, imageHeight), new Coordinate(imageWidth, 0))
      val imageContour = factory.createPolygon(Array(c0, c1, c2, c3, c0))
      val union = factory.createGeometryCollection(borders.toArray).union()
      val borderUnion = (0 until union.getNumGeometries).toArray.map(i => union.getGeometryN(i).asInstanceOf[Polygon])
      println(s"Handling ${borderUnion.length} border polygons")
      val edges /*(edges, theSegments, points)*/ = CenterLinesBuilder.getLinesFromPolygons(borderUnion, imageContour, sharedBorderCoordinates.toArray, densifyParameter, simplifyTolerance, tolerance)
      lines ++= edges
//      segments ++= theSegments
//      coordinates ++= points
      val exteriorRing = imageContour.getExteriorRing
      val exteriorConnectedCoordinates = lines.flatMap(_.getCoordinates.toSeq).filter(c => factory.createPoint(c).distance(exteriorRing) < tolerance)
      // make sure the exterior ring contains the coords we have created (avoid potential precision problems with JTS)
      val snappedExteriorRing = new GeometrySnapper(exteriorRing).snapTo(factory.createMultiPointFromCoords(exteriorConnectedCoordinates.toArray), tolerance)
      // merge the edges that can be merged
      val cc = snappedExteriorRing.getCoordinates
      val exteriorLines = (0 until cc.size - 1).map { i => factory.createLineString(Array(cc(i), cc(i + 1))) }.toArray
      lines ++= exteriorLines
      val lmg = new LineMerger()
      lines.foreach(lmg.add)
      val result = lmg.getMergedLineStrings.asScala.
        map(_.asInstanceOf[LineString]).
        map(l => if (simplifyTolerance.isDefined) DouglasPeuckerSimplifier.simplify(l, simplifyTolerance.get) else l).
        map(_.asInstanceOf[LineString])
      val filtered = result.map(l => if (l.getCoordinateN(0).compareTo(l.getCoordinateN(1)) > 0) l else l.reverse()).toArray.distinct
      (filtered, /*segments.toArray, coordinates.toArray, connectingCoordinates,*/ imageWidth, imageHeight)
    }

    println(Calendar.getInstance().getTime)
//    val input = "data/BHdV_PL_ATL20Ardt_1898_0004"
//    //  val input = "data/BHdV_PL_ATL20Ardt_1926_0004"
//    val densify = None
//    val simplify = None
    val (lines, /*segments, coordinates, sharedCoordinates,*/ imageWidth, imageHeight) = getLines(imageFile, tileSize = tileSize, threshold, densifyParameter, simplifyTolerance, tolerance)
//    val outputLines = input + s"_lines_$densify.shp"
//    val outputSegments = input + s"_segments_$densify.shp"
//    val outputPolygons = input + s"_polygons_$densify.shp"
//    val outputPoints = input + s"_points_$densify.shp"
//    val outputSharedPoints = input + s"_shared_points_$densify.shp"
//    val transformResult = true
    println("Creating the polygons now")
    val polygons = CenterLinesBuilder.getPolygonsFromLines(lines, factory)

    def transform(coordinate: Coordinate) = new Coordinate(coordinate.x, -coordinate.y)

    // save the lines, just because
    // if transformResults is true, save the inverted lines to match with the input image
    def transformLineString(line: LineString) = factory.createLineString(line.getCoordinates.map(transform))

    Utils.createShapefile(outputLines, "the_geom:LineString", lines.map(geom => Array[AnyRef](if (transformResult) transformLineString(geom) else geom)).toList)
//    Utils.createShapefile(new File(outputSegments), "the_geom:LineString", segments.map(geom => Array[AnyRef](if (transformResult) transformLineString(geom) else geom)).toList)

    // save the polygons, just because too
    // if transformResults is true, save the inverted polygons to match with the input image
    def transformPolygon(polygon: Polygon) = {
      def transformRing(ring: LinearRing) = factory.createLinearRing(ring.getCoordinates.map(transform))

      factory.createPolygon(
        transformRing(polygon.getExteriorRing),
        (0 until polygon.getNumInteriorRing).map(i => transformRing(polygon.getInteriorRingN(i))).toArray)
    }

    def onContour(c: Coordinate) = c.x == 0 || c.x == imageWidth || c.y == 0 || c.y == imageHeight

    var polygonId = 0

    def getPolygonId = {
      val id = polygonId.toString
      polygonId += 1
      id
    }

    Utils.createShapefile(outputPolygons,
      spec = "the_geom:Polygon,ID:String,contour:Boolean",
      polygons.map(p => Array[AnyRef](if (transformResult) transformPolygon(p) else p, getPolygonId, Boolean.box(p.getCoordinates.exists(onContour)))).toList)
//    Utils.createShapefile(new File(outputSharedPoints), "the_geom:Point", sharedCoordinates.map(p => Array[AnyRef](factory.createPoint(if (transformResult) transform(p) else p))).toList)
//    Utils.createShapefile(new File(outputPoints), "the_geom:Point", coordinates.map(p => Array[AnyRef](factory.createPoint(if (transformResult) transform(p) else p))).toList)
    println(Calendar.getInstance().getTime)
  }
}
