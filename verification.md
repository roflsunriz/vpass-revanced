# 検証手順と結果

## 環境

- Windows / PowerShell
- JDK 21（Gradle 実行用）と ReVanced CLI 6.0.0
- Android SDK build-tools 36.1.0（`dexdump`, `apksigner`, `aapt2`, `zipalign`）
- 実機: SH-R80P（AQUOS）、Android 16、ページサイズ 4096、`adb_enabled=1`、`development_settings_enabled=1`、root なし
- 検証日: 2026-10-24

## ビルド

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
.\gradlew.bat --no-daemon :patches:test :patches:build :patches:buildAndroid
```

結果:

- `:patches:test` 成功
- `:patches:build` 成功
- `:patches:buildAndroid` 成功
- `patches/build/libs/patches-0.1.0.rvp` に `classes.dex` と `META-INF/MANIFEST.MF` が含まれることを確認

## パッチ適用

BKS キーストアを作成し、ReVanced CLI で各 APK に適用します。

```powershell
java -Xmx5G -jar revanced-cli-6.0.0-all.jar patch `
  -p patches/build/libs/patches-0.1.0.rvp -b --exclusive `
  -e "Vpass ReVanced" -e "改ざん・Root検知の無効化" -e "USBデバッグ検知の無効化" `
  -t .analysis/tmp --purge -o out.apk `
  --keystore revanced.bks --keystore-password revanced `
  --keystore-entry-alias revanced --keystore-entry-password revanced `
  vpass-apks/apk_com.smbc_card.vpass-<version>.apk
```

結果:

| バージョン | RVP 適用 | DEX 検証 | 実機インストール | 起動 | USB デバッグ画面 |
| --- | --- | --- | --- | --- | --- |
| 5.3.0 | 成功 | PASS | 成功 | 成功 | 表示されない |
| 5.7.0 | 成功 | PASS | 成功 | 成功 | 表示されない |
| 5.8.3 | 成功 | PASS | 成功 | 成功 | 表示されない |
| 5.9.0 | 成功 | PASS | 成功 | 成功 | 表示されない |
| 5.11.0 | 成功 | PASS | 成功 | 成功 | 表示されない |
| 5.14.1 | 成功 | PASS | 成功 | 成功 | 表示されない |

DEX 検証では次を確認しています。

- `TamperAction` の `<init>` / `<clinit>` 以外の全メソッドが `return-void`
- `BaseActivity.onCreate` の `Settings.Global.getInt` の戻り値が 0 に固定され、直後の `if-eqz` がスキップ側へ分岐する
- `VpassApplication` の改ざんフラグ getter が `return false`
- `VpassApplication.<init>` が `return-void` に置換されていない
- `SplashActivity.onCreate` が呼ぶ `kh` 初期化のうち 1 つだけが `return-void` で、文字列復号側の初期化は残っている

```powershell
python scripts/verify-patched-apk.py out.apk
```

## 未パッチ版との比較

- 未パッチの 5.14.1 を同じ AQUOS にインストールして起動すると、「ご利用の端末のUSBデバッグがオンに設定されているため、本アプリはご利用いただけません。…」のダイアログが表示され、先へ進めなかった。
- パッチ版の 5.14.1 は同じ状態（`adb_enabled=1`）でオンボーディング画面（「生体認証でのログインで簡単アクセス」）まで到達した。
- 5.3.0 / 5.7.0 / 5.8.3 / 5.9.0 / 5.11.0 も同様に、USB デバッグのダイアログなしでオンボーディング画面まで到達した。

## 署名とパッケージ

- すべてのパッチ版で `package: name='com.smbc_card.vpass'` と元の `versionName` / `versionCode` が維持されている。
- `apksigner verify` で v2 / v3 署名を確認。5.14.1 は v3 のみだが、minSdk 29 のため Android 9 以降では問題ない。
- ReVanced CLI の出力を `zipalign` で後処理して `apksigner` で再署名する手順は使っていない。後処理すると v2 が付かない、またはアラインメントが崩れることを確認したため。

## 16KiB アラインメント

`zipalign -c -P 16 -v 4` の結果:

- 5.3.0 / 5.11.0: 全エントリが 16KiB を満たす
- 5.14.1: `lib/mips/libjnidispatch.so` のみ 4KiB
- 5.7.0 / 5.8.3 / 5.9.0: `lib/armeabi-v7a/libjnleeeqeor.so` のみ 4KiB

arm64 の `.so` はすべて 16KiB を満たしています。残るのは arm64 端末で使われない 32bit ARM / MIPS のエントリです。元の 5.3.0 / 5.7.0 も 16KiB を満たしていません。

## 未実施・残るリスク

- Root 化端末・エミュレータでの実行時検証は未実施（root 済み検証端末がないため）。DEX 上は Root を含む全コールバックとフラグを無効化しているが、DexGuard のネイティブ処理が別経路で停止させる可能性は残る。
- ログイン後のサーバー通信は検証していない。サーバー側のデバイス整合性判定やアカウント制限がある場合、このパッチでは解決できない。
- ReVanced Manager への `patches.json` 登録と Manager からの適用は、この環境では UI 操作を自動化していない。CLI での適用と DEX 検証、実機起動までを確認している。
- Wi-Fi デバッグ（`adb_wifi_enabled`）は APK 内に参照が見当たらない。`adb_enabled` の検査と DexGuard コールバックの無効化で対応している。
- パッチ版は公式署名と異なるため、公式アプリのデータ引き継ぎはできない。

## 更新時の確認

新しい APK を追加したら、[how-to-update.md](how-to-update.md) の手順でパッチ適用と DEX 検証を行い、実機で USB デバッグを ON にしたまま起動できることを確認してください。
