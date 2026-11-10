# The two icons and the television banner, drawn rather than kept as images
# someone has to find again. Change a colour or an angle here, run this, and
# every art file in the repo is written again:
#
#   powershell -File tools/make_art.ps1
#
# It writes:
#   app/src/main/res/drawable/ic_launcher_foreground.xml
#   app/src/main/res/values/ic_launcher_colors.xml
#   app/src/main/res/drawable/ic_stat_lens.xml
#   tv/src/main/res/drawable/ic_launcher_foreground.xml
#   tv/src/main/res/values/ic_launcher_colors.xml
#   tv/src/main/res/drawable-xhdpi/tv_banner.png
#
# Every one of those carries a comment saying it was drawn here. Editing them by
# hand is undone the next time this runs.

Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$inv = [System.Globalization.CultureInfo]::InvariantCulture

# --- the colours ------------------------------------------------------------

$plate = "#071A22"    # the plate both icons sit on
$screen = "#0A2129"   # the body of a screen, and the wash across the banner
$line = "#6FE9FF"     # the ring and the blades
$glass = "#0E3A4A"    # the disc the blades sit in
$ink = "#DFF6FF"      # the name in the banner
$washed = "#091E26"   # the plate with the banner's wash over it

# --- the lens, in one place -------------------------------------------------

$bladeStep = 60
$bladeSweep = 32

function N([double]$value) {
    ([Math]::Round($value, 1)).ToString($inv)
}

function PointAt([double]$cx, [double]$cy, [double]$r, [double]$degrees) {
    $a = $degrees * [Math]::PI / 180
    "$(N ($cx + $r * [Math]::Cos($a))),$(N ($cy + $r * [Math]::Sin($a)))"
}

# A circle as a path. The closing point sits a tenth of a unit short of the
# start, which is how a vector takes a full circle.
function Circle([double]$cx, [double]$cy, [double]$r) {
    $top = N ($cy - $r)
    "M$(N $cx),$top A$(N $r),$(N $r) 0 1,1 $(N ($cx - 0.1)),$top Z"
}

function Blades([double]$cx, [double]$cy, [double]$outer, [double]$inner, [int]$step, [int]$sweep) {
    $parts = @()
    for ($i = 0; $i -lt 6; $i++) {
        $from = -90 + $i * $step
        $parts += "M$(PointAt $cx $cy $outer $from) L$(PointAt $cx $cy $inner ($from + $sweep))"
    }
    $parts -join " "
}

function RoundedRect([double]$x, [double]$y, [double]$w, [double]$h, [double]$r) {
    $right = $x + $w
    $bottom = $y + $h
    "M$(N ($x + $r)),$(N $y) H$(N ($right - $r)) " +
    "A$(N $r),$(N $r) 0 0,1 $(N $right),$(N ($y + $r)) " +
    "V$(N ($bottom - $r)) A$(N $r),$(N $r) 0 0,1 $(N ($right - $r)),$(N $bottom) " +
    "H$(N ($x + $r)) A$(N $r),$(N $r) 0 0,1 $(N $x),$(N ($bottom - $r)) " +
    "V$(N ($y + $r)) A$(N $r),$(N $r) 0 0,1 $(N ($x + $r)),$(N $y) Z"
}

# The three shapes every icon is made of: the glass with its opening cut out,
# the ring, and the six blades.
#
# The radii are fractions of the ring, so the lens is the same shape at 28 and
# at 12. The strokes are given in the units they are drawn at, because a stroke
# scaled down with the rest is what turns a small lens grey.
function Lens([double]$cx, [double]$cy, [double]$radius, [double]$ringStroke, [double]$bladeStroke) {
    $opening = $radius * (10 / 28)
    $glassShape = "$(Circle $cx $cy $radius) $(Circle $cx $cy $opening)"
    $bladesShape = Blades $cx $cy ($radius * (25 / 28)) ($radius * (13 / 28)) $bladeStep $bladeSweep
    @"
<!-- The glass, with the opening cut out of it so the plate shows through. -->
<path
    android:fillColor="$glass"
    android:fillType="evenOdd"
    android:pathData="$glassShape" />

<!-- The ring around it. -->
<path
    android:fillColor="#00000000"
    android:pathData="$(Circle $cx $cy $radius)"
    android:strokeColor="$line"
    android:strokeWidth="$ringStroke" />

<!-- Six blades, each a fifth of a turn on from the one before. -->
<path
    android:fillColor="#00000000"
    android:pathData="$bladesShape"
    android:strokeColor="$line"
    android:strokeWidth="$bladeStroke"
    android:strokeLineCap="round" />
"@
}

