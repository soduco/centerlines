package org.geohistoricaldata

import org.jgrapht.graph.{DefaultEdge, DefaultUndirectedGraph}
import org.locationtech.jts.densify.Densifier
import org.locationtech.jts.geom._
import org.locationtech.jts.linearref.LengthIndexedLine
import org.locationtech.jts.operation.linemerge.LineMerger
import org.locationtech.jts.operation.overlay.snap.GeometrySnapper
import org.locationtech.jts.operation.polygonize.Polygonizer
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier
import org.locationtech.jts.triangulate.VoronoiDiagramBuilder

import java.io.{File => JFile}
import scala.annotation.tailrec
import scala.jdk.CollectionConverters._

object CenterLinesBuilder {
  /**
   * Get the voronoi diagram graph for the given cell
   * @param geom geometry of the cell
   * @param exteriorCoordinates the exterior coordinates of the cell
   * @return the (filtered) edges of the voronoi diagram for the cell
   */
  def getVoronoiGraph(geom: Polygon, exteriorCoordinates: Array[Coordinate], factory: GeometryFactory, densifyParameter: Option[Double], tolerance: Double):
    Array[LineString] /*(Array[LineString], Array[LineString], Array[Coordinate])*/ = {
    // densify the coordinates for the edges of the voronoi diagram to be smoother (better approximation)
    val densifiedGeom = if (densifyParameter.isDefined) Densifier.densify(geom, densifyParameter.get) else geom
    // build a voronoi diagram
    val vdb = new VoronoiDiagramBuilder()
    vdb.setTolerance(tolerance)
    vdb.setSites(densifiedGeom)
    val envelope = geom.getEnvelopeInternal
    envelope.expandBy(100)
    vdb.setClipEnvelope(envelope)
    val diagram = vdb.getSubdivision.getVoronoiDiagram(factory).asInstanceOf[GeometryCollection]
    // extract the contours of the voronoi cells and keep only those inside the current feature
    val lines = (0 until diagram.getNumGeometries).flatMap { i =>
      val coordinates = diagram.getGeometryN(i).asInstanceOf[Polygon].getExteriorRing.getCoordinates
      (0 until coordinates.size - 1).map { j => factory.createLineString(Array(coordinates(j), coordinates(j + 1)).sorted) }
    }.toSet.filter(geom.contains).toArray
    // create lines from the outgoint coordinates to the closest coordinate in the voronoi lines
    val extLines = exteriorCoordinates.map(c => factory.createLineString(Array(c, lines.map(l => (l, l.getCoordinates.map(lc => (lc, lc.distance(c))).minBy(_._2))).minBy(_._2._2)._2._1)))
    val allLines = lines ++ extLines
    // build a simple undirected graph to hold all the lines
    case class Edge(line: LineString) extends DefaultEdge {
      def getSourceVertex: Coordinate = getSource.asInstanceOf[Coordinate]
      def getTargetVertex: Coordinate = getTarget.asInstanceOf[Coordinate]
    }
    val graph = new DefaultUndirectedGraph[Coordinate, Edge](classOf[Edge])
    // add the (distinct) coordinates to the graph
    allLines.flatMap(_.getCoordinates).distinct.foreach(graph.addVertex)
    // add the edges to the graph (note : we need to force the cast here for unknown reasons)
    allLines.foreach(l => graph.addEdge(l.getCoordinates.head, l.getCoordinates.last, Edge(l.asInstanceOf[LineString])))
    // merge recursively simple vertices
    @tailrec
    def mergeEdges(): Unit = {
      def otherVertex(e: Edge, v: Coordinate) = if (e.getSourceVertex == v) e.getTargetVertex else e.getSourceVertex
      val opt = graph.vertexSet().asScala.find(v => graph.edgesOf(v).size() == 2 && !exteriorCoordinates.contains(v) && graph.edgesOf(v).asScala.map(e=>otherVertex(e,v)).size > 1)
      if (opt.isDefined) {
        val v = opt.get
        val edges = graph.edgesOf(v).asScala.toSeq
        val (e1, e2) = (edges.head, edges(1))
        val l1 = if (e1.line.getCoordinates.head == v) e1.line.getCoordinates.reverse else e1.line.getCoordinates
        val l2 = if (e2.line.getCoordinates.head == v) e2.line.getCoordinates else e2.line.getCoordinates.reverse
        val g = factory.createLineString(l1 ++ l2.tail)
        graph.addEdge(otherVertex(e1, v), otherVertex(e2, v), Edge(g))
        graph.removeVertex(v)
        mergeEdges()
      }
    }
    // recursively remove dangling edges
    @tailrec
    def removeDangling(recurse: Boolean, threshold: Option[Double], safe: Array[Coordinate]): Unit = {
      val danglingNodes = graph.vertexSet().asScala.filter(
        v => graph.edgesOf(v).size() == 1 &&
          !safe.contains(v) &&
          (threshold.isEmpty || (threshold.isDefined) && graph.edgesOf(v).iterator().next().line.getLength < threshold.get))
      if (danglingNodes.nonEmpty) {
        graph.removeAllVertices(danglingNodes.asJava)
        if (recurse) removeDangling(recurse, threshold, safe)
      }
    }
    // remove simple vertices
    mergeEdges()
    // remove the dangling edges (not connected to anything
    removeDangling(true, None, exteriorCoordinates ++ graph.vertexSet().asScala.filter(v => graph.edgesOf(v).size() > 2))
    // remove simple vertices again
    //mergeEdges()
//    (graph.edgeSet().asScala.map(_.line).toArray, allLines.map(_.asInstanceOf[LineString]), geom.getCoordinates)
    graph.edgeSet().asScala.map(_.line).toArray
  }

