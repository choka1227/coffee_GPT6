$ErrorActionPreference = 'Stop'
Push-Location "$PSScriptRoot/.."
try {
    Push-Location frontend
    try {
        npm ci --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw 'npm ci failed' }
        npm run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed' }
    } finally { Pop-Location }
    Push-Location backend
    try {
        .\mvnw.cmd -B -ntp verify
        if ($LASTEXITCODE -ne 0) { throw 'Backend verification failed' }
    } finally { Pop-Location }
} finally { Pop-Location }
