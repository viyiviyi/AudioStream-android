#requires -Version 5.1
<#
  构建 AudioStream 的 Android 端 APK。

  == 版本号 ==
  唯一来源是 version.properties（versionCode + versionName），这里与 build.gradle 都从它读。
  默认每次打包把 patch 与 versionCode 各推进一格——每次打包就是一个新版本，产物名带版本号，
  不会像以前那样互相覆盖。

    -Bump patch|minor|major   指定推进幅度（默认 patch）
    -Bump none                不推进，用当前版本反复构建（调试时用）
    -Version 1.2.0            直接指定版本名（versionCode 仍 +1，见下）

  为什么 versionCode 一定要跟着涨：Android 只认它。新包的 versionCode 不比机器上已装的那个大，
  系统就以「版本号不比已安装的新」为由拒装，而这句提示完全看不出与版本号有关。
  这也是 -Version 时仍然 +1 的原因。

  构建失败会把版本号**回滚**：号用掉了却没有对应的包，下次再打包就会出现跳号，
  事后没人能说清那个号去哪了。

  == 用法 ==
    powershell -File tools\build.ps1                   # release，patch+1，含单元测试
    powershell -File tools\build.ps1 -Bump minor
    powershell -File tools\build.ps1 -Bump none
    powershell -File tools\build.ps1 -Version 2.0.0
    powershell -File tools\build.ps1 -Variant debug
    powershell -File tools\build.ps1 -SkipTest         # 只打包
    powershell -File tools\build.ps1 -Clean
    powershell -File tools\build.ps1 -Install          # 顺手装到唯一在线的那台设备
    powershell -File tools\build.ps1 -Publish          # 顺带拷到 X:\apks

  产物：dist\audiostream-android-<版本>-<variant>.apk
#>
param(
  [ValidateSet('release', 'debug')]
  [string]$Variant = 'release',
  [ValidateSet('patch', 'minor', 'major', 'none')]
  [string]$Bump = 'patch',
  [string]$Version,
  [switch]$Clean,
  [switch]$SkipTest,
  [switch]$Install,
  [switch]$Publish,
  [string]$PublishDir = 'X:\apks'
)
$ErrorActionPreference = 'Stop'

# Gradle/Javac 是按 UTF-8 输出诊断信息的，控制台默认按 GBK 解会变成乱码
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$root        = Split-Path -Parent $PSScriptRoot
$cache       = Join-Path $root 'tools-cache'
$versionFile = Join-Path $root 'version.properties'

# ---------------- 版本号 ----------------

function Read-AppVersion([string]$path) {
  if (-not (Test-Path -LiteralPath $path)) { throw "找不到版本号文件：$path" }
  $map = @{}
  foreach ($line in Get-Content -LiteralPath $path) {
    $t = $line.Trim()
    if ($t.Length -eq 0 -or $t.StartsWith('#') -or ($t -notmatch '=')) { continue }
    $kv = $t -split '=', 2
    $map[$kv[0].Trim()] = $kv[1].Trim()
  }
  if (-not $map.ContainsKey('versionCode') -or -not $map.ContainsKey('versionName')) {
    throw "$path 里缺少 versionCode 或 versionName"
  }
  [PSCustomObject]@{ Code = [int]$map['versionCode']; Name = [string]$map['versionName'] }
}

function Write-AppVersion([string]$path, [int]$code, [string]$name) {
  $text = @"
# 版本号的唯一来源。
#
# 不要手改这个文件——用 tools\build.ps1：
#   powershell -File tools\build.ps1                 # patch 与 versionCode 各 +1
#   powershell -File tools\build.ps1 -Bump minor
#   powershell -File tools\build.ps1 -Bump none      # 反复构建同一个版本
#   powershell -File tools\build.ps1 -Version 2.0.0
#
# 为什么要用脚本改：Android 要求 versionCode 单调递增，否则新包装不到已装旧版的机器上
# （系统只回一句「版本号不比已安装的新」）。手改容易只改 versionName 忘了 versionCode。
versionCode=$code
versionName=$name
"@
  # 不带 BOM：带 BOM 时 Properties.load 会把第一个键读成 "\uFEFFversionCode"，于是报「缺少 versionCode」
  $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
  [System.IO.File]::WriteAllText($path, $text, $utf8NoBom)
}

function Step-Semver([string]$name, [string]$part) {
  $p = @($name.Split('.'))
  while ($p.Count -lt 3) { $p += '0' }
  $major = 0; $minor = 0; $patch = 0
  [void][int]::TryParse($p[0], [ref]$major)
  [void][int]::TryParse($p[1], [ref]$minor)
  [void][int]::TryParse($p[2], [ref]$patch)
  switch ($part) {
    'major' { $major++; $minor = 0; $patch = 0 }
    'minor' { $minor++; $patch = 0 }
    default { $patch++ }
  }
  return "$major.$minor.$patch"
}

if (-not (Test-Path (Join-Path $cache 'jdk17\bin\java.exe'))) {
  throw "还没准备构建环境，先跑： powershell -File tools\setup-toolchain.ps1"
}

Write-Host '== 1/5 版本号 =='
$current = Read-AppVersion $versionFile
$newCode = $current.Code
$newName = $current.Name
$bumped  = $false

