package org.geohistoricaldata

import org.geotools.data.shapefile.ShapefileDataStoreFactory
import org.geotools.data.{DataStoreFinder, DataUtilities, Transaction}
import org.opengis.feature.simple.SimpleFeature
import org.opengis.filter.Filter

import java.io.File
import java.nio.file.Path
import java.util
import scala.collection.mutable.ArrayBuffer

object Utils {
  def getShapefile(file: Path): Seq[SimpleFeature] = {
    val map = new util.HashMap[String, Object]
    map.put("url", file.toUri.toURL)
    val dataStore = DataStoreFinder.getDataStore(map)
    val source = dataStore.getFeatureSource(dataStore.getTypeNames.head)
    val collection = source.getFeatures(Filter.INCLUDE)
    val featureBuffer = ArrayBuffer[SimpleFeature]()
    val features = collection.features()
    try while (features.hasNext) featureBuffer += features.next()
    finally if (features != null) features.close()
    dataStore.dispose()
    featureBuffer.toSeq
  }
  def createShapefile(f: File, spec: String, list: List[Array[AnyRef]]): Unit = {
    val factory = new ShapefileDataStoreFactory
    val outputDataStore = factory.createDataStore(f.toURI.toURL)
    val featureType = DataUtilities.createType("Obj", spec)
    outputDataStore.createSchema(featureType)
    val outputTypeName = outputDataStore.getTypeNames()(0)
    val writer = outputDataStore.getFeatureWriterAppend(outputTypeName, Transaction.AUTO_COMMIT)
    list.foreach { g =>
      writer.next().setAttributes(g)
      writer.write()
    }
    writer.close()
    outputDataStore.dispose()
  }
}