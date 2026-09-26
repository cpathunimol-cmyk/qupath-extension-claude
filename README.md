# QuPath Claude Code extension

Adds **Extensions > Claude > Claude Code…** to QuPath 0.7+. Prompts are sent to the
[`claude` CLI](https://claude.com/claude-code) (must be installed and logged in), together with optional
image/selection context. Generated Groovy code can be opened in the script editor for review.

## Install via catalog
In QuPath: Extensions > Manage extensions > Manage extension catalogs, add
`https://github.com/cpathunimol-cmyk/qupath-catalog`.

## Build
Requires JDK 25: `./gradlew build`
