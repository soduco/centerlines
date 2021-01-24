# centerlines

Create centerlines from input vectorized edges.

Typical usage:

## Step 1. Create the vectorized shapes
You can, for instance, use gdal_polygonize:
```shell script
gdal_polygonize.py data/178_4000_5000_4500_5500_gt.png data/input.shp
```

## Step 2. Create the centerlines
```shell script
sbt "run -i data/input.shp -l data/lines.shp -p data/polygons.shp --transform"
```