  def getSharedCoordinates(p1: Array[Polygon], p2: Array[Polygon]) = {
    val coordinates1 = p1.flatMap(_.getCoordinates.distinct).distinct
    val coordinates2 = p2.flatMap(_.getCoordinates.distinct).distinct
    coordinates1.filter(coordinates2.contains)
  }
  def getConnectingCoordinates(polygons: Array[Polygon]) = {
    polygons.flatMap(_.getCoordinates.distinct).groupBy(c=>c).view.mapValues(_.length).filter(_._2 > 1).keys.toArray
  }
  def getLinesFromPolygons(polygons: Array[Polygon], contour: Polygon, sharedBorderCoordinates: Array[Coordinate], densifyParameter: Option[Double], simplifyTolerance: Option[Double], tolerance: Double) = {
    val factory = new GeometryFactory(new PrecisionModel(100.0))
    // get the (distinct) coordinates for each feature
    val coordinates = polygons.map(_.getExteriorRing.getCoordinates.dropRight(1))
    // identify the coordinates exterior to each cell (coordinates that connect to another cell, ie shared coordinates, ie appearing more than once)
    val sharedCoordinates = coordinates.map(_.filter(c => (coordinates.map(m => m.count(_.equals2D(c))).sum > 1) || (sharedBorderCoordinates.contains(c))))
    val (minX, maxX) = (contour.getCoordinates.map(_.x).min, contour.getCoordinates.map(_.x).max)
    val (minY, maxY) = (contour.getCoordinates.map(_.y).min, contour.getCoordinates.map(_.y).max)
    def onContour(c: Coordinate) = (c.x == minX) || (c.x == maxX) || (c.y == minY) || (c.y == maxY)
    def getCoordinatesOnContour(array: Array[Coordinate]) = {
      // must be connected to the contour so compute the outgoing coord
      //for each segment, determine if on the contour
      val belongToContour = array.zipWithIndex.map{case (c0, i) =>
        def c1 = if (array.isDefinedAt(i+1)) array(i+1) else array.head
        (onContour(c0) && onContour(c1), c0, c1)
      }
      // if the beginning and the end  on the contour, we need to put them together since they belong to the same contour
      @tailrec
      def positionStart(a: Array[(Boolean, Coordinate, Coordinate)]): Array[(Boolean, Coordinate, Coordinate)] = {
        if (a.head._1 && a.last._1) positionStart(Array(a.last) ++ a.dropRight(1))
        else a
      }
      // group the segments together
      val acc = positionStart(belongToContour).foldLeft((Array[Array[(Boolean, Coordinate, Coordinate)]](),Array[(Boolean, Coordinate, Coordinate)]())){
        case (acc, current) => if (current._1) (acc._1, acc._2 :+ current) else
          if (acc._2.isEmpty) acc else (acc._1 :+ acc._2, Array())
      }
      // create the middle point for each group of segment
      (if (acc._2.nonEmpty) acc._1 :+ acc._2 else acc._1).map(l=>factory.createLineString(l.flatMap(x=>Array(x._2, x._3)).distinct)).map(l=>new LengthIndexedLine(l).extractPoint(l.getLength / 2))
    }
    def getLine(geom: Polygon, array: Array[Coordinate], sharedCoordinates: Array[Coordinate]):
      Array[LineString]/*(Array[LineString], Array[LineString], Array[Coordinate])*/ = {
      // check if part of the coordinates are on the contour of the dataset
      val coordinatesOnContour = array.filter(onContour)
      // Get the exterior coordinates for the cell id with coordinates array
      val exteriorCoordinates = if (coordinatesOnContour.isEmpty) {
        sharedCoordinates
      } else {
        sharedCoordinates ++ getCoordinatesOnContour(array)
      }
      if (array.length == 4) { //that is a rectangle
        val centroid = factory.createMultiPointFromCoords(array).getCentroid.getCoordinate
        val lines = exteriorCoordinates.map(e => factory.createLineString(Array(centroid, e)))
        lines//(lines, lines, array)
      } else {
        getVoronoiGraph(geom, exteriorCoordinates, factory, densifyParameter, tolerance)
      }
    }

    val lines /*(lines, segments, points)*/ = polygons.zipWithIndex.map{case (elem, i) =>
//      println(s"\tElement $i / ${polygons.size}")
      getLine(elem, coordinates(i), sharedCoordinates(i))
    }//.unzip3
//    val exteriorRing = contour.asInstanceOf[Polygon].getExteriorRing
//    val exteriorConnectedCoordinates = lines.flatMap(_.flatMap(_.getCoordinates).toSeq).filter(c => factory.createPoint(c).distance(exteriorRing) < tolerance)
    // make sure the exterior ring contains the coords we have created (avoid potential precision problems with JTS)
//    val snappedExteriorRing = new GeometrySnapper(exteriorRing).snapTo(factory.createMultiPointFromCoords(exteriorConnectedCoordinates.toArray), tolerance)
    // merge the edges that can be merged
    val lmg = new LineMerger()
    lines.flatten.foreach(lmg.add)
//    val cc = snappedExteriorRing.getCoordinates
//    (0 until cc.size - 1).foreach { i => lmg.add(factory.createLineString(Array(cc(i), cc(i + 1)))) }
    // simplify the resulting edges
    val simplified = lmg.getMergedLineStrings.asScala.
      map(_.asInstanceOf[LineString]).
      map(l => if (simplifyTolerance.isDefined) DouglasPeuckerSimplifier.simplify(l, simplifyTolerance.get) else l).
      map(_.asInstanceOf[LineString])
    simplified.toArray//(simplified.toArray, segments.flatten, points.flatten)
  }
  def getPolygonsFromLines(lines: Array[LineString], factory: GeometryFactory) = {
    // polygonize the edges
    val polygonizer = new Polygonizer()
    lines.foreach(l => polygonizer.add(factory.createGeometry(l)))
    polygonizer.getPolygons.asScala.toArray.map(_.asInstanceOf[Polygon])
  }
  def apply(input: JFile, outputLines: JFile, outputPolygons: JFile, densifyParameter: Double, simplifyTolerance: Double, tolerance: Double, transformResult: Boolean) {
    //    val factory = new GeometryFactory()
    val factory = new GeometryFactory(new PrecisionModel(100.0))
    println("Reading shapefile")
    // read the input polygons. Use DN = 255 to identify edges
    val features = Utils.getShapefile(input.toPath).map(f => f.getID -> (f.getDefaultGeometry.asInstanceOf[MultiPolygon].getGeometryN(0).asInstanceOf[Polygon], f.getAttribute("DN").toString.equals("255"))).toMap
    println("Creating contour")
    // build the union to get the (segmented) contour
    val contour = factory.createGeometryCollection(features.map(_._2._1).toArray).union()
    val contourCoordinates = contour.getCoordinates
    println("Filtering")
    val filteredFeatures = features.filter(_._2._2).view.mapValues(_._1)
    // get the (distinct) coordinates for each feature
    val coordinates = filteredFeatures.view.mapValues(_.getExteriorRing.getCoordinates.dropRight(1))
    // identify the coordinates exterior to each cell (coordinates that connect to another cell, ie shared coordinates, ie appearing more than once)
    val sharedCoordinates = coordinates.mapValues(_.filter(c => coordinates.map(m => m._2.count(_.equals2D(c))).sum > 1))
    // get one coordinate for each group of segments on the contour
    def getCoordinatesOnContour(array: Array[Coordinate]) = {
      // must be connected to the contour so compute the outgoing coord
      //for each segment, determine if on the contour
      val belongToContour = array.zipWithIndex.map{case (c0, i) =>
        def c1 = if (array.isDefinedAt(i+1)) array(i+1) else array.head
        (contourCoordinates.contains(c0) && contourCoordinates.contains(c1), c0, c1)
      }
      // if the beginning and the end  on the contour, we need to put them together since they belong to the same contour
      @tailrec
      def positionStart(a: Array[(Boolean, Coordinate, Coordinate)]): Array[(Boolean, Coordinate, Coordinate)] = {
        if (a.head._1 && a.last._1) positionStart(Array(a.last) ++ a.dropRight(1))
        else a
      }
      // group the segments together
      val acc = positionStart(belongToContour).foldLeft((Array[Array[(Boolean, Coordinate, Coordinate)]](),Array[(Boolean, Coordinate, Coordinate)]())){
        case (acc, current) => if (current._1) (acc._1, acc._2 :+ current) else
          if (acc._2.isEmpty) acc else (acc._1 :+ acc._2, Array())
      }
      // create the middle point for each group of segment
      (if (acc._2.nonEmpty) acc._1 :+ acc._2 else acc._1).map(l=>factory.createLineString(l.flatMap(x=>Array(x._2, x._3)).distinct)).map(l=>new LengthIndexedLine(l).extractPoint(l.getLength / 2))
    }
    def getLine(id: String, array: Array[Coordinate]) = {
      // check if part of the coordinates are on the contour of the dataset
      val coordinatesOnContour = contourCoordinates.filter(array.contains)
      // Get the exterior coordinates for the cell id with coordinates array
      val exteriorCoordinates = if (coordinatesOnContour.isEmpty) {
        sharedCoordinates(id)
      } else {
        sharedCoordinates(id) ++ getCoordinatesOnContour(array)
      }
      if (array.length == 4) { //that is a rectangle
          val centroid = factory.createMultiPointFromCoords(array).getCentroid.getCoordinate
          id -> exteriorCoordinates.map(e => factory.createLineString(Array(centroid, e)))
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
      val geom = filteredFeatures(id)
      // densify the coordinates for the edges of the voronoi diagram to be smoother (better approximation)
      val densifiedGeom = Densifier.densify(geom, densifyParameter)
      // build a voronoi diagram
      val vdb = new VoronoiDiagramBuilder()
      vdb.setTolerance(tolerance)
      vdb.setSites(densifiedGeom)
      val envelope = geom.getEnvelopeInternal
      envelope.expandBy(100)
      vdb.setClipEnvelope(envelope)
      val diagram = vdb.getSubdivision.getVoronoiDiagram(factory).asInstanceOf[GeometryCollection]
//      val diagram = vdb.getDiagram(factory).asInstanceOf[GeometryCollection]
      // extract the contours of the voronoi cells and keep only those inside the current feature
      val lines = (0 until diagram.getNumGeometries).flatMap { i =>
        val coordinates = diagram.getGeometryN(i).asInstanceOf[Polygon].getExteriorRing.getCoordinates
        (0 until coordinates.size - 1).map { j => factory.createLineString(Array(coordinates(j), coordinates(j + 1)).sorted) }
      }.toSet.filter(geom.contains).toArray
      // create lines from the outgoint coordinates to the closest coordinate in the voronoi lines
      val extLines = exteriorCoordinates.map(c => factory.createLineString(Array(c, lines.map(l => (l, l.getCoordinates.map(lc => (lc, lc.distance(c))).minBy(_._2))).minBy(_._2._2)._2._1)))
      val allLines = lines ++ extLines
      // build a simple undirected graph to hold all the lines
      case class Edge(line: LineString) extends DefaultEdge {
        def getSourceVertex: Coordinate = getSource.asInstanceOf[Coordinate]
        def getTargetVertex: Coordinate = getTarget.asInstanceOf[Coordinate]
      }
      val graph = new DefaultUndirectedGraph[Coordinate, Edge](classOf[Edge])
      // add the (distinct) coordinates to the graph
      allLines.flatMap(_.getCoordinates).distinct.foreach(graph.addVertex)
      // add the edges to the graph (note : we need to force the cast here for unknown reasons)
      allLines.foreach(l => graph.addEdge(l.getCoordinates.head, l.getCoordinates.last, Edge(l.asInstanceOf[LineString])))
      // merge recursively simple vertices
      @tailrec
      def mergeEdges(): Unit = {
        def otherVertex(e: Edge, v: Coordinate) = if (e.getSourceVertex == v) e.getTargetVertex else e.getSourceVertex
        val opt = graph.vertexSet().asScala.find(v => graph.edgesOf(v).size() == 2 && !exteriorCoordinates.contains(v) && graph.edgesOf(v).asScala.map(e=>otherVertex(e,v)).size > 1)
        if (opt.isDefined) {
          val v = opt.get
          val edges = graph.edgesOf(v).asScala.toSeq
          val (e1, e2) = (edges.head, edges(1))
          val l1 = if (e1.line.getCoordinates.head == v) e1.line.getCoordinates.reverse else e1.line.getCoordinates
          val l2 = if (e2.line.getCoordinates.head == v) e2.line.getCoordinates else e2.line.getCoordinates.reverse
          val g = factory.createLineString(l1 ++ l2.tail)
          graph.addEdge(otherVertex(e1, v), otherVertex(e2, v), Edge(g))
          graph.removeVertex(v)
          mergeEdges()
        }
      }
      // recursively remove dangling edges
      @tailrec
      def removeDangling(recurse: Boolean, threshold: Option[Double], safe: Array[Coordinate]): Unit = {
        val danglingNodes = graph.vertexSet().asScala.filter(
          v => graph.edgesOf(v).size() == 1 &&
            !safe.contains(v) &&
            (threshold.isEmpty || (threshold.isDefined) && graph.edgesOf(v).iterator().next().line.getLength < threshold.get))
        if (danglingNodes.nonEmpty) {
          graph.removeAllVertices(danglingNodes.asJava)
          if (recurse) removeDangling(recurse, threshold, safe)
        }
      }
      // remove simple vertices
      mergeEdges()
      // remove the dangling edges (not connected to anything
      removeDangling(true, Option(10.0), exteriorCoordinates ++ graph.vertexSet().asScala.filter(v => graph.edgesOf(v).size() > 2))
      // remove simple vertices again
      //mergeEdges()
      graph.edgeSet().asScala.map(_.line).toArray
    }
//    val lines = coordinates.map(elem => getLine(elem._1, elem._2))
    println("Handling lines")
    val lines = coordinates.zipWithIndex.map{case (elem, i) =>
      println(s"\tElement $i / ${coordinates.size}")
      getLine(elem._1, elem._2)}
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
    // save the lines, just because
    // if transformResults is true, save the inverted lines to match with the input image
    def transformLineString(line: LineString) = factory.createLineString(line.getCoordinates.map(c => new Coordinate(c.x, -c.y)))
    Utils.createShapefile(outputLines, "the_geom:LineString", simplified.map(geom => Array[AnyRef](if (transformResult) transformLineString(geom) else geom)).toList)
    // save the polygons, just because too
    // if transformResults is true, save the inverted polygons to match with the input image
    def transformPolygon(polygon: Polygon) = {
      def transformRing(ring: LinearRing) = factory.createLinearRing(ring.getCoordinates.map(c => new Coordinate(c.x, -c.y)))
      factory.createPolygon(
        transformRing(polygon.getExteriorRing),
        (0 until polygon.getNumInteriorRing).map(i => transformRing(polygon.getInteriorRingN(i))).toArray)
    }
    val polygons = getPolygonsFromLines(simplified.toArray, factory)
    Utils.createShapefile(outputPolygons, "the_geom:Polygon", polygons.map(p => Array[AnyRef](if (transformResult) transformPolygon(p) else p)).toList)
  }
}