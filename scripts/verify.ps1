param(
    [string]$Java = 'java',
    [string]$MavenRepository = '.maven-repository',
    [string]$ObjectRepositoryJar = ''
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    if (-not (Test-Path 'target/test-classes')) {
        throw 'Ejecuta mvn test-compile antes de las verificaciones.'
    }
    $paths = @((Resolve-Path 'target/classes').Path, (Resolve-Path 'target/test-classes').Path)
    $paths += @(Get-ChildItem -LiteralPath (Join-Path $MavenRepository 'org/netbeans') -Filter '*.jar' -Recurse | ForEach-Object FullName)
    $urls = foreach ($entry in $paths) {
        $absolute = [IO.Path]::GetFullPath($entry)
        if ([IO.Directory]::Exists($absolute)) { $absolute += [IO.Path]::DirectorySeparatorChar }
        ([Uri]::new($absolute)).AbsoluteUri
    }
    # ASCII URI encoding plus manifest continuation lines avoids Windows command
    # length and native argument-file encoding limits.
    $classpathLine = 'Class-Path: ' + ($urls -join ' ')
    $manifest = "Manifest-Version: 1.0`r`n"
    while ($classpathLine.Length -gt 70) {
        $manifest += $classpathLine.Substring(0, 70) + "`r`n"
        $classpathLine = ' ' + $classpathLine.Substring(70)
    }
    $manifest += $classpathLine + "`r`n`r`n"
    $launcher = Join-Path $projectRoot 'target/check-classpath.jar'
    $stream = [IO.File]::Open($launcher, [IO.FileMode]::Create)
    try {
        $zip = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create, $true)
        try {
            $entry = $zip.CreateEntry('META-INF/MANIFEST.MF')
            $writer = [IO.StreamWriter]::new($entry.Open(), [Text.UTF8Encoding]::new($false))
            try { $writer.Write($manifest) } finally { $writer.Dispose() }
        } finally { $zip.Dispose() }
    } finally { $stream.Dispose() }
    $checks = @(
        'co.edu.cecar.objectrepositorymanager.worker.RepositoryWorkerIntegrationTest',
        'co.edu.cecar.objectrepositorymanager.RepositoryClientIntegrationTest',
        'co.edu.cecar.objectrepositorymanager.RepositoryPanelCheck'
    )
    foreach ($check in $checks) {
        $arguments = @('-Djava.awt.headless=true', '-cp', $launcher, $check)
        if ($check.EndsWith('RepositoryWorkerIntegrationTest') -and $ObjectRepositoryJar) {
            $arguments += [IO.Path]::GetFullPath($ObjectRepositoryJar)
        }
        & $Java @arguments
        if ($LASTEXITCODE -ne 0) { throw "Falló $check (código $LASTEXITCODE)." }
    }
} finally { Pop-Location }