$phoneLens = Lens 54 54 28 4 4
$tvLens = Lens 54 52 12 2.4 2.2

function Indent([string]$text, [int]$spaces) {
    $pad = " " * $spaces
    ($text.Trim("`n") -split "`n" | ForEach-Object {
        if ($_ -match '^\s*$') { "" } else { "$pad$($_.TrimEnd())" }
    }) -join "`n"
}

function Write-Art([string]$relative, [string]$text) {
    $full = Join-Path $root $relative
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $full) | Out-Null
    $trimmed = ($text.Trim("`n") -replace "`r`n", "`n") -split "`n" | ForEach-Object { $_.TrimEnd() }
    [System.IO.File]::WriteAllText(
        $full,
        ($trimmed -join "`n") + "`n",
        (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "wrote $relative"
}

# --- the phone's icon -------------------------------------------------------

$appIcon = @"
<?xml version="1.0" encoding="utf-8"?>
<!-- Drawn by tools/make_art.ps1. Edit the script, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

$(Indent $phoneLens 4)
</vector>
"@

Write-Art "app/src/main/res/drawable/ic_launcher_foreground.xml" $appIcon

$appColors = @"
<?xml version="1.0" encoding="utf-8"?>
<!-- Drawn by tools/make_art.ps1. Edit the script, not this file. -->
<resources>
    <color name="ic_launcher_background">#FF$($plate.TrimStart('#'))</color>
</resources>
"@

Write-Art "app/src/main/res/values/ic_launcher_colors.xml" $appColors

# --- the television's icon: the same lens on a screen -----------------------
#
# Everything here sits inside the 72 that a launcher masks to, corners included:
# a screen wide enough to run past that circle loses its sides when the phone
# cuts it round, and comes out as two loose lines.

$screenShape = RoundedRect 26 36 56 32 6

$tvBody = @"
<!-- A stand, so it reads as a television rather than a tablet. -->
<path
    android:fillColor="#00000000"
    android:pathData="M54,68 L54,74 M47,74 L61,74"
    android:strokeColor="$line"
    android:strokeAlpha="0.45"
    android:strokeWidth="3"
    android:strokeLineCap="round" />

<!-- The screen. -->
<path
    android:fillColor="$screen"
    android:pathData="$screenShape"
    android:strokeColor="$line"
    android:strokeWidth="3" />

$(Indent $tvLens 0)
"@

$tvIcon = @"
<?xml version="1.0" encoding="utf-8"?>
<!-- Drawn by tools/make_art.ps1. Edit the script, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

$(Indent $tvBody 4)
</vector>
"@

Write-Art "tv/src/main/res/drawable/ic_launcher_foreground.xml" $tvIcon

$tvColors = @"
<?xml version="1.0" encoding="utf-8"?>
<!-- Drawn by tools/make_art.ps1. Edit the script, not this file. -->
<resources>
    <color name="ic_launcher_background">#FF$($plate.TrimStart('#'))</color>
</resources>
"@

Write-Art "tv/src/main/res/values/ic_launcher_colors.xml" $tvColors

# --- the status bar's icon --------------------------------------------------
#
# A notification icon is one colour at 24, so the lens is flattened: the same
# ring and blades, and the opening outlined rather than filled.

$statRing = Circle 12 12 8.6
$statOpening = Circle 12 12 3.5
$statBlades = Blades 12 12 7.6 3.9 60 32

$statIcon = @"
<?xml version="1.0" encoding="utf-8"?>
<!-- Drawn by tools/make_art.ps1. Edit the script, not this file.

     The lens at 24, flattened to one colour: the system tints a notification
     icon, so the plate and the glass are left out. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="#FFFFFF">

    <path
        android:fillColor="#00000000"
        android:pathData="$statRing"
        android:strokeColor="#FFFFFF"
        android:strokeWidth="1.7" />

    <path
        android:fillColor="#00000000"
        android:pathData="$statBlades"
        android:strokeColor="#FFFFFF"
        android:strokeWidth="1.4"
        android:strokeLineCap="round" />

    <path
        android:fillColor="#00000000"
        android:pathData="$statOpening"
        android:strokeColor="#FFFFFF"
        android:strokeWidth="1.4" />
</vector>
"@

Write-Art "app/src/main/res/drawable/ic_stat_lens.xml" $statIcon

# --- the television's banner ------------------------------------------------
#
# 320 by 180 is the size the Android TV home screen asks for. The lens sits at
# the left and the name reads in one go: on a television home screen the name is
# read once.

function Hex([string]$value) {
    $h = $value.TrimStart('#')
    [System.Drawing.Color]::FromArgb(
        255,
        [Convert]::ToInt32($h.Substring(0, 2), 16),
        [Convert]::ToInt32($h.Substring(2, 2), 16),
        [Convert]::ToInt32($h.Substring(4, 2), 16))
}

$width = 320
$height = 180
$lensCx = 64.0
$lensCy = 90.0
$bannerRing = 30.0

$bitmap = New-Object System.Drawing.Bitmap($width, $height)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit

$graphics.Clear((Hex $plate))
$wash = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(153, (Hex $screen)))
$graphics.FillRectangle($wash, 0, 0, $width, $height)

