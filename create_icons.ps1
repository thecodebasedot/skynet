Add-Type -AssemblyName System.Drawing

function Create-Logo {
    param (
        [int]$size,
        [string]$outputPath
    )

    $bmp = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.Clear([System.Drawing.Color]::Black)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias

    $brush = [System.Drawing.Brushes]::Cyan
    
    $fontSize = [float]($size * 0.6)
    $fontStyle = [System.Drawing.FontStyle]::Bold
    $font = New-Object System.Drawing.Font("Arial", $fontSize, $fontStyle)

    $format = New-Object System.Drawing.StringFormat
    $format.Alignment = [System.Drawing.StringAlignment]::Center
    $format.LineAlignment = [System.Drawing.StringAlignment]::Center

    $g.DrawString("S", $font, $brush, ($size / 2), ($size / 2), $format)

    $bmp.Save($outputPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose()
    $bmp.Dispose()
}

Create-Logo -size 16 -outputPath "i:\turbovnc\java\com\turbovnc\vncviewer\turbovnc-16.png"
Create-Logo -size 48 -outputPath "i:\turbovnc\java\com\turbovnc\vncviewer\turbovnc-48.png"
Create-Logo -size 128 -outputPath "i:\turbovnc\java\com\turbovnc\vncviewer\turbovnc-128.png"
