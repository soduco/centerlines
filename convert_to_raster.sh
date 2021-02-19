#!/bin/bash

if [ $# -lt 4 ]; then
    echo "Usage: $0 <input raster file> <input polygon vector file> <input edge vector file> <output raster file>"
    exit 1
fi

# get the size of the input image (to produce similar output)
sizex=$(gdalinfo $1 -json | jq ".size[0]")
sizey=$(gdalinfo $1 -json | jq ".size[1]")
printf "input image is $sizex x $sizey\n"

# rasterize the polygons
tmpfile1=$(mktemp /tmp/convert_to_raster1.XXXX.tif)
gdal_rasterize -a ID $2 "$tmpfile1" -ts $sizex $sizey

# rasterize the lines
tmpfile2=$(mktemp /tmp/convert_to_raster2.XXXX.tif)
gdal_rasterize -burn 1 $3 "$tmpfile2" -ts $sizex $sizey

# combine the rasterized images by setting 0 where there are edges and ID + 1 where there is none
gdal_calc.py -A "$tmpfile1" -B "$tmpfile2" --outfile=$4 --calc="(A+1)*(B==0)"

# clean up temp files
rm "$tmpfile1"
rm "$tmpfile2"