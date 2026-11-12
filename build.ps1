#requires -Version 5.1
param(
    [string]$AppHome = "",
    [string]$KassaHome = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

function Find-FirstExisting([string[]]$paths) {
    foreach ($path in $paths) {
        if ($path -and (Test-Path -LiteralPath $path)) { return $path }
    }
    return $null
}

if (-not $AppHome) {
    $AppHome = Find-FirstExisting @("C:\ProgramData\BIFIT\retail", "E:\bifit\BIFIT\retail")
}
if (-not $KassaHome) {
    $KassaHome = Find-FirstExisting @("C:\Program Files\BIFIT Kassa", "E:\bifit\BIFIT Kassa")
}
if (-not $AppHome) { throw "AppHome was not found. Pass -AppHome explicitly." }
if (-not $KassaHome) { throw "KassaHome was not found. Pass -KassaHome explicitly." }

$runtime = Join-Path $KassaHome "runtime"
$plugins = Join-Path $AppHome "libs\plugins"
$retailJar = Join-Path $AppHome "libs\retail.jar"
$rrJar = Join-Path $plugins "rr-electro.jar"
$inpasJar = Join-Path $plugins "inpas-win.jar"

foreach ($path in @($runtime, $plugins, $retailJar, $rrJar, $inpasJar)) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Required path was not found: $path" }
}

$jdk = Get-ChildItem "C:\Program Files\Java" -Directory -ErrorAction SilentlyContinue |
       Where-Object { Test-Path (Join-Path $_.FullName "bin\javac.exe") } |
       Sort-Object Name -Descending | Select-Object -First 1
if (-not $jdk) { throw "JDK 17+ was not found." }

$javac = Join-Path $jdk.FullName "bin\javac.exe"
$jarTool = Join-Path $jdk.FullName "bin\jar.exe"
$java = Join-Path $jdk.FullName "bin\java.exe"

$build = Join-Path $root "build"
$classes = Join-Path $build "classes"
$testClasses = Join-Path $build "test-classes"
$stage = Join-Path $build "stage"
$dist = Join-Path $root "dist"

Remove-Item -Recurse -Force $build -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $classes, $testClasses, $stage, $dist | Out-Null

$classpath = "$retailJar;$rrJar;$inpasJar"
$sources = Get-ChildItem (Join-Path $root "src") -Recurse -Filter *.java |
           ForEach-Object { $_.FullName }

Write-Host "[1/5] Compiling for Java 17..."
& $javac --system $runtime -source 17 -target 17 "-Xlint:all,-options" `
    -cp $classpath -encoding UTF-8 -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw "Plugin compilation failed" }

Write-Host "[2/5] Packaging the plugin..."
Copy-Item -Recurse -Force (Join-Path $classes "*") $stage
Copy-Item -Recurse -Force (Join-Path $root "res\*") $stage
$outJar = Join-Path $dist "pax-fix.jar"
Remove-Item -Force -LiteralPath $outJar -ErrorAction SilentlyContinue
& $jarTool --create --file $outJar -C $stage .
if ($LASTEXITCODE -ne 0) { throw "Packaging failed" }

Write-Host "[3/5] Compiling integration and smoke tests..."
$testSources = Get-ChildItem (Join-Path $root "tests") -Recurse -Filter *.java |
               ForEach-Object { $_.FullName }
& $javac --system $runtime -source 17 -target 17 -Xlint:-options `
    -cp "$outJar;$classes;$classpath" -encoding UTF-8 -d $testClasses @testSources
if ($LASTEXITCODE -ne 0) { throw "Test compilation failed" }

Write-Host "[4/5] Running the real BIFIT slip-conversion path and ServiceLoader tests..."
& $java "-Dapp.home=$AppHome" -cp "$testClasses;$outJar;$classpath" `
    com.example.cashdesk.paxfix.kkm.SlipPipelineIntegrationTest
if ($LASTEXITCODE -ne 0) { throw "Slip pipeline integration test failed" }
& $java "-Dapp.home=$AppHome" -cp "$testClasses;$outJar;$classpath" PluginSmokeTest
if ($LASTEXITCODE -ne 0) { throw "ServiceLoader smoke test failed" }

Write-Host "[5/5] Checking class-file versions..."
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($outJar)
try {
    $classEntries = $archive.Entries | Where-Object { $_.FullName -like "*.class" }
    foreach ($entry in $classEntries) {
        $stream = $entry.Open()
        try {
            $header = New-Object byte[] 8
            if ($stream.Read($header, 0, 8) -ne 8) { throw "Damaged class file: $($entry.FullName)" }
            $major = $header[6] * 256 + $header[7]
            if ($major -ne 61) { throw "$($entry.FullName): major=$major, expected 61 (Java 17)" }
        } finally {
            $stream.Dispose()
        }
    }
} finally {
    $archive.Dispose()
}

Write-Host "[5/5] Ready: $outJar"
