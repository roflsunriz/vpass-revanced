# 更新手順

## 1. 新しい APK を追加する

`vpass-apks/` に対象バージョンの単一 APK を置きます。ファイル名は次の形式に合わせます。

```text
apk_com.smbc_card.vpass-<version>.apk
```

XAPK / APKM / APKS の場合は、PC の APKEditor や Android の Anti Split M で単一 APK に統合してから置きます。ABI と端末の CPU を一致させてください。

## 2. パッチをビルドする

ReVanced の Gradle プラグインは GitHub Packages から取得します。`githubPackagesUsername` と `githubPackagesPassword`（または `GITHUB_ACTOR` / `GITHUB_TOKEN`）を設定してください。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$env:ORG_GRADLE_PROJECT_githubPackagesUsername = '<GitHubユーザー名>'
$env:ORG_GRADLE_PROJECT_githubPackagesPassword = '<read:packages 権限のあるトークン>'
.\gradlew.bat --no-daemon clean :patches:test :patches:build :patches:buildAndroid
```

`patches/build/libs/patches-<version>.rvp` が生成されます。

## 3. BKS キーストアを用意する

ReVanced CLI は BouncyCastle の BKS キーストアを使います。初回だけ作成します。

```powershell
keytool -genkeypair -storetype BKS `
  -provider org.bouncycastle.jce.provider.BouncyCastleProvider `
  -providerpath <revanced-cli-all.jar> `
  -keystore revanced.bks -alias revanced `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -storepass <password> -keypass <password> `
  -dname "CN=Vpass ReVanced" -noprompt
```

## 4. パッチを適用する

```powershell
java -Xmx5G -jar revanced-cli-all.jar patch `
  -p patches/build/libs/patches-0.1.0.rvp -b --exclusive `
  -e "Vpass ReVanced" -e "改ざん・Root検知の無効化" -e "USBデバッグ検知の無効化" `
  -t .analysis/tmp --purge -o .analysis/patched/vpass-<version>.apk `
  --keystore revanced.bks --keystore-password <password> `
  --keystore-entry-alias revanced --keystore-entry-password <password> `
  vpass-apks/apk_com.smbc_card.vpass-<version>.apk
```

依存パッチを `-e` で明示しないと、`--exclusive` のとき本体が何も変更しない、または依存パッチの失敗で本体が失敗します。

## 5. DEX を検証する

```powershell
python scripts/verify-patched-apk.py .analysis/patched/vpass-<version>.apk
```

`PASS` になることを確認します。検証内容は [verification.md](verification.md) を参照してください。

## 6. 実機で確認する

1. 公式アプリをアンインストールする（データは失われる）。
2. パッチ版をインストールする。
3. USB デバッグと開発者オプションを ON にしたまま起動する。
4. USB デバッグのブロック画面が出ず、オンボーディング画面まで進むことを確認する。
5. 可能ならログインし、主要画面（ホーム、カード、設定）を開く。

```powershell
adb -s <serial> install -r .analysis/patched/vpass-<version>.apk
adb -s <serial> shell monkey -p com.smbc_card.vpass -c android.intent.category.LAUNCHER 1
```

## 7. パッチが失敗したときの調査

### `DexGuard signature validation initializer was not found`

`SplashActivity.onCreate` の先頭にある `kh` パッケージの `()V` 呼び出しを確認します。

```powershell
dexdump -d <classesN.dex> | Select-String "SplashActivity.onCreate"
```

2 つの `kh` 初期化のうち、同じクラス内の引数 1 個・戻り値 `Z` のメソッドを呼ぶものが署名検証です。クラス名・メソッド名が変わった場合は、`patches/src/main/kotlin/dev/roflsunriz/vpass/VpassPatch.kt` の `disableSignatureCheck` の条件を実物に合わせます。名前を固定せず、呼び出し関係で判定する方針を維持してください。

### `VpassApplication tamper flag getter was not found`

`BaseActivity.onCreate` から `VpassApplication;->...()Z` が呼ばれているか確認します。呼び出しが別メソッドへ移動した場合は、getter の探索元を `BaseActivity.onCreate` から該当メソッドへ変更します。

### `BaseActivity adb_enabled check was not found`

`BaseActivity.onCreate` の `Settings.Global.getInt(ContentResolver, String)` を確認します。`getInt` の後に `MOVE_RESULT` と `IF_EQZ` が続く形が前提です。コンパイラの最適化で形が変わった場合は、`findSettingIntChecks` の条件を実物に合わせます。

### `VerifyError: Constructor returning without calling superclass constructor`

改ざんフラグ setter の探索にコンストラクタが含まれています。`disableTamperFlag` の setter フィルタで `<init>` / `<clinit>` を除外してください。

### `VerifyError: bad exception entry`

メソッド本体を置換するときに古い try/catch を残しています。`replaceBody` は `MutableMethodImplementation(original.registerCount)` で実装ごと差し替えてください。

### 再署名後に `SIGSEGV` で落ちる

DexGuard の署名検証初期化が無効化されていません。`disableSignatureCheck` が実際に `return-void` を書き込んだか、`scripts/verify-patched-apk.py` で確認してください。署名検証の boolean を true にするだけでは不十分です。

### ReVanced CLI が `Wrong version of key store.` で失敗する

JKS / PKCS12 を渡しています。BKS キーストアを作成してください。

## 8. リリース

1. `gradle.properties` の `version` を更新する。
2. `CHANGELOG.md` に `## [<version>] - YYYY-MM-DD` を追加する。
3. `v<version>` タグを push する。
4. `.github/workflows/release.yml` が RVP と `patches.json` を生成して GitHub Release に添付する。
5. リリース後、`patches.json` の URL が README と一致していることを確認する。

## 9. ロールバック

- パッチ版をアンインストールし、公式アプリを再インストールする。
- 公式アプリのデータはパッチ版と共有できないため、ログインからやり直す。
- リリースを撤回する場合は GitHub Release とタグを削除し、`patches.json` の参照先を前のバージョンに戻す。