$glassBrush = New-Object System.Drawing.SolidBrush((Hex $glass))
$linePen = New-Object System.Drawing.Pen((Hex $line), 4)
$linePen.StartCap = 'Round'
$linePen.EndCap = 'Round'

$graphics.FillEllipse($glassBrush, $lensCx - $bannerRing, $lensCy - $bannerRing, $bannerRing * 2, $bannerRing * 2)
$graphics.DrawEllipse($linePen, $lensCx - $bannerRing, $lensCy - $bannerRing, $bannerRing * 2, $bannerRing * 2)

for ($i = 0; $i -lt 6; $i++) {
    $from = (-90 + $i * $bladeStep) * [Math]::PI / 180
    $to = (-90 + $i * $bladeStep + $bladeSweep) * [Math]::PI / 180
    $outer = $bannerRing * (25 / 28)
    $inner = $bannerRing * (13 / 28)
    $graphics.DrawLine(
        $linePen,
        [single]($lensCx + $outer * [Math]::Cos($from)),
        [single]($lensCy + $outer * [Math]::Sin($from)),
        [single]($lensCx + $inner * [Math]::Cos($to)),
        [single]($lensCy + $inner * [Math]::Sin($to)))
}

$washerBrush = New-Object System.Drawing.SolidBrush((Hex $washed))
$opening = $bannerRing * (10 / 28)
$graphics.FillEllipse($washerBrush, $lensCx - $opening, $lensCy - $opening, $opening * 2, $opening * 2)

$font = New-Object System.Drawing.Font("Segoe UI Semibold", 40, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$inkBrush = New-Object System.Drawing.SolidBrush((Hex $ink))
$lineBrush = New-Object System.Drawing.SolidBrush((Hex $line))
$tight = [System.Drawing.StringFormat]::GenericTypographic
$corner = New-Object System.Drawing.PointF(0, 0)

$frigoWidth = $graphics.MeasureString("Frigo", $font, $corner, $tight)
$frigoHeight = $frigoWidth.Height
$space = 10.0
$nameX = 136.0
$nameY = [single]($lensCy - $frigoHeight / 2)

$graphics.DrawString("Frigo", $font, $inkBrush, $nameX, $nameY, $tight)
$graphics.DrawString("TV", $font, $lineBrush, [single]($nameX + $frigoWidth.Width + $space), $nameY, $tight)

$bannerPath = Join-Path $root "tv/src/main/res/drawable-xhdpi/tv_banner.png"
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $bannerPath) | Out-Null
$bitmap.Save($bannerPath, [System.Drawing.Imaging.ImageFormat]::Png)
Write-Host "wrote tv/src/main/res/drawable-xhdpi/tv_banner.png"

$graphics.Dispose()
$bitmap.Dispose()
$wash.Dispose()
$glassBrush.Dispose()
$washerBrush.Dispose()
$linePen.Dispose()
$inkBrush.Dispose()
$lineBrush.Dispose()
$font.Dispose()
