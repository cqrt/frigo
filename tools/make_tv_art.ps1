# The television app's banner and launcher icon, drawn rather than kept as
# images someone has to find again. Run this after changing the colours in
# app/src/main/java/nz/mckenzie/frigo/ui/theme/Theme.kt:
#
#   powershell -File tools/make_tv_art.ps1
#
# The banner is 320x180, which is the size the Android TV home screen asks for.
# The icon is 512, and Android scales it down.

Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$res = Join-Path $root "tv/src/main/res"
New-Item -ItemType Directory -Force -Path (Join-Path $res "drawable-xhdpi") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $res "mipmap-xxxhdpi") | Out-Null

$night = [System.Drawing.Color]::FromArgb(255, 11, 18, 32)
$deep = [System.Drawing.Color]::FromArgb(255, 15, 24, 38)
$ink = [System.Drawing.Color]::FromArgb(255, 228, 234, 242)
$light = [System.Drawing.Color]::FromArgb(255, 143, 194, 255)
$line = [System.Drawing.Color]::FromArgb(255, 59, 72, 89)

function New-Rounded([System.Drawing.RectangleF]$rect, [single]$radius) {
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $radius * 2
    $path.AddArc($rect.X, $rect.Y, $d, $d, 180, 90)
    $path.AddArc($rect.Right - $d, $rect.Y, $d, $d, 270, 90)
    $path.AddArc($rect.Right - $d, $rect.Bottom - $d, $d, $d, 0, 90)
    $path.AddArc($rect.X, $rect.Bottom - $d, $d, $d, 90, 90)
    $path.CloseFigure()
    return $path
}

function New-Canvas([int]$width, [int]$height) {
    $bitmap = New-Object System.Drawing.Bitmap($width, $height)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $graphics.Clear($night)
    return @($bitmap, $graphics)
}

function Draw-Television($graphics, [single]$x, [single]$y, [single]$width, [single]$height) {
    $body = New-Object System.Drawing.RectangleF($x, $y, $width, $height)
    $path = New-Rounded $body ($height * 0.14)
    $brush = New-Object System.Drawing.SolidBrush($deep)
    $pen = New-Object System.Drawing.Pen($light, [Math]::Max(2, $width * 0.014))
    $graphics.FillPath($brush, $path)
    $graphics.DrawPath($pen, $path)

    # The lens: the app is for looking at cameras, not for television.
    $lens = $width * 0.28
    $lensX = $x + ($width - $lens) / 2
    $lensY = $y + ($height - $lens) / 2
    $lensBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 18, 57, 92))
    $graphics.FillEllipse($lensBrush, $lensX, $lensY, $lens, $lens)
    $graphics.DrawEllipse($pen, $lensX, $lensY, $lens, $lens)
    $glint = $lens * 0.28
    $glintBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(230, 212, 228, 255))
    $graphics.FillEllipse($glintBrush, $lensX + $lens * 0.18, $lensY + $lens * 0.16, $glint, $glint)

    # A stand, so it reads as a television rather than a tablet.
    $standPen = New-Object System.Drawing.Pen($line, [Math]::Max(2, $width * 0.018))
    $standPen.StartCap = 'Round'
    $standPen.EndCap = 'Round'
    $graphics.DrawLine($standPen, $x + $width * 0.36, $y + $height + $height * 0.10, $x + $width * 0.64, $y + $height + $height * 0.10)
    $graphics.DrawLine($standPen, $x + $width * 0.5, $y + $height, $x + $width * 0.5, $y + $height + $height * 0.10)
}

# --- the banner the television home screen shows -------------------------

$width = 320
$height = 180
$art = New-Canvas $width $height
$bitmap = $art[0]
$graphics = $art[1]

$glow = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    (New-Object System.Drawing.Rectangle(0, 0, $width, $height)),
    [System.Drawing.Color]::FromArgb(255, 11, 18, 32),
    [System.Drawing.Color]::FromArgb(255, 18, 32, 52),
    90
)
$graphics.FillRectangle($glow, 0, 0, $width, $height)

Draw-Television $graphics 22 34 104 58.5

$titleFont = New-Object System.Drawing.Font("Segoe UI Semibold", 34, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$smallFont = New-Object System.Drawing.Font("Segoe UI", 14, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$titleBrush = New-Object System.Drawing.SolidBrush($ink)
$lightBrush = New-Object System.Drawing.SolidBrush($light)
$mutedBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 175, 186, 201))

$graphics.DrawString("Frigo", $titleFont, $titleBrush, 140, 54)
$graphics.DrawString("TV", $smallFont, $lightBrush, 141, 96)
$graphics.DrawString("your cameras", $smallFont, $mutedBrush, 141, 116)

$bannerPath = Join-Path $res "drawable-xhdpi/tv_banner.png"
$bitmap.Save($bannerPath, [System.Drawing.Imaging.ImageFormat]::Png)
$graphics.Dispose()
$bitmap.Dispose()

# --- the launcher icon ----------------------------------------------------

$size = 512
$art = New-Canvas $size $size
$bitmap = $art[0]
$graphics = $art[1]

$plate = New-Rounded (New-Object System.Drawing.RectangleF(0, 0, $size, $size)) ($size * 0.22)
$plateBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    (New-Object System.Drawing.Rectangle(0, 0, $size, $size)),
    [System.Drawing.Color]::FromArgb(255, 18, 32, 52),
    [System.Drawing.Color]::FromArgb(255, 11, 18, 32),
    90
)
$graphics.FillPath($plateBrush, $plate)

Draw-Television $graphics 96 128 320 180

$iconPath = Join-Path $res "mipmap-xxxhdpi/ic_launcher.png"
$bitmap.Save($iconPath, [System.Drawing.Imaging.ImageFormat]::Png)
$graphics.Dispose()
$bitmap.Dispose()

Write-Host "Wrote $bannerPath and $iconPath"
