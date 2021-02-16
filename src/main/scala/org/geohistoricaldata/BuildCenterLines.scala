package org.geohistoricaldata

import scopt.{DefaultOParserSetup, OParser, OParserSetup}

import java.io.{File => JFile}

case class Config(
                   in: JFile = new JFile("."),
                   outLines: JFile = new JFile("."),
                   outPolygons: JFile = new JFile("."),
                   densifyParameter: Double = 0.5,
                   simplifyTolerance: Double = 1.0,
                   tolerance: Double = 0.01,
                   transform: Boolean = false
                 )
object BuildCenterLines extends App {
  val builder = OParser.builder[Config]
  val parser = {
    import builder._
    OParser.sequence(
      programName("BuildCenterLines"),
      head("BuildCenterLines", "0.1"),
      opt[JFile]('i', "in")
        .required()
        .valueName("<file>")
        .action((x, c) => c.copy(in = x))
        .validate(x =>
          if (x.getName.endsWith(".shp")) success
          else failure("input must be a shapefile (at the moment anyway)"))
        .text("in is the input file containing the shapes/polygons to extract centerlines from"),
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
      opt[Double]('d', "densifyParameter")
        .optional()
        .action((x, c) => c.copy(densifyParameter = x))
        .validate(x =>
          if ((x > 0) && (x <= 1)) success
          else failure("Value <densifyParameter> must be > 0 and < 1"))
        .text("densifyParameter is a double property used to densify the input geometries at the voronoi diagram step. It corresponds to the max distance between consecutive points on the geometry"),
      opt[Double]('s', "simplifyTolerance")
        .optional()
        .action((x, c) => c.copy(simplifyTolerance = x))
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
  OParser.parse(parser, args, Config(), setup) match {
    case Some(config) =>
      CenterLinesBuilder(config.in, config.outLines, config.outPolygons, config.densifyParameter, config.simplifyTolerance, config.tolerance, config.transform)
    case _ =>
    // arguments are bad, error message will have been displayed
  }
}
