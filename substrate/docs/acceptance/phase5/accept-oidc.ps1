# Phase 5 FINAL GATE -- audit (b): oidc mode severs S9.
# Boot: java -jar app-0.1.0.jar --spring.profiles.active=dev --substrate.demo.seed=true
#   --server.port=8081 --substrate.auth.mode=oidc
#   --substrate.auth.oidc.jwk-set-uri=http://127.0.0.1:8099/jwks.json   (local static JWKS, python http.server)
#   --substrate.auth.oidc.claims.roles=realm_access.roles
#   --substrate.auth.oidc.persona.role-map.substrate-admin=ADMIN
#   --substrate.auth.oidc.persona.role-map.substrate-operator=L3_ANALYST
# Tokens are RS256 JWTs minted locally (mint_jwt.py) against that JWKS. No IdP, no Docker, no cloud.
param([string]$BaseUrl = 'http://localhost:8081')

$out = @()
function Section($t) { $script:out += ""; $script:out += "===== $t ====="; }
function Hit($method, $url, $headers, $expect) {
    try {
        $r = Invoke-WebRequest -UseBasicParsing -Method $method -Uri $url -Headers $headers
        $code = $r.StatusCode; $body = $r.Content
    } catch {
        $resp = $_.Exception.Response
        if ($null -eq $resp) { $code = 'NO-RESPONSE'; $body = $_.Exception.Message }
        else {
            $code = $resp.StatusCode.value__
            $stream = $resp.GetResponseStream()
            $body = (New-Object System.IO.StreamReader($stream)).ReadToEnd()
        }
    }
    if ($body -is [byte[]]) { $body = [System.Text.Encoding]::UTF8.GetString($body) }
    $hdr = if ($headers) { ($headers.GetEnumerator() | ForEach-Object { $v = $_.Value; if ($_.Key -eq 'Authorization') { $v = $v.Substring(0, 24) + '...' }; "-H '$($_.Key): $v'" }) -join ' ' } else { '(no headers)' }
    $verdict = if ($code -eq $expect) { 'PASS' } else { 'FAIL' }
    $script:out += "[$verdict] $method $url $hdr -> $code (expected $expect)"
    return $body
}

$adminJwt = (Get-Content "$PSScriptRoot\admin.jwt" -Raw).Trim()
$operatorJwt = (Get-Content "$PSScriptRoot\operator.jwt" -Raw).Trim()
$subs = Get-Content "$PSScriptRoot\subs.json" -Raw | ConvertFrom-Json

$out += "Phase 5 FINAL GATE acceptance transcript -- OIDC mode (audit b + f + h)"
$out += "Date: $(Get-Date -Format o)"
$out += "Issuer: NONE (air-gapped jwk-set-uri lane) -- static JWKS at http://127.0.0.1:8099/jwks.json"
$out += "admin token: sub=$($subs.admin) realm_access.roles=[substrate-admin]"
$out += "operator token: sub=$($subs.operator) realm_access.roles=[substrate-operator]"

Section 'unauthenticated API call -> 401; health stays public'
$b = Hit GET "$BaseUrl/api/v1/domains" $null 401
$null = Hit GET "$BaseUrl/api/v1/health" $null 200
$b = Hit GET "$BaseUrl/actuator/health" $null 200
$out += "  actuator health body: $b"

Section 'unauthenticated + spoofed X-Persona-Level: ADMIN header -> STILL 401 (S9 severed)'
$null = Hit GET "$BaseUrl/api/v1/domains" @{ 'X-Persona-Level' = 'ADMIN' } 401

Section 'verified ADMIN token + spoofed X-Persona-Level: EXECUTIVE -> persona from CLAIMS, header ignored'
$auth = @{ 'Authorization' = "Bearer $adminJwt"; 'X-Persona-Level' = 'EXECUTIVE' }
$b = Hit GET "$BaseUrl/api/v1/domains" $auth 200
$j = $b | ConvertFrom-Json
$domainId = $j.data[0].id
$verdict = if ($j.meta.personaLevel -eq 'ADMIN') { 'PASS' } else { 'FAIL' }
$out += "[$verdict] meta.personaLevel=$($j.meta.personaLevel) (expected ADMIN from substrate-admin role; spoofed EXECUTIVE header inert)"

