# Vpass ReVanced

三井住友カード Vpass アプリ（`com.smbc_card.vpass`）の Root・改ざん・USB デバッグ検知を無効化する ReVanced パッチです。

## できること

- DexGuard の改ざん検知コールバック（Root、署名、チェックサム、デバッガ、エミュレータ、フック、動的計測、リソース改変）を無効化します。
- アプリ内の改ざんフラグを常に「異常なし」に固定します。
- 起動時の `adb_enabled` 参照を 0 として扱い、USB デバッグ／ADB 有効時のブロック画面を表示させません。
- 再署名で変わる APK 署名を DexGuard の署名検証初期化に渡さないようにし、パッチ後の APK が起動できるようにします。

## 対応バージョン

次の APK でパッチ適用・DEX 検証・AQUOS 実機（SH-R80P / Android 16 / ADB 有効）での起動を確認しています。

| バージョン | パッチ適用 | 起動 | USB デバッグ画面 |
| --- | --- | --- | --- |
| 5.3.0 | 成功 | 成功 | 表示されない |
| 5.7.0 | 成功 | 成功 | 表示されない |
| 5.8.3 | 成功 | 成功 | 表示されない |
| 5.9.0 | 成功 | 成功 | 表示されない |
| 5.11.0 | 成功 | 成功 | 表示されない |
| 5.14.1 | 成功 | 成功 | 表示されない |

未検証のバージョンでも、同じクラス構造と文字列が残っていれば適用できる可能性があります。適用できなかった場合は Issue で対象バージョンとログを報告してください。

## 重要な注意

- パッチ版は別の署名でインストールするため、公式アプリと同じパッケージのまま上書きできません。公式アプリをアンインストールするとアプリ内データ（ログイン状態、設定など）は失われます。
- Root 化端末・エミュレータでの実行時検証は、root 済み検証端末がないため未実施です。DEX 上は Root 検知コールバックとフラグを無効化していますが、DexGuard のネイティブ処理が別経路で検知する可能性は残ります。
- サーバー側のデバイス整合性判定は APK 内に Play Integrity / SafetyNet の利用が見当たらないため、パッチ対象外です。将来サーバー側で拒否された場合は、このパッチだけでは解決できません。
- 金融アプリです。自己責任で、自身が正当に利用できる端末とアカウントだけで使用してください。不正利用を目的とした回避を支援するものではありません。

## ReVanced Manager へパッチを追加する

ReVanced Manager の「Patches」タブで追加ボタンを押し、「Enter URL」に次を入力します。

```text
https://github.com/roflsunriz/vpass-revanced/releases/latest/download/patches.json
```

読み込み後、「Apps」タブで Vpass の単一 APK を選び、次のいずれかを適用します。

- `Vpass ReVanced`（推奨。Root・改ざん・USB デバッグ検知をまとめて無効化）
- 個別に適用する場合は `改ざん・Root検知の無効化` と `USBデバッグ検知の無効化`

`Vpass ReVanced` は 2 つの個別パッチに依存します。CLI で `--exclusive` を使う場合は、依存パッチも `-e` で明示してください。

## APK の準備

- 単一 APK をそのまま使用できます。
- XAPK / APKM / APKS は PC の APKEditor や Android の Anti Split M などで単一 APK に統合してください。
- 端末の CPU アーキテクチャと APK の ABI を一致させてください。
- 公式アプリを残したい場合は、root 端末で ReVanced Manager のマウント機能などを使う方法がありますが、このリポジトリでは検証していません。

## 適用後の確認

1. 公式アプリをアンインストールします（データは失われます）。
2. ReVanced Manager でパッチを適用した APK をインストールします。
3. USB デバッグを ON にしたまま起動し、USB デバッグのブロック画面が出ず、オンボーディング画面まで進むことを確認します。
4. ログインが必要な操作は、自身のアカウントで通常どおり行います。

## 仕組み

- `com.smbc_card.vpass.TamperAction` の各コールバックを `return-void` に置換します。
- `VpassApplication` の改ざんフラグ getter を `return false` にし、明示的な setter を無効化します。コンストラクタのフィールド初期化は残します。
- `SplashActivity.onCreate` が呼ぶ DexGuard 初期化のうち、署名検証を行う `kh` パッケージの `()V` メソッドを `return-void` にします。文字列復号などを行うもう一方の初期化は残します。
- `BaseActivity.onCreate` の `Settings.Global.getInt(..., "adb_enabled")` の戻り値を 0 に固定します。

詳細な調査結果は [findings.md](findings.md)、検証手順と結果は [verification.md](verification.md) を参照してください。

## 開発者向け

### ビルド

ReVanced の Gradle プラグインは GitHub Packages から取得します。初回は `githubPackagesUsername` と `githubPackagesPassword`（または環境変数 `GITHUB_ACTOR` / `GITHUB_TOKEN`）を設定してください。

```powershell
$env:ORG_GRADLE_PROJECT_githubPackagesUsername = '<GitHubユーザー名>'
$env:ORG_GRADLE_PROJECT_githubPackagesPassword = '<read:packages 権限のあるトークン>'
.\gradlew.bat test build :patches:buildAndroid
```

生成物:

- `patches/build/libs/patches-<version>.rvp`（CLI / Manager 用）
- `patches/build/libs/patches-<version>-sources.rvp`

### ローカルで APK に適用する

ReVanced CLI は BouncyCastle の BKS キーストアを要求します。次のように作成します。

```powershell
keytool -genkeypair -storetype BKS `
  -provider org.bouncycastle.jce.provider.BouncyCastleProvider `
  -providerpath <revanced-cli-all.jar> `
  -keystore revanced.bks -alias revanced `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -storepass revanced -keypass revanced `
  -dname "CN=Vpass ReVanced" -noprompt
```

適用例:

```powershell
java -Xmx5G -jar revanced-cli-all.jar patch `
  -p patches/build/libs/patches-0.1.0.rvp -b --exclusive `
  -e "Vpass ReVanced" -e "改ざん・Root検知の無効化" -e "USBデバッグ検知の無効化" `
  -t .analysis/tmp --purge -o out.apk `
  --keystore revanced.bks --keystore-password revanced `
  --keystore-entry-alias revanced --keystore-entry-password revanced `
  input.apk
```

### 検証

```powershell
python scripts/verify-patched-apk.py out.apk
```

Android SDK の `dexdump` が必要です。詳細は [verification.md](verification.md) を参照してください。

### 更新手順

新しい APK への追従方法は [how-to-update.md](how-to-update.md) を参照してください。

## ライセンス

[MIT License](LICENSE)
