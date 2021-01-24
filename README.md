# centerlines

Create centerlines from input vectorized edges.

To use the centerlines builder, you need to install sbt: https://www.scala-sbt.org/download.html

## Step 1. Create the vectorized shapes
You can, for instance, use gdal_polygonize:
```shell script
gdal_polygonize.py data/178_4000_5000_4500_5500_gt.png data/input.shp
```

![The test input data](data/178_4000_5000_4500_5500_gt.png "Input test image")

![The vectorized test input data](images/vectorized_test_data.png "Input test image vectorized")

Please note that the image is inverted if you visualize it with a GIS (QGIS for instance).

## Step 2. Create the centerlines
```shell script
sbt "run -i data/input.shp -l data/lines.shp -p data/polygons.shp --transform"
```

![The resulting lines for the input data](images/line_result_test_data.png "Input test image extracted lines")
![The resulting polygons for the input data](images/polygon_result_test_data.png "Input test image extracted polygons")

Note that the lines and polygons match with the input image thanks to the *transform* flag the transforms the resulting geometries.