Section 'verified OPERATOR token + spoofed X-Persona-Level: ADMIN -> persona from CLAIMS (L3_ANALYST)'
$auth = @{ 'Authorization' = "Bearer $operatorJwt"; 'X-Persona-Level' = 'ADMIN' }
$b = Hit GET "$BaseUrl/api/v1/domains" $auth 200
$j = $b | ConvertFrom-Json
$verdict = if ($j.meta.personaLevel -eq 'L3_ANALYST') { 'PASS' } else { 'FAIL' }
$out += "[$verdict] meta.personaLevel=$($j.meta.personaLevel) (expected L3_ANALYST from substrate-operator role; spoofed ADMIN header inert)"

Section 'audit (f) actuator OIDC ladder: 401 unauthenticated / 403 non-admin / 200 admin'
$null = Hit GET "$BaseUrl/actuator/metrics" $null 401
$null = Hit GET "$BaseUrl/actuator/metrics" @{ 'Authorization' = "Bearer $operatorJwt" } 403
$b = Hit GET "$BaseUrl/actuator/metrics" @{ 'Authorization' = "Bearer $adminJwt" } 200
$j = $b | ConvertFrom-Json
$substrateMeters = @($j.names | Where-Object { $_ -like 'substrate*' })
$out += "  substrate_* meters visible to admin: $($substrateMeters.Count) (e.g. $(($substrateMeters | Select-Object -First 4) -join ', '))"

Section 'MCP: unauthenticated /mcp -> 401 even with spoofed X-MCP-Principal (ADR-002 D8)'
$null = Hit POST "$BaseUrl/mcp" @{ 'X-MCP-Principal' = 'demo-agent' } 401

Section 'SSE: access_token query param accepted ONLY on stream paths (RFC 6750 s2.3)'
# On the configured stream path the token must clear AUTH (anything but 401 proves resolution).
try {
    $req = [System.Net.HttpWebRequest]::Create("$BaseUrl/api/v1/chat/stream?access_token=$adminJwt&message=hi")
    $req.Method = 'GET'; $req.Timeout = 8000; $req.ReadWriteTimeout = 8000
    $resp = $req.GetResponse(); $code = [int]$resp.StatusCode; $resp.Close()
} catch [System.Net.WebException] {
    if ($null -ne $_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode } else { $code = "TIMEOUT/STREAM-OPEN (auth cleared)" }
}
$verdict = if ("$code" -ne '401') { 'PASS' } else { 'FAIL' }
$out += "[$verdict] GET /api/v1/chat/stream?access_token=<admin> -> $code (expected anything but 401: token resolved from query on the SSE path)"
# Off the SSE path the same query param must NOT authenticate -> 401.
$null = Hit GET "$BaseUrl/api/v1/domains?access_token=$adminJwt" $null 401

Section 'audit (h): hash-chain verify green under oidc identity'
$b = Hit GET "$BaseUrl/api/v1/decision-log/verify?domainId=$domainId" @{ 'Authorization' = "Bearer $adminJwt" } 200
$out += "  body: $b"

Section 'WARN evidence: X-Persona-* headers ignored with WARN in the oidc boot log'
$warnLines = Select-String -Path "$PSScriptRoot\..\..\..\.boot-oidc.log" -Pattern 'Ignoring spoofable' | ForEach-Object { $_.Line }
$verdict = if ($warnLines.Count -gt 0) { 'PASS' } else { 'FAIL' }
$out += "[$verdict] $($warnLines.Count) WARN line(s) found:"
$warnLines | Select-Object -First 3 | ForEach-Object { $out += "  $($_.Substring(0, [Math]::Min(400, $_.Length)))" }

$out | Out-File -Encoding utf8 $PSScriptRoot\oidc-mode-transcript.txt
$out | ForEach-Object { $_ }
