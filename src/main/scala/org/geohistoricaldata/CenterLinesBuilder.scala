package org.geohistoricaldata

import java.io.{File => JFile}
import org.jgrapht.graph.{DefaultEdge, DefaultUndirectedGraph}
import org.locationtech.jts.densify.Densifier
import org.locationtech.jts.geom.{Coordinate, GeometryCollection, GeometryFactory, LineString, LinearRing, MultiPolygon, Polygon, PrecisionModel}
import org.locationtech.jts.operation.linemerge.LineMerger
import org.locationtech.jts.operation.overlay.snap.GeometrySnapper
import org.locationtech.jts.operation.polygonize.Polygonizer
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier
import org.locationtech.jts.triangulate.VoronoiDiagramBuilder

import scala.annotation.tailrec
import scala.jdk.CollectionConverters._

object CenterLinesBuilder {
  def apply(input: JFile, outputLines: JFile, outputPolygons: JFile, densifyParameter: Double, simplifyTolerance: Double, tolerance: Double, transformResult: Boolean) {
    val factory = new GeometryFactory(new PrecisionModel(100))
    // read the input polygons. Use DN = 255 to identify edges
    val features = Utils.getShapefile(input.toPath).map(f => f.getID -> (f.getDefaultGeometry.asInstanceOf[MultiPolygon].getGeometryN(0).asInstanceOf[Polygon], f.getAttribute("DN").toString.equals("255"))).toMap
    // build the union to get the (segmented) contour
    val contour = factory.createGeometryCollection(features.map(_._2._1).toArray).union()
    val contourCoordinates = contour.getCoordinates
    // get the (distinct) coordinates for each feature
    val coordinates = features.filter(_._2._2).view.mapValues(_._1.getExteriorRing.getCoordinates.dropRight(1))
    // identify the coordinates exterior to each cell (coordinates that connect to another cell, ie shared coordinates, ie appearing more than once)
    val sharedCoordinates = coordinates.mapValues(_.filter(c => coordinates.map(m => m._2.count(_.equals2D(c))).sum > 1))
    def getLine(id: String, array: Array[Coordinate]) = {
      // check if part of the coordinates are on the contour of the dataset
      val coordinatesOnContour = contourCoordinates.filter(array.contains)
      // Get the exterior coordinates for the cell id with coordinates array
      val exteriorCoordinates = if (coordinatesOnContour.isEmpty) {
        sharedCoordinates(id)
      } else {
        // must be connected to the contour so compute the outgoing coord
        sharedCoordinates(id) :+ factory.createMultiPointFromCoords(coordinatesOnContour).getCentroid.getCoordinate
      }
      if (array.length == 4) { //that is a rectangle
//        if (exteriorCoordinates.length == 2) id -> Array(factory.createLineString(exteriorCoordinates)) // 2 'exterior' coords, no brainer
//        else {
          // more than one exterior coord, create a star like shape
          // FIXME should check if we can have less than 2 ext coords
          val centroid = factory.createMultiPointFromCoords(array).getCentroid.getCoordinate
          id -> exteriorCoordinates.map(e => factory.createLineString(Array(centroid, e)))
//        }
      } else {
        id -> getVoronoiGraph(id, exteriorCoordinates)
      }
    }

    /**
     * Get the voronoi diagram graph for the given cell
     * @param id the id of the cell
     * @param exteriorCoordinates the exterior coordinates of the cell
     * @return the (filtered) edges of the voronoi diagram for the cell
     */
    def getVoronoiGraph(id: String, exteriorCoordinates: Array[Coordinate]) = {
      val geom = features(id)._1
      // densify the coordinates for the edges of the voronoi diagram to be smoother (better approximation)
      val densifiedGeom = Densifier.densify(geom, densifyParameter)
      // build a voronoi diagram
      val vdb = new VoronoiDiagramBuilder()
      vdb.setTolerance(tolerance)
      vdb.setSites(densifiedGeom)
      vdb.setClipEnvelope(geom.getEnvelopeInternal)
      val diagram = vdb.getDiagram(factory).asInstanceOf[GeometryCollection]
      // extract the contours of the voronoi cells and keep only those inside the current feature
      val lines = (0 until diagram.getNumGeometries).flatMap { i =>
        val coordinates = diagram.getGeometryN(i).asInstanceOf[Polygon].getExteriorRing.getCoordinates
        (0 until coordinates.size - 1).map { j => factory.createLineString(Array(coordinates(j), coordinates(j + 1)).sorted) }
      }.toSet.filter(geom.contains).toArray
      // create lines from the outgoint coordinates to the closest coordinate in the voronoi lines
      val extLines = exteriorCoordinates.map(c => factory.createLineString(Array(c, lines.map(l => (l, l.getCoordinates.map(lc => (lc, lc.distance(c))).minBy(_._2))).minBy(_._2._2)._2._1)))
      val allLines = lines ++ extLines
      // build a simple undirected graph to hold all the lines
      case class Edge(line: LineString) extends DefaultEdge
      val graph = new DefaultUndirectedGraph[Coordinate, Edge](classOf[Edge])
      // add the (distinct) coordinates to the graph
      allLines.flatMap(_.getCoordinates).distinct.foreach(graph.addVertex)
      // add the edges to the graph (note : we need to force the cast here for unknown reasons)
      allLines.foreach(l => graph.addEdge(l.getCoordinates.head, l.getCoordinates.last, Edge(l.asInstanceOf[LineString])))
      // recursively remove dangling edges
      @tailrec
      def removeDangling(): DefaultUndirectedGraph[Coordinate, Edge] = {
        val danglingNodes = graph.vertexSet().asScala.filter(v => graph.edgesOf(v).size() == 1 && !exteriorCoordinates.contains(v))
        if (danglingNodes.isEmpty) graph
        else {
          graph.removeAllVertices(danglingNodes.asJava)
          removeDangling()
        }
      }
      // remove the dangling edges (not connected to anything
      removeDangling().edgeSet().asScala.map(_.line).toArray
    }
    val lines = coordinates.map(elem => getLine(elem._1, elem._2))
    val exteriorRing = contour.asInstanceOf[Polygon].getExteriorRing
    val exteriorConnectedCoordinates = lines.flatMap(_._2.flatMap(_.getCoordinates).toSeq).filter(c => factory.createPoint(c).distance(exteriorRing) < tolerance)
    // make sure the exterior ring contains the coords we have created (avoid potential precision problems with JTS)
    val snappedExteriorRing = new GeometrySnapper(exteriorRing).snapTo(factory.createMultiPointFromCoords(exteriorConnectedCoordinates.toArray), tolerance)
    // merge the edges that can be merged
    val lmg = new LineMerger()
    lines.map(_._2).foreach(_.foreach(lmg.add))
    val cc = snappedExteriorRing.getCoordinates
    (0 until cc.size - 1).foreach { i => lmg.add(factory.createLineString(Array(cc(i), cc(i + 1)))) }
    // simplify the resulting edges
    val simplified = lmg.getMergedLineStrings.asScala.map(_.asInstanceOf[LineString]).map(l => DouglasPeuckerSimplifier.simplify(l, simplifyTolerance)).map(_.asInstanceOf[LineString])
    // polygonize the edges
    val polygonizer = new Polygonizer()
    simplified.foreach(polygonizer.add)
    // save the lines, just because
    // if transformResults is true, save the inverted lines to match with the input image
    def transformLineString(line: LineString) = factory.createLineString(line.getCoordinates.map(c => new Coordinate(c.x, -c.y)))
    Utils.createShapefile(outputLines, "the_geom:LineString", simplified.map(geom => Array[AnyRef](if (transformResult) transformLineString(geom) else geom)).toList)
    val polygons = polygonizer.getPolygons.asScala.map(_.asInstanceOf[Polygon])
    // save the polygons, just because too
    // if transformResults is true, save the inverted polygons to match with the input image
    def transformPolygon(polygon: Polygon) = {
      def transformRing(ring: LinearRing) = factory.createLinearRing(ring.getCoordinates.map(c => new Coordinate(c.x, -c.y)))
      factory.createPolygon(
        transformRing(polygon.getExteriorRing),
        (0 until polygon.getNumInteriorRing).map(i => transformRing(polygon.getInteriorRingN(i))).toArray)
    }
    Utils.createShapefile(outputPolygons, "the_geom:Polygon", polygons.map(p => Array[AnyRef](if (transformResult) transformPolygon(p) else p)).toList)
  }
}