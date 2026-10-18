# QuPath Claude Code extension

Adds **Extensions > Claude > Claude Code…** to QuPath 0.7+. Prompts are sent to the
[`claude` CLI](https://claude.com/claude-code) (must be installed and logged in), together with optional
image/selection context. Generated Groovy code can be opened in the script editor for review.

## Install via catalog
In QuPath: Extensions > Manage extensions > Manage extension catalogs, add
`https://github.com/cpathunimol-cmyk/qupath-catalog`.

## Skills (pathology review and QuPath scripting)
The window's **Skills folder** button points the extension at a folder containing `qupath-pathology/` and
`qupath-scripting/` (each with a `SKILL.md`). On first use it picks up `~/.claude/skills` if the pathology skill is
already there; otherwise it asks you to choose a folder and creates `qupath-pathology/` from a template. Use the
arrow menu > "Change skills folder..." to switch later (e.g. to a synced folder or a private git checkout shared
between machines). The extension passes this folder to `claude` by absolute path with `--add-dir`, so it works on
macOS, Windows and Linux regardless of where the folder lives.

## Build
Requires JDK 25: `./gradlew build`
