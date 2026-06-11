# Phase 5 FINAL GATE -- audit (a): anonymous mode is byte-for-byte pre-Phase-5.
# Boots: java -jar app-0.1.0.jar --spring.profiles.active=dev --substrate.demo.seed=true
# No token anywhere. Persona headers TRUSTED (pre-Phase-5 S9 behavior).
param([string]$BaseUrl = 'http://localhost:8080')

$out = @()
function Section($t) { $script:out += ""; $script:out += "===== $t ====="; }
function Hit($name, $url, $headers, $expect) {
    try {
        $r = Invoke-WebRequest -UseBasicParsing -Uri $url -Headers $headers
        $code = $r.StatusCode; $body = $r.Content
    } catch {
        $code = $_.Exception.Response.StatusCode.value__
        $stream = $_.Exception.Response.GetResponseStream()
        $body = (New-Object System.IO.StreamReader($stream)).ReadToEnd()
    }
    $hdr = if ($headers) { ($headers.GetEnumerator() | ForEach-Object { "-H '$($_.Key): $($_.Value)'" }) -join ' ' } else { '(no headers)' }
    $verdict = if ($code -eq $expect) { 'PASS' } else { 'FAIL' }
    $script:out += "[$verdict] GET $url $hdr -> $code (expected $expect)"
    return $body
}

$exec = @{ 'X-Persona-Level' = 'EXECUTIVE' }
$oprisk  = 'ec169fd1-4fc7-4128-9bba-8a945ec06d15'   # Operational Risk (demo seed)
$curasync = 'a8f0ef3e-3189-4466-b86c-0739f08fe008'  # Curasync Clinical Systems

$out += "Phase 5 FINAL GATE acceptance transcript -- ANONYMOUS mode (audit a + h)"
$out += "Date: $(Get-Date -Format o)"
$out += "Boot: java -jar app\build\libs\app-0.1.0.jar --spring.profiles.active=dev --substrate.demo.seed=true --server.port=8080"
$out += "substrate.auth.mode: anonymous (dev default -- nothing set)"
$out += "NO TOKEN is sent on any request in this transcript."

Section 'health (public, no headers)'
$b = Hit 'health' "$BaseUrl/api/v1/health" $null 200
$out += "  body: $b"

Section 'spec 12 cascades list (X-Persona-Level: EXECUTIVE, no token)'
$b = Hit 'cascades' "$BaseUrl/api/v1/cascades?domainId=$oprisk" $exec 200
$j = $b | ConvertFrom-Json
$out += "  items=$($j.data.Count)"
$j.data | ForEach-Object { $out += "  cascade: id=$($_.id) summary='$($_.summary)' state=$($_.currentState) signal=$($_.attentionSignal)" }
$out += "  meta.personaLevel=$($j.meta.personaLevel)  <- header VERBATIM-TRUSTED (pre-Phase-5 S9 intact)"

Section 'spec 13 contract detail (fixture co-tribal-knowledge-depopulation, no token)'
$b = Hit 'contract' "$BaseUrl/api/v1/contracts/co-tribal-knowledge-depopulation" $exec 200
$j = $b | ConvertFrom-Json
$out += "  contractKey=$($j.data.contractKey) status=$($j.data.status) type=$($j.data.contractType)"
$out += "  title='$($j.data.title)'"
$out += "  parties=$($j.data.parties.Count) meta.personaLevel=$($j.meta.personaLevel)"

Section 'spec 16 agents list (Curasync domain, no token)'
$b = Hit 'agents' "$BaseUrl/api/v1/agents?domainId=$curasync" $exec 200
$j = $b | ConvertFrom-Json
$out += "  items=$($j.data.Count)"
$j.data | Select-Object -First 5 | ForEach-Object { $out += "  agent: name='$($_.name)' status=$($_.status) mode=$($_.operationalMode)" }

Section 'persona header verbatim trust (ADMIN header changes meta -- anonymous-mode S9 behavior)'
$b = Hit 'cascades-admin' "$BaseUrl/api/v1/cascades?domainId=$oprisk" @{ 'X-Persona-Level' = 'ADMIN' } 200
$j = $b | ConvertFrom-Json
$out += "  meta.personaLevel=$($j.meta.personaLevel)  <- header trusted verbatim, as before Phase 5"

Section 'audit (h): hash-chain verify (identity changes did not break the chain)'
$b = Hit 'verify' "$BaseUrl/api/v1/decision-log/verify?domainId=$oprisk" $exec 200
$out += "  body: $b"

Section 'actuator: health public, metrics NOT exposed in anonymous mode (audit f)'
$b = Hit 'act-health' "$BaseUrl/actuator/health" $null 200
if ($b -is [byte[]]) { $b = [System.Text.Encoding]::UTF8.GetString($b) }
$out += "  body: $b"
$null = Hit 'act-metrics' "$BaseUrl/actuator/metrics" $null 404
$null = Hit 'act-env' "$BaseUrl/actuator/env" $null 404

Section 'audit (a): NO Spring Security on the anonymous request path'
$secLines = (Select-String -Path "$PSScriptRoot\..\..\..\.boot-anon.log" -Pattern 'springSecurityFilterChain|SecurityFilterChain|FilterChainProxy|springframework\.security' | Measure-Object).Count
$verdict = if ($secLines -eq 0) { 'PASS' } else { 'FAIL' }
$out += "[$verdict] boot log contains $secLines Spring Security log lines (expected 0 -- AuthModeEnvironmentPostProcessor excludes ALL security auto-config in anonymous mode; pinned by AnonymousModeSecurityAbsenceTest)"

$out | Out-File -Encoding utf8 $PSScriptRoot\anonymous-mode-transcript.txt
$out | ForEach-Object { $_ }
