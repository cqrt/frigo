# Regenerates the three frames the mock server cycles through.
# Run from this folder:  powershell -File make_frames.ps1
Add-Type -AssemblyName System.Drawing

$colours = @(@(30, 60, 110), @(140, 40, 60), @(30, 110, 60))
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

for ($i = 0; $i -lt 3; $i++) {
    $bitmap = New-Object System.Drawing.Bitmap 800, 450
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $colour = $colours[$i]

    $graphics.Clear([System.Drawing.Color]::FromArgb(12, 18, 28))
    $brush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb($colour[0], $colour[1], $colour[2]))
    $graphics.FillRectangle($brush, 60 + ($i * 200), 120, 260, 200)

    $font = New-Object System.Drawing.Font('Consolas', 48, [System.Drawing.FontStyle]::Bold)
    $graphics.DrawString("FRAME $i", $font, [System.Drawing.Brushes]::White, 60, 360)

    $bitmap.Save("$here\frame$i.jpg", [System.Drawing.Imaging.ImageFormat]::Jpeg)
    $graphics.Dispose()
    $bitmap.Dispose()
}
