#!/bin/bash
set -euo pipefail

# フックとして呼ばれた場合は Claude Code Web のリモート環境でのみ動かす。
# 複数リポジトリのセッションではフックが読まれないため、環境のセットアップスクリプトから
# CLAUDE_PROJECT_DIR なしで直接呼ばれても動くようにする
if [ -n "${CLAUDE_PROJECT_DIR:-}" ] && [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

export PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/.." && pwd)}"

echo "Configuring Gradle proxy settings..."

python3 - <<'PYEOF'
import os, sys, hashlib, urllib.request, urllib.parse, zipfile, re, subprocess, tempfile, shutil

proxy_url = os.environ.get('HTTPS_PROXY', '')
if not proxy_url:
    print("HTTPS_PROXY is not set; skipping Gradle proxy configuration")
    sys.exit(0)

parsed   = urllib.parse.urlparse(proxy_url)
host     = parsed.hostname
port     = str(parsed.port or (80 if parsed.scheme == 'http' else 443))
user     = urllib.parse.unquote(parsed.username or '')
password = urllib.parse.unquote(parsed.password or '')

gradle_home = os.path.expanduser('~/.gradle')
os.makedirs(gradle_home, exist_ok=True)

# ── プロキシ認証用 Gradle init スクリプト ─────────────────────────────────────
# Gradle が Authenticator を使えるようにする
init_d = os.path.join(gradle_home, 'init.d')
os.makedirs(init_d, exist_ok=True)
init_script = os.path.join(init_d, 'proxy-auth.gradle')
with open(init_script, 'w') as f:
    f.write(f"""import java.net.Authenticator
import java.net.PasswordAuthentication

def proxyUser = System.getProperty("https.proxyUser") ?: System.getProperty("http.proxyUser")
def proxyPassword = System.getProperty("https.proxyPassword") ?: System.getProperty("http.proxyPassword")

if (proxyUser && proxyPassword) {{
    Authenticator.setDefault(new Authenticator() {{
        @Override
        protected PasswordAuthentication getPasswordAuthentication() {{
            if (getRequestorType() == Authenticator.RequestorType.PROXY) {{
                return new PasswordAuthentication(proxyUser, proxyPassword.toCharArray())
            }}
            return null
        }}
    }})
}}
""")
print(f"Gradle init script written: {init_script}")

def download(url, dest_path, opener):
    print(f"Downloading {url} ...")
    with opener.open(url) as resp:
        total = int(resp.headers.get('Content-Length', 0))
        downloaded = 0
        with open(dest_path, 'wb') as f:
            while True:
                chunk = resp.read(65536)
                if not chunk:
                    break
                f.write(chunk)
                downloaded += len(chunk)
                if total:
                    pct = downloaded * 100 // total
                    print(f"\r  {pct}% ({downloaded}/{total} bytes)", end='', flush=True)
    print()

proxy_handler = urllib.request.ProxyHandler({'https': proxy_url, 'http': proxy_url})
opener = urllib.request.build_opener(proxy_handler)

# ── Gradle デーモン JVM の truststore にプロキシ CA を追加 ────────────
# HTTPS プロキシ (TLS 検査) の CA を JDK truststore に追加する
# これがないと Gradle が依存関係を取得できない

java_home = os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-21-openjdk-amd64')

def import_ca_into_jdk(jdk_path, label):
    """指定した JDK の truststore にプロキシ CA をインポートする"""
    cacerts = os.path.join(jdk_path, 'lib', 'security', 'cacerts')
    keytool = os.path.join(jdk_path, 'bin', 'keytool')
    cacerts_real = os.path.realpath(cacerts)
    sys_ca_bundle = '/etc/ssl/certs/ca-certificates.crt'
    if not (os.path.exists(sys_ca_bundle) and os.path.exists(keytool)):
        return
    with open(sys_ca_bundle) as f:
        bundle = f.read()
    pem_blocks = re.findall(r'-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----', bundle, re.DOTALL)
    for pem in pem_blocks:
        result = subprocess.run(['openssl', 'x509', '-noout', '-subject'], input=pem, capture_output=True, text=True)
        if 'Anthropic' not in result.stdout:
            continue
        cn_match = re.search(r'CN\s*=\s*([^\n,]+)', result.stdout)
        alias = cn_match.group(1).strip().lower().replace(' ', '-') if cn_match else 'anthropic-ca'
        check = subprocess.run([keytool, '-list', '-alias', alias, '-keystore', cacerts_real, '-storepass', 'changeit'],
                               capture_output=True, text=True)
        if check.returncode == 0:
            print(f"CA already imported into {label}: {alias}")
            continue
        with tempfile.NamedTemporaryFile(mode='w', suffix='.pem', delete=False) as tmp:
            tmp.write(pem)
            tmp_path = tmp.name
        r = subprocess.run([keytool, '-import', '-trustcacerts', '-noprompt',
                        '-alias', alias, '-file', tmp_path,
                        '-keystore', cacerts_real, '-storepass', 'changeit'],
                       capture_output=True, text=True)
        os.unlink(tmp_path)
        if r.returncode == 0:
            print(f"CA imported into {label} truststore: {alias}")
        else:
            print(f"Failed to import CA into {label}: {alias} ({r.stderr.strip()})")

def enable_basic_auth_tunneling(jdk_path, label):
    """JDK の net.properties で HTTPS トンネリング時の Basic 認証を有効化する"""
    net_props = os.path.join(jdk_path, 'conf', 'net.properties')
    if not os.path.exists(net_props):
        return
    with open(net_props) as f:
        content = f.read()
    if 'jdk.http.auth.tunneling.disabledSchemes=Basic' in content:
        content = content.replace(
            'jdk.http.auth.tunneling.disabledSchemes=Basic',
            'jdk.http.auth.tunneling.disabledSchemes='
        )
        with open(net_props, 'w') as f:
            f.write(content)
        print(f"Enabled Basic auth for HTTPS proxy tunneling in {label} net.properties")

# JDK のセットアップ
import_ca_into_jdk(java_home, 'JDK')
enable_basic_auth_tunneling(java_home, 'JDK')

# ── ~/.gradle/gradle.properties にプロキシ設定を書き込む ─────────────────────
def escape_properties_value(value):
    escaped = value.replace('\\', '\\\\')
    if escaped.startswith(' '):
        escaped = '\\' + escaped
    return escaped

def write_gradle_properties():
    managed = {
        'systemProp.https.proxyHost': host,
        'systemProp.https.proxyPort': port,
        'systemProp.https.proxyUser': user,
        'systemProp.https.proxyPassword': password,
        'systemProp.http.proxyHost': host,
        'systemProp.http.proxyPort': port,
        'systemProp.http.proxyUser': user,
        'systemProp.http.proxyPassword': password,
        'systemProp.https.nonProxyHosts': 'localhost|127.0.0.1',
        'systemProp.http.nonProxyHosts': 'localhost|127.0.0.1',
        'systemProp.jdk.http.auth.tunneling.disabledSchemes': '',
    }
    props_path = os.path.join(gradle_home, 'gradle.properties')
    kept = []
    if os.path.exists(props_path):
        with open(props_path) as f:
            for line in f.read().splitlines():
                key = re.split(r'[=:\s]', line.strip(), maxsplit=1)[0]
                if key not in managed:
                    kept.append(line)
    with open(props_path, 'w') as f:
        for line in kept:
            f.write(line + "\n")
        for key, value in managed.items():
            f.write(f"{key}={escape_properties_value(value)}\n")
    print(f"gradle.properties written (proxy={host}:{port})")

# ── Gradle distribution の事前ダウンロード ────────────────────────────────────
dist_url  = 'https://services.gradle.org/distributions/gradle-9.8.0-bin.zip'
dist_name = 'gradle-9.8.0-bin'
md5 = hashlib.md5(dist_url.encode()).digest()
n = int.from_bytes(md5, 'big')
chars = '0123456789abcdefghijklmnopqrstuvwxyz'
hash_str = ''
while n:
    hash_str = chars[n % 36] + hash_str
    n //= 36
dist_dir = os.path.join(gradle_home, 'wrapper', 'dists', dist_name, hash_str)
zip_path = os.path.join(dist_dir, f'{dist_name}.zip')

if not os.path.exists(f"{zip_path}.ok") and not os.path.exists(zip_path):
    os.makedirs(dist_dir, exist_ok=True)
    for ext in ('lck', 'part'):
        p = f"{zip_path}.{ext}"
        if os.path.exists(p):
            os.remove(p)
    download(dist_url, zip_path, opener)
    print(f"Gradle distribution download complete: {zip_path}")

write_gradle_properties()

# ── Android SDK セットアップ ──────────────────────────────────────────────────
# sdkmanager のセットアップのみ（パッケージはビルド時に AGP が自動ダウンロード）

project_dir = os.environ['PROJECT_DIR']
android_home = os.path.expanduser('~/android-sdk')
local_props = os.path.join(project_dir, 'local.properties')

os.makedirs(android_home, exist_ok=True)

# cmdline-tools のダウンロード（sdkmanager 本体の取得）
cmdline_tools_dir = os.path.join(android_home, 'cmdline-tools', 'latest')
sdkmanager_bin = os.path.join(cmdline_tools_dir, 'bin', 'sdkmanager')
if not os.path.exists(sdkmanager_bin):
    cmdline_url = 'https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip'
    cmdline_zip = os.path.join(android_home, 'commandlinetools.zip')
    download(cmdline_url, cmdline_zip, opener)
    with zipfile.ZipFile(cmdline_zip, 'r') as zf:
        zf.extractall(android_home)
    os.unlink(cmdline_zip)
    # zip は cmdline-tools/ に展開される → cmdline-tools/latest/ に移動
    extracted = os.path.join(android_home, 'cmdline-tools')
    temp_dir = os.path.join(android_home, '_cmdline-tools-temp')
    os.rename(extracted, temp_dir)
    os.makedirs(extracted, exist_ok=True)
    shutil.move(temp_dir, cmdline_tools_dir)
    os.chmod(sdkmanager_bin, 0o755)
    print(f"cmdline-tools installed: {cmdline_tools_dir}")

# ライセンス承認
licenses_dir = os.path.join(android_home, 'licenses')
os.makedirs(licenses_dir, exist_ok=True)
with open(os.path.join(licenses_dir, 'android-sdk-license'), 'w') as f:
    f.write('\n24333f8a63b6825ea9c5514f83c2829b004d1fee\n')

# local.properties に sdk.dir を書き込む
with open(local_props, 'w') as f:
    f.write(f"sdk.dir={android_home}\n")
print(f"local.properties written: sdk.dir={android_home}")

print("Session start hook completed.")
PYEOF
