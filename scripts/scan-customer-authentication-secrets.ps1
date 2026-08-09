param(
    [ValidateSet("Strong", "Suspicious")]
    [string]$Mode,
    [string]$Baseline = "c886f6f"
)

$ErrorActionPreference = "Stop"

$strongPatterns = @(
    ('A' + 'KIA[0-9A-Z]{16}'),
    ('A' + 'SIA[0-9A-Z]{16}'),
    ('A3T' + '[0-9A-Z]{16}'),
    ('github_' + 'pat_[A-Za-z0-9_]{20,}'),
    ('gh' + '[pousr]_[A-Za-z0-9_]{20,}'),
    ('gl' + 'pat-[A-Za-z0-9_-]{20,}'),
    ('xox' + '[baprs]-[A-Za-z0-9-]{20,}'),
    ('sk_' + '(?:live|test)_[A-Za-z0-9]{16,}'),
    ('A' + 'Iza[0-9A-Za-z_-]{35}'),
    ('S' + 'G\.[A-Za-z0-9_-]{16,}\.[A-Za-z0-9_-]{16,}'),
    ('npm_' + '[A-Za-z0-9]{20,}'),
    ('SK' + '[0-9a-fA-F]{32}'),
    ('-----BEGIN ' + '(?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
    ('Bearer ' + '[A-Za-z0-9._~+/=-]{20,}'),
    ('Basic ' + '[A-Za-z0-9+/=]{20,}'),
    ('eyJ' + '[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}'),
    ('(?:postgres(?:ql)?|mysql|mongodb(?:\+srv)?):' + '//[^:\s/]+:[^@\s/]+@')
)
$suspiciousPattern = '(?i)(password|passwd|pwd|secret|token|api[_-]?key|authorization|bearer|cookie|credential|private[_-]?key)'

$diff = @(git diff --unified=0 $Baseline --)
if ($LASTEXITCODE -ne 0) {
    throw "git diff failed with exit code $LASTEXITCODE"
}

$file = ""
$addedLines = 0
$strongMatches = 0
$suspiciousCounts = [ordered]@{
    deployment_placeholder = 0
    deployment_literal = 0
    production_source = 0
    test_fixture = 0
    documentation_or_spec = 0
    scanner_rules = 0
}

foreach ($line in $diff) {
    if ($line.StartsWith("+++ b/")) {
        $file = $line.Substring(6)
        continue
    }
    if (!$line.StartsWith("+") -or $line.StartsWith("+++")) {
        continue
    }

    $addedLines++
    $value = $line.Substring(1)
    foreach ($pattern in $strongPatterns) {
        if ($value -match $pattern) {
            $strongMatches++
            break
        }
    }

    if ($value -notmatch $suspiciousPattern) {
        continue
    }
    if ($file -eq "scripts/scan-customer-authentication-secrets.ps1") {
        $suspiciousCounts.scanner_rules++
    } elseif ($file -match '^(docs/|openspec/|\.superpowers/)') {
        $suspiciousCounts.documentation_or_spec++
    } elseif ($file -match '^src/test/') {
        $suspiciousCounts.test_fixture++
    } elseif ($file -match '^(deploy/|src/main/resources/)') {
        if ($value -match '\$\{[A-Z0-9_]+(?::[^}]*)?\}') {
            $suspiciousCounts.deployment_placeholder++
        } else {
            $suspiciousCounts.deployment_literal++
        }
    } else {
        $suspiciousCounts.production_source++
    }
}

if ($Mode -eq "Strong") {
    "baseline=$Baseline"
    "added_lines_scanned=$addedLines"
    "strong_rules=$($strongPatterns.Count)"
    "strong_matches=$strongMatches"
    if ($strongMatches -gt 0) {
        exit 1
    }
    exit 0
}

"baseline=$Baseline"
"added_lines_scanned=$addedLines"
"suspicious_pattern=$suspiciousPattern"
foreach ($entry in $suspiciousCounts.GetEnumerator()) {
    "$($entry.Key)=$($entry.Value)"
}
exit 0
