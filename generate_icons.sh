#!/bin/bash
SRC="/home/pc/.gemini/antigravity-cli/brain/e59f5fa5-eb94-40b0-8468-a046c0ee2858/harness_icon_1788614948201.jpg"
DEST="app/src/main/res"

# Cleanup old adaptive icons if present
rm -f $DEST/drawable/ic_launcher_background.xml
rm -f $DEST/drawable/ic_launcher_foreground.xml
rm -rf $DEST/mipmap-anydpi-v26

# We'll use convert to generate sizes
sizes=( "mdpi:48" "hdpi:72" "xhdpi:96" "xxhdpi:144" "xxxhdpi:192" )

for item in "${sizes[@]}" ; do
    key="${item%%:*}"
    val="${item##*:}"
    
    mkdir -p "$DEST/mipmap-$key"
    
    # Square icon
    convert "$SRC" -resize ${val}x${val} "$DEST/mipmap-$key/ic_launcher.png"
    
    # Round icon (create a circular mask)
    convert -size ${val}x${val} xc:none -fill white -draw "circle $(($val/2)),$(($val/2)) $(($val/2)),0" mask.png
    convert "$SRC" -resize ${val}x${val} -matte mask.png -compose DstIn -composite "$DEST/mipmap-$key/ic_launcher_round.png"
done

rm mask.png
