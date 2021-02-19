package org.geohistoricaldata
import scopt.{DefaultOParserSetup, OParser, OParserSetup}

import java.io.{File => JFile}

case class PolygonizeImageConfig(
                   in: JFile = new JFile("."),
                   outLines: JFile = new JFile("."),
                   outPolygons: JFile = new JFile("."),
                   tileSize: Int = 512,
                   threshold: Int = 200,
                   densifyParameter: Option[Double] = None,
                   simplifyTolerance: Option[Double] = None,
                   tolerance: Double = 0.01,
                   transform: Boolean = false
                 )

object PolygonizeImage extends App {
  val builder = OParser.builder[PolygonizeImageConfig]
  val parser = {
    import builder._
    OParser.sequence(
      programName("PolygonizeImage"),
      head("PolygonizeImage", "0.1"),
      opt[JFile]('i', "in")
        .required()
        .valueName("<file>")
        .action((x, c) => c.copy(in = x))
        .validate(x =>
          if (x.getName.endsWith(".tif") || x.getName.endsWith(".png")) success
          else failure("input must be a tif or png image (at the moment anyway)"))
        .text("in is the input image file"),
      opt[JFile]('l', "outLines")
        .required()
        .valueName("<file>")
        .action((x, c) => c.copy(outLines = x))
        .validate(x =>
          if (x.getName.endsWith(".shp")) success
          else failure("output lines must be a shapefile (at the moment anyway)"))
        .text("out lines is the output file containing extracted centerlines"),
      opt[JFile]('p', "outPolygons")
        .required()
        .valueName("<file>")
        .action((x, c) => c.copy(outPolygons = x))
        .validate(x =>
          if (x.getName.endsWith(".shp")) success
          else failure("output polygons must be a shapefile (at the moment anyway)"))
        .text("out polygons is the output file containing extracted polygons"),
      opt[Int]('z', "tilesize")
        .optional()
        .action((x, c) => c.copy(tileSize = x))
        .validate(x =>
          if ((x > 0)) success
          else failure("Value <tilesize> must be > 0"))
        .text("tilesize is used to tile the input image"),
      opt[Int]('h', "threshold")
        .optional()
        .action((x, c) => c.copy(threshold = x))
        .validate(x =>
          if ((x > 0) && (x <= 255)) success
          else failure("Value <threshold> must be > 0 and <= 255"))
        .text("threshold is used to binarize the input image"),
      opt[Double]('d', "densifyParameter")
        .optional()
        .action((x, c) => c.copy(densifyParameter = Some(x)))
        .validate(x =>
          if ((x > 0) && (x <= 1)) success
          else failure("Value <densifyParameter> must be > 0 and <= 1"))
        .text("densifyParameter is a double property used to densify the input geometries at the voronoi diagram step. It corresponds to the max distance between consecutive points on the geometry"),
      opt[Double]('s', "simplifyTolerance")
        .optional()
        .action((x, c) => c.copy(simplifyTolerance = Some(x)))
        .validate(x =>
          if (x >= 0) success
          else failure("Value <simplifyTolerance> must be > 0"))
        .text("simplifyTolerance is a double property used to simplify the extracted centerlines. It corresponds to the distance tolerance of the Douglas-Peucker algorithm."),
      opt[Double]('t', "tolerance")
        .optional()
        .action((x, c) => c.copy(tolerance = x))
        .validate(x =>
          if (x > 0) success
          else failure("Value <tolerance> must be > 0"))
        .text("tolerance is a double property used at multiple stages to identify coordinates, build the voronoi diagram and snap geometries."),
      opt[Unit]("transform")
        .action((_, c) => c.copy(transform = true))
        .text("transform is a flag : if set, transform the output geometries to match the input image (reverse y axis)")
    )
  }
  val setup: OParserSetup = new DefaultOParserSetup {
    override def showUsageOnError: Option[Boolean] = Some(true)
  }
  OParser.parse(parser, args, PolygonizeImageConfig(), setup) match {
    case Some(config) =>
      ImagePolygonizer(
        config.in, config.outLines, config.outPolygons,
        config.tileSize, config.threshold,
        config.densifyParameter, config.simplifyTolerance, config.tolerance, config.transform)
    case _ =>
    // arguments are bad, error message will have been displayed
  }

}
