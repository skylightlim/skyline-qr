# Loading poster

`make_poster.sh` makes the map poster of Kuala Lumpur that the website and the app show while the
city loads, with [maptoposter](https://github.com/originalankur/maptoposter) (MIT) from
OpenStreetMap data (© OpenStreetMap contributors, ODbL; the credit is printed on the poster).

It needs OpenStreetMap's Overpass API, so the easiest way to remake it is the **Poster** workflow
in the Actions tab (Run workflow), which commits the new poster to the branch it ran on. To change
the place, size or theme, edit the arguments in `make_poster.sh`; the themes are maptoposter's
`themes/*.json`.