if ($Version) {
  $newName = $Version.Trim()
  if ($newName -notmatch '^\d+(\.\d+){0,2}$') { throw "版本名格式不对：$Version（期望 1.2 或 1.2.3）" }
  $newCode = $current.Code + 1
  $bumped  = $true
} elseif ($Bump -ne 'none') {
  $newName = Step-Semver $current.Name $Bump
  $newCode = $current.Code + 1
  $bumped  = $true
}

if ($bumped) {
  Write-AppVersion $versionFile $newCode $newName
  # 日志里说清这次是怎么涨的：显式指定时 Bump 还带着默认值 patch，照它打出来会写成 [patch]，
  # 让人以为那次是自动涨的
  $how = if ($Version) { "指定 $Version" } else { $Bump }
  Write-Host ("  {0} ({1})  ->  {2} ({3})   [{4}]" -f $current.Name, $current.Code, $newName, $newCode, $how)
} else {
  Write-Host ("  保持 {0} ({1})" -f $newName, $newCode)
}

# ---------------- 环境 ----------------

# 环境变量：Gradle 用这套 JDK / SDK；缓存也放在项目里，方便复现和清理
$env:JAVA_HOME        = Join-Path $cache 'jdk17'
$env:ANDROID_HOME     = Join-Path $cache 'android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $cache 'gradle-home'
$env:PATH             = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"

Write-Host '== 2/5 准备签名证书 =='
$ks = Join-Path $cache 'audiostream.keystore'
if (-not (Test-Path $ks)) {
  # 证书落在 tools-cache（目录联接指向工作区共享的那份），密码只用于自签名 sideload，
  # 不当成秘密：谁有源码谁都能重新生成一份，重要的是同一台机器上多次构建签名一致。
  & (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -genkeypair -v `
    -keystore $ks -alias audiostream -keyalg RSA -keysize 2048 -validity 10000 `
    -storepass audiostream2024 -keypass audiostream2024 `
    -dname 'CN=AudioStream, OU=Android, O=Local, L=Local, ST=Local, C=CN'
  if ($LASTEXITCODE -ne 0) { throw '生成签名证书失败' }
  Write-Host "  已生成 $ks"
} else {
  Write-Host "  复用已有证书 $ks"
}

# ---------------- 构建 ----------------

Write-Host '== 3/5 Gradle 构建 =='
$gradle = Join-Path $cache 'gradle-8.7\bin\gradle.bat'
$tasks = @()
if ($Clean) { $tasks += 'clean' }
if (-not $SkipTest) { $tasks += ':app:testDebugUnitTest' }
$assemble = "assemble$($Variant.Substring(0,1).ToUpper() + $Variant.Substring(1))"
$tasks += ":app:$assemble"

& $gradle -p $root --no-daemon --console=plain @tasks
$buildFailed = ($LASTEXITCODE -ne 0)

if ($buildFailed -and $bumped) {
  # 回滚：号用掉了却没有对应的包，下次会跳号，事后没人说得清那个号去哪了
  Write-AppVersion $versionFile $current.Code $current.Name
  Write-Host ("  构建失败，版本号已回滚到 {0} ({1})" -f $current.Name, $current.Code)
}
if ($buildFailed) { throw "Gradle 构建失败（$($tasks -join ' ')）" }

# ---------------- 产物 ----------------

Write-Host '== 4/5 收集产物 =='
$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null

$apkDir = Join-Path $root "app\build\outputs\apk\$Variant"
$apk = Get-ChildItem $apkDir -Filter '*.apk' -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $apk) { throw "没找到 APK：$apkDir" }

$out = Join-Path $dist "audiostream-android-$newName-$Variant.apk"
Copy-Item $apk.FullName $out -Force
$size = [Math]::Round((Get-Item $out).Length / 1KB, 1)
Write-Host ("  APK: {0}  ({1} KB)" -f $out, $size)

# ---------------- 收尾 ----------------

if ($Publish) {
  Write-Host "== 5/5 发布到 $PublishDir =="
  if (-not (Test-Path $PublishDir)) {
    # X 盘是网络盘，所在主机不在线时这里会失败。不静默跳过：包已经打好了，
    # 用户需要知道它没被发布出去，以及它现在在哪个本地路径。
    throw "发布目录不可达：$PublishDir（APK 已打好，在 $out）"
  }
  $pub = Join-Path $PublishDir "audiostream-android-$newName-$Variant.apk"
  Copy-Item $out $pub -Force
  Write-Host ("  已发布 {0}" -f $pub)
}

if ($Install) {
  Write-Host '== 5/5 安装到设备 =='
  $adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
  $devices = & $adb devices | Select-String -Pattern '\sdevice$' | ForEach-Object { ($_ -split '\s+')[0] }
  if (-not $devices) { throw '没有在线设备，先连上手机或起一个模拟器' }
  if ($devices.Count -gt 1) { throw "有多个设备在线，请自己指定：`n  $adb -s <序列号> install -r `"$out`"" }
  & $adb -s $devices[0] install -r $out
  if ($LASTEXITCODE -ne 0) { throw '安装失败' }
  Write-Host ("  已装到 {0}" -f $devices[0])
  exit 0
}

Write-Host '== 5/5 完成 =='
Write-Host "版本：$newName (versionCode $newCode)"
Write-Host '安装到手机（先在手机上打开 USB 调试）：'
Write-Host "  $env:ANDROID_HOME\platform-tools\adb.exe install -r `"$out`""
exit 0
